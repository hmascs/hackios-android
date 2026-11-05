# HackiOS for Android · iOS 27 Liquid Glass 重构版

在 Android 13+ 上通过 QEMU（TCG 软件模拟）运行 iOS 16.5 的模拟器 App。原有 Material 界面已整体替换为
**Jetpack Compose + Glasmio（iOS 27 液态玻璃）** 的四标签 iOS 原生风格界面，并修复了 11 处会直接影响
「能不能启动、能不能暂停、能不能继续」的操作逻辑缺陷。

> 包名 `com.android.gudao.iphoneos` · 版本 `0.20.0 (23)` · `minSdk 33` / `targetSdk 28` / `compileSdk 37`

---

## 1. 交付状态（请先读这一节）

| 项目 | 状态 | 证据 |
|---|---|---|
| Gradle 构建（debug） | ✅ 通过 | `:app:assembleDebug` → `app-debug.apk`（138 MB） |
| Gradle 构建（release，R8） | ✅ 通过 | `:app:assembleRelease` → `app-release-unsigned.apk`（75.8 MB，dex 67 MB → 2.5 MB） |
| JVM 单元测试 | ✅ 129 / 129 通过 | `app/build/reports/tests/testDebugUnitTest` |
| Compose 仪器化测试编译 | ✅ 通过 | `:app:assembleDebugAndroidTest` |
| 设备 / 云端 E2E 实跑 | ❌ **未执行** | 本环境无 KVM、无 Android 设备、无 Firebase/Maestro 凭据 |
| 云端截图与 iOS 参考逐像素比对 | ❌ **未执行** | 同上；未伪造截图 |
| release 包在真机运行 | ❌ **未验证** | 无设备；R8 裁剪后的运行期行为只能上机确认 |

**没有伪造任何测试记录或截图。** `.maestro/` 与 `.github/workflows/android-e2e.yml` 是可落地的真实
配置，在有设备或云端凭据的机器上可直接产出报告；具体命令与预期的产物路径见
[docs/testing.md](docs/testing.md)。

---

## 2. 构建

```bash
# 需要 JDK 21、Android SDK Platform 37 + Build-Tools 37、Gradle 9.6
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # R8 裁剪：app/build/outputs/apk/release/app-release-unsigned.apk
./gradlew :app:testDebugUnitTest      # 85 个 JVM 测试
./gradlew :app:assembleDebugAndroidTest
```

