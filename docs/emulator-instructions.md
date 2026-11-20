# QEMU 指令计量与可复核基线（2026-11-19）

本轮先修复测量工具。此前的 **540 ms → 382 ms / +42%** 同时改变了内核与 QEMU 版本，不能作为优化收益；两项 timer 补丁没有可靠性能或完整正确性证明，不应进入发货资源。本轮没有修改 App assets、内核、分辨率，也没有提交 Git。

## 已有试验可以确认什么

离线复算 [glpass-uilite 的采样记录](/root/workspace/hackios-run/glpass-uilite/samples.jsonl) 与[完整运行日志](/root/workspace/hackios-run/uilite.log)：

| 指标 | 可以确认的数值与口径 |
|---|---|
| 旧日志中的测量起点 | 418.669590777 s；配置窗口为 150 s |
| 具有两个真实计数端点的子窗口 | **424.7 → 546.0 s，121.3 s** |
| 端点 ring sequence / readbacks | 24 → 223，即 **199 次** |
| 墙钟吞吐 | **199 / 121.3 = 1.64056059 次发布/回读每秒** |
| QEMU CPU 消耗 | (789.9 − 568.8) / 121.3 = **1.82275350 核** |
| 原始 serial 日志去重 | 0 条重复、0 个冲突 ID；该文件没有 loader 重放段 |
| 测量起点之后的局部 GL 耗时样本 | 29 条，均值 **763.977 ms**、中位数 **562.244 ms** |

763.977 ms 是来宾打印的某段 GL 工作耗时，不是相邻画面的墙钟间隔，**不取倒数当 FPS**。199 次发布也可能有相同像素或非最终可见画面，不能称为“199 张不同内容帧”。缺少逐帧内容证据，**不同可见内容 FPS 未测**。

旧试验只保存了 start 的 serial 字节位置 278847，没有 end 字节位置。对它的 29 条局部样本采用“起点至文件末尾”范围，不能严格证明都处于配置的完整 150 s 内；具有可信端点的吞吐仅是上述 121.3 s 子窗口。这一次试验也**不能单独证明 ui_lite 提速**：没有匹配工作量与温度的对照。

重现离线结果：

```sh
set -euo pipefail
python3 qemu/gpu/frame-stats.py /root/workspace/hackios-run/glpass-uilite --json
```

## 工具修正

[frame-stats.py](/root/workspace/hackios-next/qemu/gpu/frame-stats.py)、[glpass-desktop.py](/root/workspace/hackios-next/qemu/gpu/glpass-desktop.py) 与共用的 [trial_metrics.py](/root/workspace/hackios-next/qemu/gpu/trial_metrics.py) 现在采用以下规则：

- 按原始 **byte offset** 选择 start/end 内的完整记录；切半行不纳入统计。
- 遇到 `== KERNEL LOG ==` 停止读原始 serial 样本，排除 loader 的整段重放。同一 ID 的相同记录去重；不同内容的同一 ID 明确报告冲突，不能悄悄拼接不同启动。
- 局部 GL 耗时与墙钟发布/回读吞吐分开。输出不再用 `1e6 / total` 生成 FPS。
- 新试验写入完整精度的 start/end snapshot、serial/worker 字节边界；每秒采样明确写入 `phase` 和 `desktop` 字段。兼容旧试验时只使用可以还原的采样子窗口，标明范围。
- serial/worker 日志只增量读取，避免每 50 ms 重读整个不断增大的日志；计数不会因 1 MiB tail 滑出而下降。
- QMP 复用一个连接，避免旧程序在第一个连接仍占用时打开第二个连接并永久等待 greeting。vCPU 或 worker 退出会立即失败，失败路径也写入诊断 metadata。
- 保存实际 QEMU/worker 子 PID，清理只针对该次 `Popen` 对象；不使用 `pkill -f`、`nohup` 或名字匹配清理。
- 新试验保存 QEMU、kernel payload、dylib、worker、UEFI、loader 的 SHA256 与命令行。旧试验没有身份 manifest，不能事后把同一路径当前内容当成当时二进制的证明。

回归验证命令（已执行，8 项全部通过）：

```sh
set -euo pipefail
python3 qemu/gpu/test-trial-metrics.py
python3 -m py_compile qemu/gpu/glpass-desktop.py qemu/gpu/frame-stats.py \
  qemu/gpu/trial_metrics.py qemu/gpu/instruction-stats.py
```

