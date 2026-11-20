# HackiOS Android UI / 操作逻辑复核（task-11）

审查对象：`/root/workspace/hackios-next` 的 HackiOS Android App；不是 DSH 界面。

## 证据口径

- **代码确认**：以下结论来自当前工作树实际源码与行号，不依赖旧审计的结论。
- **已跑过的本地验证**：工作树此前报告 Kotlin 编译和 355 项 JVM 单测通过；HFR2 `FrameRingTest` 22 项通过。它们不覆盖 Compose 点击回调、真实 Android 生命周期、宿主 GPU 页面或触摸事件。
- **未验证**：本轮没有真机 UI 点击、TalkBack、旋转、进后台、通知、Adreno 回退或完整「下载→编译→启动」操作录制。因此“代码确认”不等于真机已复现。
- 性能主线仍是原分辨率实际内容 10 FPS、宿主真实 GPU、画面无闪烁/黑块/抽搐；本报告只审 UI 与操作逻辑，不把采样 FPS 当内容 FPS。

## P0：应先修的直接操作阻塞

### P0-1 设备页“准备镜像”按钮在代码上是点击无效

- **触发路径**：首次安装，`state.artifacts.imagesReady == false`；设备页 Hero 主按钮显示“准备镜像”；点击它。
- **代码证据（修复前）**：原版 `DeviceScreen.kt` 的 `onClick` 在 `!imagesReady` 分支返回 `actions.openLibrary` 函数引用，未调用。当前工作树已改为 `actions.openLibrary()`。
- **后果**：原版点击不跳资源页、不启动下载；UI也无错误提示。现在修复尚待 lead 编译验证和真机点击验证。
- **最小修法**：改为调用 `actions.openLibrary()`；此调用已写入。
- **验证方法**：无镜像状态点击 `btn_power` 应进入资源页且不创建 QEMU。当前只做代码修复与 diff 检查，Compose 点击和真机尚未验证。

## P1：会导致用户误判成功、失败或等待状态

### P1-1 准备计时切到 Running 时归零，用户看不到整段启动耗时

- **触发路径**：启动；准备阶段显示“准备已用”，QEMU spawn 后状态切到 Running。
- **代码证据（当前状态）**：准备阶段 `QemuUi.Starting(..., sinceEpochMs = diagBootAt)`，进入 `Running` 后 `sinceEpochMs` 重设；`SessionElapsed` 现在明确分别显示“准备已用”和“运行已用”，避免将重置误读为同一计时突然归零。
- **后果**：不会显示一条连续总耗时，用户无法直接从 Hero 读取整段启动总时长；但当前文案已区分两个计时起点，不再暗示同一个计时归零。
- **最小修法**：如需单一总时长，另加总计字段；保留现有 BootTimeline 分段值，不把启动准备耗时混入 guest 引导段。
- **验证方法**：单测/Compose状态切换验证准备计时和运行计时标签；冷启动/缓存启动真机检查。当前仅代码状态复核，真机未验证。

### P1-2 性能提示在 Adreno 选择下仍宣称“完全不依赖 GPU”

- **触发路径**：GPU 测试包 + Adreno 8xx；打开 App 时性能提示弹窗仍显示。
- **代码证据（原实现）**：`HackIosApp.kt:207-229` 固定写“全靠 CPU 软件渲染”，Adreno 也会看到该文案。当前工作树已按所选后端动态区分：TCG 客体 CPU 模拟始终存在；OSMesa 是 CPU 光栅化，Adreno 写作 remoteGL/Zink/Turnip 宿主 GPU 实验路径。
- **后果**：原文让 Adreno 用户误以为宿主 GPU 完全未使用。新文案修正了选择态描述，但启动后的自动 fallback 当前仍无持久本次后端原因状态（见 P1-3）。
- **最小修法**：选择态动态说明已写；后续如需证明当前运行时后端，应从实际 worker/session 状态呈现，而不能以 renderer 字符串单独作为完整验收。
- **验证方法**：OSMesa 与 Adreno 两种弹窗文案需真机核对；GPU 业务验收需物理设备、ICD、submit/fence、像素闭环。当前仅代码检查。

