# 测试与自动化迭代说明

本文分两部分：**怎么在本机/云端把它跑起来**（第 1–5 节），以及**这次实际跑了什么、没跑什么**
（第 6 节）。第 6 节是事实记录，不是计划。

---

## 1. 测试分层

四层，成本从低到高，任何一层失败都不应继续往上跑：

| 层 | 工具 | 位置 | 覆盖什么 | 需要什么 |
|---|---|---|---|---|
| L1 单元测试 | JUnit4 + coroutines-test + org.json | `app/src/test/java/` | 纯逻辑：分块续传、坐标映射、失败分类、日志增量、阶段检查点、payload 打包、引导阶段、AI 报文与工具箱护栏 | 只要 JDK 21 |
| L2 仪器化测试 | Compose UI Test | `app/src/androidTest/java/` | 首页渲染、四标签切换、日志页、资源卡、屏幕空状态、Activity 重建后状态存活 | 设备/模拟器 |
| L3 E2E | Maestro | `.maestro/*.yaml` | 真实点击流程：启动、导航、日志、QEMU 失败反馈、下载暂停/续传、触控面板 | 设备/模拟器 或 Maestro Cloud |
| L4 兼容性 | Firebase Test Lab | `.github/workflows/android-e2e.yml` | API 33 / 34 / 35 三机型实机跑 L2 | GCP 服务账号 |

**需求条目 → 测试映射**（每条操作逻辑缺陷都必须有东西护着）：

| 编号 | 缺陷 | 保护它的测试 |
|---|---|---|
| B1 | 启动前不构建 `payload.bin` / `ram.img` | `QemuPreflightTest`（6 例）+ Maestro `04` |
| B2 | 下载不可暂停、不可续传 | `ChunkPlanTest`（5 例）+ Maestro `05` |
| B2b | 下载完成后不合并分片、卡在「计算中」 | `MergeResumeTest`（5 例）+ `IpswMergerTest`（4 例）+ `ChunkRecoveryTest`（6 例）+ `FirmwareDownloaderTest`（4 例，真起 HTTP 服务跑完整流程） |
| B2c | 链路假死时速率掉到几 KB/s | `LinkHealthTest`（6 例） |
| B2d | 编译卡片卡在上一阶段的 100 %、看不到解包进度 | `SevenZipProgressTest`（6 例） |
| B3 | 编译取消无状态、下次从头再来 | `BuildCheckpointTest`（5 例） |
| B4 | 日志每 2 s 整文件重读、滚动被重置 | `LogTailerTest`（3 例）+ Maestro `03` |
| B5 | 拖动退化成连点、每次事件新建连接 | `TouchMapperTest`（6 例）+ Maestro `06` |
| B6 | 取帧失败无重试、无健康状态 | `CapturePipelineTest`（2 例）+ Maestro `06` |
| B7 | 崩溃后端口残留、失败只留一行日志 | `QemuFailureClassifierTest`（7 例）+ Maestro `04` |
| B8 | 权限 pending 动作丢失 | 手工用例（见 `refactor-report.md`） |
| B9 | 状态存在 Activity 字段，重建即清零 | `uiStateSurvivesActivityRecreation` |
| B10 | 「先重编镜像」调用了 `deploy()` | 代码审查 + 新的 Banner 流程 |
| B11 | payload 构建内存里复制 3 份 | `XnupPackerTest`（3 例） |
| B12 | AI 报文缺 `tool_call_id` / `tool_calls` 结构错，服务端只回 400 | `AiProtocolTest`（14 例） |
| B13 | 模型可越界读写、执行危险命令、把 QEMU 参数改到起不来 | `ToolBoxTest`（13 例）+ `QemuFlagsTest`（10 例） |
| B14 | 工具循环无上限，一条消息能烧掉几十次请求 | `AiAgentTest`（4 例） |

---

## 2. 本地运行

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk   # platform 37.2 + build-tools 37.0.0

# L1：129 个 JVM 测试，约 25 秒
./gradlew :app:testDebugUnitTest

# L2：需要一台 API 33+ 的设备或模拟器
./gradlew :app:connectedDebugAndroidTest

