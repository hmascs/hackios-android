# 维持原分辨率的来宾 CPU 减负候选

所有候选使用 1125×2436 和正确的 IOSurface 回写。按证据、潜在收益、风险和验证成本排序。

| 顺序 | 候选 | 证据与收益上界 | 风险与否决条件 |
|---|---|---|---|
| 1 | 同启动切换系统「降低透明度」，保持「减弱动态效果」原值 | 系统设置可把 Dock、文件夹等背景模糊换成纯色。上界是同一交互中模糊相关的来宾生成与合成工作。目前无法量化 | 样式变化，应保留可选。确认效果变化后，若每次完成交互所用 CPU 或指令无改善，或首变化、最长停顿、完成时间及有效帧退化，则否决性能推广 |
| 2 | 单独注册匹配版本的真实 `chronod` 作业 | 四份记录有重复缺标签。早期隔离试验已启动实际进程并消除 `com.apple.chronoservices` 查找失败，小组件仍空白。毛收益最多为被替代的查询及失败分支，净收益还须扣除服务和扩展的工作 | 核对域、真实可执行文件及完整服务声明。若出现反复退出或拉起、冷启动劣化或总 CPU 上升，则回退。仅查找失败减少不满足验收 |
| 3 | 对证实具有强推请求率的构建试 `dl_max=0` | 参考钩子只针对 `UIViewInProcessAnimationManager`，将原请求 20–30 改为面板默认。上界为这个类的额外回调及其下游冗余事务。当前发行注入库没有该钩子签名 | 仅对含钩子的构建测试。回调变少却漏运动中间帧、延迟末帧或触摸响应变差即否决。运行期切换还要确认已存在的 display link 是否重新申请速率 |
| 4 | 静态壁纸保持原尺寸，按真实失效更新 | 参考实现曾尝试 `shouldRasterize`，源码记载效果有限。上界是壁纸可归因的重复生成份额，须先区分来宾绘制、合成和传输 | 参考冻结曾捕获全黑。只对经连续帧确认静态的资源试验。外观、锁屏或主屏转换、壁纸变动和复用均须触发更新，出现内容陈旧立即回退 |