### P1-3 Adreno 自动回退后只有短 Toast，设备页没有持久的“本次实际使用 OSMesa”状态

- **触发路径**：用户选择 Adreno，运行时或 GPU 根镜像静态失败。
- **代码证据**：`AppViewModel.kt:279-286` `fallBackToOsmesa()` 持久化关闭 Adreno、更新设置并发 Error Toast；`DeviceScreen.kt:110-116` 的 backend 仅读 `state.settings.experimentalGpu`。失败路径继续启动 OSMesa，成功后用户只看到 OSMesa选择，历史回退原因不在设备 Hero。
- **后果**：用户稍后回到页面可能误以为自己主动选择了 OSMesa，无法确认本次 GPU 目标是否实际通过；Toast 消失后故障原因难以追溯。
- **最小修法**：在 `AppUiState` 增加一次性 `backendNotice`/`fallbackReason`，设备卡片持续显示“本次已从 Adreno 回退 OSMesa：原因；点击查看日志”，下次用户主动重新选择时清除。
- **验证方法**：构造 Adreno 静态材料失败，确认 QEMU 仍以 OSMesa启动、页面持久显示回退原因；不要把该轮计入宿主 GPU验收。代码确认，未运行。

### P1-4 自动降核可能杀掉仍在推进的慢启动，而且重启会清除“一次”保护

- **触发路径**：guest 正持续画同一张 Logo/画面；内容指纹长时间不变，但串口 frame 仍增长；达到 `STALL_FALLBACK_MS`。
- **代码证据**：`AppViewModel.kt:398-413` 的 `maybeAutoFallback()` 只接受 `unchangedForMs`，不检查新的 `redwood-gl: frame`、guest wall-clock、QEMU 进程健康或串口推进。`AppViewModel.kt:889` 每次 `startQemu()` 都 `autoFallbackUsed = false`；`restartWithOneCore()` 在 `:387-390` 调 `startQemu()`，所以“只一次”保护在自动重启后重新清零。UI文案在 `AppViewModel.kt:2169-2176` 同时说 frame 号增长代表不是卡死，却仍可触发同一策略。
- **后果**：慢但健康的启动可能被自杀并重启；连续卡顿时可能反复降核，浪费十几分钟并污染启动对比。
- **最小修法**：把“无内容变化”与“无新 guest frame/无串口/进程退出”分开；最近固定窗口有新 frame、phase/串口推进或 worker 活跃时只报警不重启。把 fallback budget 放在一次 boot session 外、直到用户明确重新启动才清零；自动重启总次数持久限制为 1。
- **验证方法**：fixture 模拟同一像素、递增 frame 号，确认只告警不重启；模拟无 frame 且进程仍活跃，确认一次降级；第二次不得自动重启。代码确认，未真机运行。

### P1-5 失败卡仍把用户指向不可达的“重新部署资源”

- **触发路径**：MissingRom / LinkerError / BundleIncomplete 等资源故障。
- **代码证据**：`engine/QemuFailure.kt:94-114,193-195` 文案写“点『重新部署资源』”；`DeviceScreen.kt:161-176` 只有 `AssetsMissing` 显示“重新部署资源”，且回调实际是 `actions.retry`，不是显式部署；其它资源类失败走“重试/查看日志”。资源页的“工具链自检”在 `LibraryScreen.kt:450-458` 只打开诊断，不展示定向重部署动作。
- **后果**：用户按文案找不到按钮；点“重试”可能再次复现同一静态失败。
- **最小修法**：为资源类故障增加可达的 `redeployAssets()` Action，明确调用 `AssetDeployer.deploy()` 后做实文件/权限/ROM验证；失败卡按钮写真实动作“重新部署资源”。静态 Adreno 材料失败继续显示“切 OSMesa/去资源页重编”，不能统一叫重试。
- **验证方法**：人为移除 ROM、破坏一个 bundle、制造 linker error，逐类断言主按钮动作与恢复后文件存在；未真机运行。