[回归用例](/root/workspace/hackios-next/qemu/gpu/test-trial-metrics.py) 覆盖重放/重复、冲突 ID、跨次追加的半行、完整测量窗口、切半行边界、无 desktop 的旧 samples、无墙钟时不产生 FPS、增量日志超过旧 tail 大小。

另已执行 `python3 qemu/gpu/test-instruction-stats.py`，3 项全部通过：两个 CPU 的增量聚合、225..230 的手算不确定区间、完整高位 kernel 地址不截断，以及拒绝倒退/重复 CPU/运行中缺失的 snapshot。这验证离线汇总逻辑，尚不能代替 JIT 运行验证。

当前隔离输入的指纹（事后读取，不能替代旧 ui_lite 当时缺失的 manifest）：

| 文件 | SHA256 |
|---|---|
| [QEMU baseline](/root/workspace/hackios-run/qemu-baseline.bin) | `aaca8ea952d1d22ab351e11f8a2d998676a42d6591e3c07c418976c9ca9b37bd` |
| [patched-a QEMU](/root/workspace/hackios-run/patched-a/qemu-android/qemu/qemu-system-aarch64) | 同上（尚未放入新计量构建） |
| [guest dylib](/tmp/eagl-delta/libredwood-gl.dylib) | `06823406f1e8b8c7d6de6bc991f7d4d8524e4918be129c3af650ec8470a998da` |
| [host worker](/tmp/wk-delta/hackios-zink-session) | `37479877ef6e94ba737985263b2f96c66be7be79512af2ba0c1d49ba04d7846e` |
| [ui_lite 试验 payload](/root/workspace/hackios-run/glpass-uilite/payload.bin) | `9599fd6da77d5c3dd1afbb6a48fce084635333b4100e8cf8d1da8ab54a5840b4` |

## 旧 profiler 为什么不能作为指令计量

旧插桩位于 C dispatcher 的 `cpu_loop_exec_tb` 前面。已直接链接的 TB 会在生成代码内部互跳，不经过这个位置，因此它只统计部分 dispatcher 入口，漏掉 direct chaining。其他错误还包括：

1. 没有实施任何抽样，却把每次计数乘以 64。
2. 多个 vCPU 同时无锁更新、清空全局计数，存在数据竞争。
3. 地址桶采用取模再左移，丢失高位地址，可能把 user 与 kernel 合并。
4. 本树 ARMCPU 默认使用 **CF_PCREL**；`tb_gen_code` 在这种模式下并不把运行时 VA 存入 `tb->pc`。旧 profiler 从 `tb->pc` 取桶，输入本身就可能是零/旧值。
5. TB 入口次数不等于指令数，更不等于每帧指令数。

`info registers` 的 PC 是 QEMU 保存的 `env->pc`，不是随每条宿主指令更新的真实执行位置。`mrs CNTVCT_EL0` 前更新 PC 会制造采样偏差，因此旧 PC/LR 分布**不能证明 timebase 时间占比**。旧 timer A/B 的全局 race、52 ns tick 量化、测量本身的开销也未被排除，不能把其 ~100 ns 直接升级为来宾总耗时结论。

## 新指令计量设计

源码位于 [QEMU 实验树](/root/workspace/hackios-run/qemu-inferno.tfi5zu/src)。旧 dispatcher profiler、host-counter 缩放和全局 `translator_io_start` 放宽已从 active source 移除，并与先前保存的原始源逐字核对一致；实验前源保存在[隔离备份](/root/workspace/hackios-run/qemu-profile-repair-backup/manifest.json)。发货资源未动。

### 模式 1：低扰动总数与 EL 分账

`HIOS_PROFILE=1` 在 **AArch64 生成的每个 TB 入口**内联累加该 TB 最终静态指令数与 TB 次数。入口位于中断提前退出检查之后，因此不执行的 TB 不收费；直接 `goto_tb` / `goto_ptr` chaining 也经过此入口。

计数、当前 TB credit 和异常修正状态都属于单个 vCPU，不共享热路径可写计数，也不在热路径读时钟。按 TB 的 EL0 / EL1+ 分账，不从虚拟地址高位猜 user/kernel。没有逐指令 hook。

TB 中途 fault/重编译时，借用 `cpu_restore_state_from_tb` 已有的 `insns_left` 信息，扣除未执行尾部。修正后清除该笔 active credit，防止重复扣减。