仓库自带 wrapper（Gradle 9.6.0）。官方 `distributionUrl` 在国内可能很慢，换成镜像只需改一行；若想同时
保证可复现，再加一行 `distributionSha256Sum` 固定发行版哈希：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-9.6.0-bin.zip
```

### 为什么是 `minSdk 33` + `targetSdk 28`

这两个数字看起来矛盾，但都是硬约束，缺一不可：

* **`minSdk 33`**：Glasmio 的 AAR 声明 `minSdkVersion 33`，液态玻璃的 `RenderEffect` / AGSL 管线也要求
  硬件加速的 Android 13+。
* **`targetSdk 28`**：QEMU、bash、python3、7zz、unzbm 全部从应用私有目录 `/data/user/0/<pkg>/files/run/`
  直接 `execve`。Android 10 起 SELinux 域由 `targetSdkVersion` 决定，`untrusted_app_29+` 对
  `app_data_file:file execute` 有 neverallow；只有 26..28 对应的 `untrusted_app_27` 允许执行。
  这是 Termux 至今仍保持低 targetSdk 的同一个原因。

AGP 9.4.1 接受该组合，合并后的清单为（实测）：

```xml
<uses-sdk android:minSdkVersion="33" android:targetSdkVersion="28" />
```

推导与验证过程见 [docs/engineering-notes.md](docs/engineering-notes.md)。

---

## 3. 分层架构

参考 FlClash 的分层，但按本项目的实际边界落地：

```
com.android.gudao.iphoneos
├── MainActivity.kt              Presentation 宿主（仅权限桥接 + setContent）
├── ui/                          Presentation：主题、组件、页面、转场
│   ├── theme/                   iOS 27 token、壁纸、深浅色
│   ├── components/              玻璃面板、进度条、Banner、Toast、矢量图标
│   ├── screens/                 设备 / 屏幕 / 资源 / 设置 / 日志 / 自检
│   └── HackIosApp.kt            标签栏 + Push/Pop 路由
├── state/                       Application Logic
│   ├── AppViewModel.kt          唯一状态源（StateFlow<AppUiState>），所有长任务编排
│   ├── UiModels.kt              密封状态：DownloadUi / BuildUi / QemuUi / CaptureSnapshot
│   └── SettingsRepository.kt    持久化偏好
├── engine/                      Core / Platform Services
│   ├── ChunkPlan · BuildCheckpoint · QemuPreflight · TouchMapper · LogTailer
│   ├── QemuFailure（失败分类）· CapturePipeline（取帧与退避）
├── FirmwareDownloader.kt        可续传 / 可取消的分块下载
├── ImageBuilder.kt              设备端解包 + APKFS 打包（阶段检查点）
├── QemuRunner.kt                payload/ram.img 构建 + QEMU 进程生命周期
├── QemuControl.kt               QMP（输入）与 HMP（screendump），长连接
├── AssetDeployer.kt             从 APK 解压 QEMU / 脚本 / 工具链
└── StoragePaths.kt              公共 Download/hackios 与私有回退目录
```

单向依赖：`ui → state → engine → platform`。UI 不持有 `Process`、`Socket` 或 `File`，只渲染状态并调用
意图方法；因此每个长任务天然带有 `Idle / Running / Success / Failed / Cancelled` 五态。

---

## 4. 界面

| 标签 | 内容 |
|---|---|
| **设备** | 液态玻璃 Hero 卡（状态胶囊、7 段引导时间线、主操作按钮）、引导阶段说明、环境分组列表、快捷入口 |
| **屏幕** | 全屏 iOS 画面（Compose 直接绘制 1125×2436 帧）、玻璃控制条（主屏/锁屏/音量±/上滑解锁）、取帧健康条与重试、Action Sheet |
| **资源** | 固件卡（进度/速度/ETA/续传/暂停/校验）、镜像分组列表、编译卡（阶段进度/取消/续跑）、自检入口 |
| **设置** | 核心数滑杆、网络开关、MCP 服务（端口/令牌/权限/状态）、外观分段控件、日志跟随、存储与维护、关于 |

导航：可折叠大标题（`GlasmioPinnedListPage`）+ 底部标签栏（`GlasmioNavigationBar`）+ iOS Push/Pop
弹簧转场；所有按钮/开关/滑杆/分段/弹窗/提示均来自 Glasmio，风格细节见
[docs/design-system.md](docs/design-system.md)。

**MCP 端口**（设置 → MCP 服务）对外提供标准 Model Context Protocol 接口，任何支持 MCP 的客户端都能
管理整条链路，见第 5 节。设备页右上角的端口按钮可直接跳到该设置区块。

---

## 5. MCP 端口（0.23.0 起，替代内置 AI 助手）

0.23.0 起不再内置对话式 AI：App 改为在本机开一个标准 MCP 端口，把整条链路暴露成工具，由外部 MCP
客户端（Claude Desktop、Cline、Cursor、MCP Inspector……）来驱动。这样排障用的是你自己选的模型与
上下文，App 只负责忠实执行。

### 连接

| 项 | 默认 |
|---|---|
| 地址 | `http://127.0.0.1:8765/mcp` |
| 电脑端 | `adb forward tcp:8765 tcp:8765` |
| 传输 | `POST /mcp`（Streamable HTTP）+ `GET /sse` / `POST /message`（旧版 SSE 兼容） |
| 健康检查 | `GET /health`，浏览器可直接打开确认端口存活 |
| 绑定 | 默认仅 `127.0.0.1`；开「局域网访问」后绑定 `0.0.0.0`，并强制 `Authorization: Bearer <token>` |
| 协议 | MCP 2025-03-26（也接受 2024-11-05 / 2025-06-18），JSON-RPC 2.0 |

