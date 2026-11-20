# CPU 转译（TCG）效率：基线、对照与差距

阶段 5。**指标口径（按需求变更后的最终版）**：
* **主指标 = 总墙钟**：同一镜像、同一 cmdline 下 QEMU 启动到 `SBHomeScreenWindow` 的时间，
  以及同一固定窗口内的内容帧数/串口行数；
* **副指标 = 翻译成本占比**（翻译/优化/寄存器分配/发射 ÷ QEMU CPU 时间）与每 TB 成本；
* **产品指标 = 实际内容帧率**（每秒真正变化的帧，不是宿主采样次数）。

本文把「实测」与「推断」分开写；只有带对照的数据才进结论。当前状态见 §8.5「本批完成度」：
翻译占比、TB 结构、锁等待、内容帧率基线、第 2 段 screendump 成本已实测；
**SB 总墙钟与硬化 flag 的 A/B 尚未跑完**。

## 0. 结论摘要（截至本批）

**主指标口径已改为「总墙钟」**：同一镜像、同一 cmdline 下 QEMU 启动到 `SBHomeScreenWindow`
的时间（以及同一固定窗口内的帧数/串口行数）。翻译成本占比降为副指标。

1. **翻译侧实测（副指标，⚠ 仅 Inferno 10.2.2 树）**：插桩两次（120 s 窗口）得翻译成本
   **2.68% / 2.78%**（4.66 s / 173.7 s、5.08 s / 182.7 s，均值 2.7%）。即使翻译成本降到 0，
   端到端也只有约 2.7% 的空间；**降 50% ⇒ 约 1.3%**。所以「翻译 -50~60%」只能当相对指标。
   **注意**：App 跑的是 stock 11.1.2，这一条不代表 App；它只说明「TCG 翻译在这类负载里占比很小」
   这个机制。
2. **MTTCG 串行化被实测否掉**：region 分配锁 0.04 s + TB 页锁 0.06 s = 0.1 s / 173.6 s CPU
   （0.06%）。「缩小 tb_lock 粒度 / lock-free TB 分配」在这条负载上没有收益空间。
3. **TB 极碎**：平均 17 字节来宾码/TB（≈4.3 条指令）、256 字节宿主码/TB、膨胀 14.7×；
   240 s 内 467,819 个 TB、234 MB 宿主码、**flush 0 次**（缓存只用 16.4%）。
   跨页 TB = 0，44%（240 s 口径）/52%（120 s 口径）的 TB 至少被链化过一次。
4. **`tb-size` 加大无机制**：1360 从不 flush，2048/4096 不会减少重翻译。`-cpu max` 无可判定差异；
   `lse` 无运行时属性；`apple-a13-cpu`/`apple-gxf` 在 `-M virt` 下不可用。
5. **构建 flag 问题（重要）**：这棵树的每个编译单元都带
   `-DDEBUG -ftrivial-auto-var-init=zero -fzero-call-used-regs=used-gpr`
   （`src/meson.build:263/556/573`），且构建配置是 `buildtype=debug`。后两条是上游硬化选项，
   `-fzero-call-used-regs` 会在每次函数返回前清零已用寄存器，对 TCG 这种调用密集的代码
   很可能有可观开销。**但本批没能测出来**：两次对照构建分别停在 `sep.h`→`nettle/drbg-ctr.h`
   和一处 `vsnprintf` 相关错误（处理方式见 §5.5）。**且 App 跑的是 stock 11.1.2，它本来就没有
   `-DDEBUG`**，所以这条对 App 不构成可落地杠杆。
6. **端到端的大头在执行侧，而且不止一处**：
   * 第 1 段：单帧 1.58 s 中 **1.14 s（72%）**是来宾把 10.9 MB 画面拷出（≈9.6 MB/s）；
   * 第 2 段：宿主 `screendump` 写 8 MB PPM 实测 **30–41 ms**（同步 HMP，文件已写完），
     与 App 侧 40–56 ms 自洽；早期「250 ms 外推」已作废（异步命令读到了半截文件）。
7. **20 FPS 的算术（实测值 + 一步推断）**：20 FPS = 50 ms/帧，当前 1580 ms → 需 **31×**。
   即使把第 1 段完全消除，只剩 440 ms 来宾 + ~56 ms 截屏 ≈ 0.5 s/帧 ≈ **2 FPS**。
   所以 20 FPS 只能来自「宿主合成 + 零拷贝上屏」，改 QEMU 编译选项或 TCG 都不可能到。
8. **当前实际内容帧率实测：0.05–0.7 FPS**（来宾自报，典型 0.1–0.3）。

## 0.5 数据来源（每个数字用哪个二进制跑的）

**这一栏必须先读**：App 实际运行的是内置的 **stock QEMU 11.1.2（bionic）**，而本仓库源码树
`qemu-inferno.tfi5zu` 是 **ChefKissInferno 10.2.2 hybrid（glibc）**。两者不是同一份源码。

| 数据 | 来源二进制 | 可否代表 App |
|---|---|---|
| `redwood-gl: frame` 帧计时（1.58 s / copying out 1.14 s）、驱动帧率（0.05–0.7 FPS）、66 帧 | 桌面成功运行 `inferno-boot.bykOAn`/`oTZvyQ`。**目录里没有记录二进制**；`docs/qemu-performance-drivers.md` 写的是「Android QEMU 11.1.2 …跑到真正的 SpringBoard」，App 内置也是 11.1.2 → 最可能为 stock 11.1.2 | **很可能代表**（无法从产物确证） |
| 翻译成本 2.7%、TB 结构、锁等待、dispatch/links | **Inferno 10.2.2 glibc + 自建插桩** | **不代表**；只说明 TCG 机制 |
| bionic 新旧 A/B（§3.11） | old = stock 11.1.2 bionic；new = ChefKissInferno 10.2.2 bionic | old 组可当 App 基线 |
| `tb-size`/`-cpu max`/mem 后端对照 | Inferno 10.2.2 glibc（stock 二进制） | **不代表**，但 `tb-size` 的结论（不 flush）由 QEMU 通用机制决定 |
| screendump 30–41 ms | Inferno 10.2.2 glibc | 路径是 QEMU 通用代码，量级可参考 |