不是所有退出都能还原到一条指令：无 restore 的 longjmp 把当前 TB 的整笔 credit 记为 `uncertain`。报告区间为：

```
[credited_instructions - uncertain_credit, credited_instructions]
```

这可能保守地把已完成的末尾 TB 也计为不确定，**不称为精确 PMU 退休指令计数**。必须同时给出不确定比例；如果区间太宽，不能用单值指令数下结论。

### 模式 2：较重的完整地址诊断

`HIOS_PROFILE=2` 在模式 1 之外增加每 TB 的 helper，通过运行时 `cpu_pc` 获取 **CF_PCREL 下真正的完整 64 位 VA**，按 4 MiB 分桶。每个 vCPU 独有 hash table，使用完整地址作键；不把地址取模截断、不乘抽样因子。

该模式有额外 helper/hash 开销，只用于定位指令分布，**不能把其帧率直接当低扰动基线**。总指令/吞吐结论优先使用模式 1；模式 2 必须另报自身扰动。

### 暂停读取与时间括号

只在 QMP `stop` 确认所有 vCPU 停止之后，通过 `info jit` 导出 per-vCPU JSON 行。VM 仍在 running 时拒绝导出这些普通计数，避免读取数据竞争。测量边界记录 stop request/ack、cont request/ack 的宿主时间，以括号而非假定零延迟表示计量区间。

[instruction-stats.py](/root/workspace/hackios-next/qemu/gpu/instruction-stats.py) 按两个暂停 snapshot 求差，输出指令区间、MIPS 区间、EL 分账、TB 次数、实际新翻译次数、修正/不确定退出，以及模式 2 的完整地址桶。它不会把指令占比称为 CPU 时间占比。

## 验证状态与下一步

截至本文首次写入时，新计量代码已实现，**尚未编译或在来宾上验证**。内核代理占用重负载窗口，QEMU 侧只做代码与离线验证；不以“代码存在”当成计量成功。

所有编译与试验必须由父代理协调，并获取同一把锁：

```sh
flock /tmp/hackios-qemu-trial.lock <build-or-trial-command>
```

先做短的已知指令循环，验证 direct chaining、两个 vCPU 独立累加、提前 fault 的尾部修正和 64 位桶；再以同一二进制分别关闭/打开计量衡量扰动。只有通过这些检查，才运行长启动测来宾每次发布的指令数。每帧指令数及 MIPS 当前仍为**未测**；没有由它们支持的 QEMU 性能改进结论。

## 固定队员 tcg 接管复核

接管 task-1 后，独立复跑原8项测量fixture通过，保留了原普通代理的全部TB入口实现和短probe，没有复制启动长实验。旧timer两文件与前实验备份逐字相同：

- `target/arm/helper.c` SHA256 `9fe13a93482d717343680900d98abc87516fad05e1dec5d6b72101eea2ceed97`。
- `accel/tcg/translator.c` SHA256 `181d5eae4455c037fa8664c9ef8858289d5a5321f64aadbff752db8131d58b6a`。

新计量只统计AArch64；EL1+一栏还包含固件/异常处理，不等价某个进程的内核CPU时间。仅入口credit及异常扣减不能自动代表PMU retired指令，未restore退出必须保留不确定区间。暂停snapshot本身会打断运行，指令窗口和ring窗口不完全同端点，平均每发布指令数只能近似，不能升级为每张可见内容帧。

### 测量失效闭合与端点误差

接管后schema3新增整次snapshot的 `t_before/t_after` 和各计数源时刻，要求logs→ring→CPU→thermal有序且在完整bracket内；时刻必须有限，声明bracket须与实际时间差一致，单端点跨源偏移超过100ms则拒绝发布吞吐。吞吐分母使用对应ring/log源的实际时间差，并给出由整次bracket推导的rate上下界。NaN不能绕过时间检查。

运行失败、readback producer重置、计数回退、serial冲突、窗口内loader重放、无效字节边界、新schema缺精确端点/身份信息均标为 `invalid`，吞吐为空，CLI退出2。EOF未结尾记录不当完整样本。旧schema仍可输出描述性子窗口，但明确 `legacy_unverified`，不能据此确认优化收益。

输入身份新增初始vars、RAM/system后端、harness及metrics脚本、ROM目录哈希；成功窗口结束后在计时外记录两子进程实际映射的可执行DSO及白名单GPU/loader环境。此终态记录**不能证明窗口中已卸载过的动态库**，不能把当前路径内容倒填成旧试验身份。

