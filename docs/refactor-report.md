# 操作逻辑修复报告

对原工程（`gitee.com/Gudao211/hackios-androidapp`，0.18.1）逐文件通读后确认的问题。每条都给出
**原代码位置**、**用户可见症状**、**根因**、**修复方式**、**保护用例**。行号对应重构前的
`app/src/main/java/com/android/gudao/iphoneos/*.kt`。

---

## 总览

| # | 问题 | 严重度 | 保护用例 |
|---|---|---|---|
| B1 | 启动前不构建 `payload.bin` / `ram.img` | 阻断启动 | `QemuPreflightTest`（6） |
| B2 | 下载不可取消、不可续传 | 6 GB 流量白费 | `ChunkPlanTest`（5）、Maestro 05 |
| B3 | 编译取消语义混乱且无法续跑 | 20–40 分钟白费 | `BuildCheckpointTest`（5） |
| B4 | 日志整文件重读 + 滚动冲突 | 卡顿 / 无法阅读 | `LogTailerTest`（3）、Maestro 03 |
| B5 | 触控事件模型错误（按下即点击、移动建连接） | 手势全废 | `TouchMapperTest`（6） |
| B6 | 取帧失败无重试、无状态 | 黑屏无从判断 | `CapturePipelineTest`（2）、Maestro 06 |
| B7 | 端口残留不检测 | 二次启动必失败 | `QemuFailureClassifierTest`（7） |
| B8 | 权限 pending 动作丢失 | 授权后无反应 | 手工用例 P-01 |
| B9 | 状态全在 Activity，重建即丢 | 旋转后进度归零 | `uiStateSurvivesActivityRecreation` |
| B10 | 「先重编镜像」调用错方法 | 点了没反应 | 代码审查 + 新流程 |
| B11 | 构建 payload 时 3 份全量副本 | 手机 OOM | `XnupPackerTest`（3） |

---

## B1 · 启动前从不构建引导负载

**原代码**：`MainActivity.kt:555 startQemuNow()` 只做 `deployer.deploy()` + `runner.start()`；
`runner.prepare()` 只出现在另一个按钮的处理函数 `buildImages()`（`MainActivity.kt:506`）里。

**症状**：全新安装后直接点「启动」→ QEMU 进程 exit 1，界面只有一句「QEMU 启动后立即退出（exit=1）」。
用户在「启动」和「构建」之间没有任何提示说明必须先点后者。

**根因**：`payload.bin`（XNUP：kernel + root.apfs + boot-args）和 `ram.img` 是 `prepare()` 的产物，
而 `start()` 只负责 `execve`。两者被拆成两个互不知情的按钮。

**修复**：`AppViewModel.startQemu()` 在启动前调用 `QemuPreflight.needsPrepare()`，用
`镜像 length+mtime + smp + IMAGE_REV` 的指纹（`.payload-stamp`）判断缓存是否仍然有效，失效则自动重建
并显示「准备引导负载（payload / ram.img）」步骤；重建失败会区分 OOM 与其它原因。

**测试**：`QemuPreflightTest` 覆盖「不存在 / 指纹匹配 / 改核数 / 重编镜像 / 改版本号 / 0 字节」六种情形。

---

## B2 · 下载不可取消、不可续传

**原代码**：`FirmwareDownloader.kt:56`
```kotlin
partDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
```
`download()` 没有任何取消入口，`MainActivity.startDownload()` 也没有暂停按钮。

**症状**：6.1 GB 下到 99% 断网/切后台 → 下次从 0 开始；想暂停只能杀进程，且杀掉后同样从 0 开始。

**根因**：把「清理上次残留」和「开始新下载」当成同一件事；进度只存在于回调里，没有可取消的状态机。

**修复**：
* `ChunkPlan` 记录每个分块已有字节，`Range` 从 `start + have` 开始；
* `.part` 文件在开始、失败、取消时都保留，只在完整校验通过后清理；
* `FirmwareDownloader.cancel()` 用 `@Volatile` 标志同时打断拷贝循环与进度泵，返回
  `Outcome.Cancelled`，UI 进入 `DownloadUi.Paused(resumeBytes)`；
* 分块失败改为指数退避（1/2/4/8 s）重试 5 次，而不是立刻整体失败；
* 合并后 SHA-256 校验也支持进度回调与取消（原来 6 GB 哈希期间界面完全无反馈）。

**测试**：`ChunkPlanTest` 覆盖分块边界、部分续传、超长 `.part` 截断、越界索引、0 字节；
Maestro `05_firmware_download_cancel.yaml` 覆盖「下载 → 暂停 → 继续下载」的实际交互。

---

## B3 · 编译取消语义混乱

**原代码**：`MainActivity.kt:599 stopQemu()`
```kotlin
if (imageBuilder.isRunning) { imageBuilder.cancel(); tvImages.text = "已取消编译…" ; return }
```
取消编译的入口是「停止 QEMU」按钮，且只在编译恰好运行时才生效；取消后没有任何持久状态，下次编译从
解包 7.4 GB 重新开始。