MCP 客户端配置示例（Streamable HTTP）：

```json
{
  "mcpServers": {
    "hackios": { "url": "http://127.0.0.1:8765/mcp" }
  }
}
```

局域网模式再加 `"headers": { "Authorization": "Bearer <设置页里的令牌>" }`。

### 工具（按权限档裁剪）

| 档位 | 可用工具 |
|---|---|
| 只读（默认） | `device_status`、`get_config`、`mcp_info`、`inspect_chain`、`capture_screen`、`read_log`、`list_files`、`read_file`、`hmp` |
| 读写 | 上面全部 + `start_qemu` / `stop_qemu` / `restart_qemu` / `force_cleanup` / `send_key`、`set_smp` / `set_net` / `set_qemu_flags` / `set_boot_args` / `set_config` / `reset_config`、`deploy_assets` / `compile_images` / `cancel_compile` / `download_firmware` / `cancel_download` / `verify_firmware` / `export_logs` / `run_diagnostics` / `refresh_artifacts`、`write_file` / `run_shell` / `android_log` |
| 完全访问 | 上面全部 + `delete_chain`，路径与命令放开到整台 Android |

`set_config` 覆盖所有持久化配置，包括 MCP 自身的 `mcp_enabled` / `mcp_port` / `mcp_bind_lan` /
`mcp_token` / `mcp_allow_write` / `mcp_full_access`——端口自己也能被端口管理（关掉请留到最后一次
调用）。改权限后下一次 `tools/list` 立刻生效，不用重启端口。

### 删除整条链路

`delete_chain` 是分级、默认安全的拆链工具：

1. 先 `inspect_chain` 看清 `run/`（QEMU 二进制、工具链、payload、日志）、`images/`、`ipsw/`、
   `logs/`、`exports/` 各有多少文件、多大。
2. `scope` 选 `cache`（payload/ram.img/stamp/构建日志）、`run`（整个运行目录）、`images`、
   `firmware`（6.1 GB IPSW）、`logs`、`exports`、`config`（重置 QEMU/外观设置）或 `all`。
3. **默认 `dry_run=true` 只返回预览**；真正执行必须同时传 `dry_run=false` 与
   `confirm="DELETE-CHAIN"`，且需要完全访问档。执行前自动停 QEMU，执行后刷新产物状态。

删完之后 `deploy_assets` / `download_firmware` / `compile_images` 可以把链路原样重建；MCP 端口配置
本身保留，避免把自己关掉。

### 安全边界

* 端口默认只听 loopback，靠 adb forward 暴露；局域网模式强制 Bearer token。
* 三档权限按档裁剪工具表，低权限档看不到高权限工具；删链另需确认串与完全访问。
* 读写档把路径钉在 `run/` 内、命令过黑名单；完全访问档放开到整台 Android，由内核（SELinux/uid）
  兜底。这是**护栏不是沙箱**。
* 端口随 App 进程存活，排障时保持 App 在前台（QEMU 运行时本来就会 keepScreenOn）。

已知边界：服务端与协议在 JVM 上有真实端口的端到端单测覆盖，但**未在 arm64 真机上与真实 MCP
客户端联调**；删除链路涉及公共存储的 6.1 GB 文件，请先用 dry-run 预览。

---

## 6. 修复清单

完整说明（原代码位置 → 根因 → 修复 → 保护用例）见 [docs/refactor-report.md](docs/refactor-report.md)：