[SpringBoard 辅助功能](</root/workspace/hackios-run/storage-tools.wu0PHZ/kernel-source/userland/src/inject_sb.c#L2018>) · [Display link](</root/workspace/hackios-run/storage-tools.wu0PHZ/kernel-source/userland/src/inject_sb.c#L1845>) · [壁纸冻结](</root/workspace/hackios-run/storage-tools.wu0PHZ/kernel-source/userland/src/inject_sb.c#L1215>) · [真实服务试验](<guest-system-components.md#L5>)

## 实际产物与日志

发行脚本包中的 `prebuilt/userland/libredwood-sb.dylib` 与 `glpass-uilite`、`glpass-iofix`、`glpass-fast` 的库均为 SHA-256 `4225eb874a791c86df24c9172c1aa97c7ff6f1800ef584e4e1c3d1e84f72c0e0`。四份二进制均无 `ui_lite=`、`dl_max=0`、`ax_rm`、`ax_rt`、`display links:` 字面量。`uilite` 串口证实命令行参数传达，未见 SpringBoard 启用回执。撤回把该轮 121.3 秒内 199 次发布归因于 `ui_lite` 的说法。另据传输侧发现，旧差分链路有 stride 正确性问题。这批旧结果可作诊断材料，配对实验需要先使用正确回写构建并验连续帧。[发行脚本包](<../app/src/main/assets/hackios-scripts.zip>) · [uilite 串口](</root/workspace/hackios-run/glpass-uilite/serial.log>)

以各目录 `measure-start.txt` 的字节位置切开串口，截止首个 `== KERNEL LOG ==` 重放标记或文件末尾。下表仅匹配可辨认的显式记录，未展开日志合并行，交错或截断会漏计。段尾可能超出测量结束。四份记录的运行时间不同。

| 原始段 | 开始后 SpringBoard 查找 sleepd 失败 | 开始后 SpringBoard 查找 commcenter 失败 | 开始后 launchd 缺 chronod 标签 | lsd CPU monitor 告警，开始前 → 开始后 |
|---|---:|---:|---:|---:|
| uilite | 115 | 75 | 59 | 1 → 0 |
| iofix | 1243 | 627 | 592 | 1 → 0 |
| v2c2 | 345 | 238 | 171 | 701 → 0 |
| fast | 526 | 287 | 254 | 1 → 0 |

`lsd` 审计已区分 CPU monitor 权限告警与查询失败。试验 root 内 `mobileactivationd` plist 声明两个激活服务，注册与请求成功仍应分别验证。sleepd、commcenter 要先查真实服务及模拟硬件依赖。`mkjobs.py` 的显式作业保留触发属性，`--rest` 需要存在的可执行文件和 MachServices，且移除自启动触发。暂缺 CPU 归因，无法从日志条数推算收益。[lsd 审计](<lsd-lookup-audit.md>) · [作业生成](</root/workspace/hackios-run/storage-tools.wu0PHZ/kernel-source/userland/mkjobs.py#L265>)

旧 QMP PC 受 `env->pc` 保存点偏置，旧 TB profiler 对 `CF_PCREL` 读取未赋值的 `tb->pc`，也漏算直接 chaining。阶段 CPU 归因依赖校准后的计量。参考 `cputop` 的 `PROC_PIDTASKINFO` 可作进程旁证，短命进程和无权限条目可能漏采，参考工具只报告前十。精简内核改过 user/system 分账时，以两者合计与宿主独立证据对比。[cputop](</root/workspace/hackios-run/storage-tools.wu0PHZ/kernel-source/userland/src/cputop.c#L13>)

## 验证方案

1. 正确回写构建上用来宾设置做同启动 ABBA，A 关闭降低透明度，B 开启。每次确认设置状态与效果，回到同一页面。分别录固定静置和同序列的文件夹、控制中心或应用开闭。开关操作不计入窗口，最后恢复原值。若设置页路径不可用，再用隔离来宾设置探针。试验启动钩子从来宾执行 `rwtouch wait 800` 后触发图层转储，800 秒按来宾计时，避免直接与宿主单调时间对齐。试验可共同去掉这条诊断任务。[试验启动钩子](</root/workspace/hackios-run/glpass-uilite/rootfs/etc/rc.jobs>)
2. 固定 QEMU、内核、客体镜像、工作器和双 vCPU。记录宿主墙钟与 QEMU 线程 CPU，待校准后记录每 vCPU 指令增量，按完成交互数归一。空闲用单独窗口。比输入到首变化、最长停顿、动作完成时间及最终状态。只在空闲段省 CPU 时按空闲收益汇报。
3. 诊断模式在工作器发布时逐帧采全尺寸 BGRA 摘要、序号、单调时钟与局部变化。比较相邻发布的画面，检查运动板块的进展和末帧。DELTA 字节参照同一 surface 的历史版本。只有 ring 序号时只报告发布吞吐。跨档静态样式不同，切换本身不计一次动画。单独确认逐帧采集的扰动。
4. `chronod` 用同构冷启动配对，唯一改变是一份真实作业注册。比较到首屏、可交互和稳态三个阶段。记录服务成功/失败、launchd spawn/exit、daemon 累计 CPU 与总 CPU。稳定窗口的空闲收益须与实际交互一起汇报。

交叉审查包括 TCG 的每次完成交互归一及 EL/partial TB 误差，验证侧的服务净成本和掉帧否决线，以及传输侧的邻接全帧对比。我向传输侧反馈同 VA 复用、CPU 写未发区块、提交失败与交替 surface 的回归点。共享通道应在相同锁和提交语义下只替换搬运路径。来宾减负和精简内核各做单因素后再测组合。

## 隔离夹具与输入标识

已准备 `hackios-run/patched-guest-load/rootfs-no-layer-dump`，逐项比较了 66 个普通文件或符号链接。唯一字节差异是删掉 `rwtouch wait 800 notify com.redwood.layers` 诊断行。启用此夹具的同一次启动覆盖 A/B 两档。首选在来宾 Settings → Accessibility → Display & Text Size 打开或关闭 Reduce Transparency，先记录原值并在最后恢复。若该路径不可用，SDK 已核对 `_AXSEnhanceBackgroundContrastEnabled` 和 `_AXSSetEnhanceBackgroundContrastEnabled` 两个 C 符号，供隔离探针读回和设置。SDK 导出本身仍需来宾验证，探针尚未编译。

| 输入 | SHA-256 |
|---|---|
| 参考 `inject_sb.c` | `4a398e3c01c837d6d9f29cc544be394828905eb8d9f9f67f5eed3f99d89e286a` |
| iPhoneOS16.5 `libAccessibility.tbd` | `e08274e301ae014c9a923974fb3b964be7eac00e2a56a1ed5ca4185cf7b73cab` |
| 发行脚本包 | `dbb6c3fb90be59980a8fde6534de9ed4380936c17c82cdcce6f679b8b6e3f080` |
| `payload-shipped.bin` 输入 | `059258ced5e4dffe04bcb82adca8042292bd606ae550b36f6ef5f3a43f6fdc32` |
| 原始启动钩子 | `b65879db86630d54435d4ea720a7ffdccdb9b204b52fffb6bf0872db918e5e81` |
| 隔离启动钩子 | `0aa01701d9baaafdf1d9ec706184c661a77f4231bd9524f2cfa399c21dfd6a86` |

候选的性能运行等待修正 stride 的回写构建、连续帧验收和 lead 的单机队列。启动时以该轮试验记录的最终 payload、QEMU、worker、dylib 哈希为准。

## 当前启动异常（只读调查，2026-11-19）

当前精简内核运行 839.11 宿主秒仅发布 4 次，stock 在本次读取到的 626.1 秒仅 2 次。两者没有进入测量阶段。不能据此说精简改动造成回退，也不能把少回读视作 GPU 握手失败。查看相同原始日志，双方都已创建 Adreno 830 context，并成功完成前两次完整回写。

### 已核对的相同项和不同项

- `desk-lean`、`desk-baseline`、`glpass-iofix`、`glpass-fast`、`glpass-v2c2` 的 XNUP ramdisk 均为 **5,046,272 B / SHA256 5bc1202b609ceb4d879ece4cf5f034c38029fe9c98fcb7e97690a14ece691e9e**。对展开 root 的所有普通文件逐项比字节、目录模式及符号链接目标和额外路径，当前两份与 fast 均无差异。无需再猜 `rc.jobs`、作业表或 APFS root 打包内容发生分叉。
- 原生 `fbscale=3`、2 vCPU、`virtio_input=1 virtio_net=1`、`epoch=1793876701`、`loader_return_after=1200`、`gl=1 plain_vibrancy=1 wdt=-1 wdt_test=no_user_checkin launchd_unsecure_cache=1`、`rdres=948fb000` 相同。loader 均为 `9375b0beadf609f5cafb6f65df3b6d6e3d4d168d62280c0e3701194e6ea01288`。uilite 多一个缺少实现标记的 `ui_lite=1`，不作为功能对照。
- 当前两个 identity 记录 QEMU **20be6a6b… / 11.1.2**，worker **37479877…**，guest GL **06823406…**。lean 内核 **f73df2af…**，stock 内核 **7b1f9083…**。iofix/fast payload 中同为 **7b1f9083…**；v2c2 payload 的内核是 **03e794e8…**，不能混作相同内核。
- 旧 iofix/fast 的运行记录指向 Inferno 测试二进制，旧 v2c2 指向发行 QEMU。旧运行没有完整持久 runtime identity，不能靠今天覆盖过的 `patched/.../qemu` 给历史运行重新补 hash。当前两份精确身份以各自 identity 为准。
- 网络设备都报告 slot31、offered features **0x30bf8024**、MAC **52:54:00:12:34:56**、link up；当前 lean DHCP 3.1 guest 秒取得 **10.0.2.15**。目前没有“网卡没起来”的证据。`mobileactivationd` 在上述各运行均有启动成功记录，不能再次归咎于完全没注册激活服务。
- 不扫描或哈希活动的大 RAM 镜像。本轮仅确认各 identity/命令指向同一 `ram-correct.img`，其内容的历史不变性仍是待验证前提。UEFI vars 是可变状态，现有旧试验只存启动后文件，无法从其现值证明当时初始 vars 相同。但双方已经进入内核和图形库，后段等待不能仅用 EFI 启动顺序解释。

身份材料：[lean](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/desk-lean/identity.json>) · [stock](</root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/desk-baseline/identity.json>)。

### 等待发生在哪一段

| 运行 | frame2 完成到下一 read_surface 开始的 guest `gap` | 下一回写自身 total | frame3 后到 frame4 的 gap |
|---|---:|---:|---:|
| lean 当前 | 609.061 s | 0.626 s | 120.146 s |
| iofix 旧 | 324.807 s | 0.179 s | 38.694 s |
| fast 旧 | 297.065 s | 0.168 s | 46.123 s |
| uilite 旧 | 301.924 s | 0.191 s | 32.009 s |

这是 guest 中两个 read_surface 调用的间隔，**不是 socket 正在传一个包耗了 609 秒**。lean 的首次两帧在 host 约 96.5、106.7 秒，第三帧到 host 715.7 秒才出现；worker 完成了其 reply。当前 lean 的 read4 甚至是 8-byte 零带回复，完成 socket write 仅约 2.1 ms，但之后桌面仍未就绪。stride 错误还没部署修复，不影响 connect/EA_OPEN 的判断；后续可见内容正确性必须另验。

lean 同一次启动：host119.0秒时最后可辨日志11:06:46.439，host360.0秒为11:10:30.256，host599.3秒为11:14:10.834，host719.8秒为11:16:47.979，host839.1秒为11:18:45.268。日志有缓冲与交错，不能拿每条推精密时钟倍率；但足够排除来宾整个时钟和所有进程完全停死。旧 fast/iofix 后来持续发布的事实，也不能证明它们始终处在相同工作量或可见正确桌面。

### 三条可证伪解释（按下一步价值排序）

1. **QEMU 版本与内核/默认机器版本的组合差异**。当前 lean 与 stock 都慢；旧 Inferno 配同 stock 7b 内核曾继续推进，旧发行 QEMU 配另一 03e7 内核也曾推进。这个不完整交叉矩阵比“lean 坏了”更值得排查。两侧均使用 `-M virt` 别名：Inferno 源码 `hw/arm/virt.c:3519` latest 是 10.1，当前发行二进制的 resolved 默认 machine 没被 identity 持久记录，不应假定相同。**证伪**：先保留单机锁、不改 poll，查询并记录可用显式机器版本；仅在同 stock kernel/root/worker 和固定初始 vars 下对发行 11.1.2 显式 `virt-10.1` 与当前 alias 做单变量配对。必要时再做两内核×两 QEMU 的最小矩阵。若同 source/同显式机器与完整输入复现相同长 gap，则该项不成立；不能把未经核对的 timer 补丁加回充当修复。
2. **lsd 初始化扫描/注册等真实来宾工作或内部等待拖住 SpringBoard**。lean 有不同于权限告警的 CPU ledger 证据：`thread 335 ... actual recent usage 55% over ~164 seconds`、lifetime **256.431393s**。但旧成功 iofix 同样出现 54%/~165s、lifetime **172.866562s**，说明它既有真实成本，也不是新版本独有故障。lean 的 lsd 对 installd/analytics/profiled 的首批可辨查找直到 guest11:16:32 才出现，随后 frame3 才推进；这只是次序线索，不能证明缺服务导致这 600 秒。**证伪**：经校准的 EL/指令分账加少量来宾进程累计 CPU/线程状态，按初始化阶段而非仅墙钟对齐；若 gap 期间 lsd CPU 并不增加、主要线程等待的事件来自别处，或 lsd 完成后仍相同停滞，应转向依赖与合成状态，不去屏蔽服务。精简内核把时间合并记为 user，比较应使用总量。
3. **宿主有效执行吞吐、内存调度或来宾时序竞争，放大原本有限初始化**。当前 stock 首两次完整回写的 worker socket_write 为 **4.067/3.894秒**；旧 fast 为 **1.222/1.142秒**。这已显示前段运行成本也较高，不只在某个固定 UI 定时器上空等。lean 的 total CPU 在 host239.4→599.3秒增加446.77 CPU秒（约1.24核），很多宿主CPU未变成可见进展。温度最大值与累计CPU不足以分辨有用计算、poll、缺页或调度停顿。**证伪**：下一次受控运行在相同阶段低频记录每线程user/sys、major/minor faults、调度运行/等待、实际CPU亲和与频率（有权限才读），对齐网络队列/IRQ和guest CPU；保持手机单负载、同输入及冷启动条件。若资源压力/线程执行吞吐相当而差异严格随固定 QEMU/kernel 组合出现，则降级这个解释。不能靠关闭其他应用后碰巧一次更快就宣布优化。

### resolved 机器版本的轻量验证

lead 允许后，对两个固定二进制只执行 `-M help`，设置相同的依赖库路径，两个进程都立即以 0 退出，没有创建来宾。

| binary SHA-256 | `-M virt` 解析结果 | 是否支持显式 `virt-10.1` |
|---|---|---|
| `aaca8ea952d1d22ab351e11f8a2d998676a42d6591e3c07c418976c9ca9b37bd` | QEMU 10.1 / alias of virt-10.1 | 是 |
| `20be6a6b99733888e6b46d26d9bed058e5f6dae80cab44fa9b0c7dc4fc6783e3` | QEMU 11.1 / alias of virt-11.1 | 是 |

最便宜的下一步是发行 11.1.2 二进制与 stock 7b 内核不动，仅把 `-M virt,gic-version=3` 改成 `-M virt-10.1,gic-version=3`。TCG 队员评审支持先做这个单因素，固定原始 vars hash、firmware/loader/ROM、CPU/cntfrq/vCPU 拓扑、epoch/bootargs、virtio force-legacy、RAM/root、worker/dylib、测量和退出条件。显式老 machine 一次会带入一组兼容属性，因此即使恢复启动，也只能归因为 machine compat 组，不能直接宣称某个定时器或网络属性是根因。

identity 建议记录 `machine_requested`、`machine_resolved`、对应 QEMU 版本与 help 输出 hash；使用别名时不要只保存字符串 virt。先比较已保存的 property 与设备树，后续在已安排的唯一 VM 上做 QMP machine/device 查询，不能为了查属性额外启动第二个 VM。

### 启动减负的后续合理路线

先恢复相同输入下可见正确且可交互的基线，再缩短启动。参考 `init.c:1074` 与 `var_store.c:1–12` 明确 `/private/var` 每启动从空 ramfs 重建，应用注册与派生缓存可能重做。当前试验缺少持久化实现回执，不能把重复冷启动当成已有缓存热启动。可在独立可丢弃的数据卷设计版本绑定的派生缓存保留/恢复，先确认具体缓存和 lsd 的实际 CPU/读写工作，再做冷/暖对比；内核、系统镜像、已安装应用列表或用户身份变化时必须失效。这里只是设计，不复制个人数据库或活动用户盘。

不能直接开启参考 `var_store=1`：该实现写 `/dev/rufs_darwin` 的固定 8 GiB 偏移，是参考手机移植的物理存储协议，不是本项目当前 QEMU 数据盘接口。未经专门移植与可丢弃盘验证，严禁借“缓存优化”去写真实手机分区。

正式目标是原分辨率、连续典型交互实际内容 10 FPS 且图形无异常，2–5 FPS 仅中间里程碑；本报告没有把发布吞吐换成目标达成。以上仍为解释和待排实验。本轮仅读已有约 MB 级日志、payload/小 root 与 manifest；帮助命令未创建来宾，未复制大 root、未改当前 stock 试验。
