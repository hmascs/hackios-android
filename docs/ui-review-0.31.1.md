# 0.31.1 · UI 一致性审查与操作人性化

审查对象是 0.31.0 工作区（未提交的 0.31 改动 + 本次 UI 调整），**未改版本号、未改
`app/build.gradle.kts`、未打包 APK、未动 assets 与公共存储**。带「原」的是 0.31.0 原始行号
（对应代码已被本次改动覆盖）；其余为当前工作区行号。

验证：`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 ./gradlew :app:compileDebugKotlin
--no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m` 通过。没有跑 `assembleDebug`
（主代理在编 QEMU），也没有跑单测；本次改动集中在 UI、文案与一处入参校验，不涉及 QEMU 拓扑。

## 总览

- 最严重的三类问题：**可写数据盘选不中 / 改容量会偷偷切模式**、**启动中仍可点「启动」且屏幕页
  说「没有运行」**、**危险操作（清理残留、清日志）缺少一致的保护**。
- 视觉上主要是三类不一致：资源页与其它页的 section 标题不是一套、日志/诊断页水平内边距叠了两层、
  「绿色 = 成功」被装饰性图标占用。
- 首页密度偏高，本次只压缩了行高与「已选择/选择」这类噪音，**没有折叠驱动/存储**；这是留给
  主代理决定的结构性改动。
- 已知的「固定标题栏透字」不是 App 能靠参数修的：Glasmio 0.1.1 在 `GlasmioPageBody` 里固定
  10dp progressive blur，本次没有改库，详见 A1。

## A. 协调性（视觉一致性）

| ID | 严重度 | 现象 | 影响 | 证据 | 处理 |
| --- | --- | --- | --- | --- | --- |
| A1 | 高（待真机） | 设置页列表滚到固定标题栏下方时，内容仍然可辨认，像标题和列表叠字 | 标题、分组标签读起来发花；弱玻璃档反而不明显 | `ui/screens/SettingsScreen.kt:99`；Glasmio 0.1.1 `GlasmioPageBody` 固定 `progressiveBlurBackdrop(..., maxRadius = 10.dp)`，`scrollEdgeContentFade` 顶部只降到 25% 不透明度 | **未改库**。App 层没有 blur 半径/不透明度参数，硬改只能复制 `GlasmioPinnedPage` 内部实现。建议二选一：给 Glasmio 提 `topBarBlurRadius`/`topBarTint` 参数；或三个列表页改成 `GlasmioPinnedPage + 自绘 LazyColumn`，传 `topBarBlurProgress = 1f`。需真机截图确认严重程度 |
| A2 | 中 | 资源页用 `GlasmioSectionTitle`（17pt / 39dp 高），设备、设置、诊断用 `SectionLabel`（13pt / 约 26dp） | 同一个「分组标题」层级出现两种字号和高度，跨页切换时节奏不一致 | `ui/screens/LibraryScreen.kt` 原 252、277；对照 `ui/components/Common.kt:202` | 已统一为 `SectionLabel`（资源页两处） |
| A3 | 中 | 日志页分段在屏幕 32dp 处、日志正文在 30dp 处；诊断页卡片 32dp、标签 36dp、结果 32dp | 同一页左右边线对不齐，看起来像随手摆的 | `ui/screens/LogsScreen.kt:132-136`、`ui/screens/DiagnosticsScreen.kt:50-73`；`GlasmioPinnedPage` 的 `padding` 已含 16dp | 已去掉内层重复的 16/20/14dp；日志页分段与正文现在都从 16dp 开始 |
| A4 | 中 | `GlassPanel` 自写 `RoundedCornerShape(26.dp)`，其它玻璃面是 Glasmio 的连续曲率 | 同一屏里卡片圆角的曲率不同，细看「边角收得太急」 | `ui/components/Common.kt` 原 80、92 | 改用 `LocalGlasmioShapes.current.medium`；`GlassKey` 的 18dp 也改为 `shapes.small` |
| A5 | 中 | 「绿色 = 成功/开启」被装饰占用：宿主算力池图标和数值是绿色；`system.apfs`/`root.apfs` 缺失时图标仍是绿色；固件「未下载」是蓝色 | 用户会把「宿主算力池」误读为已开启状态，或把缺失镜像看成正常 | `ui/screens/SettingsScreen.kt:144-160`、`ui/screens/LibraryScreen.kt:258-266`、原 103-108 | 宿主算力池改蓝色；镜像图标按存在与否绿/橙；固件未下载改中性灰。绿色只留给成功/开启 |
| A6 | 中 | 错误提示用错图标：Toast 的 Error 画的是锁，失败 Banner 画的是电源 | 「锁」会被读成权限/加密，「电源」会被读成启停，不像错误 | `ui/components/Common.kt` 原 420；`ui/screens/DeviceScreen.kt` 原 156 | 新增 `Glyph.Alert`（圆圈感叹号），两处都换掉 |
| A7 | 中 | 首页从 hero 到页脚约 1900dp（估算）：hero + 驱动 2 行 + 存储 6 行 + 环境 5 行 + 3 段脚注 | 第一屏能看到「启动」，但驱动/存储这类低频设置占了首屏之后的大段滚动；存储四模式每行都带说明 | `ui/screens/DeviceScreen.kt:130-470` | 只做了低风险压缩：模式行的 subtitle 移到脚注、去掉「已选择/选择」、容量说明缩短。**未折叠**；建议下一步把四种模式收进一个 Action Sheet（约省 150dp），或把驱动/存储放到「环境」之后可折叠分组 |
| A8 | 低 | 屏幕页 guest 画面 30dp、控制条 24dp 仍是普通圆角 | 与全局连续曲率不一致，但这是沉浸式容器，观感影响小 | `ui/screens/DisplayScreen.kt:151/339` | 本次未动，避免改沉浸式页面的观感 |
| A9 | 低 | 长 subtitle 会被截断：`GlasmioListRow` 的 subtitle 固定 `maxLines = 1`，路径与 GPU 识别串经常放不下 | 「固件与镜像位置」看不到完整路径，排障时只能点按复制 | `ui/screens/DeviceScreen.kt:428`（路径行）；Glasmio `GlasmioListRow` 内部限制 | 本次保持「点按复制」，没有改行高；建议把路径改成可换行的脚注或单独一行等宽文本 |