| # | 问题 | 保护用例 |
|---|---|---|
| B1 | 启动前从不构建 `payload.bin` / `ram.img`，冷启动必然失败且无解释 | `QemuPreflightTest` (6) |
| B2 | 下载不可暂停、不可续传，每次启动先删掉所有 `.part`；连接假死时速率跌到几 KB/s 且不恢复 | `ChunkPlanTest` (5) + `LinkHealthTest` (6) + Maestro 05 |
| B3 | 编译的「取消」藏在停止 QEMU 按钮里，取消后无状态、下次从头再来 | `BuildCheckpointTest` (5) |
| B4 | 每 2 秒整文件重读日志并重设 TextView，滚动位置被重置 | `LogTailerTest` (3) + Maestro 03 |
| B5 | 按下即 `tap`、每次移动新建 QMP 连接，拖动退化成连点、iOS 手势被打断 | `TouchMapperTest` (6) |
| B6 | 取帧失败只返回 null，无重试、无健康状态；SurfaceView 置顶会盖住 UI | `CapturePipelineTest` (2) + Maestro 06 |
| B7 | 崩溃后 4444/4445 端口残留，下次启动只留一行日志 | `QemuFailureClassifierTest` (7) |
| B8 | 权限回调只更新一个 TextView，pending 动作会丢 | 手工用例（见报告） |
| B9 | 所有状态在 Activity 字段，旋转/重建即清零 | `uiStateSurvivesActivityRecreation` |
| B10 | 「镜像已过期」对话框的「先重编镜像」调用的是 `deploy()`，点了什么也不会发生 | 代码审查 + 新 Banner 流程 |
| B11 | 构建 payload 时把 kernel + root.apfs 全读进内存并复制 3 份，手机必 OOM | `XnupPackerTest` (3) |

---

## 7. 测试

```bash
./gradlew :app:testDebugUnitTest                 # 129 个纯 JVM 测试
./gradlew :app:connectedDebugAndroidTest         # 需要设备/模拟器
maestro test .maestro                            # 需要设备/模拟器
```

云端与 CI（Firebase Test Lab API 33/34/35 + Maestro Cloud）配置在
`.github/workflows/android-e2e.yml`，注入 `MAESTRO_API_KEY` / `GCP_SA_KEY` 后自动运行并回传截图、
logcat 与语义树。详见 [docs/testing.md](docs/testing.md)。

---

## 8. 已知限制

* **未在真机运行过**：本环境没有 `/dev/kvm`、没有 Android 设备，因此没有「实机截图」。所有未验证项
  已在第 1 节列明，不以模拟记录替代。
* **首次启动仍需 2–3 分钟**：TCG 软件模拟的固有速度，与 UI 无关。
* **debug APK 138 MB / release 75.8 MB**：其中工具链 25.4 MB、QEMU bundle 21.7 MB、脚本 14.8 MB、
  iOS 内核 6.0 MB。dex 在 debug 下是 67 MB（未裁剪 + `minSdk ≥ 28` 时 dex 以 stored 方式放置），
  release 开 R8 后降到 2.5 MB。原构建因把两个 64 MiB 稀疏 pflash 镜像设为 `noCompress`，体积是
  274 MB。逐项拆分见 [docs/engineering-notes.md](docs/engineering-notes.md) 第 6 节。
* **SF Pro / SF Symbols 字体不随包分发**：Apple 字体有许可限制。Glasmio 在缺少字体时回退到系统字体与
  Material 图标；把授权字体放进 `res/font/` 并调用 `GlasmioIconProvider` 即可切换为原生字形。

---

## 9. 致谢

* [Glasmio](https://github.com/NEORUAA/Glasmio)（Apache-2.0）—— iOS 27 液态玻璃材质、控件与导航脚手架。
* 上游 HackiOS（OOSEX GANG）—— QEMU 启动参数、EDK2/XNU 资源与设备端构建脚本。
* [FlClash](https://github.com/chen08209/FlClash) —— 分层与信息层级的参考。