### P1-6 ExecDenied 失败卡没有主恢复动作

- **触发路径**：`QemuFailureKind.ExecDenied`，`retryable=false`。
- **代码证据**：`QemuFailure.kt:117-125` 设置 `retryable=false`；`DeviceScreen.kt:161-173` 对非 retryable 且非特殊种类给 `primaryLabel=null`，只保留“查看日志”。
- **后果**：用户得到“系统拒绝执行 QEMU”，但没有“覆盖安装/导出日志/重部署”下一步，操作链终止。
- **最小修法**：主按钮“导出日志并查看安装建议”或“重新部署资源”；文案明确覆盖安装不会删除公共镜像与固件。若无法自动处理，必须把“需要重新安装 APK”作为可见下一步。
- **验证方法**：构造 classifier 的 exit=126/permission denied UI 状态，断言有主按钮；代码确认。

### P1-7 “可写实验盘（持久化）”的 UI 可能让用户相信 iOS 数据已持久化，但当前说明承认 guest 不会挂载

- **触发路径**：设备页存储选择 `PRIVATE_RW`，启动/重启后用户期待 iOS 数据保留。
- **代码证据**：`DeviceScreen.kt` 将 `PRIVATE_RW` 标为“宿主可写实验盘（guest 挂载待验证）”，并按 `DISABLED`、`PRIVATE_RW`、公共只读和私有只读模式分别说明。数据盘模式本身不再因 Adreno 被 UI 一概禁止；Adreno 仍受设备型号和运行时条件门禁。
- **后果**：宿主 qcow2 文件持久并不等于 iOS 数据已写入并跨重启恢复；块设备可写也不等于客体 APFS 已挂载到 `/private/var`。
- **最小修法**：宿主文件、块设备可写能力和 guest 文件系统/挂载分开描述；只有读回 guest 实际数据成功后才使用“iOS 数据已持久化”。当前 UI 已保留该边界，实际挂载仍待真机验收。
- **验证方法**：OSMesa/Adreno 与 `DISABLED`、`PRIVATE_RW`、只读模式逐项核 UI；guest 内写文件→退出→重启→读回仍待真机验收。

### P1-8 顶层性能闸门文案和 GPU 选项不一致，容易遮挡首次操作

- **触发路径**：每次打开 App，`perfGateDismissed=false`。
- **代码证据**：`HackIosApp.kt:207-233` 弹模态性能提示；“不再弹出”与永久设置分开，符合旧需求但首次必须关闭弹窗后才能点首页；同时固定文案错误描述 GPU。
- **后果**：用户首次点设备页/驱动会先被性能弹窗阻塞；在 Adreno 测试包上形成错误预期。若要强调等待，应保留但改为后端相关，并让“继续”明确关闭而非依赖点空白。
- **最小修法**：首次弹窗显示当前 backend/预计等待；按钮“继续”直接设置会话 ack，长期“不再提示”另存；将 GPU真实状态移入动态文案。
- **验证方法**：首次启动、旋转/切页、重启 App 验证 ack范围；代码确认。

## P2：可用性、导航和诊断口径

### P2-1 设备页无镜像时主按钮修复后仍应有一键链路，而不是仅跳资源页

- **代码证据**：`DeviceScreen.kt:230-249` 文案写“下载固件 → 编译镜像”，`LibraryScreen.kt:221-233,421-445` 仍由用户分别点下载/校验/编译/启动；`AppViewModel` 入口仍是分开的 download/compile/start。
- **后果**：即使修复 P0-1，用户仍需多次操作并在长等待中回页。
- **最小修法**：增加 `prepareAndStart()`，但每一步必须在状态卡显示、允许取消/恢复、且不重复下载/编译。
- **验证方法**：清空公共镜像、点击一次，执行下载成功→编译→启动；任何失败卡给可达恢复动作。当前未真机验证。