结论：**与「用户实际感受」直接相关的只有 §3.6 帧率、§3.11 old 组、§3.10 的 CPU 利用率口径**；
翻译侧数字必须标注为「实验树机制测量」。下一批第一件事应当是**用 stock 11.1.2 跑到桌面**，
复测内容帧率与逐秒 CPU 利用率（见 §8.5）。

## 1. 环境与固定身份

| 项 | 值 |
|---|---|
| 宿主 | vivo V2463A / SM8750，aarch64，8 核可见，无 `/dev/kvm`，proot Ubuntu 24.04 |
| QEMU（基线） | `qemu-inferno.tfi5zu/build-final/qemu-system-aarch64`，113,063,344 B，SHA-256 `0550a6588c6c0b8d8bc32b1a46204e72a8949ec79ce360991efc4a63960630f4` |
| `--version` | `ChefKissInferno-1.0.0rc1_QEMU-10.2.2_10.1.3_hybrid` |
| 源码 | `qemu-inferno.tfi5zu/src`，commit `cc4302a99167abec69b714cfd00c38caece7e7de` |
| guest 内存镜像 | `/root/workspace/ram-correct.img`，12,884,901,888 B，`readonly=on,share=off`（写时复制，不修改） |
| payload（XNUP） | `redwood-settings-content.M5VER6/boot/payload.bin`，21,855,712 B，SHA-256 `6e1d65267c100390d6e86dca3f01904af4132c512157af0ae9234a4cfd7ec497` |
| 固件 | `QEMU_EFI.fd` SHA-256 `5f8ef962…39181a`；`QEMU_VARS.fd` SHA-256 `bd3c2962…c5c` |
| boot-args | payload 内烘死：`debug=0x8 serial=3 rd=md0 loader_return_after=1200 … wdt=-1 wdt_test=no_user_checkin launchd_unsecure_cache=1 bootlog=0 rec_secs=1200 …` |

`bootlog=0` 已烘进 payload，所以每次试验画面都没有日志叠层，boot-args 完全一致。

### 动态库注记（运行前提，不是系统改动）

二进制链接 `liblzfse.so.1`（系统未装）与 nettle 3.10.2（系统 3.9.1 缺 `NETTLE_8` 的
`nettle_drbg_ctr_aes256_init`）。两者缺一都直接起不来。试验用源码树里已解包的依赖
（`deps/nettle-3.10.2/lib`、`deps/extracted/usr/lib/aarch64-linux-gnu`）经
`LD_LIBRARY_PATH` 注入；系统目录未变。

## 2. 方法

### 2.1 固定命令行

```
-L <share> -M virt,gic-version=3 -cpu cortex-a76 \
-smp 2,sockets=1,cores=2,threads=1 -m 7G -accel tcg,thread=multi,tb-size=1360 \
-chardev socket,id=gpu,path=<trial>/gpu.sock \
-netdev user,id=net0,restrict=on,guestfwd=tcp:10.0.2.100:19484-chardev:gpu \
-device virtio-net-device,netdev=net0 \
-drive if=pflash,format=raw,readonly=on,file=QEMU_EFI.fd \
-drive if=pflash,format=raw,file=<trial>/vars.fd \
-drive file=fat:rw:<trial>/esp,format=raw,if=virtio \
-device loader,file=<trial>/payload.bin,addr=0xf0000000,force-raw=on \
-object memory-backend-file,id=ram,size=7G,mem-path=/root/workspace/ram-correct.img,readonly=on,rom=off,share=off \
-machine memory-backend=ram -global virtio-mmio.force-legacy=false \
-device virtio-tablet-device -device virtio-keyboard-device -device ramfb \
-qmp unix:<trial>/qmp.sock,server=on,wait=off -display none -monitor none \
-serial file:<trial>/serial.log -no-reboot
```

对照只替换被评估的一项（`-accel` 的 `tb-size`/`thread`/`split-wx`、`-cpu`、追加
`-mem-prealloc` 或 `-overcommit mem-lock=off`），其余逐字不变。每次试验把完整命令行写进
`<trial>/cmdline.txt` 并复制进 `results.json`。

### 2.2 测量窗口

* 筛选窗口 **180 s**（固定墙钟，父代理建议的 120–180 s 区间）；完整基线另跑 **240 s**。
* 阶段定义：`== XNULOADER|cmdline:` → 第一个 `redwood-gl: frame N` → `graphics handoff|
  bootlog retired` → `SBHomeScreenWindow`；另记 `MiniIOMobileFramebuffer: swap N`。
* 串口行由脚本每秒增量读取并打上**宿主单调时钟**，不依赖来宾日志内时间戳。
* 固定窗口而不是「跑到某阶段」：避免「跑得快的配置样本更少」的偏差。

### 2.3 采集

1. `/proc/<pid>/stat` 的 utime/stime（1 Hz）→ QEMU 进程 CPU 时间；按 QMP `query-cpus-fast`
   的 vCPU 线程 ID 从 `/proc/<pid>/task/<tid>/stat` 分线程记录；RSS；GPU worker 的 CPU。
2. QMP `x-query-jit`（`accel/tcg/monitor.c:19` → `tcg_dump_stats()`）在 15/60/120/180(240) s
   各取一次，得到 TB 数、gen code size/capacity、avg target/host size、expansion ratio、
   cross page TB、direct jump、TB flush/invalidate、TLB flush 计数。
3. 温度：每个样本读 `thermal_zone*/temp` 的最大/均值；报告给开始前/窗口内最大/结束后。
4. 宿主负载：**本 proot 内 `/proc/loadavg`、`/proc/stat`、`/proc/uptime` 全部
   `Permission denied`**（已实测），`uptime`/`top` 也不可用。因此用 `ps` 派生：
   可运行（R）进程数、全系统 `%CPU` 之和、以及 `java/gradle/kotlin/qemu-system` 的
   **CPU 占用和**。

### 2.4 为什么没有「翻译/执行」的精确拆分（以及怎么补）