验证命令 `python3 qemu/gpu/test-trial-metrics.py`：**13项测试通过**（旧8保留，新增失效条件/完整性用例，另有NaN/源越界/虚报bracket子case）。`py_compile` 和 `git diff --check` 通过。指令解析离线fixture独立复核 `[225,230]` 区间、完整高位VA保留、计数回退拒绝三项通过。

### 两类优化候选（未测，非结论）

1. **减少来宾重复工作**：与guest-load队员交叉审查单一真实服务重试/唤醒和系统ReduceTransparency设置。用同一已完成交互的EL0/EL1+指令、线程CPU及正确图像配对；日志次数不能当CPU占比，不能批量禁服务或假成功应答。若仅空闲率上升、交互未变快，不算达到目标。
2. **相同源码的宿主编译配置**：当前Inferno Meson实际为optimization=2、b_lto=False、b_pgo=off。可以单因子试O2/O3或LTO，但代码膨胀、链接负担及热漂移可能抵消收益；必须同source/toolchain匹配，微基准只校准，不当典型交互FPS。
3. **有证据后才优化helper/原子慢路径**：先区分来宾指令工作量和宿主每指令开销；不再用偏置env->pc指向timebase后全局放宽ARM_CP_IO/屏障语义。

DELT padded-stride正确性由transport队员、HFRM v1覆写发布竞态由lead分别处理，tcg未改这两块源码。新旧二进制/协议不能混合成一个基线。

### 正在排队的短校准

隔离输出 `/root/workspace/hackios-run/patched-a/tcg-calibration.s972LT`。通过共享flock排队，ninja `-j2` 成功才复制独立binary，所有构建及probe日志保存。首构建因 `translate-a64.c` 中 `hios-profile.h` 位于完整CPUState定义之前失败，修正include顺序后增量链接成功；第一次probe在初始化时缺默认网卡ROM，加入 `-nic none` 隔离不相关设备后成功。失败日志均保留，不以旧binary或失败试验冒充成功。

### 短校准实际结果（已完成）

- managed job `bash-e59970a6d0414cfa` 已完整回收，exit0；没有遗留QEMU/probe进程。
- 二进制 `/root/workspace/hackios-run/patched-a/tcg-calibration.s972LT/qemu-system-aarch64`，SHA256 **7772701930f5cdc177cd82ebc05a0a8a5b42d026de18280a4eac939238670b01**。
- QEMU源码base commit `cc4302a99167abec69b714cfd00c38caece7e7de`；计量patch为 `qemu/patches/0004-instruction-measurement.patch`，其reverse check与active source一致。无需、也没有叠加旧timer0002/0003；最终源码与patch哈希见该试验目录 `validated-source-sha256.json`。该base的其它既有构建修改仍须随整份源码/config一并保留，不能声称仅凭一个commit就能重建同binary。
- 产物 `probe-attempt2/result.json` 与 `probe-attempt2.log` 保存 **11个子运行**。模式1和2各3轮，EL0均精确读到 **30,000,004 = 3 × 10,000,000 + 4**；完全链接的三指令循环未因direct chaining漏数。
- 两个fault子运行均读到同一预期EL0值；`corrected_tail=9`、`corrections=4`，证实同步data-abort后的未执行尾部得到扣减。`kernel_uncertain=1`被保留为不确定credit，没有强装精确。
- 模式2检查完整VA的0x40000000桶存在且桶和等于指令总数；未启动的第二vCPU计数为零。

同binary、交替顺序3轮的UART标记工作区间中位数：

| 模式 | 中位区间 | 相对模式0 |
|---|---:|---:|
| 0，无插桩 | 40.701 ms | 1.00× |
| 1，入口总数/EL分账 | 57.814 ms | 约1.42× |
| 2，入口总数+完整地址hash | 287.174 ms | 约7.06× |

这是每TB仅三条指令、入口计量成本被放大的**极端微基准**，且UART观测受主机调度影响；不能把42%扰动推广成真实iOS负载开销。但也绝不能忽略它：模式2只能用于诊断，模式1的吞吐不能直接拿来当无插桩性能基线，真实负载必须另做扰动对照。

未独立校准的边界：异步timer IRQ、running状态中途QMP stop/continue、非A64执行。现有证据不应写成所有异常/所有平台的精确退休指令验证。

