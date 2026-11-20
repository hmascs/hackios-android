#!/usr/bin/env python3
"""汇总 cpu-tcg-bench.py 的 results.json，输出对照表与相对基线的差值。

不重新跑任何东西，只读已有结果；默认把 --base 指定的 tag 当基线。
用法：
  python3 tools/cpu-tcg-report.py --dir /root/workspace/hackios-run/tcg-bench
  python3 tools/cpu-tcg-report.py --dir ... --base base1360 --csv out.csv
"""

import argparse
import glob
import json
import os


def load(d):
    rows = []
    for path in glob.glob(os.path.join(d, "*", "results.json")):
        try:
            with open(path) as f:
                r = json.load(f)
        except Exception:
            continue
        r["_dir"] = os.path.basename(os.path.dirname(path))
        rows.append(r)
    rows.sort(key=lambda r: r.get("started", ""))
    return rows


def jit_last(r):
    for s in reversed(r.get("snapshots") or []):
        if "jit" in s:
            return s["jit"]
    return {}


def stages_str(r):
    st = r.get("stages_s") or {}
    order = ["loader", "first_frame", "graphics_handoff", "sb_home_window", "panic"]
    return " ".join(f"{k[:9]}={st[k]:.1f}" for k in order if st.get(k) is not None) or "-"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="/root/workspace/hackios-run/tcg-bench")
    ap.add_argument("--base", default=None, help="基线 tag；给出时输出相对差值")
    ap.add_argument("--csv", default=None)
    a = ap.parse_args()

    rows = load(a.dir)
    if not rows:
        print(f"{a.dir} 下没有 results.json")
        return 1

    hdr = ["tag", "dir", "accel", "cpu", "frames", "cpu_s", "TB", "gen_MB",
           "flush", "翻译s", "翻译占比%", "ns/TB", "dispatch/TB", "污染", "温度", "stages"]
    print(("\t".join(hdr)))
    out = []
    for r in rows:
        j = jit_last(r)
        tb = j.get("tb_count", "-")
        gen = (j.get("gen_code_size") or [0])[0] // 1024 if isinstance(j.get("gen_code_size"), list) else "-"
        temp = (r.get("host_temp_c") or {}).get("max_c", -1)
        line = [r.get("tag", "?"), r["_dir"],
                (r.get("argv_accel") or "").replace("tcg,", ""),
                r.get("cpu", "?"), str(r.get("frames_at_end", 0)),
                f"{r.get('qemu_cpu_s') or 0:.1f}",
                str(tb), str(gen // 1024 if isinstance(gen, int) else "-"),
                str(j.get("flush", "-")), str(r.get("translation_cpu_s", "-")),
                str(r.get("translation_share_pct", "-")), str(r.get("ns_per_tb", "-")),
                str(r.get("dispatch_per_tb", "-")),
                f"{len(r.get('contaminated_s') or [])}s" if r.get("contaminated_s") else "无",
                f"{temp:.0f}" if temp and temp > 0 else "-", stages_str(r)]
        print("\t".join(line))
        out.append(line)

    if a.base:
        base = next((r for r in rows if r.get("tag") == a.base), None)
        if base:
            b = jit_last(base)
            print(f"\n相对基线 {a.base} 的差值（正=更高）：")
            for r in rows:
                if r is base:
                    continue
                j = jit_last(r)
                d_tb = (j.get("tb_count", 0) or 0) - (b.get("tb_count", 0) or 0)
                d_cpu = (r.get("qemu_cpu_s") or 0) - (base.get("qemu_cpu_s") or 0)
                d_fr = (r.get("frames_at_end") or 0) - (base.get("frames_at_end") or 0)
                d_ff = None
                sf, sb = (r.get("stages_s") or {}).get("first_frame"), \
                         (base.get("stages_s") or {}).get("first_frame")
                if sf is not None and sb is not None:
                    d_ff = sf - sb
                print(f"  {r.get('tag'):<14} Δframes={d_fr:+4d} Δcpu={d_cpu:+7.1f}s "
                      f"ΔTB={d_tb:+7d} Δ首帧={'n/a' if d_ff is None else f'{d_ff:+.1f}s'}")
        else:
            print(f"找不到基线 tag={a.base}")

    if a.csv:
        with open(a.csv, "w") as f:
            f.write("\t".join(hdr) + "\n")
            for line in out:
                f.write("\t".join(line) + "\n")
        print(f"已写 {a.csv}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