* `-d stats` 不存在；`-d help` 只有 `in_asm/op/exec/...` 这类逐 TB/逐指令日志，写盘量不可用于计时。
* 构建未启用插件：`-plugin` 是非法选项（`libinsn`/`libexeclog` 不可用）。
* 未编入 `CONFIG_TCG_STATS` 式逐指令计数或 gprof 剖析器；`accel/tcg/tcg-stats.c` 只聚合 TB 级量。
* 因此精确占比靠**自建插桩**（第 7 节）：在 `tcg_gen_code()` 内按阶段取
  `g_get_monotonic_time()`，把累计值接到 `info jit`。这是测量，不改变翻译行为。

### 2.5 宿主繁忙判定（含一次自查修正）

最初按「进程名出现 java/gradle」判定，结果把**长期驻留但空闲的 Gradle/Kotlin 守护进程**
判成繁忙，导致污染标记全为「是」。现改为：`java/gradle/kotlin/qemu-system` 的
**CPU 占用和 ≥ 60%（0.6 核）**才算繁忙；并且**排除本试验自己的 QEMU/worker pid**
（否则自己的 QEMU 140% CPU 会把每条样本都标成污染）。

### 2.6 可复跑脚本

* `tools/cpu-tcg-bench.py`：默认 dry-run，`--execute` 才跑；`--out` 下每次建
  `<时间戳>-<tag>-r<N>/`；只拷 `esp/vars.fd/payload.bin`（约 86 MB），12 GiB 的 ram 镜像
  只读+COW 复用；结束默认删临时副本、保留 `results.json`（含逐秒样本与 QMP 快照原文）。
* `tools/cpu-tcg-report.py`：把多个 `results.json` 汇总成对照表，`--base` 给相对差值。

```sh
python3 tools/cpu-tcg-bench.py --tag base --seconds 180            # dry-run
python3 tools/cpu-tcg-bench.py --tag base --seconds 180 --execute --repeat 2
python3 tools/cpu-tcg-bench.py --tag cpumax --cpu max --seconds 180 --execute
python3 tools/cpu-tcg-bench.py --tag tb512 --tb-size 512 --seconds 180 --execute
python3 tools/cpu-tcg-report.py --dir /root/workspace/hackios-run/tcg-bench --base base180
```

## 3. 基线实测数字

环境：MTTCG 2 vCPU、`cortex-a76`、`tb-size=1360`、`m 7G`、`ramfb` + Adreno worker。
温度是窗口内 `thermal_zone` 最大值；「可运行」是 `ps` 里 R 状态进程数（loadavg 不可读）。

| 试验 | 窗口 | 阶段时间 | 帧 | 串口行 | QEMU CPU | worker CPU | TB 数 | gen code | flush | 温度 |
|---|---|---|---|---|---|---|---|---|---|---|
| base1360（r1） | 240 s | loader 7.70, 首帧 70.22 | 2 | 2039 | 334.7 s | 0.48 s | 467,819 | 234 MB | 0 | 69.9 ℃ |
| base180 | 180 s | loader 7.70, 首帧 83.72 | 1 | 1849 | 243.4 s | 0.32 s | 465,903 | 222 MB | 0 | 61.0 ℃ |
| cpumax180（`-cpu max`） | 180 s | loader 7.74, 首帧 77.04 | 2 | 1800 | 258.3 s | 0.48 s | 467,384 | 224 MB | 0 | 65.2 ℃ |

240 s 基线的 `info jit` 快照（原样，t=241 s）：

```
gen code size       234179704/1425981440
TB count            467819
TB avg target size  17 max=1332 bytes
TB avg host size    256 bytes (expansion ratio: 14.7)
cross page TB count 0 (0%)
direct jump count   210346 (44%) (2 jumps=168578 36%)
TB hash buckets     217860/262144 (83.11% head buckets used)
TB hash occupancy   42.22% avg chain occ.
TB flush count      0
TB invalidate count 208
TLB full flushes    0
TLB partial flushes 394517
TLB elided flushes  3828968
```

读法：

* **翻译缓存远未填满**（16.4%），所以本阶段所有 `tb-size` 加大都不会改变 flush 行为。
* **TB 极碎**：17 字节来宾码/TB（≈4.3 条指令）、256 字节宿主码/TB。每个 TB 的固定开销
  （prologue/epilogue、优化与活跃性分析的建表、寄存器分配初始化）被摊在极少的指令上。
* **跨页 TB = 0**：`cpu-exec.c:945` 里「跨页 TB 不建立直接跳转」的那条限制在本负载下不生效，
  也就是说「跨页 TB 不链化」不是本项目的瓶颈。
* **44% 的 TB 已建立直接跳转**；剩下的多半是间接分支目标（BR/RET）与只执行过一次的 TB。
* **SMC 失效 208 次**：重翻译量可忽略。

### 3.1 重复性与噪声

首帧时间在三次运行里是 70.2 / 77.0 / 83.7 s（同一配置族），极差 13.5 s，约占 17%。
因此**单次运行的首帧差异 <15% 不能作为结论**；这也是下文只用「同一次运行内的阶段差」
以及「同窗口的 TB 数/CPU 时间」做比较的原因。


### 3.2 主指标：翻译成本占比（插桩实测，n=2）

插桩版（第 7 节 patch，实测耗时单位 µs）在同样的 120 s 窗口跑两次：

| 试验 | QEMU CPU | TB 数 | 翻译总计 | **占比** | 每 TB | translate | optimize | liveness | emit | tb_link | tb_alloc | page_lock |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| instrbase120 | 173.7 s | 465,738 | 4.66 s | **2.68%** | 10 µs | 1.00 s | 0.77 s | 1.37 s | 1.43 s | 0.80 s | 0.04 s | 0.06 s |
| instrbase120b | 182.7 s | 466,457 | 5.08 s | **2.78%** | 10 µs | 1.12 s | 0.85 s | 1.49 s | 1.53 s | 0.89 s | 0.04 s | 0.07 s |

两次一致：**翻译成本 = QEMU CPU 时间的 2.7%**（含 tb_link/alloc/pagelock 为 3.2%）。
拆分（取两次均值）：`emit`（寄存器分配+发射）39%、`liveness` 37%、`translate`（来宾解码→TCG op）
23%、`optimize` 19%、`reachable` 2%。