### P2-2 首页下载失败只显示“下载失败”，原因藏在资源页

- **代码证据**：`DeviceScreen.kt:622-631` 的 `downloadSubtitle` 对 `DownloadUi.Failed` 返回固定“下载失败”；`DeviceScreen.kt:507-512` 资源行只做 `openLibrary`，设备 Hero 没有 `BuildUi.Failed/DownloadUi.Failed` 卡；详情实际在 `LibraryScreen.kt:191-198`。
- **后果**：用户在首页看不到原因/是否可续传，容易重复点启动或误以为 QEMU坏了。
- **最小修法**：首页显示失败消息首行、已保留字节和“去资源页”动作；不把下载失败冒充 QEMU失败。
- **验证方法**：离线/中断下载状态下观察首页状态卡；代码确认。

### P2-3 6.2 GB 下载没有蜂窝确认

- **代码证据**：`AppViewModel.kt:724-734` 只调整蜂窝连接数；`LibraryScreen.kt:200-206` 只写 CDN/断点续传，不显示网络类型或数据量确认。
- **后果**：误触可能消耗数 GB 移动流量。
- **最小修法**：检测 `TRANSPORT_CELLULAR`，在首次启动下载前显示“当前移动数据，约 6.2 GB”确认，可记住本次选择。
- **验证方法**：模拟网络类型；未真机验证。

### P2-4 编译/QEMU 没有前台服务/通知，后台生命周期风险仍未解决

- **代码证据**：`AndroidManifest.xml` 没有 Service/foregroundServiceType；`LibraryScreen.kt:330-335` 反而要求用户保持页面前台；`HackIosApp.kt:80-85` `keepScreenOn` 仅在 `QemuUi.Running`，编译/准备阶段不保持屏幕；编译/启动路径未见持久通知。
- **后果**：锁屏、切后台或 Android 进程管理可能中断长任务；实际被杀概率仍需真机生命周期实验，不能直接断言必杀。
- **最小修法**：加 `dataSync` 前台服务承载编译/QEMU，或至少为编译/准备加唤醒锁和明确后台通知；所有子进程退出都要清理端口。
- **验证方法**：开始编译/启动后按 Home、锁屏、返回，检查任务继续、通知和 QEMU 退出清理；当前待真机。

### P2-5 DisplayScreen 的“内容 fps”仍是采样端指纹变化，不是最终屏幕实际内容 FPS

- **代码证据**：`CapturePipeline.kt:187-196,294-315` 用 `QemuPpm.stats` 的 signature 变化计算 `contentFps`；`DisplayScreen.kt:261-266` 直接展示“内容 fps”。这只能说明 App 捕获到的帧指纹变化，不能证明 Compose/Android 屏幕提交、guest IOSurface 回写和用户可见内容同步完成。
- **后果**：用户或开发者可能误把采集变化数当 10 FPS 达成，尤其在旧帧/撕裂/采样重放时。
- **最小修法**：标签改为“捕获变化率（诊断）”，正式 10 FPS 验收另用连续 producer pattern + guest writeback + 最终可见动画/帧提交 oracle；UI可以并列显示但不能叫正式内容 FPS。
- **验证方法**：固定序号图案+真实滑动/开关 App，至少两次独立 boot，去掉首帧预热，核对最终画面与回写；待真机。

### P2-6 性能闸门、引导说明与实际 backend 的用户语言需统一

- **代码证据**：`HackIosApp.kt:215-219` 固定说 softpipe；`DeviceScreen.kt:360` 写“Adreno 830 已实测，其它 8xx 理论通用但未验证”；当前生产图形身份应写 remoteGL/Zink/Turnip/KGSL，而不是给用户暗示具体 VKGL 已接入。
- **最小修法**：分“OSMesa CPU 软件”“Adreno 实验 GPU（Zink/Turnip，830 实测）”；测试包和普通包提示不同，若自动回退则持久显示实际 backend。
- **验证方法**：对照 worker manifest 中 physical-device/ICD/hash/submit/fence/pixels；不以 renderer 字符串或独立探针代替完整系统画面。

