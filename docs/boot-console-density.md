# 启动串口：CPU monitor 重复告警

在已验 Android APK 和 Inferno 启动里，`lsd` 各输出 701 条 CPU monitor 权限告警，均早于首条可识别的 `lsd` 服务查找失败。它们来自 XNU `task_set_cpuusage`：对无对应 entitlement 的请求恢复默认值或裁剪到上限。不要把它们当作 XPC 重试，也不要通过给来宾进程授权来消除日志。

## 实现与边界

XNU 只合并同一 task 的自请求重复告警：原文输出第一次，之后在累计 2、4、8、16 等次输出带 PID 和累计次数的摘要。跨任务请求继续逐条输出原文，独立 task 的首条互不影响。计数在原有 task 锁下更新，达到最终可打印节点后饱和，不分配内存、不查时间。照常执行 entitlement 判断、默认值裁剪、CPU monitor 配置和 ledger 更新；不是权限变更，也不减少请求本身。摘要最后的计数不是该任务的精确最终拒绝总数。

## 验证与下一道门槛

- 用当前 XNU 8796.121.2 源在临时副本对三个相关文件执行 `git apply --check` 并实际应用，验证变更前的权限判断及变更后的 CPU monitor/ledger 代码保持字节一致。
- 宿主 C 测试：`cc -std=c11 -O2 -Wall -Wextra -Werror xnu/test-cpumon-log.c -o /tmp/cpumon-log-test && /tmp/cpumon-log-test`。模拟同一任务 701 次拒绝，输出 10 次，最后报告累计 512；验证不同任务独立、计数不回绕。这个比例是函数测试，不是新内核实测。
- 已安装缺少的 Ubuntu 工具；XNU 的隔离环境使用 Theos iPhoneOS 16.5 SDK（仓库提交 `0222fd5413cf4b9af096f37b4621afa2688572f7`）、原生 ARM64 cctools-port（提交 `e79d784d667816e4b15a0abd78828f9abb0a0b99`）、macOS 13.3 SDK 的 DriverKit 公共头，已完成阶段一及导出 XNU 头和构建配套静态库的阶段二。现有 APK、默认内核、镜像与数据盘未变。
- `bootlog=1` 是独立的可选功能：由现有 framebuffer presenter 读取真实内核 log ring，不新增线程。面板只画底部，720p 用 1×，1125×2436 用 2×。每 64 个可显示字符换行，最近六个视觉行可见，不把单条长日志的后缀裁掉；更早内容仍在串口。GCC UBSan 与逐像素测试覆盖换行、滚动、非法 pitch、区域外不动。
- 图形接管后只在已验证的 iOS 16.5 1125×2436 QEMU Logo 上继续显示。识别比对真实画面的 Logo 轮廓和背景，未识别或不匹配的图形帧不会被覆盖；显示范围还必须是完全均匀的底色。每次真正合成新画面前恢复原底部，离开 Logo 后永久退休，不会污染后续局部更新或给应用页重新打开叠层。其他分辨率只保证前置日志面板，不保证识别同屏 Logo。
- 隔离 XNU 已链接为 ARM64 Mach-O，SHA-256 `192a769621dbad5cd87cdbe5257a166ac6cfdbdc5330326efc3dbc861ff4afdc`；只剩仓库既有判定允许的 kext symbol-set 检查失败。初次编译的 `init_sysent.c` 失败是 AWK 保留了 `\(` / `\)` 的无必要转义；移除格式字符串中的这两种转义，生成的 C 类型转换语义不变。
- 用 `tools/repack-xnu-test-payload.py` 换入实验内核，分别追加 `bootlog=0/1`；已在现版 payload 验证不带选项直通字节一致，开关 A/B 仅差一字节。
- 第一版真机隔离 Inferno 验证了诊断合并：`lsd` 首条原文后有累计 2、4、8、16 摘要；但旧试跑 42 秒出现日志、60 秒出现 Logo，未同屏。
- 第二版试验内核 SHA-256 `7b1f908324fa13154c065f917555852406ddfbfc654f6c247c78c6d6a0644f17`。125 秒的隔离试跑第 58.8 秒记录 `bootlog drawn on Apple Logo frame`，QMP 同帧捕获到真实 Logo 和从上一行换下来的日志后缀；该短测未结束到桌面。宿主测试从实测 Logo 帧逐像素验证覆盖可精确还原，并确保真实 SpringBoard 桌面帧不会被识别为 Logo。第二次独立运行 710 秒再次确认 Logo 同屏；第 690 秒仍停在 Logo，整个来宾只有两次完整 GPU 回读，没有观察到撤除标记。**桌面端到端尚未通过**；不能把 SpringBoard 进程启动等同于桌面加载完成。下一步以同内核开关对照、相同输入序列及既有内核控制区分是否为叠层引入，使实际进入桌面成为验收前提。现有 APK 未安装或替换。
- 第 29 轮同内核开关试跑：开启组的第 675 秒仍为 Logo + 面板；关闭组第 400 秒捕获真实 Logo 且无面板。两组各只有 2 次完整 GPU 回读。关闭组首次定时截图滞后到 +328.4 秒、后段因宿主调度时间跳跃错过；来宾串口推进到 11:12:19，开启组到 11:16:24。因此只能确认关闭开关时不画面板、关闭后也观察到停在 Logo，**不能据此排除叠层回归或通过桌面验收**。后续应用同一启动流程加旧内核控制，按实际场景/来宾事件触发，而不是用明显迟到的宿主定时指令假充匹配。
- 应用侧「重试」改为唤醒当前抓帧线程的等待，不抢占正在读帧的 QMP 命令，多次点击最多积压一个请求。Android GPU build 的 323 项 JVM 测试通过；未改变常态抓帧节奏，也未安装 APK。
- 第 30 轮重放历史成功启动的原始 XNUP（完整 SHA-256 `6e1d65267c100390d6e86dca3f01904af4132c512157af0ae9234a4cfd7ec497`）。这次第 720 秒仍为 Logo，但随后 `lsd` 首次可见服务查找发生于来宾 11:17:07，此后才有第三次真实回读；晚段因宿主调度跳时中断，未重新截出桌面。历史成功运行同一 `lsd` 日志在 11:13:10；因此早期试跑截在 Logo 并非新补丁独有，单靠 710 秒判回归没有充分证据。两版的 ramdisk、rdres、首次两笔 swap 前的 backboardd selector 序列相同；原始内核和重建内核自身不相同，仍不能归因到某条补丁。
- 抓帧验收器新增按真实串口 swap 和 `lsd` 进展截帧、按来宾 swap 可选发送一次上滑，并跳过严重逾时的定时手势。应用侧阶段 7 改为要求 live 帧在中央 Logo 与底部日志区之外有足够画面：取样重放真实黑底日志、无叠层 Logo、Logo 同屏均未升级，真实主屏与 Settings 升级。这是画面启发式，不替代核对桌面截图。
- 第 30 轮延长候选运行：同次启动 +59.9 秒 QMP 确认 Logo 与日志同屏，+728.5 秒见 `lsd` 首条查找，+736.6 秒捕获 `bootlog retired at graphics handoff` 及无日志的黑色过渡帧。后续重新出现 Logo，仍没有日志；即使 +827.1 秒已记录 `SBHomeScreenWindow`，QMP 截到的仍是无日志 Logo。试跑共有 38 次 1125×2436 GPU 回读、至少 8 次内核交换，但没有通过可见桌面参考图验收。已验证实际交接不会残留或重新叠加，仍须在同内核关闭对照和原始物理 framebuffer / 展示路径上追踪 GPU 输出与主屏截图间的差异。
- 第 31 轮用同一内核、相同镜像和 Adreno worker，设置 `bootlog=0` 重跑（无额外 QMP 手势；镜像内原有 `rwtouch` 仍运行）。约 +1080 秒发生清屏交接，+1172.6 秒记录主屏窗口后 QMP 仍是 Logo。直接从 loader 报告的物理 ramfb 读出同样的画面，与邻近 QMP PNG 的 RGB 平均绝对差为 **0.0000**；已验证的所有成对样本一致。这一现象关闭日志仍发生，且不是这些样本的 QMP 截图缓存；未证明所有延迟都跟叠层无关。从前一次成功取得桌面的是原始发行内核，不能把新版内核、面板渲染和用户态合成混为同一变量。
- 新增只读诊断：`capture-bootlog-frames.py --probe-ramfb` 保存指定事件的物理 BGRA 与相邻 PNG；`check-bootlog-frames.py` 比对两路的真实像素。下一层通过仅用于试验的 `--record-iosurface` 和 `--surface-ring-at` 配合 XNU 已有的 4 MiB、140×304 时序环，在 ramfb 写出之前检查 IOSurface 的内容；正常运行不启用。`read-iosurface-ring.py` 合成回归覆盖环绕、时序、颜色和坏格式拒绝。两路读取不是原子操作，动态画面差异不能只凭单对判定。225 秒真机烟测已经从该环读出 3 帧；以 XNU 一致的坐标下采样真实 Logo 参考，三帧 RGB 平均绝对差均为 0.0000。尚未读到晚期 IOSurface；下一次长跑才用于判断主屏是否在合成源已出现但没拷到 ramfb。
- 第 32 轮在宿主空闲时用同一重建内核、`bootlog=0` 和 4 MiB 录制环长跑 1185 秒（`inferno-boot.bykOAn`）。这次有 172 次全尺寸 Adreno 回读（此前受宿主负载影响的同类试跑只有 22–38 次），并在 +829.2 秒首次记录 `SBHomeScreenWindow`；同刻 QMP 已是真实 iOS 16 主屏，与历史主屏参照的 RGB 平均绝对差 **0.30**（阈值 15），底部无日志面板。物理 ramfb 与 QMP 的差仍是 **0.0000**。1157 秒处的 32 帧录制环（140×304，来宾 1018–1149 秒）末段也是同一主屏，证明合成源已经持有桌面、内核也确实把它写进面板。上次「记录主屏窗口但画面仍是 Logo」是宿主负载导致的来宾进度差异，不是日志叠层或 blit 造成的画面错误。
- **第 32 轮同一次启动完成完整验收**（`inferno-boot.oTZvyQ`，重建内核 + `bootlog=1`，宿主空闲）：+60.6 秒串口记 `bootlog drawn on Apple Logo frame`，QMP 为真实 Apple Logo 且日志面板存在；面板里超宽行按 64 列折行，被截断的 `0xff…` 后缀在下一行继续可见。+417.9 秒记 `bootlog retired at graphics handoff`，同刻面板消失，底部为过渡黑。+531.9 秒（swap 8）与 +829.1 秒（`SBHomeScreenWindow`）以及 +1145 秒三张截图全部匹配真实 iOS 16 主屏（RGB 平均绝对差 0.07–0.30，阈值 15），且**都没有日志面板**。物理 ramfb 与同刻 QMP 的差仍为 0.0000。至此「亮 Logo 同时有日志、超宽折行、进桌面后画面无日志」在同一启动内全部实测通过。

