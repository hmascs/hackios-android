#!/usr/bin/env python3
"""
云端 QEMU 主链路实测器（0.24.0）。

用途：把 App `QemuRunner.start()` 的启动参数逐字复刻到云端 x86_64 的
qemu-system-aarch64（TCG）上，用项目自带的**真实资产**（QEMU_EFI.fd /
QEMU_VARS.fd / loader.efi / mach.development.bcm2837）拉起 aarch64 virt
机器，通过 QMP 核实 vCPU 拓扑、取一帧真实帧缓冲，并留下串口与日志证据。

「不管任何更改，都要在云端上实测跑动」的落地方式：改完参数后重跑本脚本，
`--smp` / `--mttcg` 的变化会在 QMP `query-cpus-fast` 里直接体现。它不是
模拟器截图，不伪造画面：取不到帧就如实记录取不到。

    python3 tools/cloud_verify.py --out /workspace/cloud-verify
    python3 tools/cloud_verify.py --out ... --smp 6 --mttcg 1 --secs 25

设计边界（务必读）：
  这个环境是 x86_64、无 /dev/kvm，用的是发行版标准 QEMU，而 App 自带的是
  Android/arm64 版 QEMU；更关键的是**没有 6.17 GB IPSW，也就没有 system.apfs
  与 root.apfs**（SpringBoard / 设置 app 都在里面）。所以本脚本验证的是
  「UEFI → ESP → loader → -device loader 加载 XNUP 载体 → ramfb」这一段
  可启动骨架，不会、也不能假装进入了 Darwin 桌面。
"""
import argparse
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

ASSETS = Path("/workspace/hackios-ios27/app/src/main/assets")
QEMU = shutil.which("qemu-system-aarch64") or "/usr/bin/qemu-system-aarch64"

# 与 Constants.kt / QemuTopology.kt 保持一致；这里重复一遍是为了让脚本可以独立审计。
RAM_GB = 7
FILE_SIZE = 12 << 30
RAM_BASE = 0x40000000
RD_ADDR = 0xFC000000
RAM_IMG_OFFSET = RD_ADDR - RAM_BASE
LOADER_ADDR = 0xF0000000
TB_SIZE_BASE = 384
TB_PER_CORE = 170


def tb_size(cores: int, mttcg: bool) -> int:
    return max(TB_SIZE_BASE, cores * TB_PER_CORE) if mttcg else TB_SIZE_BASE


def smp_arg(cores: int, mttcg: bool) -> str:
    return f"{cores},sockets=1,cores={cores},threads=1" if mttcg else str(cores)


def accel_arg(cores: int, mttcg: bool) -> str:
    return f"tcg,thread=multi,tb-size={tb_size(cores, mttcg)}" if mttcg else f"tcg,tb-size={TB_SIZE_BASE}"


# ---- XNUP 载荷：复刻 XnupPacker.kt 的字节格式 ----------------------------------------------
MAGIC = 0x50554E58  # "XNUP"，little-endian 存成 58 4E 55 50


def xnup_entry(name: str, data: bytes) -> bytes:
    nb = name.encode()[:16].ljust(16, b"\x00")
    head = nb + struct.pack("<II", len(data), 0)
    pad = (-len(data)) % 16
    return head + data + b"\x00" * pad


def xnup_pack(dest: Path, kernel: Path, ramdisk: bytes, cmdline: str, rdres: str) -> int:
    body = b"".join([
        xnup_entry("kernel", kernel.read_bytes()),
        xnup_entry("ramdisk", ramdisk),
        xnup_entry("cmdline", (cmdline + "\x00").encode()),
        xnup_entry("rdres", (rdres + "\x00").encode()),
        b"\x00" * 24,  # terminator
    ])
    dest.write_bytes(struct.pack("<II", MAGIC, len(body)) + body)
    return len(body) + 8


# ---- QMP -------------------------------------------------------------------------------------
class Qmp:
    def __init__(self, port: int, timeout: float = 0.4):
        self.port = port
        self.sock = None
        self.f = None
        self.greeting = None
        for _ in range(60):
            try:
                self.sock = socket.create_connection(("127.0.0.1", port), timeout)
                self.f = self.sock.makefile("rwb", buffering=0)
                self.greeting = json.loads(self.f.readline())
                return
            except Exception:
                time.sleep(0.15)
        raise RuntimeError(f"QMP 端口 {port} 未打开")

    def call(self, execute: str, arguments: dict | None = None) -> dict:
        obj = {"execute": execute}
        if arguments:
            obj["arguments"] = arguments
        self.f.write((json.dumps(obj) + "\n").encode())
        while True:
            line = self.f.readline()
            if not line:
                return {"error": "QMP closed"}
            msg = json.loads(line)
            if "return" in msg or "error" in msg:
                return msg

    def close(self):
        try:
            if self.sock:
                self.sock.close()
        except Exception:
            pass