## B. 人性化（操作逻辑）

| ID | 严重度 | 现象 | 影响 | 证据 | 处理 |
| --- | --- | --- | --- | --- | --- |
| B1 | 高 | 点「可写实验盘（持久化）」只弹「请选择 1–128 GiB 的整数容量」，选项不生效 | 0.31.0 新增的可写盘在界面上完全不可用，用户会以为盘坏了 | `state/AppViewModel.kt` 原 265-270：白名单只有 DISABLED / PRIVATE_QCOW2 / LEGACY_PUBLIC_READ_ONLY | 已把 `PRIVATE_RW` 加入白名单，并把「模式不支持」与「容量越界」拆成两条错误。**没有改 IosDataStorage/QEMU 语义** |
| B2 | 高 | 在「实验数据盘容量」里改容量，会静默把模式切回只读私有盘 | 可写盘用户改一次容量就丢模式，且界面只在下一帧变化，容易以为没保存 | `ui/screens/DeviceScreen.kt` 原 339-349：`onConfirm` 写死 `setDataDisk("PRIVATE_QCOW2", ...)` | 已改为沿用当前 `mode`；提示改成「修改只影响下次启动使用的盘文件」 |
| B3 | 高 | 启动中「启动 iOS」仍可点；屏幕页在启动中显示「QEMU 没有运行」并再次邀请启动 | 点了没反应（VM 直接 return）；屏幕页的文案与真实状态相反 | `ui/screens/DeviceScreen.kt` 原 253-267；`ui/screens/DisplayScreen.kt` 原 127-134 | 启动中按钮置灰、文案「启动中…」、图标一起降透明；屏幕页按 Starting / Failed / StaleImages 分别给「正在启动」「启动失败」「镜像版本不一致」，动作改成查看日志或回设备页 |
| B4 | 高 | 镜像版本不一致时的「仍然启动」不会启动，只把状态改成已停止，还 toast「仍要启动旧镜像」 | 入口承诺了做不到的事，是典型的「能点但没反应」 | `ui/screens/DeviceScreen.kt` 原 185；`state/AppViewModel.kt:1261` `ignoreStaleImages` | 已在界面上移除该入口，副操作改成「查看日志」。是否真的允许强启旧镜像留给主代理决定（VM 的 `ignoreStaleImages` 现在是死代码） |
| B5 | 高 | 设置页「清理残留进程」没有确认；QEMU 运行时点一下会直接杀掉当前实例 | 正在引导的会话被无提示中断；用户以为只是清理旧进程 | `ui/screens/SettingsScreen.kt` 原 577 | 已加确认框；运行中标题与正文明确写「会结束当前实例，未保存状态丢失」 |
| B6 | 中 | 设置页副标题仍写「性能、外观与存储」，但存储在 0.31.0 已挪到首页 | 用户会去设置页找存储，找不到 | `ui/screens/SettingsScreen.kt` 原 101 | 已改为「性能、MCP 与外观」 |
| B7 | 中 | MCP 关闭时按钮仍写「重启」，点了只得到「MCP 服务已关闭」 | 看起来是重启失败，实际只是没开 | `ui/screens/SettingsScreen.kt` 原 291-295 | 关闭时按钮变「启动」并调用 `setMcpEnabled(true)`；已开启才显示「重启」 |
| B8 | 中 | 固件缺失时「编译镜像」仍可点，失败卡片只有「重试」，重试还是编译 | 首次使用会进入「编译失败 → 重试 → 再失败」的循环，不知道要先下载固件 | `ui/screens/LibraryScreen.kt` 原 390、374；`state/AppViewModel.kt` 原 734 | 编译卡片在无固件时改成「先下载固件」；Failed 状态下若无固件，主按钮也是「下载固件」 |
| B9 | 中 | 编译完成后卡片只有「重新编译 / 查看日志」，没有「启动」 | 主流程的最后一步断了，用户要自己想起来回设备页 | `ui/screens/LibraryScreen.kt` 原 321-334 | 编译完成增加「启动 iOS」主按钮；QEMU 已运行/启动中时禁用并显示「QEMU 运行中」 |
| B10 | 中 | 构建失败详情的注释说「从底部开始」，实际 `verticalScroll` 从顶部开始 | 真正的原因在最后几行，用户要先手动滚到底 | `ui/screens/LibraryScreen.kt` 原 357-370 | `LaunchedEffect` 等两帧后 `scrollTo(maxValue)`；用户之后仍可自由上滚 |
| B11 | 中 | 清日志在运行中直接禁用，但确认弹窗里专门写了「运行中」的警告；浮动按钮还会压住最后几行 | 警告文案永远走不到；日志末尾被「清除日志」遮住 | `ui/screens/LogsScreen.kt` 原 210-230、309、188 | 运行中改为可点 + 橙色胶囊，交给确认框说明代价；列表底部留 76dp；清除/跳到最新都到 44dp |
| B12 | 中 | 运行/启动中驱动与存储只是「点不动」，没有原因，也不变暗（代码用 `onClick = null` 而不是 `enabled = false`） | 用户不知道是权限、bug 还是正在运行 | `ui/screens/DeviceScreen.kt` 原 339/361、298-315 | 行与开关改用 `enabled = false`（Glasmio 行会降到 40% 不透明度）；运行/启动中加一条橙色脚注「驱动与存储不可修改」；「虚拟网络」在启动中也禁用（原来只判断 running） |
| B13 | 中 | 「卡住自动降核」开关在运行中显示为关闭，即使设置是开的 | 状态说谎，用户会以为自动降核被关了 | `ui/screens/SettingsScreen.kt` 原 211：`checked = cpu.guestSettingsEditable && autoFallback` | 改为 `checked = state.settings.autoFallback`，是否可点由 `enabled` 决定 |
| B14 | 中 | 多处触摸目标小于 44dp：日志分段 26dp、清除/跳到最新约 30dp、屏幕「更多/重试」约 28dp、导出 34dp | 手指点不中，尤其在小屏或单手时 | `ui/screens/LogsScreen.kt` 原 150、211、232；`ui/screens/DisplayScreen.kt` 原 237、300 | `GlassActionButton` 统一 44dp；日志分段、清除、跳到最新 44dp；屏幕顶部整条状态胶囊可点（不再只点「更多」）；返回/显示控制条 44dp。**「降核重启」「重试」仍是小文字链接**（放大要撑高整条胶囊），需真机决定是否改成整条可点 |
| B15 | 中 | 纯图标按钮没有 `contentDescription`：设备顶栏 MCP/日志、日志导出、屏幕返回、显示控制条 | TalkBack 只会读「按钮」，读不出用途 | `ui/screens/DeviceScreen.kt` 原 134-144；`LogsScreen.kt` 原 126；`DisplayScreen.kt` 原 308-325 | 已补「MCP 服务与端口」「运行日志」「导出与分享日志」「返回设备页」「显示控制条」「清除日志」 |
| B16 | 中 | 镜像还没准备时，首屏主按钮仍是「启动 iOS」，点下去必然失败 | 首次使用第一步就走错，失败后再去资源页，路径变长 | `ui/screens/DeviceScreen.kt` 原 264 | 主按钮按状态变成「准备镜像」→ 跳资源页；hero 标题/说明改成「先准备 iOS 镜像」 |
| B17 | 低 | 首页脚注说「首次启动约 2–3 分钟」，性能弹窗说「可能慢到半小时」 | 两处预期互相打架，弱机用户会以为卡死 | `ui/screens/DeviceScreen.kt` 原 453；`ui/HackIosApp.kt` 原 216 | 统一为「达标机型约 2–3 分钟；较弱机型十几分钟到半小时以上」 |
| B18 | 低 | 按钮写「重新部署」，VM 的提示写「重新部署资源」 | 用户在提示里找不到同名按钮 | `ui/screens/DeviceScreen.kt` 原 161；`state/AppViewModel.kt:903/916/1094` | 按钮改成「重新部署资源」 |
| B19 | 中（待确认） | 编译耗时有两套口径：VM/MCP/设计文档写 20–40 分钟，资源页写首次 1–3 小时 | 用户无法判断「慢」是否正常；两处都出现时信任度下降 | `ui/screens/LibraryScreen.kt:425`；`state/AppViewModel.kt:935/1531`；`docs/ui-0.31.0.md` | **未改**。需要按真机实测统一；如果无法确定，建议写成「首次 20 分钟–3 小时，随处理器差异很大」 |
| B20 | 低 | 设备顶栏的端口按钮注释说「跳到 MCP 服务区块」，实际只切到设置页顶部 | 用户点完看不到 MCP，以为按钮没用 | `ui/screens/DeviceScreen.kt:75-76/137`；`ui/HackIosApp.kt:298` | **未改**。实现滚动锚点需要把 LazyListState 与「目标区块」状态提到页面外；也可以删掉这个快捷入口，只留底部「设置」 |
| B21 | 中（待确认） | 可写盘脚注写「内核放行写入」，但 0.31.0 文档写「现用内核 virtio-blk 只读，UI 应写 guest 写入会被拒绝」 | 用户会以为现在就能在 guest 里落盘；这是产品承诺，不是措辞问题 | `ui/screens/DeviceScreen.kt:447-449`；`docs/ui-0.31.0.md:69-72` | **未改语义**。本次只删掉了模式行里的「写入已放行」短句；脚注仍是「内核放行写入」。请主代理确认内核是否已换成可写实现，再统一文案 |