### 冻结的试验接口

`/root/workspace/hackios-run/patched-a/harness-frozen.h_u689ho/` 包含harness、metrics、ring模块与tests及manifest。
`glpass-desktop.py` SHA256 **2f4bcc1bf417f50f8a43bb4d2035fe5b95b7118d74fafa78a53f2db808cf5cf8**。

- 显式 `--machine 'virt-10.1,gic-version=3'`；默认仍为 `virt,gic-version=3`，启动后 `query-machines` 记录alias解析类型。
- 显式 `--ring-version 2` 使用HFR2/version2、固定1125×2436、pubcount@24、guard@40。只有偶guard且等于2×pubcount、两次guard相等才采纳发布元数据；写入中为unavailable/null，既不伪装零也不计为reset。默认version1保留旧workerpair口径。
- 这只是发布元数据一致性检查，不是像素内容oracle。ring v2的画面正确性仍需producer hash/连续画面验证。
- 冻结版 **14项metrics + 8项ring fixtures通过**，不改进行中的kernel固定harness。

## 下一步：以正确图形root为前提的指令窗口与10FPS方案

目标已更新为原1125×2436、真实宿主GPU、无闪烁/抽搐/黑块的**实际内容10FPS**；2–5只作中间里程碑。此处不延用旧“5FPS无路径”推断。

1. **先把平台变量固定**。guest-load查实发行QEMU的virt别名解析为11.1，而Inferno为10.1。下一轮候选只在发行binary上把machine显式设virt-10.1，其余kernel/root/vars/loader/ROM/GPUpair/hash不变；这是compat可证伪试验，不是已确认原因，也不能把整组compat的收益归因于一个CPU helper。
2. **单boot短mode1计数窗**。在正确新图形root能达到明确视觉状态后，用已校准binary+HIOS_PROFILE=1，QMP stop/`info jit`/cont取起点，执行预先冻结的交互输入工作单位（例如一次完整滚动或打开关闭，必须有可验证完成标志），末端同样暂停取数。同时保存publication/readback、正确图像证据、单线程CPU和墙钟区间。以一次已完成交互的指令区间定位EL0/EL1+工作量，不能用单纯指令/秒下降当提速，也不把ring发布叫不同内容帧。
3. **模式0/1代表负载扰动对照**。用同一binary、同一kernel/root/初始vars/GPU组件，仅HIOS_PROFILE开关改变；交互输入、完成条件、预热状态相同。至少交替顺序、记录温度/线程CPU/内容更新与总时长。跨boot噪声不可被一次比值掩盖；在没有可恢复外部GPU状态的条件下不强用VM快照。模式1只提供计量，模式0才是性能基线。
4. **优化方向由指令/工作单位决定**。若指令数量过大，优先减少重复效果生成/真实服务重试等有语义保证的guest工作；若指令数量不大但host CPU成本高，再看相同源码O2/O3/LTO、TCG helper/原子/访存慢路径。无证据不修改timer与内存屏障；内容正确性优先于减少像素回写。

当前来宾真实典型交互的指令/内容更新、MIPS、内容FPS提升都仍**未测**。上述是可执行的下一阶段方案，不是假造10FPS已达成。

## 预构建APFS根必须是实际运行输入

后续复核发现：仅在fixture清单写预构建root哈希，并不会让旧harness使用它；旧路径会从rootfs执行mkapfs生成另一个root。已新增显式 `--root-apfs PATH`，默认不指定时继续原先rootfs+dylib注入+打包行为。

指定后检查源为常规非symlink文件、4KiB对齐且4KiB..48MiB，直接复制至隔离输出 `root.apfs`；不复制rootfs、不执行或要求mkapfs、不进行dylib注入。原CLI的rootfs/mkapfs/dylib位置保留以兼容调用，预建模式不依赖它们存在。外部dylib若存在，仅标作声明组件，不能伪称检查或注入了APFS内对应文件。

`identity.root_preparation` 记录源复制前后、实际隔离root、最终XNUP payload的ramdisk条目。只有三处bytes/SHA完全相同才继续；frame-stats也核对预建源/actual/payload-ramdisk与inputs.root_apfs身份一致。更换实际图形root后必须产生新payload及新manifest，不把新哈希倒贴到旧运行上。