### 3.3 MTTCG 串行化：实测否掉了这个假设

| 锁/等待点 | 累计 | 占 QEMU CPU |
|---|---|---|
| `tcg_tb_alloc` 内的 region 分配锁 | 0.04 s | 0.02% |
| `tb_lock_page0` 页锁 | 0.06 s | 0.03% |
| `tb_link_page`（qht 插入 + 页表更新，非等锁） | 0.80 s | 0.46% |
| `tb_add_jump` 成功链化次数 | 242,040 次 | — |
| 主循环 dispatch 次数 | 8.65M 次 | — |

即：**`thread=multi` 下翻译并没有被锁串行化**——锁等待合计 0.1 s / 173.6 s CPU（0.06%）。
「缩小 tb_lock 粒度 / lock-free TB 分配」在这条负载上没有收益空间。`tb_link` 的 0.8 s 是哈希
插入与页表更新的固有成本，不是争用。

### 3.4 目标算术（这是本文最重要的一节）

* 翻译侧现在 2.7%。**即使翻译成本降到 0，端到端也只有约 2.7% 的空间**；
  降 50% ⇒ 约 1.3%。
* 因此「翻译效率 -50%~-60%」只能作为**相对指标**承诺（4.66 s → ~2.3 s），
  不能表述为「App 快 50%」。
* 端到端的大头在执行侧：单帧 1.58 s 里 1.14 s（70%）是来宾把 10.9 MB 画面拷出
  （软 MMU 存储路径 + 来宾 memcpy）。要实现 50% 量级的端到端改善，必须打这条路径
  （零拷贝 / IOSurface 直通 / 减少整帧拷贝），不是 TCG 翻译。


### 3.6 内容帧率基线（来宾自报，实测）

用空闲成功运行 `inferno-boot.bykOAn`（真进桌面、172 次 Adreno 回读）的串口统计：

| 量 | 值 |
|---|---|
| 整次启动内 `redwood-gl: frame` 总数 | **66 帧**（约 1185 s 观测） |
| 驱动自报帧率 `MiniIOMobileFramebuffer: N frames in T ms (X a second)` | 54 个样本，整数 **X 全为 0**；原始比值 0.049–0.66 fps（如 3 帧/5.35 s=0.56、2 帧/40.8 s=0.049） |
| 单帧总耗时（60 条完整计时） | 中位 **1.58 s** |
| 其中 `copying out`（来宾把画面拷出 GL 上下文） | 中位 **1.14 s**，占 **72%** |
| `handing on` / `waiting` | 2–4 ms / ~0 ms（可忽略） |
| `before it for IOSurfaces`（纹理回填） | 中位 0 s，25/60 帧非零 |

**当前实际内容帧率 ≈ 0.05–0.7 FPS**，典型 0.1–0.3 FPS。与「每帧 1.58 s」自洽。

### 3.7 20 FPS 是否可达（基于上面数字）

* 20 FPS = **50 ms/帧**；当前 1580 ms/帧 → 需要 **约 31 倍**改善。
* 即使把 1.14 s 的拷贝**完全去掉**，只剩约 440 ms/帧 ≈ **2.3 FPS**，仍差 8.7 倍。
* 所以 20 FPS 不是「去掉一次拷贝」能达到的：必须同时把**来宾侧合成/光栅化**也移出 TCG
  （宿主机渲染 + 零拷贝上屏），或者把来宾合成路径本身加速一个数量级。
* 这也解释了为什么翻译侧优化（2.7% 占比）与 20 FPS 无关：它最多动 1.3%。

### 3.8 传输通道与「1.14 s 三段拆分」的现状

已有的可测事实：
* 拷贝量 1125×2436×4 B = **10,962,000 B ≈ 10.9 MB**；1.14 s ⇒ **9.6 MB/s**。这个速率对
  memcpy 太慢，对「slirp TCP + 来宾 TCP 校验和/拷贝」这一量级反而合理——但**尚未证明**。
* worker 日志只有 `EAGL readback: ... size=1125x2436` 计数，**没有耗时字段**；因此
  「宿主 `glReadPixels` / slirp 传输 / 来宾写 framebuffer」三段目前**无法从现有日志拆开**。
  要拆必须给 worker 加计时（宿主侧 `clock_gettime` 包住 readback 与 send），这需要 worker 源码
  与一次宿主侧构建，本批未做。
* `--gpu off`（不建 gpu chardev/virtio-net）**不可用**：来宾连 XNULOADER 都进不去
  （120 s 只有 17 行串口、QEMU CPU 10.3 s）。说明该 virtio-net + guestfwd 设备在引导关键路径上，
  不能靠摘掉设备来消除回读。

工程代价估计（**推断，未实现**）：
* (a) 换传输（virtio-serial / vsock / ivshmem 共享内存）：来宾需要新驱动或新设备节点，worker 与
  来宾两侧都要改；收益上界 = 把 1.14 s 压到传输本身的下限，但**仍剩 440 ms 合成**，天花板 ~2.3 FPS。
* (b) 宿主渲染结果直接写来宾 ramfb（QEMU 加写入口）：砍掉 readback + 传输 + 来宾拷贝，
  但来宾仍要合成 SpringBoard 内容（`redraw` 的 GL 调用与光栅化在来宾 OSMesa 里）。
  只有把**这一步也搬到宿主**，才可能到 20 FPS。
* 因此 20 FPS 的路线应是「宿主侧合成 + 共享内存/零拷贝上屏」，属于阶段 6 的 GPU 后端，
  不是 TCG 效率问题。


### 3.9 第 2 段拷贝：宿主 `screendump` 写 PPM（实测，已修正一次错误外推）

App 侧取帧的第二次整帧拷贝：QEMU 把 framebuffer 转成 PPM 写文件，App 读回解码。
**第一版测量是错的**：QMP `screendump` 是异步的，命令返回时文件只写了一半（我 stat 到 1.4 MB），
按字节线性外推得到「8.2 MB ≈ 250 ms」——这个数作废。

改成同步 HMP `screendump`、5 ms 轮询等文件稳定、并在命令返回到那一刻记录已写字节后，实测
（10 个 8 MB 样本，落到 `/dev/shm`）：