### P2-7 触摸/旋转/缩放需做真机操作序列，而不是只依赖纯函数测试

- **代码证据**：`DisplayScreen.kt:184-205` 以当前 Compose guest surface 的 `size` 和 `TouchMapper.toGuest()` 映射；`TouchMapper.kt:35-65` 仅对静态 letterbox 做几何映射；`DisplayScreen.kt:208-223` 用 aspect-correct canvas 绘制，理论上一致。`TouchMapper` 有 JVM 测试，但没有发现覆盖 Android旋转、窗口Insets变化、触摸过程中尺寸变化的 UI 测试。
- **后果**：旋转、分屏、键盘/底部条出现时可能映射偏移；用户以为 GPU画面异常，实际是触摸操作落点错误。
- **最小修法**：把同一 `Viewport` 计算结果同时用于绘制和触摸；屏幕尺寸/insets变化时取消当前手势并重新开始；增加 Compose pointer/insets 测试。
- **验证方法**：锁屏/解锁、旋转、进入设置、滑页、返回；记录 guest 坐标与可见控件是否一致。当前未真机。

### P2-8 返回键和屏幕页状态会隐藏操作上下文

- **代码证据**：`HackIosApp.kt:267-269` detail 页返回 detail=null；屏幕页返回切设备页。`DisplayScreen.kt:128-163` 启动中/失败页“回到设备页”或“查看日志”，但没有在当前页直接切后端/去资源页。
- **后果**：错误发生在屏幕页时，用户需要多步返回设备页找恢复按钮；启动中看不到资源状态。
- **最小修法**：错误卡保留“看日志/去设备页/去资源页”明确动作；返回后保留失败上下文，不让状态只存在上一页。
- **验证方法**：从屏幕页启动、失败、返回、切日志、回设备页；确认 back 不误停 QEMU，stop 仍需明确确认。

### P1-9 触摸拖出画面 letterbox 时可能丢失 release，后续手势像“卡住”

- **代码证据（原实现）**：原 `DisplayScreen.kt:184-205` 对 down/move/up 分别 map；up 落在 viewport 外为 null，未调用 release；反向从 letterbox 按下再拖进画面会发无 down 的 move。
- **后果**：客体可能收到按下但没有释放，用户可能误判画面/触摸/GPU卡住。该风险为代码确认，未真机复现。
- **修复**：当前工作树新增纯 Kotlin `engine/TouchGesture.kt`；无效 down 拒绝整序列，有效 move 越界时在 last-valid 坐标发一次 up，正常 up/cancel也恰好一次。`DisplayScreen.kt` 用 `try/finally` 兜底 pointer 丢失、手势协程取消、`running` 变化；对已被 overlay child 消费的 down 不转发到 guest。
- **集成验证结果**：lead 已运行单 worker 全量编译与单测，BUILD SUCCESSFUL（1m6s）；JUnit 45 个 suite、373 tests、0 failure/error/skipped，含 TouchGesture 新增5项与 FrameRing 23项。该结果是此前 APK/JVM 编译通过，不等价于当前 0.4 变更的重新编译，也不等价于 Android真机手势或 Compose overlay 集成通过。
- **仍待真机**：拖出 guest viewport 松手后 QEMU 是否收到释放、`更多` overlay 不穿透、导航/Running状态变化时 QMP event 顺序；需在GPU画面稳定后按固定的点击/滑动序列验收。