## 0.30.0：合并进 App

- 内核资产 `app/src/main/assets/mach.development.bcm2837` 已换成带 bootlog 叠层与 CPU monitor 合并告警的重建内核：15,791,240 字节，SHA-256 `7b1f908324fa13154c065f917555852406ddfbfc654f6c247c78c6d6a0644f17`（旧资产为 16,809,112 字节，SHA-256 `03e794e8e1d389a6abb9bcd2431eaccf9747271ff8c1234922225718c70441e9`）。
- 托管启动参数追加 `bootlog=1`，`QemuPreflight.CMDLINE_PROFILE` 5 → 6，让所有旧 `payload.bin` 失效并重烘；否则老用户会继续加载旧内核。
- 新增 `AssetKernelTest`：检查资产是 64 位 Mach-O、大小与标记字符串（`bootlog drawn on Apple Logo frame`、`bootlog retired at graphics handoff`、`CPU monitor self-denials:`）都在，并把 profile ≥ 6 固定下来。325 项 JVM 单测通过。
- 产物：`HackiOS-0.30.0-bootlog-gpu.apk`（versionCode 56，versionName 0.30.0-bootlog-gpu，188,256,202 字节，SHA-256 `776bd908e92e9b167428b870b1761b2d28c96e7457c6fddf2d64e54eafdc72b8`），放在 `/sdcard/Download/DSH-Go/`。它是 **debug 签名**，与已发布的 0.3.5-gpu-test3 证书不同，直接覆盖安装会失败；要装需先卸载旧包（会清 App 数据，镜像与数据盘在公共目录、不受影响）。
- 已验证与未验证：同一内核、同一 `bootlog=1` 在隔离 Inferno 启动里通过「Logo+日志同屏 → 交接撤除 → 主屏无叠层」；**尚未**在 Android App 域里装这份 APK 实测。

## 0.31.0：默认不再在 guest 画面上叠日志

- `QemuRunner` 的托管 cmdline 去掉 `bootlog=1`，`CMDLINE_PROFILE` 6 → 7 强制重烘旧 payload。
  画面因此只有干净的 Apple Logo → 桌面，不再有底部日志面板。
- 内核资产不换：叠层代码仍在，只在带 `bootlog=1` 时绘制。想复现本文的历史验收，仍可用
  `tools/repack-xnu-test-payload.py --bootlog` 或手工把 `bootlog=1` 加回托管 cmdline。
- 串口本身不受影响：`serial.log` 照写，App 的「日志」页仍能看全过程；`AssetKernelTest` 的
  内核标记照旧，profile 断言改成 ≥ 7。详见 [0.31.0 UI 变更](ui-0.31.0.md)。