| 量 | 值 |
|---|---|
| PPM 大小 | **8,223,953 B = 7.84 MiB**（1125×2436×3） |
| HMP 返回时已写字节 | 8,223,953（**返回即写完**） |
| 往返墙钟 | 中位 **30.0 ms** |
| 完全落盘 | 中位 **41.1 ms**（min 39.8，5 ms 轮询下限约 10 ms） |
| 文件系统对照 | `dd` 8 MB 到同一目录 **945 MB/s**（不是瓶颈） |

结论：**QEMU 侧 8 MB 截屏约 30–41 ms**，与 App 侧「写+读+解码 40–56 ms」自洽。
所以第 2 段成本是 ~56 ms，不是 250 ms；20 FPS 的账由「来宾 0.44 s」支配，不是截屏。

（经验教训：QMP 里所有「启动一个后台任务」的命令都要验证返回时副作用是否已完成，否则计时
会测成「入队时间」。）

### 3.10 帧时间是「TCG 忙」还是「等 GPU/IO」：用 CPU 占用率定性（实测）

不需要拆十段：**QEMU 进程 CPU 占用高 = 卡在 TCG；CPU 低而墙钟长 = 在等 GPU 或 IO**。
用插桩运行的逐秒 `/proc/<pid>/stat` 采样（2 vCPU 上限 = 2.0 核）：

| 时段 | QEMU CPU 利用率 |
|---|---|
| 整 120 s 窗口 | 中位 **1.37** 核、均值 1.46（第二次：中位 1.62、均值 1.53） |
| 帧 1 前 5 s | 1.66 核 |
| 帧 1 → 帧 2 之间 | 1.55 核 |
| 帧 2 后 5 s | 1.60 核 |
| 利用率 < 0.5 核的秒数 | 4 / 110（两次都是 4） |

结论（**限定在引导期首帧**）：帧时间内 QEMU 几乎一直占满 1.5–1.7 核，**没有出现「墙钟长、
CPU 低」的空等**，所以这段帧时间主要是 TCG 在跑来宾代码，而不是等 Adreno/Turnip。
这支持「20 FPS 需要 8.8× 的 TCG 提速，不现实」的判断。

边界：这是**引导期前两帧**（Logo 阶段），不是桌面稳态。桌面每帧 1.58 s 里是否有等 GPU 的部分，
要把同样的 CPU 利用率测到桌面窗口才能定；本批未做。


### 3.11 bionic 新旧二进制端到端 A/B（ABBA，实测）

同一镜像、同一 cmdline、120 s 窗口，顺序 旧→新→新→旧（抵消漂移）：

| 运行 | 首帧 | QEMU CPU | 帧 | 串口行 | TB 数 | 温度 |
|---|---|---|---|---|---|---|
| old_a（QEMU 11.1.2） | 66.51 s | 185.96 s | 2 | 1696 | 465,876 | 54.0 ℃ |
| new_a（ChefKissInferno 10.2.2） | 55.13 s | 173.63 s | 2 | 1801 | 465,988 | 54.8 ℃ |
| new_b | 51.15 s | 172.71 s | 2 | 1864 | 468,357 | 59.8 ℃ |
| old_b | 54.40 s | 173.73 s | 2 | 1806 | 467,264 | 59.8 ℃ |

均值：旧 首帧 **60.5 s** / CPU **179.8 s**；新 首帧 **53.1 s** / CPU **173.2 s**；
**new/old CPU 比值 0.963（-3.7%）**。

判定：**不可分辨**。同组内极差就有 12.1 s（old 66.5 vs 54.4，22%），-3.7% 完全落在噪声内。
要判定 3% 级差异，每组需要约 6 次重复。

**另一个必须先说清的前提**：这两个二进制**不是同源同版本的两种编译选项**——
旧的是 `QEMU 11.1.2`（30,949,440 B），新的是 `ChefKissInferno-1.0.0rc1_QEMU-10.2.2_10.1.3_hybrid`
（37,337,088 B）。所以上面的差值**不能归因到 `--disable-debug`**；它混了 fork/版本差异。
要做「编译选项」的因果判定，必须在同一份源码上编两版。

### 3.5 本次会话内的实测漂移（重要）

同一配置族在会话不同时间的首帧时间：**83.7 → 70.2 → 67.7 → 67.4 → 40.6 → 55.3 → 48.3 → 80.0 s**，
loader 阶段也从 7.7 s 降到 6.5–6.6 s。这不是配置差异，而是：
ram-correct.img（12 GiB）的页缓存随重复运行变热、设备温度在 53–76 ℃ 间变化、宿主偶发负载。
**结论：跨时间的单次墙钟对比不可用**；本报告只把「同窗口内的 CPU 时间、TB 数、翻译占比」
当作可比量，墙钟只作方向参考，且每次重复都记录温度与负载代理。

## 4. 候选对照（运行时参数）

| 候选 | 结果 | 判定 |
|---|---|---|
| `tb-size` 1360 → 2048/4096 | 基线 flush=0、占用 16.4%，加大无机制可起作用 | **不成立**（不需要实测） |
| `tb-size` 1360 → 512 | 可能触发 flush（待补测，编译窗口后做 2×） | 待测 |
| `-cpu max` | 1 次 180 s：首帧 77.0 vs 83.7 s、CPU 258 vs 243 s、TB 467k vs 466k | **无可判定差异**（n=1） |
| `-cpu ...lse=on` | `Property 'lse' not found` | **不可用** |
| `-cpu apple-a13-cpu` / `apple-gxf` | `unable to find CPU model` / `Invalid CPU model` | **不可用** |
| `-mem-prealloc` | 未测（被 bionic 编译窗口打断） | 待测 |
| `-overcommit mem-lock=off` | 未测 | 待测 |
| `-accel tcg,...,split-wx=off` | 120 s：首帧 55.3 s、CPU 174.3 s、TB 466,751（对照 120 s：40.6 s / 160.7 s / 470,135） | 无收益，且 CPU 时间更高（单次，见 3.5 漂移说明） |
| `--ram-share on`（`memory-backend-file` 去掉 readonly） | 120 s：首帧 48.3 s、CPU 168.2 s、TB 466,949 | 无可判定差异（单次） |
| `-mem-prealloc` | 120 s：首帧 80.0 s、CPU 175.0 s、TB 464,836 | 无收益，倾向更慢（预热 7 GB，单次） |
| 大页 `-mem-path`（hugetlbfs） | 无 `/dev/hugepages`，`HugePages_Total`=0 | **不可用** |
| THP | `transparent_hugepage/enabled=[never]`；QEMU 虽对 TB 缓冲请求 `MADV_HUGEPAGE`（`tcg/region.c:723`）也拿不到 | **不可用** |