新的冻结包 `/root/workspace/hackios-run/patched-a/harness-prebuilt.ko8p535u/`，glpass SHA256 **46fb489f07699e4ad5ac0118e83798861752228193816a8d9511d2590b793a64**；包含所有依赖、3类测试与manifest，继续支持显式machine/ring版本。4项预建根provenance fixture、14项metrics、8项ring测试通过，未启动VM。旧冻结包未被修改，旧运行不受影响。

## D3：非零first与gl_VertexID已复现并修复

实际production encoder曾将DrawArrays first=7/count=3及Elements索引7/8/9压成0/1/2，host临时VBO只有3顶点；HAA1没有原base。相同attribute及完全同一shader以gl_VertexID-7判断时，Adreno830/e12 worker中保10顶点前缀/原索引的reference是绿，旧production两包是红，均GLerror0。这是原生32×32语义oracle，不是改变iOS 1125×2436分辨率，也没有证明它就是旧系统全部缺块的唯一原因。

已在[eagl-client-arrays.c](/root/workspace/hackios-next/qemu/gpu/eagl-client-arrays.c)与[接口头](/root/workspace/hackios-next/qemu/gpu/eagl-client-arrays.h)保留原index，vertex_count=last+1；prefix填零，仅读实际min..max的guest源内存。不改wire、host BaseVertex或shader。仍限制last<65536、总包≤4MiB，超限明确返回0且written=0，不悄悄重基。PROT_NONE不可读prefix、输出canary、65535/65536/UINT32_MAX、zero count、原GL/线程handoff均回归通过。

[修复后GPU结果](/root/workspace/hackios-run/patched-a/render-vertexid/verification.KBZCGX/native-result/result.json)中两个新包与reference整张RGBA的hash相同且绿；保留旧重基包负控仍红，4case均GLerror0。[生产源码与补丁manifest](/root/workspace/hackios-run/patched-a/render-vertexid/verification.KBZCGX/production-fix-manifest.json)记录C bc229d…、h229f91…，完整失败/重试日志也保留；旧GCC fixture未使用返回值warning与mock timing storage缺失已单独识别，没有改无关production日志来掩盖。

## PPM行pitch与交互证据

旧发行QEMU的1125宽P6截屏body是3376×2436字节，真实RGB每行3375字节。直接Pillow读取会逐行错位；这次修的是Python测试/参考图路径，**不是Android App已有packed/padded兼容decoder的缺陷**。

[ppm_pixels.py](/root/workspace/hackios-next/qemu/gpu/ppm_pixels.py)仅接受header后body精确匹配packed或4-byte aligned行pitch；注释仅在header token间处理，maxval后只消必要delimiter/尺寸可证明的CRLF，不吞pixel首字节空白或#。short/extra/歧义fail-closed，不猜stride。5项格式测试涵盖3375/3376、oddwidth、红蓝BGRA→RGB与PNG同hash及旧Pillow负控。

[真实cross-format/gate结果](/root/workspace/hackios-run/patched-a/ppm-decoder-audit.03rlzl3r/fixed-preconditions.json)：纠正PPM与正确PNG的icon-grid误差0，SpringBoard信号存在，PASS；旧缺块ring与正确PNG误差32.063，仍超过19并FAIL。原81.178同时含Python误读因素；修正后ring大矩形缺块仍是真实差异，不能把阈值放宽成点击许可。

可选post-measure计划只允许tap/swipe/capture，复用同一QMP，在正式measure/profile_end之后执行；取消/超时finally也release。先保存主屏并比对已人工核对的icon-grid参考和SpringBoard日志，失败不Down。记录精确输入/ack、连续ring seq/guard/hash、PPM和Preferences候选串口行；ack不是点击成功，串口行不是live process探针，计划完成也不等于Home成功。

实际r3只确认主屏→Settings；末图仍Settings，不能按home-evidence文件名宣布返回。下一CPU计划把唯一home尝试改为(562,2420)→(562,1400)/420ms，仍无自动重放，必须看新截图验收。旧计划与旧run不变。

## 可保留的发布率诊断与full-copy口径

[旧compat-run只读diagnostic](/root/workspace/hackios-run/patched-a/publication-audit.gpoqfevc/diagnostic.json)：136次ring发布，按ring实际读区间得到120.103365..120.105129秒，即1.132341..1.132358发布/秒；用整个snapshot最大不确定范围仍有1.128530..1.134159/秒。CPU约1.70018核。0.1906/0.4075秒snapshot宽度大部分是0.1891/0.4044秒thermal读取，不能把有序计数观测一律抹去。