**修复**：
* 「资源」页编译卡内独立的「取消编译」按钮 → `AppViewModel.cancelCompile()`；
* 新增 `BuildUi.Cancelled(stage)`，明确显示「已完成到哪个阶段、下次从这里继续」；
* `BuildCheckpoint` 在 `extract / fetch / build` 成功后各写一个标记，`ImageBuilder.build()` 按标记跳过；
  取消不写标记，因此必然从被打断的阶段重跑；
* 成功发布后 `checkpoint.reset()`。

**测试**：`BuildCheckpointTest` 覆盖「未开始 / 跨实例持久 / 中断后从 build 继续 / 幂等 / 成功后清空」。

---

## B4 · 日志刷新卡顿与滚动冲突

**原代码**：`MainActivity.kt:745 refreshConsole()` 每 2 秒 `File.readText()` 整个 `serial.log`，拼接后
`tvConsole.text = clipped`（`MainActivity.kt:804`）。`updateBootPhase()` 同样每 2 秒重读文件。

**症状**：启动后界面周期性卡顿；用户向上翻看日志会被自动拉回底部；日志越长越卡（文件可达数 MB）。

**根因**：把「追加日志」实现成「重设全文」，UI 线程上做整文件 IO + 全量布局。

**修复**：
* `LogTailer` 用 `RandomAccessFile` 记录字节偏移，只返回新增的完整行，处理截断/重启；
* 环形缓冲 3000 行，400 ms 合批写入 `StateFlow`；
* 日志页用 `LazyColumn`，`followLog` 开关持久化，关闭后不会被拉回，并提供「跳到最新」；
* 串口 / QEMU / 构建三路日志分源查看，复制走剪贴板而不是读文件。

**测试**：`LogTailerTest` 覆盖「只取新行 / 半行拼接 / 截断重启 / 单次上限与剩余投递」。

---

## B5 · 触控与 iOS 手势冲突

**原代码**：`MainActivity.kt:164`
```kotlin
ACTION_DOWN -> postInput { touch.tap(x, y) }
ACTION_MOVE -> postInput { touch.tap(x, y) }
ACTION_UP   -> postInput { touch.tap(x, y) }
```
而 `TouchSender.tap()` 本身是 `move + down + sleep(60) + up`；`QemuControl.qmpRaw()` 每次调用都
新建一条 TCP 连接并重做 `qmp_capabilities` 握手。

**症状**：滑动变成长按+连点，滚动/上滑解锁/控制中心全部失效；快速拖动明显掉帧；画面有黑边时点击位置偏移。

**根因**：
1. 事件模型错了：把「指针移动」实现成「完整点击」；
2. 坐标用 View 宽高归一化，而画面是 `fitCenter` 绘制，两者不等价；
3. 每个事件一条 socket，60 次/秒的握手把 QMP 拖垮。

**修复**：
* `QemuControl` 保持长连接，按 JSON `id` 匹配应答（不再用「读到第一个 return」这种会和异步事件串台的写法）；
* 新增 `pointerMove` / `button` / `pointer(down)`，`TouchSender` 用真正的 down → move* → up；
* `TouchMapper` 计算 `fitCenter` 视口，黑边区域的触摸返回 null 而不是被夹到边缘；手势按 iOS 阈值分类
  （<250 ms 且 <10 pt = Tap，>500 ms = LongPress，位移超阈值 = Drag）；
* ViewModel 对移动事件做 12 ms 节流（约 60 Hz 上限）。

**测试**：`TouchMapperTest` 覆盖横/竖黑边、四角映射、黑边外拒绝、四种手势分类。

---

## B6 · 取帧失败无重试

**原代码**：`QemuControl.captureFrame()` 任何异常都 `return null`；`FramebufferView.startCapture()`
（`FramebufferView.kt:113`）在 `catch (_: Throwable)` 里静默继续，没有失败计数、没有退避、没有状态。

**症状**：QEMU 还在启动时是黑屏（正常），但监视器真的挂了也是同样的黑屏；用户无法区分，也没有「重试」
可点，只能退出页面再进。

**修复**：`CapturePipeline` 显式维护 `Idle / Live / Retrying(n) / Stopped`，失败按 250 ms → 4 s 指数退避，
连续 6 次失败进入 `Stopped` 并保留 4 s 慢轮询；界面显示「第 n 次失败 · 重试」并提供 `retryNow()`。
帧位图复用同一个 `Bitmap`（原来每帧 `createBitmap + recycle`，1125×2436 每帧 11 MB）。

**附带修复**：原 `FramebufferView` 必须 `setZOrderOnTop(true)` 才能盖住不透明祖先，但那样 Surface 会浮在
整个窗口之上，把标签栏、弹窗、玻璃控件全部盖住。现在改为 Compose `Canvas` 直接绘制帧，UI 层级正确。

**测试**：`CapturePipelineTest` 覆盖退避序列与上限、初始状态；Maestro 06 覆盖「重新取帧」。

---