## 5. 代码层评估

### 5.1 热块/超级块：链化已经做了，剩下的是每 TB 固定开销

* TB 上限 `TCG_MAX_INSNS 512`（`include/tcg/tcg.h:130`），终止条件是「分支 / 输出缓冲满 /
  达到上限」（`accel/tcg/translator.c:175-178`）。**提高上限不会变长 TB**——本负载的 TB 是被
  分支终止的（平均 4.3 条指令），不是被 512 条上限截断的。
* 链化：`cpu-exec.c:900-955` 主循环 → `tb_add_jump()`（`cpu-exec.c:594-630`，`qemu_spin_lock
  (&tb_next->jmp_lock)` + `qatomic_cmpxchg` 占槽 + `tb_set_jmp_target` 原子补丁 +
  `flush_idcache_range`）→ `tcg/aarch64/tcg-target.c.inc:1979-2005` 预留 `B` 槽。
  链上之后两个 TB 之间不回到 C 主循环。
* 所以「超级块」要省的是：TB prologue/epilogue、`insn_start` 记账、`can_do_io` 切换，
  以及每 TB 的优化/活跃性分析固定成本。**代价**：多入口 TB 会破坏「一个 TB ↔ 一组页」的
  失效模型（现在只有 `jmp_reset_offset[2]`、`page_addr[2]`）；MTTCG 下补丁点成倍增加，
  需要重新论证 `CF_INVALID` 的可观测性；热块识别要在 TB 入口加原子计数（直接进快路径）。
* **不做**：本阶段不实现超级块，只记录上面的结构与代价。

### 5.2 寄存器映射：数量上就不成立，且撞 TCG 契约

* 宿主可分配通用寄存器：`X20-X28`(9) + `X8-X15`(8) + `X0-X7`(8) = **25**；固定保留
  `X16/X17`（临时）、`X18`（平台寄存器）、`X19`（`TCG_AREG0`=env）、`X29`(fp)、`X30`、
  `SP`、`V31`（`tcg/aarch64/tcg-target.h:45`、`tcg-target.c.inc:60-92`）。
* 来宾常驻状态：`pc` + `X0..X31` + `exclusive_high` = **34 个 TCG global**
  （`target/arm/tcg/translate-a64.c:83-95`），再加 env 指针本身。25 < 34，**完整固定映射不可能**，
  只能部分映射。
* 契约冲突：`tcg/tcg.c:3785 la_global_sync()` 在基本块结束把 global 标回内存；helper 调用、
  异常/中断从 TB 中间跳出、gdbstub、savevm 都假定 `CPUARMState` 内存是最新的。跨 TB 固定映射
  等于改整个 accel/tcg ↔ target/arm 接口。
* MTTCG 方面反而简单：`tcg/tcg.c:1814 tcg_max_ctxs = max_threads`，每 vCPU 一套 TCGContext。

### 5.3 libSystem 纯函数混合执行：前提多，默认不能开

需要函数级 ABI/语义封闭（无 Mach trap、无 dyld/objc 回调、浮点/errno/TLS 一致）、可拦截的
调用点（静态改写破坏代码签名；dyld interposition 需要 shim 层，本树没有）、状态切换协议
（TCG 没有受支持的「调用宿主函数再精确恢复」路径；宿主若读写来宾内存必须走
`cpu_memory_rw_debug` 或显式 TLB 失效，否则与其它 vCPU 竞争）、以及逐函数等价性验收。
一条不满足就是静默数据损坏，因此**默认不能开**。

### 5.4 virtio-gpu 与 CPU 模型对齐：改变负载结构，不是提高 TCG 效率

* 实测：空闲基线（`inferno-boot.bykOAn` 的 60 条 `redwood-gl: frame`）单帧中位 1.58 s，
  其中 `copying out` 中位 1.14 s（**70%**），每帧约 10.9 MB 从来宾内存拷出。
* 这说明 TCG 的负载结构里**来宾内存拷贝/软 MMU 存储路径比 TB 调度更重**。virtio-gpu 会把
  合成搬到宿主，改变的是这条结构；但 iOS 显示栈是 IOMobileFramebuffer+IOSurface，没有
  virtio-gpu 驱动，属阶段 6。
* `-cpu max`/Apple 模型改的是来宾可见 ISA，理论上影响翻译代码量与指令选择，但不会改变上面
  这条「拷贝占大头」的结构。

### 5.5 构建 flag：`-DDEBUG` 与两条硬化选项

`src/meson.build`：
* 第 263 行 `qemu_common_flags += ['-DDEBUG', '-fno-omit-frame-pointer']`；
* 第 556 行 `hardening_flags = ['-ftrivial-auto-var-init=zero']`；
* 第 573 行 `hardening_flags += '-fzero-call-used-regs=used-gpr'`。

用 `ninja -t commands` 抽查任意对象（如 `libsystem.a.p/tcg_tcg.c.o`），三个 flag **同时出现在
1896 条编译命令里**（binutils 计数 1896 次）。`-ftrivial-auto-var-init=zero` 把每个栈变量清零，
`-fzero-call-used-regs=used-gpr` 在每次函数返回前清零已用寄存器——TCG 是调用密集的代码生成器，
后者的开销理论上最大；`-DDEBUG` 还会打开若干 `#ifdef DEBUG` 的额外检查/打印路径。

已确认构建配置是 **`buildtype=debug, debug=True`**（`build/meson-info/intro-buildoptions.json`），
所以 `-DDEBUG -fno-omit-frame-pointer` 一直生效；hardening 两条是上游无条件加的。