## 实际改动文件

1. `ui/components/Glyph.kt`：新增 `Glyph.Alert`（错误/失败语义），替换 Toast 与失败 Banner 的锁/电源图标。
2. `ui/components/Common.kt`：`GlassPanel` 改用连续曲率；`GlassActionButton` 保证 44dp 触摸高度；`GlassKey` 用 Glasmio 小圆角。
3. `ui/screens/DeviceScreen.kt`：hero 按 Starting/未准备镜像分流主操作；启动中按钮禁用；移除「仍然启动」；存储容量不再硬编码模式；模式行用勾选 + `enabled` 置灰；补锁定脚注；驱动/网络文案缩短；处理器行跳设置；空状态/失败图标与文案；顶栏无障碍描述。
4. `ui/screens/DisplayScreen.kt`：启动中/失败/待重编的空态；状态胶囊整条可点；返回/显示控制条 44dp 与描述；重试/降核胶囊高度。
5. `ui/screens/LibraryScreen.kt`：统一 `SectionLabel`；编译前检查固件；编译完成加「启动 iOS」；失败详情自动滚到底；镜像图标按存在状态着色；文案「存储权限/查看日志」。
6. `ui/screens/LogsScreen.kt`：去掉双层水平内边距；允许运行中清日志但必须确认；列表底部留白；分段/浮动按钮 44dp；导出按钮描述。
7. `ui/screens/SettingsScreen.kt`：副标题；MCP「启动/重启」；清理残留确认；自动降核开关状态；宿主算力池颜色；版本号查询改为 `remember`。
8. `ui/screens/DiagnosticsScreen.kt`：去掉卡片/标签/结果的重复水平内边距。
9. `ui/HackIosApp.kt`：接线 `openSettings`、`LibraryActions.start`，移除 `ignoreStale`。
10. `state/AppViewModel.kt`：`setDataDisk` 接受 `PRIVATE_RW`，模式与容量分开报错；`setNetEnabled` 与界面一样在启动中拒绝；「重新部署资源」文案对齐。