- `DeviceScreen` 准备入口、性能后端文案、数据盘持久性说明已在 lead 授权的 UI 小 batch 修改；仍待 lead 统一编译和真机交互验证。
- `DisplayScreen` 越界/取消 release 风险已有独立 `TouchGesture` 状态机与 5 项 JVM 测试源，overlay down 改为尊重 child consumption；当前等待 lead 统一 Gradle，Compose pointer / 真机触摸测试未完成。

本轮 UI 改动仅涉及 `DeviceScreen.kt`、`HackIosApp.kt`、`DisplayScreen.kt`、`engine/TouchGesture.kt`、对应纯 Kotlin 测试与本报告；未修改 QMP、TouchSender、图形代码、assets、镜像、用户数据或 GitHub 远端。实际运行验收仍待进行。

## 复核更正

- 自动降核并非数学意义上的无限循环：`QemuTopology.fallbackCores` 在稳定下限停止；但 `startQemu()` 重置 `autoFallbackUsed`，仍违反“一次”保护，并可能在 6→3→2 的多轮重启中中断慢但健康的启动。Adreno 路径在 `maybeAutoFallback` 中明确排除，不能把该风险归因到所有 GPU 启动。

- **首页驱动和存储**：代码确认，`DeviceScreen.kt:315-447` 均在首页；可写数据盘设置存在，但 guest 真正挂载/持久化未证明，见 P1-7。
- **删除导入驱动**：当前首页只有 OSMesa/Adreno 两个内置后端（`DeviceScreen.kt:82-86,315-335`），未发现导入驱动 UI入口；代码确认，未做 APK 真机导航全量扫描。
- **Adreno 门禁**：`DeviceScreen.kt:120-128` 和 `AppViewModel.kt:249-264` 均要求 `isAdreno8xx`；UI提醒“理论上 8xx 通用，830 已实测”，符合要求。仍需确认自动 fallback 后页面不误报 GPU（见 P1-3）。
- **去 guest 屏幕日志叠层**：当前 boot args 在 `QemuRunner.kt` 中注释说明不再加入 `bootlog=1`；UI日志页是 Android 诊断页，不等同 guest overlay。代码确认，未真机截图复核。
- **可写持久存储**：宿主 qcow2 创建路径与跨重启文件保留代码存在，guest 实际 APFS/挂载不成立，必须降级文案和实测验收。

## 优先修复顺序 / 当前状态

1. **P0-1 已修复，待验证**：`!imagesReady` 主按钮改为调用 `actions.openLibrary()`；待 lead 编译和 Compose/真机点击验证。
2. **P1-4 待修**：自动降核可在多个档位逐次重启直到 2 核下限，并可能中断慢但健康的启动；加入 guest 进展证据，保护一次 fallback 预算。
3. **P1-1 部分解决**：准备和运行分别标注计时，避免切状态像从零开始；若需总耗时应再单独增加全程时长，不能将准备与引导混为一个不清晰的表盘。
4. **P1-2 / P1-7 / P2-6 文案已修，待编译和运行时核对**：按所选后端说明图形路径和 guest 存储验证边界；运行后仍需核对实际 fallback 与 worker 身份。
5. **P1-3、P1-5、P1-6 待修**：持久提示实际 GPU fallback；资源失败卡有可达重部署；ExecDenied 有明确的安装/导出日志下一步。
6. **P1-9 状态机与 5 项 JVM 测试已写，待 lead 编译和真机验收**：覆盖有效/无效 down、越界、重入、正常 up、取消；仍需 Compose pointer / overlay 消费 / QMP touch 端到端验证。
7. **P2-2 / P2-3 / P2-4 待评估**：首页失败信息、蜂窝下载确认、后台服务/通知。
8. **P2-5 / P2-7 待验收**：区分采集变化率和最终可见 FPS，做真实连续画面、触摸与旋转序列。


## 复核限制

本报告未修改图形/QMP/TouchSender代码、assets、镜像或用户数据；新增的UI小batch及纯手势状态机/测试均在当前工作树，lead统一Gradle验证前不宣称通过，真机触摸/旋转仍未验证，未提交commit。