def ppm_to_png(ppm: Path, png: Path) -> dict | None:
    if not ppm.is_file() or ppm.stat().st_size == 0:
        return None
    try:
        from PIL import Image
        import numpy as np
        im = Image.open(ppm)
        im.save(png)
        # 顺手统计像素，判断是不是全黑（黑屏 vs 有画面）
        arr = np.asarray(im.convert("RGB").resize((32, 32)), dtype=np.int32)
        mean = float(arr.mean())
        nonblack = int((arr.sum(axis=2) > 12).sum())
        return {"width": im.width, "height": im.height, "mean": round(mean, 2),
                "nonblack_32x32": nonblack, "png": str(png)}
    except Exception as e:
        return {"error": str(e)}


# ---- 单档位实测 ------------------------------------------------------------------------------
def run_case(out: Path, smp: int, mttcg: bool, secs: int, tag: str, qmp_port: int,
             mon_port: int, session: int = 1200) -> dict:
    d = out / tag
    shutil.rmtree(d, ignore_errors=True)
    d.mkdir(parents=True)

    # 真实资产
    shutil.copy(ASSETS / "QEMU_VARS.fd", d / "VARS.fd")
    esp = d / "esp" / "EFI" / "BOOT"
    esp.mkdir(parents=True)
    shutil.copy(ASSETS / "loader.efi", esp / "BOOTAA64.EFI")

    # ram.img：12 GiB 稀疏文件，system.apfs 落在 loader 期望的 resident 偏移。
    ram = d / "ram.img"
    with open(ram, "wb") as f:
        f.truncate(FILE_SIZE)
        f.seek(RAM_IMG_OFFSET)
        f.write(b"HACKIOS-MINIMAL-SYSTEM-IMAGE\0")  # 没有 IPSW，只能放占位；如实记录

    ramdisk = b"HACKIOS-NO-ROOT-APFS\0"  # 没有从 IPSW 重建的 root.apfs
    # loader_return_after 是 loader 的会话时长（App 默认 1200s），**不是**我们的观察窗口。
    # 之前把它设成 secs，结果 QEMU 在观察期到点正常返回（exit 0），第二帧就取不到了。
    cmdline = (
        f"loader_return_after={session} epoch={int(time.time())} "
        f"cpus={smp} virtio_input=1 gl=1 fbscale=3 plain_vibrancy=1"
    )
    payload_bytes = xnup_pack(d / "payload.bin", ASSETS / "mach.development.bcm2837",
                              ramdisk, cmdline, format(len(ramdisk), "x"))

    serial = d / "serial.log"
    cmd = [
        QEMU, "-L", "/usr/share/qemu",
        "-M", "virt,gic-version=3", "-cpu", "cortex-a76",
        "-smp", smp_arg(smp, mttcg), "-m", f"{RAM_GB}G",
        "-accel", accel_arg(smp, mttcg),
        "-drive", f"if=pflash,format=raw,readonly=on,file={ASSETS / 'QEMU_EFI.fd'}",
        "-drive", f"if=pflash,format=raw,file={d / 'VARS.fd'}",
        "-drive", f"file=fat:rw:{d / 'esp'},format=raw,if=virtio",
        "-device", f"loader,file={d / 'payload.bin'},addr=0x{LOADER_ADDR:x},force-raw=on",
        "-object", f"memory-backend-file,id=ram,size={RAM_GB}G,mem-path={ram},share=off",
        "-machine", "memory-backend=ram",
        "-global", "virtio-mmio.force-legacy=false",
        "-device", "virtio-tablet-device", "-device", "virtio-keyboard-device",
        "-device", "ramfb",
        "-monitor", f"tcp:127.0.0.1:{mon_port},server,nowait",
        "-qmp", f"tcp:127.0.0.1:{qmp_port},server,nowait",
        "-display", "none", "-serial", f"file:{serial}",
        "-no-reboot",
    ]
    (d / "cmdline.txt").write_text(" ".join(cmd))

    t0 = time.time()
    log = open(d / "qemu.log", "wb")
    p = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
    result = {
        "tag": tag, "smp": smp, "mttcg": mttcg,
        "smp_arg": smp_arg(smp, mttcg), "accel": accel_arg(smp, mttcg),
        "cmdline_boot": cmdline, "payload_bytes": payload_bytes,
        "qemu": subprocess.run([QEMU, "--version"], capture_output=True, text=True).stdout.splitlines()[0],
        "argv": cmd,
    }
    try:
        q = Qmp(qmp_port)
        result["spawn_ms"] = int((time.time() - t0) * 1000)
        result["qmp_version"] = q.greeting.get("QMP", {}).get("version", {})
        q.call("qmp_capabilities")
        cpus = q.call("query-cpus-fast")
        result["vcpu_count"] = len(cpus.get("return", []))
        result["vcpu_props"] = [c.get("props", {}) for c in cpus.get("return", [])][:2]
        result["status"] = q.call("query-status").get("return", {}).get("status")
        # 真实取帧 1：QMP 刚连上时（UEFI 阶段，通常 640x480）
        ppm = d / "frame_uefi.ppm"
        sd = q.call("screendump", {"filename": str(ppm)})
        result["screendump"] = sd.get("return") if "return" in sd else sd
        result["frame_uefi"] = ppm_to_png(ppm, d / "frame_uefi.png")

        # 观察窗口：逐秒取帧，抓 loader 把 ramfb 从 640x480 重设成 1125x2436 的时刻，
        # 以及帧缓冲内容何时开始变化。QEMU 一旦退出就停止（panic → loader return → UEFI exit）。
        frames = []
        for t in range(1, secs + 1):
            time.sleep(1)
            if p.poll() is not None:
                break
            try:
                fp = d / f"frame_t{t:02d}.ppm"
                q.call("screendump", {"filename": str(fp)})
                info = ppm_to_png(fp, d / f"frame_t{t:02d}.png")
                if info and "width" in info:
                    info["t_sec"] = t
                    frames.append(info)
            except Exception as e:
                frames.append({"t_sec": t, "error": str(e)})
                break
        result["frames"] = frames
        result["alive_after_secs"] = p.poll() is None
        result["exitcode"] = p.poll()
        sized = [f for f in frames if "width" in f]
        best = max(sized, key=lambda f: f["width"] * f["height"], default=None)
        result["frame_final"] = best
        result["frame_resized_to"] = f'{best["width"]}x{best["height"]}' if best else None
        try:
            q.close()
        except Exception:
            pass
    except Exception as e:
        result["error"] = str(e)
    finally:
        p.terminate()
        try:
            p.wait(timeout=4)
        except Exception:
            p.kill()
        log.close()

    full_serial = serial.read_bytes().decode("utf-8", "replace") if serial.is_file() else ""
    result["serial"] = full_serial[:4000]  # 摘要；完整串口文本留在 serial.log
    result["serial_lines"] = full_serial.count("\n")
    result["qemu_log"] = (d / "qemu.log").read_bytes().decode("utf-8", "replace")[:2000]
    # 从真实串口里把主链路的每一级里程碑抽出来，报告直接用这些行当证据。
    keys = [
        "UEFI firmware", "BdsDxe: starting", "== XNULOADER", "display 1125 x 2436",
        "cores for the kernel", "jumping to", "Darwin Kernel Version",
        "MiniIOMobileFramebuffer", "BSD root:", "bsd_init: vfs_mountroot",
        "load_init_program: attempting", "failed loading /sbin/launchd", "panic(cpu",
    ]
    lines = full_serial.splitlines()
    marks = []
    for i, ln in enumerate(lines, 1):
        for k in keys:
            if k in ln:
                marks.append({"line": i, "stage": k, "text": ln.strip()[:180]})
                break
    result["milestones"] = marks
    (d / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="/workspace/cloud-verify")
    ap.add_argument("--smp", type=int, default=6)
    ap.add_argument("--mttcg", type=int, default=1)
    ap.add_argument("--secs", type=int, default=20)
    ap.add_argument("--baseline", type=int, default=1, help="同时跑一个 1 核单线程基线做对照")
    ap.add_argument("--qmp-port", type=int, default=14601)
    ap.add_argument("--mon-port", type=int, default=14611)
    ap.add_argument("--session", type=int, default=1200, help="loader_return_after，与 App 默认一致")
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)

    if not Path(QEMU).exists():
        print("qemu-system-aarch64 不存在，先 apt install qemu-system-arm", file=sys.stderr)
        return 2

    cases = []
    if a.baseline:
        cases.append(run_case(out, 1, False, max(8, a.secs // 2), "baseline_smp1",
                              a.qmp_port, a.mon_port, a.session))
    cases.append(run_case(out, a.smp, bool(a.mttcg), a.secs,
                          f"target_smp{a.smp}_{'mttcg' if a.mttcg else 'single'}",
                          a.qmp_port + 1, a.mon_port + 1, a.session))

    report = {
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S %Z"),
        "host": {"arch": os.uname().machine, "kvm": os.path.exists("/dev/kvm"),
                 "qemu": subprocess.run([QEMU, "--version"], capture_output=True, text=True).stdout.splitlines()[0]},
        "assets": {n: (ASSETS / n).stat().st_size for n in
                   ["QEMU_EFI.fd", "QEMU_VARS.fd", "loader.efi", "mach.development.bcm2837"]},
        "cases": cases,
    }
    (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    summary = []
    for c in cases:
        summary.append({k: c.get(k) for k in
                        ("tag", "smp", "mttcg", "smp_arg", "accel", "vcpu_count", "status",
                         "alive_after_secs", "exitcode", "spawn_ms", "serial_lines",
                         "frame_uefi", "frame_resized_to", "frame_final", "error")})
        summary[-1]["milestones"] = [m["stage"] for m in c.get("milestones", [])]
        summary[-1]["frame_count"] = len(c.get("frames", []))
        summary[-1]["frame_timeline"] = [
            {"t": f.get("t_sec"), "size": f'{f.get("width")}x{f.get("height")}',
             "mean": f.get("mean")}
            for f in c.get("frames", []) if "width" in f
        ]
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