## 留给主代理决定

1. **固定标题栏透字**：需要真机确认是否仍明显。App 层无参数，改库或改页面脚手架都超出「小步」范围。
2. **首页是否折叠驱动/存储**：本次只压行高。若要把四种存储模式收进 Action Sheet，会少约 150dp，但多一次点击。
3. **是否允许强启旧镜像**：现在「仍然启动」已移除，VM 的 `ignoreStaleImages` 是死代码。要保留能力就得在 `startQemu` 加显式 bypass，属于 QEMU 语义改动。
4. **编译耗时口径**（20–40 分钟 vs 1–3 小时）与 **可写盘是否真的能写入**：两处都需要实测/内核状态确认，本次没有擅自改。
5. **屏幕页「降核重启」「重试」**：仍是胶囊里的小文字链接；改成整条可点会更安全，但会改变橙色提示条的交互含义。
6. **顶栏 MCP 快捷入口**：要么实现滚动锚点，要么删掉，避免「切到设置页顶部」的弱反馈。

## 风险与未验证

- 没有真机截图：44dp 胶囊、去掉模式行 subtitle 后的分组节奏、浅色/深色/弱玻璃三档的对比度都需要装包确认。
- `PRIVATE_RW` 只是让界面能选中并保存；`IosDataStorage`/QEMU 的行为没有改，guest 是否可写仍以 B21 的结论为准。
- 未跑 `assembleDebug`、未跑单测；`compileDebugKotlin` 通过。

## 0.31.1 产物与验证

- APK：`/sdcard/Download/DSH-Go/HackiOS-0.31.1-ui-gpu.apk`（versionCode 59，versionName
  0.31.1-ui-gpu，188,272,586 字节，SHA-256 `8ae16ab0342991a2a6fe730a16d778513b22e798da97bd9036b63350e7519885`）。
  debug 签名；覆盖安装旧包会冲突，需要先卸旧包（镜像、数据盘、GPU 材料都在公共目录）。
- 单测 332 项通过（含存储 10 项、GPU 识别 7 项）；`:app:compileDebugKotlin` 通过。
- 主代理在合入后额外做的两处：删掉顶栏「MCP」快捷按钮（落不到 MCP 区块，B20）与
  `ignoreStaleImages` 死代码；编译耗时统一成「通常 20–40 分钟，弱机 1–3 小时」；可写盘文档
  与 UI 统一成「内核已含可写实现（静态证据），guest 侧挂载未做」。
- 仍未在真机安装；A1（固定标题栏透字）、44dp 胶囊与分组节奏需要真机截图确认。