实验方式（修正后）：**不新开 build 目录、不重新 configure**（新 configure 会因
`hw/arm/apple-silicon/sep.h` 需要 `nettle/drbg-ctr.h` 而点亮 CONFIG_NETTLE，本机没有 dev 头）。
改为复制 `build-final` → `tcg-fast3/build`，直接在 `build.ninja` 的 ` ARGS = ` 行里删掉三条 flag
（命中 343 个热路径对象：tcg_/accel_tcg_/softmmu_/system_/exec_/hw_display_/hw_virtio_/hw_net_），
再 `ninja` 增量重编这些对象。这样不动 meson.build、不触发 reconfigure。

**状态：未完成（本批）**。删 flag 后重编到 270/313 时 `hw/arm/apple-silicon/sep.h` 需要
`nettle/drbg-ctr.h`；补 `C_INCLUDE_PATH=$deps/nettle-3.10.2/include:$deps/extracted/usr/include`
后越过该点，又停在一处 `vsnprintf` 相关编译错误。可复跑的命令：

```sh
R=/root/workspace/hackios-run/tcg-fast3
export LIBRARY_PATH=$deps/extracted/usr/lib/aarch64-linux-gnu
export C_INCLUDE_PATH=$deps/extracted/usr/include:$deps/nettle-3.10.2/include
ninja -C $R/build qemu-system-aarch64 -j6
```

**价值判断**：App 跑的是 stock 11.1.2，它本来就没有 `-DDEBUG`；hardening 两条上游也无条件加。
所以即使这个 ABBA 测出收益，也只能作为「若将来换 Inferno 树」的机制证据，**不构成对 App 的
可落地杠杆**，优先级应排在「stock 11.1.2 桌面帧率」之后。

## 6. 失败与不可用项

| 项 | 结果 |
|---|---|
| `-d stats` | 不存在 |
| QEMU 插件 | 未启用（`-plugin` 非法） |
| 逐指令 TCG 计数 / gprof | 未编入 |
| `hackios-run/ram.img` 当固定镜像 | **不可用**：与 `ram-correct.img` 在 3.9 GB 偏移处不同，启动后来宾 8 s panic（`Process 1 exec of /sbin/launchd failed, errno 8`） |
| `apple-a13-cpu` / `apple-gxf` | `-M virt` 下不可用 |
| `-cpu ...,lse=on` | 属性不存在 |
| hugetlbfs / THP | 不可用 |
| `/proc/loadavg` `/proc/stat` `/proc/uptime` | Permission denied |

## 7. 源码插桩 patch（测量用，不改变行为）

`/root/workspace/hackios-run/tcg-exp/0001-instrument-translation-time.patch`（218 行，
`diff -u` 生成），改动 4 个文件：

| 文件 | 插桩点 | 计数 |
|---|---|---|
| `accel/tcg/translate-all.c` | `setjmp_gen_code()` 里 `translate_code()`；`tcg_tb_alloc()`；`tb_lock_page0()`；`tb_link_page()` | 翻译、分配（含 region 锁）、页锁等待、链接（qht） |
| `tcg/tcg.c` | `tcg_gen_code()` 的 `tcg_optimize()` / `reachable_code_pass()+liveness_pass_0/1` / 发射循环（寄存器分配+代码生成） | 四个阶段各自 ns |
| `accel/tcg/cpu-exec.c` | 主循环 `tb_lookup()` 前；`tb_add_jump()` 成功补丁处 | 派发次数、链化次数 |
| `accel/tcg/tcg-stats.c` | `dump_exec_info()` 末尾 | 把上述累计值接到 `info jit` |

只在每个 TB 上加约 7 次 `g_get_monotonic_time()`（每次约 20–30 ns）；以 467k TB 计约
0.1 s 量级，相对 335 s CPU 可忽略。计数用 `__atomic_add_fetch(..., RELAXED)`，MTTCG 下不丢计。

patch 文件：`0001-instrument-translation-time.patch`，8,560 B，SHA-256 前缀
`10fb139da8f1964aa8061718`。已用项目自身的编译参数（从 `build.ninja` 抽出 `cc` 命令行）对
4 个改动文件做过 `-fsyntax-only` 验证：**0 error、0 nested-extern 警告**。

**重建步骤**（`/root/workspace/hackios-run/tcg-exp/build-exp.sh`，默认 dry-run）：

1. `cp -a qemu-inferno.tfi5zu/src tcg-exp/src`（124 MB）
2. `cp -a qemu-inferno.tfi5zu/build-final tcg-exp/build`（381 MB，保留 mtime 以免触发 reconfigure）
3. `sed -i 's#^SRC_PATH=.*#SRC_PATH=<exp>/src#' build/config-host.mak`
   —— `build.ninja` 里的源码引用是 `../src/...`，src/build 作为兄弟目录后自动指向副本
4. `patch -p1 -d src < 0001-instrument-translation-time.patch`
5. `ninja -C build qemu-system-aarch64 -j6`

### 7.1 翻译量敏感度（上界）实验

只测「翻译占比」还不够说明目标是否可达，所以设计了一个**不写代码的敏感度实验**：
`-accel tcg,...,one-insn-per-tb=on` 会把每个来宾指令编成一个 TB（TB 数成倍上升、
per-TB 固定开销全部放大），配合同一份插桩可以测出：

* 翻译成本占比随 TB 数的变化（验证插桩对翻译量敏感、不是噪声）；
* **翻译量放大 N 倍后端到端墙钟变化多少**——这是「翻译对端到端影响」的实验上界。

如果翻译量翻几倍、墙钟只慢一点，那么「翻译成本 -50~60%」的端到端收益上限也就可以直接算出来，
不用靠推断。

## 8. 三栏交付

**A. 现在就能进 APK 的（运行时参数）**

| 项 | 结论 |
|---|---|
| 默认 cmdline | **不改**：`tb-size` 加大无机制（1360 从不 flush）、`-cpu max` 无证据、`lse` 无属性、`apple-*` 不可用 |
| `split-wx=off` / `share=on` / `-mem-prealloc` | 各 1 次 120 s：无收益（`split-wx=off` CPU 174.3 s vs 对照 160.7 s；`share=on` 168.2 s；`-mem-prealloc` 175.0 s）——受 3.5 的漂移限制，只能说「没有证据支持」，不能说「更差」 |
| `--gpu off` | **不可用**：来宾进不了 loader |
| 帧率相关（20 FPS） | 运行时参数**没有任何一项**能把 1.58 s/帧拉向 50 ms；必须改渲染/传输路径 |