# L3：Maestro（先装：curl -Ls "https://get.maestro.mobile.dev" | bash）
maestro test .maestro                       # 全部 6 个 flow
maestro test .maestro/01_launch_home.yaml   # 单个
maestro test --format junit --output artifacts/maestro.xml .maestro
```

模拟器起法（有 KVM 的机器）：

```bash
sdkmanager --install "system-images;android-34;google_apis;arm64-v8a"
avdmanager create avd -n api34 -k "system-images;android-34;google_apis;arm64-v8a" -d pixel_7
emulator -avd api34 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot &
adb wait-for-device
```

> QEMU 本体是 **aarch64** 的，`x86_64` 模拟器只能验证 UI 与导航（L2/L3 的 UI 部分），
> 不能真正跑 iOS。要验证 B1/B2/B3/B6 的端到端行为，必须用 arm64 真机或 arm64 模拟器。

---

## 3. 产物在哪

| 层 | 路径 | 内容 |
|---|---|---|
| L1 | `app/build/reports/tests/testDebugUnitTest/index.html` | 每个用例耗时、失败堆栈 |
| L1 | `app/build/test-results/testDebugUnitTest/*.xml` | JUnit XML，可喂给任何 CI |
| L2 | `app/build/reports/androidTests/connected/debug/index.html` | 用例 + 失败截图 |
| L2 | `app/build/outputs/androidTest-results/connected/debug/*.xml` | logcat + 截图目录索引 |
| L3 | `artifacts/maestro-report/`（`--output`） | 每步截图、`maestro.log`、JUnit XML、语义树快照 |
| L4 | `artifacts/ftl/` | `screenshot-*.png`、`logcat-*.txt`、`test_result_*.xml`、video |

Maestro 的 `takeScreenshot: shots/xx` 会落在设备的 `/sdcard/maestro/shots/`，`maestro cloud` 会自动
收集到报告的 `screenshots/` 下；本地跑则用 `adb pull /sdcard/maestro/shots artifacts/shots` 取回。

---

## 4. 云端

### 4.1 Firebase Test Lab（L4，跑仪器化用例）

```bash
gcloud auth activate-service-account --key-file=$GCP_SA_KEY
gcloud firebase test android run \
  --type instrumentation \
  --app app/build/outputs/apk/debug/app-debug.apk \
  --test app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --device model=oriole,version=33,locale=zh_CN \
  --device model=oriole,version=34,locale=zh_CN \
  --device model=shiba,version=35,locale=zh_CN \
  --use-orchestrator --timeout 30m \
  --results-bucket gs://$GCP_RESULTS_BUCKET \
  --results-dir hackios-$(date +%s)
```

`--use-orchestrator` 会把每个用例隔离成独立进程，`recreate()` 类用例（B9）在 orchestrator 下行为更接近
真实用户旋转/切后台。

### 4.2 Maestro Cloud（L3，跑交互流）

```bash
maestro cloud \
  --api-key "$MAESTRO_API_KEY" \
  --app-file app/build/outputs/apk/debug/app-debug.apk \
  --device-model "Pixel 7" --android-api-level 34 \
  --output artifacts/maestro-report \
  .maestro
```

### 4.3 CI

`.github/workflows/android-e2e.yml` 有四个 job：

1. `preflight`：用 step 里的 env 探测 `MAESTRO_API_KEY` / `GCP_SA_KEY` 是否存在，写进 job outputs。
   （GitHub 不允许在 job 级 `if` 里读 `secrets`，必须这样绕。）
2. `build-and-unit-test`：JDK 21 + SDK 37 + Gradle 缓存，跑 `testDebugUnitTest`、`assembleDebug`、
   `assembleRelease`、`assembleDebugAndroidTest`，上传 APK 与测试报告。
3. `maestro-cloud`：仅当 `needs.preflight.outputs.maestro == 'true'` 时运行。
4. `firebase-test-lab`：仅当 `needs.preflight.outputs.gcp == 'true'` 时运行，API 33/34/35 三机型。

注入密钥：

```bash
gh secret set MAESTRO_API_KEY      --body "..."   # Maestro Cloud → Settings → API Key
gh secret set GCP_SA_KEY           --body @sa.json
gh secret set GCP_RESULTS_BUCKET   --body "my-ftl-bucket"
```

没有密钥时前两个 job 照常跑，后两个 **跳过而不是伪造通过**。

---

## 5. AI 自动修复循环（截图 → 语义树 → 补丁 → 重跑）

这个循环的设计目标是「机器可判定」，所以每一轮都必须产出四样东西：**失败步骤、截图、语义树、
对应 Compose 文件**。人工只审 patch，不做定位。

```
        ┌──────────────────────────────────────────────────────────┐
        │ 1. maestro test .maestro  →  失败 flow + 步骤号            │
        │ 2. 取该步骤截图 + 语义树                                    │
        │ 3. 截图与 iOS 参考并排（同一视口尺寸）                       │
        │ 4. 代理按语义树里的 text/testTag 反查 Compose 文件与行号      │
        │ 5. 生成最小 patch（只改样式/布局/状态分支，不改业务逻辑）      │
        │ 6. ./gradlew :app:testDebugUnitTest && 重跑该 flow          │
        └──────────────────────────┬───────────────────────────────┘
                                   │ 全绿且截图无差异
                                   ▼
                                  交付
```

抓语义树与截图（本地设备）：

```bash
# 语义树：Compose 的 testTag / text 都在这里，比截图更适合机器定位
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml artifacts/ui.xml
adb exec-out screencap -p > artifacts/current.png
adb pull /sdcard/maestro/shots artifacts/shots
```

把「语义树节点 → 源文件」这一步做成约束，可以显著降低代理改错文件的概率：项目里每个可交互元素
都带 `testTag`（`btn_power`、`btn_open_screen`、`firmware_card`、`build_card`、`slider_smp`、
`seg_theme`、`log_tab_Serial`…），命名与文件一一对应：

```bash
# 例：语义树里出现 testTag=btn_retry_capture，直接定位到唯一文件
grep -rn 'testTag("btn_retry_capture")' app/src/main/java
# → app/src/main/java/com/android/gudao/iphoneos/ui/screens/DisplayScreen.kt
```

代理提示词模板（放进 `devilge MCP` / Antigravity 的 system prompt 里）：

```
你是 Android Compose 修复代理。输入：失败的 Maestro 步骤、截图、uiautomator 语义树、
仓库路径。约束：
1. 只允许修改 app/src/main/java/**/ui/** 下的 Compose 文件；
2. 不得新增依赖、不得改 minSdk/targetSdk、不得改 engine/ 下的业务逻辑；
3. 改动必须能用语义树里的 testTag 或 text 解释「为什么这处改动能让该断言通过」；
4. 输出 unified diff，并给出要重跑的命令。
```

判定「截图与 iOS 参考肉眼无差」的可操作化：同尺寸并排 + 三个硬指标，任一不达标就打回。

| 指标 | 门槛 | 取法 |
|---|---|---|
| 布局盒偏差 | 关键元素 bounding box 与参考差 ≤ 4 dp | 语义树 `bounds` vs 参考标注 |
| 主色偏差 | 采样 5 个点，ΔE ≤ 3 | 截图取色 |
| 圆角/描边 | 视觉复核（Squircle 连续圆角、1 px 高光边） | 人工看 2× 放大图 |

---

## 6. 这次实际执行了什么（事实记录）

环境：Ubuntu 22.04 容器，JDK 21.0.12.1，Gradle 9.6.0，AGP 9.4.1，compileSdk 37。

| 命令 | 结果 | 说明 |
|---|---|---|
| `./gradlew :app:testDebugUnitTest` | ✅ **160 / 160 通过** | 含 MCP 协议/HTTP/工具箱三组新用例，0 failure / 0 error |
| `./gradlew :app:assembleDebug` | ✅ 通过 | `app-debug.apk` 138 MB |
| `./gradlew :app:assembleRelease` | ✅ 通过 | R8 生效，`app-release-unsigned.apk` 75.8 MB（dex 67 MB → 2.5 MB） |
| `./gradlew :app:assembleDebugAndroidTest` | ✅ 编译通过 | 测试 APK 未在设备上执行 |
| `aapt2 dump badging` | ✅ | `minSdkVersion:'33'`、`targetSdkVersion:'28'`、`compileSdkVersion='37'`、`native-code: 'arm64-v8a' 'x86_64'` |
| `./gradlew :app:connectedDebugAndroidTest` | ❌ **未执行** | 无设备：`adb devices` 为空 |
| `maestro test .maestro` | ❌ **未执行** | 无设备；无 `MAESTRO_API_KEY` |
| `gcloud firebase test android run` | ❌ **未执行** | 无 `GCP_SA_KEY` |
| 云端截图 vs iOS 参考比对 | ❌ **未执行** | 依赖上面三项 |
| 与真实 MCP 客户端联调 | ❌ **未执行** | 沙箱无设备；协议与传输在 JVM 真实端口上端到端覆盖 |
| MCP 设置页在设备上的渲染 | ⚠️ 仅模拟器截图 | x86_64 模拟器可验证 UI 与导航，跑不了 aarch64 载荷 |

为什么不能在本机起模拟器（实测）：

```
$ ls -l /dev/kvm
ls: cannot access '/dev/kvm': No such file or directory
$ grep -c -E "vmx|svm" /proc/cpuinfo
0
```

没有硬件虚拟化，x86_64 模拟器无法启动；即使能启动也跑不了 aarch64 的 QEMU 载荷。**因此没有任何
截图可以拿来和 iOS 参考比对，也没有伪造截图或测试报告。**

要在有条件的机器上补齐，按顺序做三步即可产出全部证据：

```bash
# 1. 真机（arm64，Android 13+）跑 L2 + L3
adb devices && ./gradlew :app:connectedDebugAndroidTest
maestro test .maestro && adb pull /sdcard/maestro/shots artifacts/shots

# 2. 云端跑 L4（三机型）
gh workflow run android-e2e.yml   # 先 gh secret set 好 GCP_SA_KEY / GCP_RESULTS_BUCKET

# 3. 用 artifacts/shots 与 iOS 参考做第 5 节的三个指标比对
```

三个 `.md` 之外，`docs/refactor-report.md` 里有每条缺陷的「原代码行号 → 根因 → 修复 → 保护用例」，
`docs/design-system.md` 里有视觉 token 与控件映射，`docs/engineering-notes.md` 里有工具链与构建故障记录。