该run的旧映射记录缺71/33项QEMU/worker DSO身份，没有存dev/inode，不能事后补成已证明；[原JSON前后hash](/root/workspace/hackios-run/patched-a/publication-audit.gpoqfevc/unchanged-original-sha256.json)一致。新采集用maps dev/inode与文件fstat精确匹配、验证过的proot前缀和/proc候选，不靠basename；仍可能缺proot-loader，不因此断言software renderer。发布诊断不是内容FPS，也未批准跨机型/版本因果结论。

新boundary温度读缓存并显示sample age，不再在计数边界做数百次sysfs读取。[trial_metrics.py](/root/workspace/hackios-next/qemu/gpu/trial_metrics.py)和[frame-stats.py](/root/workspace/hackios-next/qemu/gpu/frame-stats.py)也已解析shadow_prepare/compose/full_copy_us/full_copy_bytes/mutex_wait/forced_full；compose/fullcopy嵌套在unpack/total，不能再相加或取倒数叫FPS。旧日志缺字段不补零，partial字段不冒称完整样本。

transport的[r3成本报告](/root/workspace/hackios-run/patched-rendering/r3-transport-cost-5b0qzflx/report.md)表明fullcopy约9ms只是该段local wall的一小部分，反而有约93.6串行wire命令/回读、465.5ms gap；header还含host dispatch。Settings阶段命令密度更高。它们只是墙钟/协议数，不能替代指令或排他CPU归因，尤其不能把EL0全部叫GL、EL1+全部叫network。

## 下一CPU诊断已构建与可执行交接

[独立build manifest](/root/workspace/hackios-run/patched-a/cpu-profile-build.B69bYm/manifest-unified.json)已完成：D1+D2 hash-profile source4d634…+D3 guest **53f6b933…**，559472字节、407exports；预建root **949055d7…**，5046272字节。源前后稳定、generated body匹配、稳定stage字节重现、native桥与c371 Adreno session均过。不覆盖D1232d048/dd60，也不发布assets。hash profiling默认off，缺receipt/缺completed frame行不是零hash。

[session2400完整mode1/0 argv](/root/workspace/hackios-run/patched-a/cpu-profile-build.B69bYm/cpu-run-plan-session2400.json)由kernel作为唯一VM执行者接管。冻结[harness](/root/workspace/hackios-run/patched-a/harness-cpu-session.vn83effw/glpass-desktop.py) SHA793780b1…；实际runtime绑定已校准QEMU777270…、显式virt-10.1、D4 worker c371…，两轮同root/初始vars/flags/操作。HIOS_PROFILE只在启动设0或1，没有热切换。模式1保存main与独立post两窗EL分账/不确定区间，后同binary mode0测扰动，不能拿发行11帧率当收益。

必须显式session-seconds=2400：host boot1800+settle30+main30+post最大300共2160秒，留240秒；host timeout不会覆盖guest loader_return_after。repack只修改隔离run副本，精确去重managed token并记录原/新cmdline与两payload hash；默认None保旧值。对真实源payload059258…的[preview receipt](/root/workspace/hackios-run/patched-a/cpu-profile-build.B69bYm/session2400-preview-receipt.json)已验证唯一loader_return_after=2400，输出payload83f54918…。8CPU小fixture含重复/错误token、300..7200范围及总预算拒绝，另4root/2socket/16metrics回归通过。

native glhash数据保留calls/attempts/bytes/total_us和首次setup，bytes包含行间padding。boot flags同时启用glhash=1/glframes=1/touch_lat=2，以获得相邻completed frame_end的ended时刻，避免频繁SpringBoard runloop trace。hash/tl:g无contextID；除非单accepted-client/单context且无restart/fork疑点被独立确认，否则只保留offset/原记录，不凭f值跨context归因。不能用含setup的hash_us除以不含setup的同frame_us；[hash-stats.py](/root/workspace/hackios-next/qemu/gpu/hash-stats.py)只在明确确认scope后用相邻ended差。

当前来宾真实指令/每publication、mode1实际扰动与10内容FPS依然待该真实运行返回；工具/构建/校准通过不等于目标已达成。QEMU11公开取源只读准备见[移植最小计划](/root/workspace/hackios-run/patched-a/qemu11-source-review/port-plan.md)，未编译，也不阻这轮Inferno同binary诊断。