**B. 需要 PC 用 NDK 重编 QEMU 的（源码 patch）**

* `tcg-exp/0001-instrument-translation-time.patch`：测量用插桩（已在本机构建、跑通），不建议进包。
* `tcg-fast` 构建：去掉 `-DDEBUG`/`-ftrivial-auto-var-init=zero`/`-fzero-call-used-regs=used-gpr`
  的对照（`src/meson.build:263/556/573`）。**这是目前唯一可能带来两位数整体提速、且改动最小的
  源码级候选**；A/B 结果见「本批完成度」。
* 传输/上屏：宿主直写 ramfb、virtio-serial/vsock/ivshmem ——属阶段 6，需来宾驱动配合。

**C. 推断但未验证**

* 1.14 s 拷贝中 slirp/来宾 TCP 占多大比例（需要 worker 侧计时才能拆）。
* 去掉硬化 flag 的实际收益（重建进行中）。
* 超级块、固定寄存器映射、libSystem 混合执行的收益。
* 20 FPS 需要「宿主合成 + 零拷贝」这一判断基于「零拷贝后仍剩 440 ms/帧」的算术，属推断，
  但所用 1.58 s / 1.14 s / 0.44 s 都是实测值。

## 8.5 本批完成度

| 项 | 状态 | 来源二进制 |
|---|---|---|
| 内容帧率基线（0.05–0.7 FPS，66 帧，单帧 1.58 s / copy-out 1.14 s） | ✅ 实测 | 桌面成功运行（最可能 stock 11.1.2；无法从产物确证） |
| 帧时间定性（QEMU 1.37–1.66 核，非等 GPU） | ✅ 实测（引导期前两帧） | Inferno 10.2.2 glibc |
| 翻译成本占比 2.68% / 2.78% | ✅ 实测 | **Inferno 10.2.2 glibc + 插桩** |
| 阶段拆分 / 锁等待 / dispatch / TB 结构 | ✅ 实测 | Inferno 10.2.2 glibc |
| `screendump` 8 MB 成本（30–41 ms） | ✅ 实测（已修正一次错误外推） | Inferno 10.2.2 glibc |
| bionic 新旧 ABBA（-3.7% CPU，不可分辨；且混版本） | ✅ 实测 | old=stock 11.1.2，new=ChefKissInferno 10.2.2 |
| 去掉 hardening flag 的 ABBA | ❌ 未完成：重建到 270/313 时 `hw/arm/apple-silicon/sep.h` 需要 `nettle/drbg-ctr.h`，补 `-I deps/nettle-3.10.2/include` 后又撞到另一处编译错误；且 App 跑 stock 11.1.2，该实验已降级为机制验证 | Inferno 10.2.2 glibc |
| **stock 11.1.2 的桌面帧率与桌面 CPU 利用率** | ❌ **未测（最重要缺口）** | — |
| SBHomeScreenWindow 总墙钟（2 次） | ❌ 未测 | — |

## 8.6 下一批（按优先级）

1. **用 stock 11.1.2 跑到桌面**（约 12–15 min/次 ×2），量：到 `SBHomeScreenWindow` 的总墙钟、
   桌面 60 s 窗口内的内容帧率、逐秒 QEMU CPU 利用率。这决定「用户实际感受」的全部结论，
   也是唯一能把 §3.6 的数据从「很可能」变成「确证」的办法。
2. 硬化 flag 若要测，必须先把 `nettle` 内部头与 `-Werror` 类编译错误在本机副本里解决
   （`C_INCLUDE_PATH=$deps/nettle-3.10.2/include:$deps/extracted/usr/include`），再
   `ninja -j6`；本批两次尝试分别停在 `sep.h` 和一处 `vsnprintf` 相关告警/错误。
3. 若桌面 CPU 利用率明显低于 1.5 核 → 说明桌面帧时间里有等 GPU/IO，20 FPS 结论要改；
   若仍接近 1.5 核 → 证实「TCG 跑来宾合成」是墙。
4. 桌面分辨率下重复 `screendump`（本批已在 8 MB 下测得 30–41 ms，只差桌面窗口验证）。
5. 硬化 flag 的 ABBA（本批未完成；只作机制验证，不影响 App 二进制决策）。
6. 若要动 TCG：只做 liveness 快速路径（1.37 s→目标 ≤0.9 s），并接受端到端 ≈0.4% 的上限。

## 9. 未验证边界

* **总墙钟主指标未测**：本批所有运行都是 120–240 s 窗口，只到 Logo/首帧；`SBHomeScreenWindow`
  引用的是 `docs/boot-console-density.md` 的已验收运行（+829 s），不是本次复测。
* 翻译/执行占比只在启动期（前 120 s）测过；桌面上稳态的翻译占比可能更低（代码已翻译完）。
* 三段拆分与 worker 侧计时缺失；20 FPS 的可行性判断建立在「零拷贝后仍剩 440 ms 合成」的算术上。
* 墙钟漂移极大（首帧 40.6–83.7 s），跨时间的单次比较无效；下一批应做 A/B 交替（ABAB）而不是串行。
* 温度 53–76 ℃，未采频率，无法排除降频。
## 9. 未验证边界

* `SBHomeScreenWindow` 级别的长跑基线本阶段未重测（父代理的 bionic 编译占用了唯一空闲窗口）；
  引用的 +418 s / +829 s 来自 `docs/boot-console-density.md` 的已验收运行。
* 翻译/执行占比尚无实测（插桩 patch 未编译）。
* `tb-size 512`、`-mem-prealloc`、`-overcommit`、`split-wx` 未判。
* GPU worker 走 Adreno（Vulkan/zink），其驱动行为不受本文控制；已单独记录 worker CPU
  （0.3–0.5 s/窗口，可忽略）。
* 温度在连续试验中从 56 ℃ 升到 70 ℃，可能带来降频；本文用同窗口与重复对照控制，但没有采频率。