## B7 · 端口残留不检测

**原代码**：`QemuRunner.start()` 直接 `-monitor tcp:...:4444` / `-qmp tcp:...:4445`，没有预检。

**症状**：上次 QEMU 被系统杀掉（后台清理、崩溃），端口仍在监听 → 新实例 `Failed to bind socket`，
`serial.log` 为空，界面只说「exit=1」。

**修复**：`QemuPreflight.busyPorts()` 在启动前探测 4445/4444；占用则先发 QMP `quit` 并等待 700 ms，
仍占用则进入 `QemuUi.Failed(PortInUse)`，卡片上给出「强制清理」按钮（`forceCleanup()` 会 quit + 强杀 +
关闭本地会话）。`QemuFailureClassifier` 同时识别日志里的 `Address already in use`。

**测试**：`QemuFailureClassifierTest` 的端口用例断言分类为 `PortInUse` 且提示包含「残留」。

---

## B8 · 权限 pending 动作丢失

**原代码**：`MainActivity.kt:74 pendingAfterPermission` 是 Activity 字段，`permissionLauncher`
（`:96`）回调里执行；`onRequestPermissionsResult`（`:319`）里再取一次。拒绝时只改 `tvDownload` 一行字。

**症状**：点「下载」→ 授权 → 什么都没发生（pending 在配置变更/进程重建后为 null）；拒绝后也没有
「会用私有目录」的说明。

**修复**：`pending` 移到 ViewModel（`PendingAction.Download/Compile`），`onPermissionResult(granted)`
统一分发：授权则继续原动作，拒绝则提示「固件与镜像将保存到应用私有目录」，并且 `StoragePaths` 自动回退。

**手工用例 P-01**：清除权限 → 点下载 → 拒绝 → 断言提示文案且「资源」页路径显示为
`/sdcard/Android/data/.../files/hackios`；再次点下载 → 允许 → 断言进入下载态。

---

## B9 · 状态不跨配置变更

**原代码**：`MainActivity.kt:48-74` 的 `tvDeploy / progressBar / systemApfs / pendingAfterPermission`
等全部是 Activity 字段；下载与编译进度只写进 TextView。

**症状**：旋转屏幕、切换深色模式、系统回收后重建 → 进度条归零、状态文字回到初始值，用户以为任务失败。

**修复**：`AppViewModel` + `StateFlow<AppUiState>` 持有全部状态，`SettingsRepository` 持久化核心数/网络/主题/
日志跟随；`MainActivity` 只负责权限桥接与 `setContent`。

**测试**：`AppNavigationTest.uiStateSurvivesActivityRecreation` 在「设置」页执行 `scenario.recreate()` 后
断言仍在同一标签页。

---

## B10 · 「先重编镜像」调用了错误的方法

**原代码**：`MainActivity.kt:543`
```kotlin
.setPositiveButton("先重编镜像") { _, _ -> deploy() }
```
`deploy()` 只解压 APK 资源，**不会**编译镜像。用户点「先重编镜像」后，`root.apfs` 仍然是旧版本，下一次启动
依旧弹出同一个对话框。

**修复**：过期镜像不再是模态对话框，而是「设备」页顶部的 `GlassBanner`：
主按钮「编译镜像」→ `compileImages()`（真的开始编译），次按钮「仍然启动」→ 明确记录「跑旧镜像」并继续。

**验证**：代码审查 + Maestro 04 的失败路径（无镜像时的 Banner 结构一致）。

---

## B11 · 构建 payload 时的内存放大

**原代码**：`QemuRunner.prepare()`（`:76`）
```kotlin
"kernel" to kernel.readBytes(),
"ramdisk" to rootApfs.readBytes()
```
`XnupPacker.pack()` 再把它们写进 `ByteArrayOutputStream`，最后 `ByteBuffer.allocate(8 + body.size)` 再复制
一次 —— 峰值约为 payload 的 3 倍。root.apfs 通常 1–3 GB，在手机上必然 `OutOfMemoryError`，而异常发生在
工作线程，界面只会显示「构建失败」。

**修复**：`XnupPacker.packTo()` 预计算 body 长度后流式写入文件（1 MB 缓冲），峰值内存与镜像大小无关；
`QemuRunner.prepare()` 改用 `packTo`。字节格式由 `XnupPackerTest` 逐字节对齐旧实现。

---

## 附：新增的防御性行为

| 场景 | 行为 |
|---|---|
| 启动时 QEMU 进程 1.5 s 内退出 | `QemuFailureClassifier` 归类并给出「重试 / 强制清理 / 查看日志」 |
| 固件已存在但哈希不符 | 明确「期望/实际」前 16 位，分块清理，可重下 |
| 存储空间不足 | 下载/发布前预检，给出所需与可用空间 |
| 取帧连续失败 | 指数退避 + 可见状态 + 手动重试 |
| 编译阶段中断 | 标记已完成的阶段，下次跳过 |
| 用户正在上翻日志 | 不再强制滚动，提供「跳到最新」 |
