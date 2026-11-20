#!/usr/bin/env python3
"""CPU 转译（TCG）阶段 5 基线/对照试验器。

固定镜像 + 固定命令行 + 固定 boot-args 启动 Inferno QEMU，采集：
  * 串口阶段时间（loader → Logo/首帧 → graphics handoff → SBHomeScreenWindow）
  * QEMU 进程 CPU 时间（/proc/<pid>/stat utime/stime，按 vCPU 线程拆分）
  * QEMU 自身 TB 统计（QMP x-query-jit：TB 数、翻译代码字节、TB flush/invalidate）
  * 宿主侧温度、可运行进程数、GPU worker 的 CPU 时间

设计约束（同样是硬约束）：
  * 默认 dry-run，只打印将要执行的命令；真正跑必须显式 --execute。
  * 不复制 12 GiB 的 ram.img；用 memory-backend-file,readonly=on,share=off 复用（写时复制）。
  * 不写公共镜像目录；每次试验在 --out 下建带时间戳的独立目录。
  * 宿主有 java/gradle/qemu 在跑时拒绝计时（除非 --allow-busy），因为已确认宿主负载会
    显著改变来宾进度。

用法示例：
  python3 tools/cpu-tcg-bench.py --tag base --seconds 300            # dry-run
  python3 tools/cpu-tcg-bench.py --tag base --seconds 300 --execute --repeat 2
  python3 tools/cpu-tcg-bench.py --tag tb2048 --tb-size 2048 --seconds 300 --execute
"""

import argparse
import json
import os
import re
import shutil
import socket
import statistics
import subprocess
import sys
import time

ROOT = "/root/workspace/hackios-run"
DEFAULT_QEMU = f"{ROOT}/qemu-inferno.tfi5zu/build-final/qemu-system-aarch64"
# 二进制动态链接 liblzfse.so.1（系统未装）与 nettle 3.10.2（系统是 3.9.1，缺 NETTLE_8 符号）。
# 用源码树里已经解包的依赖目录，不改系统。
DEFAULT_LDPATH = ":".join([
    f"{ROOT}/qemu-inferno.tfi5zu/deps/nettle-3.10.2/lib",
    f"{ROOT}/qemu-inferno.tfi5zu/deps/extracted/usr/lib/aarch64-linux-gnu",
])
DEFAULT_SHARE = f"{ROOT}/qemu-share"
# 既有脚本（boot-candidate.sh / liverun*.sh）用的都是 ram-correct.img。
# 'hackios-run/ram.img' 在 ~3.9GB 偏移处与它不同，会让 guest 在 launchd exec 处 panic（已实测）。
DEFAULT_RAMIMG = "/root/workspace/ram-correct.img"
DEFAULT_EFI = f"{ROOT}/QEMU_EFI.fd"
DEFAULT_VARS = f"{ROOT}/QEMU_VARS.fd"
DEFAULT_ESP = f"{ROOT}/esp"
DEFAULT_WORKER = f"{ROOT}/gpu-apk-native.ccuPh9/runtime/gpu-android-arm64/start-worker.sh"
DEFAULT_PAYLOAD = f"{ROOT}/redwood-settings-content.M5VER6/boot/payload.bin"

BUSY_COMMS = ("java", "gradle", "kotlin", "qemu-system", "qd-")
THERMAL_GLOBS = "/sys/class/thermal/thermal_zone%d/temp"

