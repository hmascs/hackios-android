# HackiOS 来宾内核精简试验

目标是在 1125×2436 的 Adreno 路径上评估来宾 CPU 开销。此试验采用 BCM2837 的 DEVELOPMENT 配置和 2 个 vCPU。

## 构建改动

- 给 `DEVELOPMENT` 增加 `config_skip_precise_user_kernel_time`。BCM2837 没有 `HAS_FAST_CNTVCT`。该选项让 ARM64 异常入口和返回路径省去精确的用户态与内核态分账。XNU 仍积累 CPU 用量，并在向用户态报告时将合并的时间归入用户时间。[BCM2837 配置](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/tree/config/MASTER.arm64.bcm2837#L74>) · [ARM64 板级判断](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/tree/pexpert/pexpert/arm64/board_config.h#L134>) · [CPU 用量汇总](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/tree/osfmk/kern/recount.c#L436>)
- 从 `VM_DEV` 移除 `ubsan_minimal`，并以 `UBSAN_MINIMAL_RUNTIME=DEBUG` 跳过 DEVELOPMENT 的 `-fsanitize=signed-integer-overflow` 与 minimal runtime 编译参数。这组开关同时覆盖 sanitizer 的插桩和运行时配置。[VM_DEV 配置](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/tree/config/MASTER.arm64.bcm2837#L65>) · [编译参数来源](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/tree/makedefs/MakeInc.def#L507>)
- 保留 `HAKIOS_DRIVERS`、文件系统、网络和安全相关配置。也保留 DTrace、IOKit 统计及其他 DEVELOPMENT 选项，以便限制本轮的变更范围。[原机型配置](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/original/MASTER.arm64.bcm2837#L73>)

## 精简路线与风险（只对第一路线已有构建产物）

1. **当前最小组合路线（已编译；效果待配对测量）**：关闭精确 user/kernel 分账，移除 UBSan minimal 插桩/运行时；保留其它 DEVELOPMENT 设施。风险：用户/系统 CPU 记账精度降低，并且两个变量同时改变，后续任何速度差异只能归因于该组合，不能声称是哪一项单独起效。可先用原版 kernel 做“只关 UBSan”分支，隔离精确记账这个行为变化；需新的独立输出树与独立 payload。
2. **仅关闭 UBSan 路线（建议下一条隔离路线）**：保留 `config_skip_precise_user_kernel_time` 不启用，只移除 `ubsan_minimal`/对应 `-fsanitize=signed-integer-overflow`。预期行为差异最小，适合校准UBSan的编译/运行时成本；仍需保持其它 DEVELOPMENT 配置与驱动不变。此路线目前**未构建、未启动、未实测**。
3. **扩大到 RELEASE 子组（风险更高，暂不建议直接发货）**：从 `PERF_DBG_DEV`、`MACH_DEV`、`IOKIT_DEV`、`BSD_DEV` 等切到 RELEASE 组，可去掉更多诊断/记账功能；但它们可能包含本移植依赖的配置选项、驱动支持与调试能力，可能改变内核行为甚至无法启动。应一组一组改，生成 config 后对比差异、单独构建/启动，不能把所有 RELEASE 组一次性换入。

当前生成配置核验：`san/DEVELOPMENT/config.DEVELOPMENT_ARM64` 与 `osfmk/DEVELOPMENT/config.DEVELOPMENT_ARM64` 均列出 `CONFIG_SKIP_PRECISE_USER_KERNEL_TIME`；`san/DEVELOPMENT/config_ubsan_minimal.h` 内容为 `#define CONFIG_UBSAN_MINIMAL 0`。lean 对象树 `.CFLAGS`（2,005 字节）不含 `-fsanitize-minimal-runtime` 或 `-fsanitize=signed-integer-overflow`，原 DEVELOPMENT 对象树 `.CFLAGS`（2,139 字节）两者都有。`llvm-nm -g` 检查 lean unstripped kernel 无 `ubsan` 符号，原发行 kernel 有 4 个相关锁/数据符号；`locore.o` 的 recount_enter/leave_user 调用 relocation 从原版移除。recount 符号仍存在于 lean kernel 其它路径，因此不可误写成“内核删掉了 recount”。

## 工具链与命令

原产物与 App 资产均为 SHA-256 `7b1f908324fa13154c065f917555852406ddfbfc654f6c247c78c6d6a0644f17`。实验树复制了对应源码，采用自己的 `BUILD/obj`。原配置及原内核备份存于实验目录下。[App 原版内核](<app/src/main/assets/mach.development.bcm2837>) · [实验备份](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/original/mach.development.bcm2837>)

历史 RELEASE 日志中的 `DAPPLE: No such file or directory` 和 `installfile: open(gssd_mach.h): No such file or directory` 来自 `storage-tools` 的环境。该环境的工具目录缺少 MIG，日志另报 macOS `Kernel.framework` 的头文件目录缺失。配套的环境包含 `mig`、`migcom` 与头文件。DEVELOPMENT 实验从干净对象目录重新生成了 `clock_priv.h` 和 `gssd_mach.h`。[历史构建日志](</root/workspace/hackios-run/kernel-release-build.log#L238>) · [配套环境](</root/workspace/hackios-run/xnu-bootlog-build.qzbibE/xnu-env.sh>) · [干净构建日志](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/build.log#L263>)

```sh
set -euo pipefail
ref=/root/workspace/hackios-run/xnu-bootlog-build.qzbibE
trial=/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN
export THEOS=/root/workspace/hackios-run/xnu-toolchain-v1/theos
export XNU_SRC="$trial/tree" LC_ALL=C
source "$ref/xnu-env.sh"
HOSTCC="clang -include $XNUDEPS/hostinc/hostcompat.h -I$XNUDEPS/hostinc \
-Wno-unused-command-line-argument -Wno-error=implicit-function-declaration"
flock -x /tmp/hackios-qemu-trial.lock make -C "$XNU_SRC" \
  MAKEJOBS=--jobs=2 HAKIOS_OBJECTS= SDKROOT=iphoneos \
  ARCH_CONFIGS=ARM64 MACHINE_CONFIGS=BCM2837 KERNEL_CONFIGS=DEVELOPMENT \
  ARCH_STRING_FOR_CURRENT_MACHINE_CONFIG=arm64 BUILD_WERROR=0 BUILD_LTO=0 \
  DO_CTFMERGE=0 HOST_CC="$HOSTCC" HOST_FLEX=flex HOST_BISON=bison \
  HOST_GM4=m4 CC=xnu-clang CXX=xnu-clang++ LOGCOLORS=n \
  UBSAN_MINIMAL_RUNTIME=DEBUG build
```

这次构建从空对象目录开始。顶层 Makefile 将传入的 `-j2` 覆盖成 `--jobs=9`，生成头文件并开始编译后改用 `MAKEJOBS=--jobs=2` 续编。内核编译、链接和剥符号完成。默认 `all` 目标的后置 `check_all_exports` 退出 2。`exported name not in import list` 的 91 个名字与发行内核构建日志中的缺项完全一致，包含 DriverKit 的导出。独立的 `build` 目标退出 0。[顶层 Makefile](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/tree/Makefile#L239>) · [后置检查](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/build-j2.log#L1156>) · [原构建的同一检查](</root/workspace/hackios-run/xnu-bootlog-build.qzbibE/kernel-logo-overlay-v2.log#L632>) · [内核构建日志](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/build-kernel.log>)

## 产物与静态核验

精简内核为 Mach-O arm64，15,574,384 B，比原版少 216,856 B。SHA-256 为 `f73df2af3cfff44de6f582b837aa915506481b5950a4f2115322fa0208d5d79c`。`llvm-objdump -r` 确认 ARM64 `locore.o` 移除了对 `_recount_enter_user` 和 `_recount_leave_user` 的调用重定位。`llvm-nm` 确认五个 UBSan 最小运行时处理函数已移除。原版有 23,093 个已定义的外部符号，精简版有 23,088 个。差异为 `_no_precise_pmcs` 和四个 UBSan 相关名字。[精简产物](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/tree/BUILD/obj/DEVELOPMENT_ARM64_BCM2837/mach.development.bcm2837>) · [入口和返回的编译开关](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/tree/osfmk/arm64/locore.s#L559>)

精简 payload 的 SHA-256 为 `f1468746bb423f1b48eac640e596cf31899bc855fb25397edb6cdb41d1443fa3`。替换脚本校验基准内核的发行哈希，再保留其他 XNUP 条目。以原版内核运行脚本后，`cmp` 确认输出与输入逐字节一致。[替换脚本](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/replace-kernel.py>) · [精简 payload](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/payload-lean.bin>)

## 启动与帧率

配对试验均使用从 App 归档提取的 QEMU 11.1.2（`20be6a6b99733888e6b46d26d9bed058e5f6dae80cab44fa9b0c7dc4fc6783e3`），来宾画面为 1125×2436，2 vCPU，TCG 多线程，TB 缓存 1360 MiB。Adreno worker 与来宾 dylib 均为 delta v2。两次的 QEMU 参数、环境和非内核 XNUP 项匹配。启动 ramdisk 的 SHA-256 在两次都是 `5bc1202b609ceb4d879ece4cf5f034c38029fe9c98fcb7e97690a14ece691e9e`。测量安排在第 10 次回读后的第 30 秒开始，持续 150 秒。计量脚本输出宿主时间窗口内的帧环发布率和采样 GL 成本。实际内容帧率还需核对连续画面变化。[配对命令](/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/run-paired-trial.sh) · [精简版输入](/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/desk-lean/identity.json) · [原版输入](/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/desk-baseline/identity.json) · [计量脚本](/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/harness/frame-stats.py)

此次精简组合在 840 秒启动预算内完成 4 次回读。首帧在 96.04 秒出现；串口记录 Darwin、SpringBoard、backboardd 及 1125×2436 面板初始化，worker 记录 Adreno 830。前 840 秒未见 kernel panic。第 4 次发布与第 2 次的 Apple Logo 按 RGB 逐像素一致，说明这些回读不是桌面内容变化。试验在 `fewer than 10 readbacks after 840.0s` 处结束，桌面测量窗口未开始。故结果是**启动/场景筛查超时**，不是内容 FPS，也不表示精简组合导致了慢启动。 [精简版运行结果](/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/desk-lean/run.json:3) · [回读日志](/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/desk-lean/worker.log:10) · [启动画面](/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/desk-lean/boot-ring-2.png)

精简版串口里 `lsd[26]` 的 CPU monitor self-denial 数量按 2、4、8、16、32、64、128、256、512 增长，约 11:14:10 guest log 报 thread 335 在约 164 秒窗口使用约 55% CPU。旧 fast/iofix 成功试验也有相似 lsd ledger（约 172.9 秒、54%、约 165 秒），故它是背景负载线索，不是精简内核独有证据。只读比对确认本轮与历史 fast/iofix 的 ramdisk hash 同为 `5bc1202b...`，rootfs 普通文件逐项一致，cmdline/rdres/loader 相同；但内核、QEMU 与试验阶段不同，不能据此做帧率因果比较。guest 日志时间仍随 wall 前进，未见全系统停死。

另，worker 的读回日志显示 lean 的 out2→out3 间隔为 609.061 秒，而 out3 请求本身总计 0.626 秒。这说明长间隔发生在**下一次读回请求前**，不能解释为传输路径正耗时 609 秒；目前无法仅凭这些日志区分动画/调度/来宾请求节奏变化。

| QEMU 11.1.2 配对 | 宿主首帧时间 | 840 秒内回读 | 内核 panic 记录 | 桌面测量 |
| --- | ---: | ---: | --- | --- |
| App 原版 DEVELOPMENT `7b1f9083…` | 132.24 s | 3 | 无 | 未达就绪阈值 |
| 精简 DEVELOPMENT `f73df2af…` | 96.04 s | 4 | 无 | 未达就绪阈值 |

原版内核对照也在 840 秒时未达 10 次回读门槛：首帧 132.24 秒、3 次回读、desktop=null；没有 kernel panic。两次都是独立冷启动，所以首帧时刻只是单次观察，不能据 96.04 秒与 132.24 秒断言精简内核提速。两次都未进入桌面或150秒测量窗口，因此不能计算/推断内容 FPS、稳态帧环吞吐或任何内核提速。两轮都受同一套 QEMU 11.1.2 默认 `virt`（解析为 virt-11.1）、相同 delta2 artifact 影响；已知该 delta2 对 padded stride 有图像差分缺陷，回读序列并非足以验收画面。后续应先修复图形回写并使用连续画面验证，再单独调查该场景的 boot/request 间隔。历史 Inferno 测试中的 `virt` 则解析为 virt-10.1；它是重要兼容性变量，但并非已经证明的慢启动根因。[原版运行结果](/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/desk-baseline/run.json:3) · [原版 identity](/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/desk-baseline/identity.json) · [Machine alias review](/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/machine-compat-review.md)



## Frozen HFR2 recovery-baseline diagnostic (virt-10.1)

## HFR2 D123 postplan recovery run (virt-10.1)

The corrected D1–D3 candidate was booted with the stock DEVELOPMENT kernel `7b1f9083…`, QEMU `20be6a6b…`, `virt-10.1,gic-version=3`, 2 vCPU, 1125×2436, and HFR2 v2. The prebuilt root source, copied `root.apfs`, and embedded XNUP ramdisk all matched SHA-256 `dd60af05…` (5,046,272 bytes); guest was `2d048201…`, worker remained E12 `e12f9a0f…`. Runtime QEMU/worker mapped-DSO identity is incomplete, even though QEMU executable, machine, kernel, worker binary and root identities were recorded. [Identity](/root/workspace/hackios-run/d123-r3.6a9f3b2c/identity.json) · [manifest summary](/root/workspace/hackios-run/d123-r3.6a9f3b2c/diagnostic-summary.json)

`run.json` completed without a harness failure (772.31 s wall). Panel initialized at 7.42 s, first GL frame at 55.00 s. The 5-publication readiness heuristic occurred at 468.40 s; it is not a desktop classifier. The valid-skew measurement boundary was 498.439–618.486 s (120.047 s), with readbacks/HFR2 seq 33→219 (guard 66→438), or 186 publications (1.549/s). The frozen frame-stat validator still marks the result **invalid** due to incomplete runtime DSO identity for QEMU and worker. Distinct visible-content FPS is unmeasured; publication throughput and mean GL callback duration are not content FPS, so this run does not prove 10 FPS or stable performance. [Measure](/root/workspace/hackios-run/d123-r3.6a9f3b2c/measure.json) · [run](/root/workspace/hackios-run/d123-r3.6a9f3b2c/run.json)

The corrected PPM decoder read the padded QEMU P6 output as source pitch 3376 and packed RGB pitch 3375. The pre-action frame passed the fixed reference gate (icon-grid mean error 0.1504/19, contrast 100.27) and visibly showed SpringBoard with the expected icons; that is a scene-specific readiness check, not an app-identity oracle. The single same-QMP tap at (694,1727) was followed by a captured Settings screen visibly showing the Settings main list and `Preferences[37]` serial lines. This is direct evidence that Settings was presented; the QMP acknowledgement alone would not have been proof. The one post-Settings swipe was acknowledged, but its final screenshot still shows the Settings list at a different scroll position—not SpringBoard Home—so the requested Settings→Home closed loop **did not complete**. No settings were toggled, and all virtual buttons were released.

At the final state, the QMP PPM screenshot and HFR2 final ring were bracketed at seq 259/guard 518. After proper PPM row-padding and BGRA→RGB decoding, the screenshots’ packed RGB SHA-256 matched exactly (`11a4aad5…`; all 2,740,500 pixels equal). Both represent the Settings list, not Home. Earlier Settings capture is ring seq255/guard510, but its corresponding ring pixels were not retained, so no bytewise stage comparison is claimed for that earlier frame. [Settings screenshot](/root/workspace/hackios-run/d123-r3.6a9f3b2c/post-interaction/013-settings-evidence.png) · [post-swipe screenshot](/root/workspace/hackios-run/d123-r3.6a9f3b2c/post-interaction/017-home-evidence.png) · [postplan record](/root/workspace/hackios-run/d123-r3.6a9f3b2c/post-interaction/result.json)

This D123 production guest boot and Settings presentation are useful evidence, but do not prove every renderer case or 10 FPS. The run used the original E12 worker; a separate C371 worker has independently passed reported D4 transport/validation tests, but that D4-fixed worker was not integrated in this run. The prior old PPM comparison also had a one-byte-per-row legacy-reader issue; do not use Pillow’s packed-row interpretation of those older PPMs. The corrected parser and bundled PNG reference were used here. Do not equate the earlier HFR2 black-region screenshot and a temporally unrelated QMP capture without a matched sequence/guard bracket. Next boot evidence should combine the required CPU-diagnostic mode with a bottom-edge Home gesture plan; do not repeat a D123-only boot or label the post-swipe Settings screenshot as Home.

原始 Inferno 10.1 二进制（SHA-256 `aaca8ea952d1d22ab351e11f8a2d998676a42d6591e3c07c418976c9ca9b37bd`）历史上曾以原版内核、同一 APFS 和含 `ui_lite=1` 启动参数达到 6 次回读（408.65 秒）。后续普通代理曾排过精简内核 Inferno 复测任务，但没有保存完整的 identity/run 证据；不把它当成已完成结果。本轮显式 virt-10.1 的身份与诊断已单独记录。[Inferno 历史数据](/root/workspace/hackios-run/uilite.log:23) · [Machine alias review](/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/machine-compat-review.md)

可复现材料另保留了两个配置行的补丁、双 worker 构建脚本，以及包含完整输入哈希、移除的全局符号和两次试验结果的 JSON 证据。[配置补丁](/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/config-lean.patch) · [构建脚本](/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/build-lean.sh) · [试验证据](/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/evidence.json)