# 串口阶段标记（实测文本，来自真实 serial.log）。顺序 = 阶段顺序。
STAGE_PATTERNS = [
    ("loader", re.compile(rb"== XNULOADER|^cmdline: ")),
    ("first_frame", re.compile(rb"redwood-gl: frame \d+")),
    ("graphics_handoff", re.compile(rb"graphics handoff|bootlog retired")),
    ("sb_home_window", re.compile(rb"SBHomeScreenWindow")),
    ("springboard_ready", re.compile(rb"springboard.*ready|FrontBoard.*ready", re.I)),
    ("panic", re.compile(rb"panic\(cpu |Debugger message: panic")),
]
FRAME_RE = re.compile(rb"redwood-gl: frame (\d+)")
SWAP_RE = re.compile(rb"MiniIOMobileFramebuffer: swap (\d+)")
READBACK_RE = re.compile(rb"EAGL readback:")
JIT_FIELDS = {
    "gen_code_size": re.compile(r"gen code size\s+(\d+)/(\d+)"),
    "tb_count": re.compile(r"TB count\s+(\d+)"),
    "avg_target": re.compile(r"TB avg target size\s+(\d+) max=(\d+)"),
    "avg_host": re.compile(r"TB avg host size\s+(\d+) bytes \(expansion ratio: ([\d.]+)\)"),
    "cross_page": re.compile(r"cross page TB count\s+(\d+)"),
    "direct_jump": re.compile(r"direct jump count\s+(\d+)"),
    "flush": re.compile(r"TB flush count\s+(\d+)"),
    "invalidate": re.compile(r"TB invalidate count\s+(\d+)"),
    "tlb_full": re.compile(r"TLB full flushes\s+(\d+)"),
    "tlb_part": re.compile(r"TLB partial flushes\s+(\d+)"),
    "tlb_elide": re.compile(r"TLB elided flushes\s+(\d+)"),
    # 自建插桩（tcg-exp/0001-instrument-translation-time.patch）才有的字段
    "tr_translate_ns": re.compile(r"translate (?:ns|us)\s+(\d+)"),
    "tr_gen_ns": re.compile(r"gen_code (?:ns|us)\s+(\d+)"),
    "tr_opt_ns": re.compile(r"optimize (?:ns|us)\s+(\d+)"),
    "tr_reach_ns": re.compile(r"reachable (?:ns|us)\s+(\d+)"),
    "tr_live_ns": re.compile(r"liveness (?:ns|us)\s+(\d+)"),
    "tr_emit_ns": re.compile(r"emit (?:ns|us)\s+(\d+)"),
    "tr_total_ns": re.compile(r"translation total (?:ns|us)\s+(\d+)"),
    "ns_per_tb": re.compile(r"(?:ns|us) per TB\s+(\d+)"),
    "tb_alloc_ns": re.compile(r"tb_alloc (?:ns|us)\s+(\d+)"),
    "page_lock_ns": re.compile(r"page_lock (?:ns|us)\s+(\d+)"),
    "tb_link_ns": re.compile(r"tb_link (?:ns|us)\s+(\d+)"),
    "dispatch": re.compile(r"dispatch count\s+(\d+)"),
    "links": re.compile(r"link count\s+(\d+)"),
}


def now():
    return time.monotonic()


def read_thermal():
    temps = []
    for i in range(0, 40):
        try:
            with open(THERMAL_GLOBS % i) as f:
                v = int(f.read().strip())
        except OSError:
            continue
        if v > 0:
            temps.append(v / 1000.0)
    if not temps:
        return {"max_c": None, "mean_c": None}
    return {"max_c": max(temps), "mean_c": round(sum(temps) / len(temps), 1)}


def ps_state(exclude=()):
    """proot 里 /proc/loadavg、/proc/stat、/proc/uptime 全部 Permission denied，
    所以用 ps 的可运行(R)进程数 + 各进程 %CPU 之和作为负载代理，并如实标注。

    注意：Gradle/Kotlin 守护进程会长期驻留（comm=java）但空闲时几乎不占 CPU，
    因此「忙」按 CPU 占用和判定，而不是仅按进程名出现判定。"""
    try:
        out = subprocess.run(["ps", "-eo", "pid=,stat=,pcpu=,comm="],
                             capture_output=True, text=True, timeout=10).stdout
    except Exception:
        return {"runnable": None, "pcpu_sum": None, "busy_comms": [], "busy_load": 0.0}
    runnable = 0
    pcpu = 0.0
    busy = set()
    busy_load = 0.0
    for line in out.splitlines():
        parts = line.split(None, 3)
        if len(parts) < 4:
            continue
        pid, st, pct, comm = parts
        if int(pid) in exclude:      # 自己的 QEMU/worker 不算宿主忙
            continue
        if st.startswith("R"):
            runnable += 1
        try:
            v = float(pct)
        except ValueError:
            continue
        pcpu += v
        if any(comm.startswith(b) for b in BUSY_COMMS):
            busy.add(comm)
            busy_load += v
    return {"runnable": runnable, "pcpu_sum": round(pcpu, 1),
            "busy_comms": sorted(busy), "busy_load": round(busy_load, 1)}


BUSY_LOAD_THRESHOLD = 60.0  # 0.6 核；守护进程空闲时通常 < 5


class QMP:
    def __init__(self, path, timeout=10.0):
        self.sock = socket.socket(socket.AF_UNIX)
        self.sock.settimeout(timeout)
        self.sock.connect(path)
        self.buf = b""
        self.msg_id = 0
        self._recv()  # greeting
        self.cmd("qmp_capabilities")

    def _recv(self):
        while b"\n" not in self.buf:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("QMP closed")
            self.buf += chunk
        line, self.buf = self.buf.split(b"\n", 1)
        return json.loads(line)

    def cmd(self, execute, **kw):
        self.msg_id += 1
        payload = {"execute": execute, "id": self.msg_id}
        payload.update(kw)
        self.sock.sendall((json.dumps(payload) + "\n").encode())
        while True:
            msg = self._recv()
            if msg.get("id") == self.msg_id:
                if "error" in msg:
                    raise RuntimeError(f"{execute}: {msg['error']}")
                return msg.get("return")

    def jit(self):
        txt = self.cmd("x-query-jit")["human-readable-text"]
        out = {"raw": txt}
        for key, rx in JIT_FIELDS.items():
            m = rx.search(txt)
            if m:
                out[key] = [int(g) if g.isdigit() else float(g) for g in m.groups()] \
                    if len(m.groups()) > 1 else int(m.group(1))
        return out

    def vcpu_threads(self):
        try:
            cpus = self.cmd("query-cpus-fast")
        except Exception:
            return []
        return [{"cpu_index": c.get("cpu-index"), "tid": c.get("thread-id")}
                for c in cpus]

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def proc_cpu(pid, tids=()):
    """返回 (user_s, sys_s, rss_mb) 以及按 tid 的 {tid: (user_s, sys_s)}。"""
    hz = os.sysconf("SC_CLK_TCK")
    per = {}
    user = sys_ = 0.0
    rss = 0
    try:
        with open(f"/proc/{pid}/stat") as f:
            fld = f.read().rsplit(")", 1)[1].split()
        user = int(fld[11]) / hz
        sys_ = int(fld[12]) / hz
        rss = int(fld[21]) * os.sysconf("SC_PAGE_SIZE") / (1024 * 1024)
    except OSError:
        pass
    for tid in tids:
        try:
            with open(f"/proc/{pid}/task/{tid}/stat") as f:
                fld = f.read().rsplit(")", 1)[1].split()
            per[tid] = (int(fld[11]) / hz, int(fld[12]) / hz)
        except OSError:
            continue
    return user, sys_, round(rss, 1), per


def load_check(extra_seconds):
    st = ps_state()
    busy = st["busy_comms"]
    runnable = st["runnable"] or 0
    cores = os.cpu_count() or 8
    reasons = []
    if busy and st.get("busy_load", 0) >= BUSY_LOAD_THRESHOLD:
        reasons.append("宿主有 " + ", ".join(busy) + f" 在跑且占 {st['busy_load']:.0f}% CPU"
                       "（已知会让来宾进度大幅波动；另一个 qemu-system 还会争 vCPU，结果不可比）")
    if runnable > max(2, cores // 2):
        reasons.append(f"可运行进程 {runnable} > 核数一半 {cores // 2}")
    return reasons, st


def build_argv(a, trial):
    qemu = [a.qemu, "-L", a.share,
            "-M", "virt,gic-version=3", "-cpu", a.cpu,
            "-smp", a.smp, "-m", a.mem,
            "-accel", a.accel]
    if a.gpu == "on":
        qemu += ["-chardev", f"socket,id=gpu,path={trial}/gpu.sock",
                 "-netdev", "user,id=net0,restrict=on,"
                            "guestfwd=tcp:10.0.2.100:19484-chardev:gpu",
                 "-device", "virtio-net-device,netdev=net0"]
    qemu += ["-drive", f"if=pflash,format=raw,readonly=on,file={a.efi}",
             "-drive", f"if=pflash,format=raw,file={trial}/vars.fd",
             "-drive", f"file=fat:rw:{trial}/esp,format=raw,if=virtio",
             "-device", f"loader,file={trial}/payload.bin,addr=0xf0000000,force-raw=on",
             "-object", f"memory-backend-file,id=ram,size={a.mem},"
                        f"mem-path={a.ramimg},"
                        f"{'readonly=on,' if a.ram_share == 'off' else ''}"
                        f"rom=off,share={a.ram_share}",
             "-machine", "memory-backend=ram",
             "-global", "virtio-mmio.force-legacy=false",
             "-device", "virtio-tablet-device",
             "-device", "virtio-keyboard-device",
             "-device", "ramfb",
             "-qmp", f"unix:{trial}/qmp.sock,server=on,wait=off",
             "-display", "none", "-monitor", "none",
             "-serial", f"file:{trial}/serial.log",
             "-no-reboot"]
    if a.extra:
        qemu += a.extra.split()
    return qemu


def summarize(samples, stages, snapshots, qemu_cpu_final, worker_cpu, args):
    def at_stage(name):
        return stages.get(name)

    first_frame = stages.get("first_frame")
    out = {
        "tag": args.tag,
        "seconds": args.seconds,
        "argv_accel": args.accel,
        "cpu": args.cpu, "smp": args.smp, "gpu": args.gpu, "mem": args.mem,
        "stages_s": {k: (round(v["t"], 2) if v else None) for k, v in stages.items()},
        "frames_at_end": max((s.get("frame", 0) for s in samples), default=0),
        "serial_lines": max((s.get("lines", 0) for s in samples), default=0),
        "swaps": max((s.get("swap", 0) for s in samples), default=0),
        "readbacks": max((s.get("readback", 0) for s in samples), default=0),
        "qemu_cpu_s": qemu_cpu_final,
        "worker_cpu_s": worker_cpu,
        "host_temp_c": samples[-1].get("temp") if samples else None,
        "host_runnable": max((s.get("runnable") or 0 for s in samples), default=None),
        "contaminated_s": [s["t"] for s in samples if s.get("busy")],
        "snapshots": snapshots,
        "samples": samples,
    }
    sn_last = snapshots[-1].get("jit", {}) if snapshots else {}
    if sn_last.get("tr_total_ns") and qemu_cpu_final:
        out["translation_share_pct"] = round(
            100.0 * sn_last["tr_total_ns"] / 1e6 / qemu_cpu_final, 2)
        out["translation_cpu_s"] = round(sn_last["tr_total_ns"] / 1e6, 2)
        if sn_last.get("ns_per_tb"):
            out["ns_per_tb"] = sn_last["ns_per_tb"]
        for k in ("dispatch", "links"):
            if sn_last.get(k):
                out[f"{k}_per_tb"] = round(sn_last[k] / max(sn_last.get("tb_count", 1), 1), 2)
        out["gen_share_pct"] = round(
            100.0 * sn_last.get("tr_gen_ns", 0) / 1e6 / qemu_cpu_final, 2)
    if first_frame and qemu_cpu_final:
        cpu_at = first_frame.get("cpu")
        if cpu_at:
            out["cpu_s_to_first_frame"] = round(cpu_at, 2)
            out["frames_per_cpu_s"] = round(out["frames_at_end"] / qemu_cpu_final, 3) \
                if qemu_cpu_final else None
            sn = snapshots[-1] if snapshots else {}
            if "gen_code_size" in sn:
                out["tb_bytes_per_cpu_s"] = round(sn["gen_code_size"][0] / qemu_cpu_final, 0)
            if "tb_count" in sn:
                out["tbs_per_cpu_s"] = round(sn["tb_count"] / qemu_cpu_final, 1)
    return out


def run_once(args, idx):
    ts = time.strftime("%Y%m%d-%H%M%S")
    trial = os.path.join(args.out, f"{ts}-{args.tag}-r{idx}")
    os.makedirs(trial, exist_ok=False)

    # 只复制可写的小文件；12 GiB ram.img 复用只读 + COW。
    shutil.copytree(args.esp, f"{trial}/esp")
    shutil.copy2(args.vars, f"{trial}/vars.fd")
    shutil.copy2(args.payload, f"{trial}/payload.bin")
    pidfile = None
    worker = None
    qmp = None
    samples, snapshots, stages = [], [], {}
    t0 = now()
    started_wall = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    try:
        if args.gpu == "on":
            worker_cmd = ["/system/bin/sh", args.worker, "Adreno",
                          "--listen-unix", f"{trial}/gpu.sock"]
            worker = subprocess.Popen(worker_cmd, cwd=trial, stdout=open(f"{trial}/worker.log", "wb"),
                                      stderr=subprocess.STDOUT, env=dict(os.environ))
            for _ in range(200):
                if os.path.exists(f"{trial}/gpu.sock"):
                    break
                if worker.poll() is not None:
                    raise RuntimeError("GPU worker exited early; see worker.log")
                time.sleep(0.05)
        argv = build_argv(args, trial)
        env = dict(os.environ, LD_LIBRARY_PATH=args.ldpath, TMPDIR=f"{trial}/tmp")
        os.makedirs(f"{trial}/tmp", exist_ok=True)
        with open(f"{trial}/cmdline.txt", "w") as f:
            f.write(" ".join(argv) + "\n")
        qlog = open(f"{trial}/qemu.log", "wb")
        qemu = subprocess.Popen(argv, cwd=trial, stdout=qlog, stderr=subprocess.STDOUT, env=env)
        pidfile, pid = f"{trial}/qemu.pid", qemu.pid
        with open(pidfile, "w") as f:
            f.write(str(pid))

        # QMP 可能要几百毫秒才就绪
        for _ in range(200):
            if os.path.exists(f"{trial}/qmp.sock"):
                try:
                    qmp = QMP(f"{trial}/qmp.sock")
                    break
                except OSError:
                    pass
            time.sleep(0.05)
        tids = [c["tid"] for c in qmp.vcpu_threads()] if qmp else []
        snap_at = sorted(set(args.snapshots), reverse=True)
        serial_pos = 0
        wlog_pos = 0
        frame_max = swap_max = readback = 0
        lines = 0
        t_last_ps = 0.0
        next_dump = args.screendump_every
        dumps = []
        while True:
            t = now() - t0
            if t > args.seconds:
                break
            # 串口增量
            try:
                with open(f"{trial}/serial.log", "rb") as f:
                    f.seek(serial_pos)
                    chunk = f.read()
                    serial_pos = f.tell()
            except OSError:
                chunk = b""
            if chunk:
                for raw in chunk.split(b"\n"):
                    lines += 1
                    for name, rx in STAGE_PATTERNS:
                        if name not in stages and rx.search(raw):
                            stages[name] = {"t": t, "line": raw.decode("utf-8", "replace")[:200]}
                m = FRAME_RE.search(chunk)
                for m in FRAME_RE.finditer(chunk):
                    frame_max = max(frame_max, int(m.group(1)))
                for m in SWAP_RE.finditer(chunk):
                    swap_max = max(swap_max, int(m.group(1)))
            # worker 回读计数
            if args.gpu == "on":
                try:
                    with open(f"{trial}/worker.log", "rb") as f:
                        f.seek(wlog_pos)
                        w = f.read()
                        wlog_pos = f.tell()
                    readback += len(READBACK_RE.findall(w))
                except OSError:
                    pass
            user, sys_, rss, per = proc_cpu(pid, tids)
            temp = read_thermal()
            st = ps_state(exclude={pid, worker.pid if worker else -1}) \
                if (t - t_last_ps) > 5 else None
            if st is not None:
                t_last_ps = t
            wcpu = None
            if worker and worker.poll() is None:
                try:
                    wu, ws, _, _ = proc_cpu(worker.pid)
                    wcpu = round(wu + ws, 2)
                except Exception:
                    pass
            sample = {"t": round(t, 2), "cpu_user": round(user, 3), "cpu_sys": round(sys_, 3),
                      "worker_cpu": wcpu,
                      "rss_mb": rss, "frame": frame_max, "swap": swap_max,
                      "readback": readback, "lines": lines,
                      "temp": temp, "vcpu": {str(k): [round(x, 3) for x in v] for k, v in per.items()}}
            if st:
                sample["runnable"] = st["runnable"]
                sample["pcpu_sum"] = st["pcpu_sum"]
                if st["busy_comms"] and st.get("busy_load", 0) >= BUSY_LOAD_THRESHOLD:
                    sample["busy"] = st["busy_comms"]
                    sample["busy_load"] = st["busy_load"]
            while snap_at and t >= snap_at[-1]:
                snap_at.pop()
                if qmp:
                    try:
                        snapshots.append({"t": round(t, 2), "cpu_s": round(user + sys_, 3),
                                          "jit": qmp.jit()})
                    except Exception as e:
                        snapshots.append({"t": round(t, 2), "error": str(e)})
            if args.screendump_every and qmp and t >= next_dump:
                next_dump = t + args.screendump_every
                fn = os.path.join(args.dump_dir, "dump.ppm")
                try:
                    # HMP 的 screendump 是同步的（QMP screendump 会异步返回，之前的数就是这样废的）
                    d0 = now()
                    qmp.cmd("human-monitor-command",
                            arguments={"command-line": f"screendump {fn}"})
                    ms_rt = (now() - d0) * 1000
                    sz_at_rt = os.path.getsize(fn) if os.path.exists(fn) else 0
                    # 5 ms 轮询，等文件大小连续两次相同（下限约 10 ms）
                    last, sz, stable = -1, 0, 0
                    while now() - d0 < 6:
                        sz = os.path.getsize(fn) if os.path.exists(fn) else 0
                        if sz > 0 and sz == last:
                            stable += 1
                            if stable >= 2:
                                break
                        else:
                            stable = 0
                        last = sz
                        time.sleep(0.005)
                    ms_all = (now() - d0) * 1000
                    dumps.append({"t": round(t, 1),
                                  "ms_roundtrip": round(ms_rt, 1),
                                  "ms_total": round(ms_all, 1),
                                  "mb": round(sz / 2**20, 2),
                                  "byte_at_roundtrip": sz_at_rt,
                                  "complete": stable >= 2,
                                  "dir": args.dump_dir})
                    if os.path.exists(fn):
                        os.remove(fn)
                except Exception as e:
                    dumps.append({"t": round(t, 1), "error": str(e)})
            if qemu.poll() is not None:
                samples.append(sample)
                break
            samples.append(sample)
            time.sleep(args.interval)

        if qmp:
            try:
                user, sys_, _, _ = proc_cpu(pid, tids)
                snapshots.append({"t": round(now() - t0, 2), "cpu_s": round(user + sys_, 3),
                                  "jit": qmp.jit(), "final": True})
            except Exception:
                pass
        final_cpu = None
        try:
            user, sys_, _, _ = proc_cpu(pid)
            final_cpu = round(user + sys_, 2)
        except Exception:
            pass
        if not final_cpu and samples:  # QEMU 已退出时退回最后一次采样值
            final_cpu = round(samples[-1]["cpu_user"] + samples[-1]["cpu_sys"], 2)
        worker_cpu = next((s["worker_cpu"] for s in reversed(samples)
                           if s.get("worker_cpu")), None)
        res = summarize(samples, stages, snapshots, final_cpu, worker_cpu, args)
        res["dumps"] = dumps
        res.update({
            "started": started_wall, "trial_dir": trial,
            "cmdline": " ".join(build_argv(args, trial)),
            "host_at_end": ps_state(),
            "qemu_exit_code": qemu.poll(),
        })
        with open(f"{trial}/results.json", "w") as f:
            json.dump(res, f, indent=1, ensure_ascii=False)
        return res
    finally:
        try:
            if 'qemu' in locals() and qemu.poll() is None:
                qemu.terminate()
                try:
                    qemu.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    qemu.kill()
        except Exception:
            pass
        if qmp:
            qmp.close()
        if worker and worker.poll() is None:
            worker.terminate()
            try:
                worker.wait(timeout=8)
            except subprocess.TimeoutExpired:
                worker.kill()


def fmt_table(results):
    hdr = (f"{'tag':<12}{'stages':<34}{'frames':>7}{'cpu_s':>8}{'TB':>8}"
           f"{'flush':>7}{'temp':>7}{'run':>5}")
    rows = [hdr, "-" * len(hdr)]
    for r in results:
        st = r["stages_s"]
        stages = " ".join(f"{k[:4]}={v:.0f}s" for k, v in st.items() if v is not None) or "-"
        sn = (r.get("snapshots") or [{}])[-1].get("jit", {})
        tb = sn.get("tb_count", "-")
        fl = sn.get("flush", "-")
        temp = (r.get("host_temp_c") or {}).get("max_c")
        rows.append(f"{r['tag']:<12}{stages:<34}{r['frames_at_end']:>7}"
                    f"{(r['qemu_cpu_s'] or 0):>8.1f}{tb:>8}{fl:>7}"
                    f"{(temp if temp is not None else -1):>7.0f}{r.get('host_runnable') or 0:>5}")
    return "\n".join(rows)


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--tag", default="base")
    p.add_argument("--seconds", type=int, default=300,
                   help="固定测量窗口（秒）。默认 300：宿主空闲时足够覆盖 loader→Logo→首帧")
    p.add_argument("--repeat", type=int, default=1)
    p.add_argument("--interval", type=float, default=1.0)
    p.add_argument("--snapshots", default="15,60,120,180,240,300",
                   help="在这些秒数取一次 QMP TB 统计")
    p.add_argument("--execute", action="store_true", help="真正运行（默认只 dry-run）")
    p.add_argument("--allow-busy", action="store_true", help="宿主繁忙也跑（结果只作管线验证）")
    p.add_argument("--tb-size", type=int, default=1360)
    p.add_argument("--thread", choices=["multi", "single"], default="multi")
    p.add_argument("--cpu", default="cortex-a76")
    p.add_argument("--smp", default=None, help="默认按 thread 推导，与 App 默认一致")
    p.add_argument("--mem", default="7G")
    p.add_argument("--gpu", choices=["on", "off"], default="on")
    p.add_argument("--one-insn-per-tb", action="store_true",
                   help="把 one-insn-per-tb=on 并进 -accel（不能另加一个 -accel）")
    p.add_argument("--screendump-every", type=float, default=0,
                   help="每 N 秒做一次 QMP screendump 并记墙钟（量宿主写 PPM 的第 2 段拷贝）")
    p.add_argument("--dump-dir", default="/dev/shm",
                   help="screendump 落盘目录；/dev/shm 是 tmpfs，可与工作目录对比")
    p.add_argument("--ram-share", choices=["on", "off"], default="off",
                   help="memory-backend-file 的 share=，默认 off（写时复制）")
    p.add_argument("--extra", default="", help="追加到命令行末尾的实验参数")
    p.add_argument("--out", default=f"{ROOT}/tcg-bench")
    p.add_argument("--qemu", default=DEFAULT_QEMU)
    p.add_argument("--ldpath", default=DEFAULT_LDPATH)
    p.add_argument("--share", default=DEFAULT_SHARE)
    p.add_argument("--ramimg", default=DEFAULT_RAMIMG)
    p.add_argument("--efi", default=DEFAULT_EFI)
    p.add_argument("--vars", default=DEFAULT_VARS)
    p.add_argument("--esp", default=DEFAULT_ESP)
    p.add_argument("--payload", default=DEFAULT_PAYLOAD)
    p.add_argument("--worker", default=DEFAULT_WORKER)
    p.add_argument("--keep", action="store_true", help="保留试验目录（默认只保留 results.json）")
    a = p.parse_args()

    if a.smp is None:
        a.smp = "2,sockets=1,cores=2,threads=1" if a.thread == "multi" else "2"
    a.accel = (f"tcg,thread=multi,tb-size={a.tb_size}" if a.thread == "multi"
               else f"tcg,tb-size={a.tb_size}")
    if a.one_insn_per_tb:
        a.accel += ",one-insn-per-tb=on"
    a.snapshots = sorted(int(x) for x in a.snapshots.split(",") if x.strip())
    a.snapshots = [s for s in a.snapshots if s <= a.seconds] or [a.seconds]

    for f in (a.qemu, a.ramimg, a.efi, a.vars, a.payload):
        if not os.path.exists(f):
            sys.exit(f"缺少固定输入：{f}")
    if not os.path.isdir(a.esp):
        sys.exit(f"缺少 esp 目录：{a.esp}")
    if a.gpu == "on" and not os.path.exists(a.worker):
        sys.exit(f"缺少 GPU worker：{a.worker}")
    if not os.environ.get("DSH_FORCE_BUSY") and shutil.disk_usage(ROOT).free < 3 * 2**30:
        sys.exit("宿主剩余空间 < 3 GiB")

    reasons, st = load_check(a.seconds)
    print(f"宿主：核={os.cpu_count()} 可运行={st['runnable']} %CPU和={st['pcpu_sum']} "
          f"忙碌进程={st['busy_comms'] or '无'} /proc/loadavg 在 proot 内不可读")
    print(f"温度：{read_thermal()}")
    if reasons and not a.allow_busy:
        print("拒绝计时（硬约束）：" + "；".join(reasons))
        print("→ 先做源码分析；等宿主空闲再跑，或加 --allow-busy 仅验证管线。")
        return 3

    for i in range(1, a.repeat + 1):
        trial = f"{a.out}/{time.strftime('%Y%m%d-%H%M%S')}-{a.tag}-r{i}"
        argv = build_argv(a, trial)
        print(f"\n[trial {i}/{a.repeat}] {trial}\n  " + " ".join(argv))
        if not a.execute:
            print("  (dry-run；加 --execute 才启动)")
            continue
        os.makedirs(a.out, exist_ok=True)
        r = run_once(a, i)
        print(f"  阶段：{ {k: v for k, v in r['stages_s'].items() if v is not None} }")
        print(f"  帧={r['frames_at_end']} 串口行={r['serial_lines']} QEMU CPU={r['qemu_cpu_s']}s "
              f"worker CPU={r['worker_cpu_s']}s")
        if not a.keep:
            # 只留结果，删掉 payload/esp/vars 等上百 MB 的临时副本
            for name in ("payload.bin", "esp", "vars.fd", "tmp"):
                path = os.path.join(r["trial_dir"], name)
                if os.path.isdir(path):
                    shutil.rmtree(path, ignore_errors=True)
                elif os.path.exists(path):
                    os.remove(path)
            print(f"  已清理临时镜像副本，保留 {r['trial_dir']}/results.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
