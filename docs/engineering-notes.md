# 工程笔记：工具链、约束推导与构建故障记录

这份文档只记录**能被命令复现的东西**：版本号、报错原文、体积数字。凡是没有实测的部分会明确写
「未验证」，不做推断性陈述。

---

## 1. 工具链矩阵（实测）

| 组件 | 版本 | 为什么是这个版本 |
|---|---|---|
| JDK | 21.0.12.1 (Ubuntu OpenJDK) | AGP 9.x 要求 JDK 17+，而 `minCompileSdk=37` 的 AAR 元数据要求 AGP ≥ 9.1，AGP 9 的 R8/desugar 在 21 上最稳 |
| Gradle | 9.6.0 | AGP 9.4.1 的最低要求是 9.5；9.6.0 是当前 9.x 最新 |
| AGP | 9.4.1 | 必须 ≥ 9.1.0（见第 2 节 AAR 元数据）；选最新 patch |
| Kotlin Compose 编译器插件 | 2.4.21 | 与 Glasmio 0.1.1 编译时使用的版本一致 |
| compileSdk | 37 | `minCompileSdk=37`，见第 2 节 |
| Build-Tools | 37.0.0 | 与 compileSdk 对齐 |
| minSdk | 33 | Glasmio 强制，见第 3 节 |
| targetSdk | 28 | SELinux 强制，见第 4 节 |

启动命令（沙箱内实际使用，非 wrapper）：

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
/opt/gradle-9.6.0/bin/gradle :app:assembleDebug
```

仓库同时提供 wrapper（`gradle wrapper --gradle-version 9.6.0 --distribution-type bin` 生成）：

```
$ ./gradlew --version
Gradle 9.6.0
Kotlin:        2.3.21
Launcher JVM:  21.0.12.1
```

> 本沙箱访问 `services.gradle.org` 会 TLS 中断，所以上面这次验证是把 `distributionUrl` 临时指向
> `mirrors.cloud.tencent.com/gradle/gradle-9.6.0-bin.zip` 完成的，验证后已还原为官方地址。
> 国内网络构建时照第 7 节替换这一行即可。

---

## 2. 为什么 compileSdk 必须是 37

AAR 元数据里写着硬门槛（直接读 Gradle 缓存的 transform 产物，非文档推断）：

```
$ cat .../transformed/foundation/META-INF/com/android/build/gradle/aar-metadata.properties
minCompileSdk=37
minAndroidGradlePluginVersion=9.1.0

$ cat .../transformed/glasmio-scaffold/META-INF/com/android/build/gradle/aar-metadata.properties
minCompileSdk=37
minAndroidGradlePluginVersion=1.0.0
```

即：`androidx.compose.foundation:1.12.1`（Glasmio 0.1.1 的传递依赖）要求 `compileSdk ≥ 37` 且
`AGP ≥ 9.1.0`。先用 `compileSdk 36` 构建会在依赖校验阶段直接被拒，这就是本工程装
`platforms;android-37.2` 而不是 36 的原因。

Kotlin 版本同理：Glasmio 用 2.4.21 编译，Compose 编译器插件必须同版本，否则运行期
`ComposeRuntimeVersionMismatch`。

---

## 3. 为什么 minSdk 必须是 33

Glasmio 的每个 AAR 自带 `minSdkVersion 33`。把它压到 30 会得到明确的合并失败（实测原文）：

```
$ sed -i 's/minSdk = 33/minSdk = 30/' app/build.gradle.kts && gradle :app:processDebugMainManifest
> Task :app:processDebugMainManifest FAILED
uses-sdk:minSdkVersion 30 cannot be smaller than version 33 declared in library
[io.github.neoruaa.glasmio:glasmio-scaffold:0.1.1] ...
Manifest merger failed : uses-sdk:minSdkVersion 30 cannot be smaller than version 33 ...
```

这不是可以 `tools:overrideLibrary` 绕过的声明式限制：液态玻璃的折射层依赖 `RenderEffect`
（API 31+）与 AGSL `RuntimeShader`（API 33+）的硬件加速合成路径，33 以下没有等价实现，绕过去
只会得到一张不透明的灰底。所以 minSdk 33 是**能力下限**，不是偏好。

---

## 4. 为什么 targetSdk 必须是 28

这一条和 UI 无关，是进程能不能起来的问题。

1. QEMU、`bash`、`python3`、`7zz`、`unzbm` 都是随 APK 分发的可执行文件，运行时从应用私有目录
   `/data/user/0/com.android.gudao.iphoneos/files/run/bin/` 直接 `execve`。
2. Android 10（API 29）起，应用的 SELinux 域由 `targetSdkVersion` 决定：
   `targetSdk ≥ 29` → `untrusted_app_29+`，其策略对 `app_data_file:file execute` 有 **neverallow**，
   编译期就被禁止，运行期 `execve` 返回 `EACCES`。
3. `targetSdk ≤ 28` → `untrusted_app_27` 域，允许执行私有目录下的文件。Termux 长期停留在低
   targetSdk 就是同一个原因。

后果（必须知道）：

* 无法上架 Google Play（Play 要求 targetSdk 跟随年度门槛）。本工程定位是侧载/自建分发。
* 没有 scoped storage 强制，仍然用 `requestLegacyExternalStorage` + `MANAGE_EXTERNAL_STORAGE`
  访问 `Download/hackios`，这是老权限模型下的行为。
* 部分 29+ 行为变更（后台启动限制、前台服务类型）不受新策略约束。

**AGP 9.4.1 接受 `minSdk 33 > targetSdk 28` 这个反直觉组合**，合并结果实测：

```
$ aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk | head -3
package: name='com.android.gudao.iphoneos' versionCode='20' versionName='0.19.0'
         compileSdkVersion='37' compileSdkVersionCodename='17'
minSdkVersion:'33'
targetSdkVersion:'28'
native-code: 'arm64-v8a' 'x86_64'
```

AGP 会给一条 `minSdkVersion (33) is greater than targetSdkVersion (28)` 的提示。这是预期内的，
已在 `gradle.properties` 用 `android.sync.suppressAgpWarnings=MIN_SDK_VERSION_GREATER_THAN_TARGET_SDK`
静音，避免真警告被噪音淹没。

---

## 5. 依赖形态

```kotlin
implementation("io.github.neoruaa.glasmio:glasmio-scaffold:0.1.1")   // → ui → glass → core, icons
implementation("io.github.neoruaa.glasmio:glasmio-preference:0.1.1")
```

`glasmio-scaffold` 的传递依赖里有一个容易踩的点：

```
io.github.neoruaa.glasmio:glasmio-ui:0.1.1
  └── sh.calvin.reorderable:reorderable:3.1.0
        └── sh.calvin.reorderable:reorderable-android:3.1.0   ← 变体解析需要联网，不能全程 --offline
```

首次构建如果加了 `--offline`，会在 `:app:mergeReleaseNativeLibs` 报
`No cached version of sh.calvin.reorderable:reorderable-android:3.1.0 available for offline mode`。
先联网跑一次 `assembleDebug` 把变体元数据缓存下来，之后才能离线。

---

## 6. APK 体积账（实测）

拆分方式：按 zip 内每个 entry 的 `compress_size` 汇总。

### debug（138 MB）

| 内容 | 压缩后 |
|---|---|
| `classes*.dex`（11 个，stored 不压缩） | **67.0 MB** |
| `assets/hackios-toolchain.zip` | 25.4 MB |
| `assets/qemu-android-bundle.zip` | 21.7 MB |
| `assets/hackios-scripts.zip` | 14.8 MB |
| `assets/mach.development.bcm2837`（XNU 内核） | 6.0 MB |
| `assets/QEMU_EFI.fd` / `QEMU_VARS.fd` | 1.5 / 0.3 MB |
| `lib/`（arm64-v8a + x86_64） | 0.7 MB |

### release（75.8 MB，R8 + shrinkResources）

| 内容 | 压缩后 |
|---|---|
| `classes*.dex` | **2.5 MB**（67.0 → 2.5，−96%） |
| 其余 assets | 与 debug 完全一致 |

两点需要注意：

1. **dex 不压缩是正常的**：`minSdk ≥ 28` 时 AGP 以 stored 方式放置 dex，让系统直接 mmap，冷启动更快。
   所以「dex 67 MB」不是配置错误，是没开 R8。
2. **两个 64 MiB 的 pflash 镜像**（`QEMU_EFI.fd` / `QEMU_VARS.fd`）是稀疏文件，99% 是零。
   原工程把它们写进了 `noCompress`，于是 128 MiB 零字节原样进包（APK 274 MB）。改为只对 `zip`
   关闭压缩后，这两个文件各压到 1.5 MB / 0.3 MB，而 zip 仍然 stored（解压即字节拷贝，不额外耗 CPU）。

release 的 R8 规则见 `app/proguard-rules.pro`：只 keep `AppViewModel(Application)` 构造函数
（`AndroidViewModelFactory` 按名字实例化）与 `QemuFailureKind` 的枚举方法（诊断文本要可读），
其余交给 R8。**release 包未在设备上运行过**，这是当前最大的未验证点，见第 11 节。

---

## 7. 工具链安装（国内网络可用）

官方 `sdkmanager` 在本沙箱只有约 5 KB/s，改用腾讯镜像直接取 zip 手工安装：

```bash
mkdir -p /opt/android-sdk && cd /opt/android-sdk
M=https://mirrors.cloud.tencent.com/AndroidSDK
curl -O $M/platform-37.2_r02.zip && unzip -q platform-37.2_r02.zip -d platforms-tmp
curl -O $M/build-tools_r37_linux.zip && unzip -q build-tools_r37_linux.zip
curl -O $M/platform-tools_r37.0.1-linux.zip && unzip -q platform-tools_r37.0.1-linux.zip
# build-tools 的 zip 里多一层 android-17/，要拍平：
mv 37.0.0/android-17 37.0.0_tmp && rm -rf 37.0.0 && mv 37.0.0_tmp 37.0.0
```

Gradle：

```bash
curl -O https://mirrors.cloud.tencent.com/gradle/gradle-9.6.0-bin.zip
unzip -q gradle-9.6.0-bin.zip -d /opt && chmod +x /opt/gradle-9.6.0/bin/gradle
```

JDK：`apt-get install -y openjdk-21-jdk-headless`（Adoptium/Microsoft 的 tarball 在本沙箱多次超时）。

---

## 8. 下载：掉速、卡在「计算中」、以及分片合并

### 8.1 为什么网络正常却掉到几 KB/s

不是一个原因造成的，拆开是六件事：

| # | 机制 | 现象 | 处理 |
|---|---|---|---|
| 1 | **连接假死**：TCP 拥塞/丢包后窗口归零，`read()` 长期阻塞又不超时，偶尔挤进来几百字节 | 速率掉到个位数 KB/s，几十秒后才缓过来，反复发作 | **看门狗线程**每 1.5 s 扫一遍活跃 socket：静默 ≥ 8 s、或 12 s 后均速 < 3 KB/s 就直接 `disconnect()`，worker 从**已有字节处**重连（新连接通常立刻恢复满速）。旧实现只能等 20 s 读超时，所以每次抖动要付 30 s |
| 2 | **单连接限速**：Apple CDN 按连接限速 | 峰值卡在一个固定值 | 并发随链路类型缩放：Wi-Fi/以太网 8，蜂窝 4 |
| 3 | **慢分块拖累整体** | 最后 10% 特别慢 | 分块改为**工作队列**，干完就领下一块；分块从 256 MB 缩到 **64 MB**，单块抖动只损失 1/96 的进度 |
| 4 | **整体停滞**：8 条 socket 同时被限速 | 全局速率趴在地上 | 主循环发现 25 s 内**总字节零增长**就整池断开重连 |
| 5 | **息屏进入 Doze** | 后台下载突然变 KB/s | `PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_HIGH_PERF`（`DownloadPowerLock`，4 小时硬上限防泄漏） |
| 6 | **每次 Range 重新握手** | 每块开头一秒级停顿 | 成功响应不再 `disconnect()`，socket 归还 keep-alive 池 |

两个附带修正：

- **速率显示**：250 ms 采样 + α=0.25 EMA，掉速是真的才会显示。
- **慢链路不是失败**：低速重连单独计数（上限 10 次，带 150–500 ms 抖动避免 8 个 worker 同时冲同一个 CDN 边缘），用尽后**接受慢链路跑完**；此时看门狗对该连接停止干预（`ConnStat.watched=false`），否则会在「杀掉—重连—还是很慢」之间死循环。

### 8.2 为什么下载完一直显示「计算中」，分片不合并

UI 里的「计算中…」是 **ETA 占位符**（`Fmt.eta(null)`），不是合并状态。所有字节落盘后速率为 0，ETA 必然为 null，于是界面在 100% 处永远显示「计算中…」——而真正耗时的合并（6.1 GB 拼接）在旧实现里**没有任何进度回调**，看起来就是卡死。

修法：

- 新增 `DownloadUi.Merging` + `Listener.onMerging(done, total)`，合并每 32 MB 回调一次，界面显示「正在合并分块为 IPSW · 62%」。
- 合并逻辑抽到 `engine/IpswMerger`，可单测；`FileChannel.transferTo` 分 8 MB 块搬运，边搬边回调。
- **边合并边删分片**：旧实现要同时存在 6.1 GB 分片 + 6.1 GB 成品，手机只剩 7 GB 空间时永远合不完；现在峰值只多一个分块。
- 合并可续：删掉的都是已并入暂存文件的前缀，所以暂存文件长度就是断点（`MergeResume`），中断后截断重来即可。
- **合并前校验分片长度**，短的分片先补齐再合并，绝不把截断数据拼进镜像（否则要等 6 GB 写完才在 SHA-256 处失败）。
- 暂存文件已完整但没来得及改名时（上次在 `renameTo` 前被杀），下次启动直接采纳并校验，不重下 6 GB。

### 8.3 其余吞吐/稳定性改动

- `Accept-Encoding: identity`：避免透明 gzip 让 `Range` 语义失效。
- 服务端忽略 `Range` 返回 200 时，丢弃前缀后继续；前缀超过 512 MB 则明确报错（不把 6.1 GB 追加进 64 MB 的分块文件）。
- `416` 视为分块已完成；`429/503` 读 `Retry-After` 再退避。
- SHA-256 缓冲 8 MB，每 64 MB 回报一次进度。
- 分块文件超长时先 `truncate`，避免旧版本写爆的分块被拼进镜像。

保护用例：`LinkHealthTest`（6）、`MergeResumeTest`（5）、`IpswMergerTest`（4，含中断续合、暂存尾部半截、缺块必须报错）、`ChunkRecoveryTest`（6，含「已并入暂存的分块不能再下」「半覆盖的分块必须重下」）。

### 8.4 端到端测试发现的真 bug：Range 用了分块内偏移

把下载引擎接到本地 HTTP 服务器上跑完整流程（分块 → 合并 → 校验）时，SHA-256 直接对不上。根因：

```kotlin
// downloadChunk 里的 offset 是分块内偏移（0..chunk.length）
fetchRange(chunk, offset, out, ...)
// fetchRange 里却当成文件绝对偏移写进 Range 头
setRequestProperty("Range", "bytes=$offset-${chunk.endInclusive}")
```

于是除第 0 块外，**每一块都在请求 `bytes=0-…`**，92 个分块写进去的都是镜像开头那一段，合并结果必然校验失败。旧实现里同样的写法在 `append = from > chunk.start` 处还自相矛盾（分块内偏移永远小于 `chunk.start`）。

现在统一约定：`downloadChunk` / `fetchRange` 内部一律**分块内偏移**，绝对偏移只在 `fetchRange` 里用 `chunk.start + from` 现算。`FirmwareDownloaderTest` 用真实 HTTP Range 服务器断言了「已下完的分块不再请求」「半截分块从精确字节续传」，这类偏移错误以后不可能再溜过去。

测试总数：68（`FirmwareDownloaderTest` 4 个，其中一个是真起 HTTP 服务、真写分块、真合并、真校验）。

## 9. 编译镜像：为什么看起来卡在 100 %

症状（用户在资源页看到的）：卡片写着「读取 rootfs 目录结构」，右侧 **100%**，副标题 `cryptex-app.dmg 10 / 10 MB · 100%`，半小时不动。

### 9.1 那 100% 是上一阶段的

进度是分阶段的：`BuildUi.Running(phase, detail, fraction)`。原来 `onPhase()` 保留旧的 `fraction` 与 `detail`，所以阶段一变，数字还是上一阶段的。

`cryptex-app.dmg 10 / 10 MB` 是**解包 IPSW 成员**那一步的最后一条进度（三个成员 4.7 + 2.7 + 0.01 GB，累计正好 100%）。紧接着 `fetch.sh` 已经开始，阶段标题换成了 `listing the image` →「读取 rootfs 目录结构」，但数字没换。

修法：`onPhase()` 在阶段名变化时把 `fraction` 置为 `BuildUi.Running.NO_PROGRESS`（-1）、`detail` 清空，并记录 `phaseStartedAtMs`；UI 看到 -1 就显示**扫动的不确定进度条**和「进行中」，不再显示假百分比。

### 9.2 那一步确实没有任何输出

`fetch.sh` 里：

```bash
echo "fetch.sh: listing the image"
$SEVENZIP l -slt rootfs.dmg > rootfs.slt          # 4.7 GB，输出被重定向，stdout 全静默
python3 select-system.py rootfs.slt ...
$SEVENZIP x -y -orootfs rootfs.dmg @system.files > extract-system.log   # 同上，几十分钟
```

7z 的进度是**同一条行**用 `\r` 刷新的，重定向到文件后 app 一行都读不到。所以从「读取目录结构」到「解包完成」这段时间（手机上通常 30–90 分钟）界面完全静止。

修法（`fetch.sh` 新增 `unpack()`）：

```bash
unpack() {                       # unpack LOGFILE 7z-args...
	local log=$1; shift
	: > "$log"
	$SEVENZIP "$@" -bsp1 2>> "$log" | tr '\r' '\n' | tee -a "$log"
}
```

- `-bsp1`：把进度强制送到 stdout；
- `tr '\r' '\n'`：把刷新的同一行变成多行，`BufferedReader.readLine()` 才读得到；
- `tee -a`：日志文件照旧完整；
- `set -o pipefail`：7z 失败不会被 `tee` 的退出码盖掉。

app 侧 `SevenZipProgress`（可单测，6 例）解析 `"  47% 812 - rootfs/usr/lib/…"`，只在**百分比变化**时更新状态——7z 一秒刷 20 次，每帧都重组带模糊玻璃的卡片正是之前卡顿的来源。

顺带修掉两个阶段识别错误：

| 原匹配 | 问题 |
|---|---|
| `"extracting" in line && "rootfs" in line` | 脚本打的是 `fetch.sh: extracting 1234 files`，永远不匹配，解包全程顶着「读取目录结构」 |
| `"jobs" in line` | 任何含 `rc.jobs` 的行都会把阶段改成「生成 LaunchDaemons」并重置计时；改为 `"mkjobs.py:"`，并在 `build.sh` 里补打点 |

### 9.3 打包阶段同理

`build.sh` 的 `python3 mkapfs.py -z -o system.apfs … | tail -1` 把 5.4 GB 文件压成 2.5 GB，期间零输出，`tail` 还要等进程结束。现在在两条 `mkapfs.py` 调用前各 `echo` 一行阶段名，UI 至少知道「打包 system.apfs（2.5 GB，最久的一步）」，并显示已运行时长。

### 9.4 现在的观感与真实耗时

| 阶段 | 数据量 | 手机上大致耗时 | 有百分比吗 |
|---|---|---|---|
| 解包 IPSW 成员 | 7.4 GB | 10–20 分钟 | 有 |
| `7z l` 列 rootfs | 4.7 GB | 2–10 分钟 | 无（扫动条 + 计时） |
| 解包 rootfs | 4.7 GB | 30–90 分钟 | 有（7z 进度） |
| 解包 OS cryptex + dyld | 2.7 GB | 10–30 分钟 | 有 |
| 打包 root.apfs | — | 5–15 分钟 | 无 |
| 打包 system.apfs | 5.4 → 2.5 GB | 30–90 分钟 | 无 |

用户侧建议写进了界面：插电、保持前台（锁屏会降频），首次 1–3 小时属正常。

## 10. 构建故障记录

按发生顺序，每条都记录「症状 → 根因 → 处理」。

| # | 症状 | 根因 | 处理 |
|---|---|---|---|
| 1 | 所有 Gradle 任务在初始化阶段就失败，任何工程都构建不了 | `~/.gradle/init.gradle` 里 `mavelCentral()` 拼写错误（应为 `mavenCentral()`），Groovy 解析失败 | 重命名为 `init.gradle.broken.bak` |
| 2 | `sdkmanager` 下载 platform 只有 5 KB/s，反复超时 | dl.google.com 在沙箱内被限速 | 改用腾讯镜像手工解压安装（第 7 节） |
| 3 | build-tools 解压后目录变成 `37.0.0/android-17/`，`aapt2` 找不到 | 镜像 zip 多包了一层 | 拍平目录 |
| 4 | `compileSdk 36` 构建时依赖校验失败 | `minCompileSdk=37`（第 2 节） | 安装 platform-37.2，`compileSdk = 37` |
| 5 | AGP 9.4.1 报「requires Gradle 9.5 or newer」 | 本地 Gradle 9.3 | 安装 Gradle 9.6.0 |
| 6 | manifest merger 失败：`Namespace 'demo' is not a valid Java package name` | 验证工程 namespace 没点号 | 改 `com.demo.glasmio` |
| 7 | 编译报 `Unresolved reference: ContinuousRoundedRectangle` / `com.kyant.capsule` | 那是 Glasmio 内部的绘制实现，不是公开 API | 改用 `RoundedCornerShape` |
| 8 | 编译报 `Unresolved reference: testTag`（`ui.semantics`） | Compose 的 `testTag` 在 `ui.platform` | 换包名，并改为 `Modifier.testTag(...)` 链式调用 |
| 9 | `LogTailerTest.line limit bounds one poll` 偶发失败 | 单次 `poll(maxLines)` 已达上限后，剩余未投递行在下次 `poll` 因 `length == offset` 提前返回 | `poll()` 开头先 `drain()` 已缓存的完整行，再读新字节 |
| 10 | APK 274 MB | `noCompress += "fd"` 把两个 64 MiB 稀疏 pflash 原样打进包 | 只对 `zip` 关闭压缩 → 138 MB |
| 11 | debug dex 67 MB | 未开 R8，Compose 运行时整包保留 | release 开 `isMinifyEnabled` + `isShrinkResources` → dex 2.5 MB，APK 75.8 MB |
| 12 | `./gradlew` 下载发行版 TLS 中断 | 沙箱到 `services.gradle.org` 的连接被中断 | 验证时临时换腾讯镜像，验证后还原；`distributionUrl` 一行可换 |
| 13 | 手机上「编译镜像」停在 `job com.apple…` 一串任务名，报 `build.sh 失败（退出码 1）`，看不到原因 | 两层**从头截断**：`mkjobs.py` 因一个读不出的 plist 抛 `InvalidFileException`（traceback 在末尾），而错误卡片 `msg.take(1200)` 和 `take(4000)` 都保留**开头**——正好是 40 行 job 列表，把 traceback 全切掉 | 见 10.1 |

### 10.1 一个坏 plist 让整个构建失败，而报错把原因切掉了

现象：手机上编译到「生成 LaunchDaemons」，卡片先列出

```
  job com.apple.notifyd: /usr/sbin/notifyd
  ...
  job com.appl
```

然后就是 `build.sh 失败（退出码 1）`，下面直接是「重试 / 日志」按钮。看上去像卡在某个 job 上。

根因有两层，且都在截断：

1. **`mkjobs.py` 不带容错**。`REST=1` 要对 `rootfs/System/Library/LaunchDaemons` 里 490 个 plist 逐个
   `plistlib.load`，任何一个读不出（4.7 GB 解包时丢了某个文件的块，或 `unzbm` 没还原完 decmpfs 属性）
   就抛 `InvalidFileException`，python 退出 1，`set -e` 让 build.sh 在打包任何镜像之前停住。
2. **错误信息从错误的一端截断**。`runProcess` 的 `tail` 存最后 40 行，但拼好后用了
   `take(4000)`；`AppViewModel` 又用了 `msg.take(1200)`。两者都保留**开头**，而开头正是那 40 行
   `job com.apple…`（每行 70–90 字符，正好吃满 4000），**traceback 在尾部，被整段丢掉**。
   屏幕上那句 `job com.appl` 不是卡住的地方，是字符串被切在第 4000 个字符的位置。

处理（三处，缺一不可）：

```python
# mkjobs.py：读不出的 plist 不抛 traceback。分两类：
#   IOS_JOBS 里的（镜像启动就靠它们）→ 明确失败并告诉用户删除 ios165/rootfs 重解包，
#     因为缺 SpringBoard 的镜像能打包、能发布，开机黑屏且一句话不说；
#   --rest 里的其余 480 个 → 跳过并记一行 SKIPPED，镜像照做。
def load_job(path, critical=False):
    try:
        with open(path, 'rb') as f:
            return plistlib.load(f)
    except (OSError, ValueError, plistlib.InvalidFileException) as e:
        print(f'  job {path}: SKIPPED -- {e.__class__.__name__}: {e}', file=sys.stderr)
        (critical_lost if critical else skipped).append(path)
        return None
```

```kotlin
// ImageBuilder：保留尾部，并把日志路径放在最末（takeLast 会保住它）
val shown = if (joined.length > TAIL_CHARS) "…（前 …略）\n" + joined.takeLast(TAIL_CHARS) else joined
// BuildError 的标题行取 tail 里最后一条顶格的行 —— python 的 "XxxError: 原因" 就在那里
```

```kotlin
// AppViewModel：同样 takeLast，不再 take
val detail = if (msg.length > FAIL_CHARS) "…（前略）\n" + msg.takeLast(FAIL_CHARS) else msg
```

外加 `build.sh` 兜底：`mkjobs.py` 真的挂了（不只是坏 plist）时，若已写出的 job 少于 10 个就
`exit 1`，否则继续打包并在 stderr 说明——**一个 job 都没有的镜像会开机黑屏**，那比失败更难查。

还有一处配套：提示让用户删 `ios165/rootfs` 之后，`.build-stage` 里 `fetch` 的标记会让重试**跳过**
解包，直接又死在 build.sh。所以 `build()` 现在先对账——标记说做完了、产物却不在，就作废该阶段：

```kotlin
if (stageDone(STAGE_EXTRACT) && !File(ios165Dir, "rootfs.dmg").isFile) {
    checkpoint.forget(STAGE_EXTRACT); checkpoint.forget(STAGE_FETCH)   // fetch 的输入来自 extract
} else if (stageDone(STAGE_FETCH) && !File(ios165Dir, "rootfs").isDirectory) {
    checkpoint.forget(STAGE_FETCH)
}
```

UI 也改了：失败详情放进 `heightIn(max = 220.dp)` + `verticalScroll` 的等宽小字块，卡片不再被撑到
一屏高，原因就在第一眼。

> 教训：**错误信息要保留尾部**。栈回溯、`No space left on device`、编译器的最后一句，都在最后一行；
> 从头截断等于把唯一有用的部分扔掉，只留下复现路径。

---

### 一处业务逻辑 bug 的细节（B4）

`LogTailer` 的增量读取有两个坑，第二版才修对：

```kotlin
// 错误版本：一次读到上限就直接 return，把「剩余未投递的完整行」丢了，
// 因为下一轮 offset 已经等于 length，不会再去读，那些行永远出不来。
fun poll(maxLines: Int): List<String> { /* ...读到 maxLines 就 return out... */ }

// 正确版本：每次 poll 先把缓冲区里已经完整的行吐出去（受 maxLines 约束），
// 再决定要不要读新字节。
fun poll(maxLines: Int): List<String> {
    val out = ArrayList<String>(maxLines)
    drain(out, maxLines)          // 先消费上次剩下的
    if (out.size >= maxLines) return out
    /* 再读新字节、拆行、把最后半行留在 partial 里 */
}
```

这就是「日志刷新卡顿 + 滚动被重置」之外的第三个隐藏问题：滚动位置被重置是表面现象，真正让人
以为「日志卡住了」的是行被吞掉。

---

## 11. AI 助手：一条能动手的对话链（历史，0.23.0 已移除）

### 11.1 为什么是「工具」而不是「让它读截图」

排障需要的是**证据**，而证据都在文件里：串口几十万行、QEMU 自己的 stderr、构建脚本每一步的输出。
把截图发给多模态模型看起来更"智能"，但它读不出 `errno=13` 是哪一行、哪个文件。所以助手接的是
function calling：模型自己决定读哪个日志、grep 什么词、要不要看 `info registers`。

模型看到的设备状态是一段**文本摘要**（`device_status`），不是 JSON。原因很实际：一行
`QEMU: 运行中 12:34，1 vCPU，阶段 4/7` 比一棵嵌套对象更省 token，也更不容易被读错；需要细节时它会
自己调工具。

### 11.2 不做流式

流式响应要按 SSE 累积 `delta.content` 与 `delta.tool_calls[].index`。文字部分好写，工具调用部分
要处理"同一个 index 的名字先到、参数分几段到、id 只在第一段出现"——拼错一个字段，结果是**有文字但
没有工具调用**，而这恰恰是最难从现象反推原因的故障。工具调用必须可靠，所以选了非流式。

代价是等待期间没有逐字输出。补法是 `AiEvent.Step`：界面显示"思考中"→"正在执行 read_log"，并且
`HttpURLConnection` 的 readTimeout 设成 180 秒——推理模型在长上下文上确实会想这么久，没有这个
超时，"停止"按钮之外不会有任何反馈。

### 11.3 权限：工具表随开关裁剪，而不是调用后再拒

`ToolBox` 在构造时按 `allowWrite` 生成工具表。只读模式下 `write_file` / `run_shell` /
`set_qemu_flags` / `set_boot_args` / `restart_qemu` **根本不在 `tools` 数组里**。这比"提供了但
拒绝执行"少一类坏行为：模型看不到工具就不会反复尝试，也不会在回答里承诺"我已经改好了"。

打开开关是一次独立的授信确认（`GlasmioAlertDialog`，Destructive 角色），并且会清空当前会话——
历史里模型记得自己"刚才能写"，换权限后继续用会判断错。

### 11.4 路径沙箱的实现

```kotlin
private fun resolve(path: String): File {
    val base = bridge.runDir.canonicalFile
    val file = (if (candidate.isAbsolute) candidate else File(base, path)).canonicalFile
    if (file != base && !file.absolutePath.startsWith(base.absolutePath + File.separator)) throw ToolError(...)
    return file
}
```

两个细节：
* 必须 `canonicalFile` 之后再比前缀，否则 `run/../../etc/passwd` 能通过字符串检查；
* 前缀要带上分隔符，否则 `run-evil/` 会被判定在 `run/` 里面。

shell 命令走另一套检查：一份危险模式黑名单（格式化、写块设备、重启、装/卸应用、改系统设置、
`setenforce`、`mount`、`su`、递归删系统目录），外加"命令里所有绝对路径必须在 `run/` 内"
（`/dev/null`、`/system`、`/proc/self` 例外）。命中时把原因回给模型，让它换做法，而不是静默失败。

### 11.5 `-S` 必须被点名拒绝

QEMU 的 `-S` 是 "freeze CPU at startup"，留给 gdb 用的。它看起来像一个无害的调试开关，实际效果是
访客永远不启动，症状与"卡死/黑屏"完全一致——一个排障助手把它打开，会把用户带进更深的坑。所以
`QemuFlags` 单独给它一条错误信息，而不是笼统地说"不允许"。

同一个守门函数还拒绝 `-device` / `-drive` / `-M` / `-smp`：那些属于构建配置，改它们等于让 App 的
命令行和界面设置互相矛盾。

### 11.6 boot-args 改了必须让 payload 失效

`payload.bin` 的 cmdline 是构建时烘进去的。`QemuPreflight.stampFor()` 原来只算
`sys/root/smp/rev`，AI 改了 `debug=0x144` 却不会触发重建，下一次启动仍然用旧 payload——表现为
"设置明明保存了，但什么都没发生"。stamp 里加上了 `boot=<args>`，改动即失效、下次启动重建。

额外参数追加在托管参数**之后**：XNU 对重复 key 取最后一个，这样 `gl=0` 之类的覆盖才真的生效；
而 `cpus=` / `rd=` / `epoch=` / `loader_return_after=` 在 `QemuFlags` 里就被拒绝，避免两个来源打架。

### 11.7 单测为什么要真 org.json

单测跑在 JVM 上，`android.jar` 里的 `org.json` 是空壳，配合 `isReturnDefaultValues = true` 后每个
方法返回 `null`/`0`，`JSONObject.put` 不报错但什么也不做——测试会"通过"，而线上报文是空的。

```kotlin
testImplementation("org.json:json:20240303")
```

加真实实现后，`AiProtocolTest` 才真的在验证字段名。这是这一节里唯一一个"测试基础设施"的坑，但它的
失败方式最隐蔽：**全绿**。

### 11.8 明文 HTTP 的取舍

局域网里的自建网关（`http://192.168.1.10:8000/v1`）是常见用法，而 `targetSdk 28` 起默认禁止明文。
manifest 里开了 `usesCleartextTraffic="true"`：这个 App 是侧载排障工具，地址由用户自己填，公网服务
仍然走 https。代价是应用内任何 http 请求都不再被系统拦——已知，接受。

### 11.9 这一节的未验证部分

| 项目 | 状态 | 原因 |
|---|---|---|
| 对真实 OpenAI 兼容服务端完成一次对话 | ❌ 未执行 | 无 API Key。请求构造、响应解析、工具循环、护栏共 41 个用例覆盖，HTTP 客户端只到编译期 |
| 真机上 `run_shell` 的 PATH / PYTHONHOME 是否够用 | ⚠️ 未验证 | 依赖部署后的 `toolchain/` 布局；脚本自己会 `source xnu-env.sh`，这里只是兜底 |
| 助手页在 arm64 真机上的键盘避让（0.22.0 历史项，已随页面移除） | ⚠️ 仅模拟器 | `imePadding()` + `adjustResize`，x86_64 模拟器可验证布局，真机输入法差异未覆盖 |
| 长对话的 token 成本 | ⚠️ 未量化 | 历史裁剪到 30 条、单个工具输出截断到 6 KB，但没有实测账单 |

---

## 12. 未验证项（必须知道）

| 项目 | 状态 | 原因 |
|---|---|---|
| release 包在设备上运行 | ❌ 未验证 | R8 可能裁掉运行期反射依赖；本环境无设备。若崩溃，先加 `-keep class com.android.gudao.iphoneos.** { *; }` 定位 |
| 真机 UI 截图 | ❌ 未验证 | 无 `/dev/kvm`、无 arm64 设备 |
| 云端 E2E（FTL / Maestro Cloud） | ❌ 未执行 | 无凭据，见 `docs/testing.md` 第 6 节 |
| QEMU 端到端启动（B1/B2/B3/B6 的真实行为） | ❌ 未验证 | 需要 arm64 真机；逻辑层有单元测试覆盖，但进程行为只能在设备上确认 |
| SF Pro 字体 | ⚠️ 回退 | 字体有许可限制，未随包分发，运行时回退到系统字体 |

第 1–10 节的数字与报错全部来自本机实测（第 8 节的下载策略为代码推导，未做真机吞吐对比；第 9.4 节的耗时是手机上量级估算，未逐项打表）；第 11 节列的是**没有做的部分**，不用模拟记录替代。

---

## 13. 2026-11-04 真机日志（0.20.0，HONOR REA-AN00 / Android 15）

当天用户导出了一份 4.8 万行日志（`hackios-logs-20261104-163602.txt`）。两条结论都能在日志里定位到行：

**13.1 取帧慢/偶发连不上：HMP 提示符没有换行，`readLine()` 读不到它**

`-monitor tcp:...` 的提示符是 `(qemu) `，**后面没有 `\n`**。旧代码用 `BufferedReader.readLine()`
等这一行，函数会一直阻塞到 socket 超时（6 s）才抛异常、关掉会话，下一帧再重连。日志里表现为
`QEMU 输出 qemu.log` 为空（QEMU 没报错）、画面却有十几秒不动。0.21.0 改成逐字符读到
`"(qemu) "`，并按命令设置 `soTimeout`，抓帧耗时从「6–12 秒且每次重连」回到「一次 screendump 的时间」。

同一处还有两个次生 bug：

- 抓帧失败时不删旧的 `shot.ppm`，解码器会把上一帧当新帧返回 —— 看起来是「画面冻住」，不是「取帧失败」。
- QMP（输入）与 HMP（抓帧）共用一把锁，一次慢抓帧会把整段拖拽排在后面。

**13.2 串口安静 ≠ 卡死，以及"阶段 7/7"是怎么骗人的**

同一份日志里还有一条被误读的时间线：

```
07:29:26  backboardd ... startup complete. 120.868s elapsed
07:29:31  Bootstrap system shells ready
07:29:43  process lsd[27] denied attempt to escape CPU monitor ...
（此后 1 小时 6 分钟没有输出，App 仍显示"阶段 7/7 进入锁屏"）
```

当时把它当成"8 vCPU 把 guest 拖死"，是错的：`-smp 8` 只是慢，真正的原因是 13.3 的
`fbscale`。串口安静只说明没有进程在打印；`BootPhase` 旧规则一看到 `backboardd` 就报
"进入锁屏"，于是把"系统起来了但一个像素都没画"显示成了"已进入锁屏"。

0.21.0 起：第 7 级只由**真实帧**决定（非黑帧且内容变化 ≥3 次），文本最多给到 6；
画面 90 s 不变时「屏幕」页出现提示，>2 核时附带「改回 1 核」（`SMP_STABLE_MAX = 2`），
`run/app.log` 记录 App 侧事件并进入导出与 `read_log(source=app)`。

**13.3 全黑：`fbscale=1` 让 WindowServer 只画 375×812，ramfb 却是 1125×2436**

0.21.0 装上后再跑，AI 读串口给出的报告（用户截图）与日志原文一致：

```
mg_test: main-screen-scale = ...{value = +1.0000000000}
StartWindowServer: MG scale:1 display preferredScale:1
StartWindowServer: initial main screen size:{375, 812} scale:1
[core] main display width 375 height 812 framebuffer width 375 height 812 scale 1
```

即 iPhone X 的 `main-screen-scale` 应该是 3.0（375×812 点 = 1125×2436 像素），实际是 1.0。
根源在 App 自己：`QemuRunner.prepare()` 把 `fbscale=1` 写进了 payload 的 boot-args。
上游 `docs/ru/notes.md` 对 `fbscale` 的定义是"每个点画几个像素，默认 3；2 = 少画一点再拉伸
到面板"，所以 1 就是让 WindowServer 建 375×812 的帧缓冲，合成结果落不进 1125×2436 的 ramfb，
屏幕全黑，而串口里没有任何错误。

0.21.1 的修法：

- `fbscale=3`（原生 1:1，与 ramfb 同尺寸）。想省算力可在「额外启动参数」里写 `fbscale=2`
  —— 额外参数排在托管参数之后，XNU 取最后一个值。
- `QemuPreflight.CMDLINE_PROFILE = 2` 进入 `.payload-stamp`：托管 cmdline 一改，旧
  `payload.bin` 必须失效重建，否则"改了代码、设备上没变化"（这次就是它把 `fbscale=1`
  留在了老缓存里）。
- `BootPhase` 解析 `main-screen-scale` / `initial main screen size`，倍率不是 3 时直接在
  诊断卡上写"渲染倍率 1x……画面全黑"，不再让人从 4 万行里找。

`surf_test: 80 checks, 0 failed`、根目录没有 EACCES —— 渲染内存与权限都在这一节之外被排除。

---

## 14. 0.22.0：AI 助手的权限分级与 agent 基础能力（历史，0.23.0 已移除）

**权限三档**（设置 → AI 助手）：

1. 只读：只有 read_log / device_status / capture_screen / list_files / read_file / hmp（白名单动词）。
2. 读写（允许修改与执行）：+ write_file（留 .bak）、run_shell（限 run/ 内路径 + 危险命令黑名单）、
   android_log（logcat main/crash/events）、set_qemu_flags / set_boot_args / restart_qemu。
3. 完全访问：路径与命令放开到整台 Android。前提是一个事实：**Android 用户态本身就是完整 Linux**
   （toybox、/proc、/sys、logcat、pm、am），卡住 AI 的从来是权限层而不是缺命令。此档只保留
   `DENY_CORE`：写块设备、mkfs、reboot/shutdown、rm -rf /；su 交给设备的 root 状态（root=true
   走 `su -c`，找不到 su 时把原因原样返回）。内核拒绝（SELinux avc denied）会原样回到模型——
   那比任何白名单都更有诊断价值。权限变化会丢弃整个 agent（模型必须重新被告知能做什么）。

**上下文管理**：Agent 每次请求前与结束时发 `AiEvent.Stats(chars, messages)`（工具调用参数与
结果都计入），界面按 2 字符 ≈ 1 token 显示；达到 24k token 提示"建议清空重置"。

**对话导出**：`ChatTranscript.toMarkdown()`（纯函数、可测）把气泡转成 Markdown，工具调用的
参数与输出完整保留（各截 2k/12k 字符），写到 Download/hackios/logs/hackios-chat-<时间>.md 并
弹系统分享。含 ``` 的提问外层围栏升级为四反引号。

**清空的语义**：清空 = 取消运行 + 丢弃 agent（上下文真的重置）+ 清屏 + toast 说明。此前版本
行为一致，但没有任何提示让用户相信上下文确实没了。

**其他 agent 能力**：失败后"重试上一条提问"（lastAiPrompt）；长按用户/助手气泡复制原文。

---

## 15. 0.23.0：删掉内置 AI，改为 MCP 端口管理整条链路

> 第 11、14 节记录的是 0.20–0.22 的内置对话式助手，**0.23.0 已整体移除**：`ai/` 包、
> `AssistantScreen`、Agent 循环、OpenAI 客户端、`ChatTranscript` 与其单测全部删除。保留下来的
> 只有 `ToolBox`——它迁到 `mcp/` 包，改作 MCP 服务端的工具执行层。

### 15.1 为什么换

内置助手把「模型选哪家、上下文留多久、怎么导出对话」这些本该由客户端决定的事塞进了一个
侧载 App，还引入了 API Key 落盘、对话页键盘避让、气泡 id 冲突（0.21.0 的闪退）等一堆与
排障无关的复杂度。用户最终要的是「外部工具能管这条链路」，不是「App 里再养一个聊天机器人」。
MCP 正好是这件事的标准接口：App 只暴露能力，客户端自己带模型。

### 15.2 实现

| 文件 | 职责 |
|---|---|
| `mcp/McpConfig.kt` | 端口、绑定方式、令牌、权限档的持久化模型与常量 |
| `mcp/McpProtocol.kt` | JSON-RPC 2.0 分发：initialize / tools/list / tools/call / ping / 空 resources / prompts |
| `mcp/McpHttpServer.kt` | 纯 JDK `ServerSocket` 的 HTTP 服务，Streamable HTTP + 旧版 SSE，token 鉴权、会话与统计 |
| `mcp/ToolBox.kt` | 工具表与护栏；`QemuBridge` 收窄为 status/config/mcpInfo/hmp/screen + 一个 `dispatch` |
| `state/AppViewModel.kt` | 服务生命周期与 `dispatch` 全链路实现（每个动作就是 App 原本的公开 intent） |

几个刻意的选择：

* **不用 `com.sun.net.httpserver`**：Android 运行时没有这个包；也不为一个调试端口引入 Ktor/Netty。
  手写最小 HTTP/1.1（请求行 + 头 + Content-Length），只用 `java.net` 与 `org.json`，于是服务端能
  在 JVM 上起真实端口做端到端单测。
* **两代传输都接**：`POST /mcp` 回 `application/json`（或按 Accept 回单条 SSE），`GET /mcp` 保持
  SSE 通知流，`DELETE /mcp` 结束会话并回 `Mcp-Session-Id`；旧客户端走 `GET /sse` 拿到
  `event: endpoint` 后 POST 到 `/message?sessionId=...`，响应推回那条 SSE 连接。
* **权限档做成 getter**：`QemuBridge.allowWrite/fullAccess` 每次从当前配置读，`ToolBox.specs` 每次
  `tools/list` 重算，改权限不用重启端口。`set_config` 还能改 MCP 自己的端口/开关/令牌；为避免
  把正在处理的请求掐断，这类变更延迟 400 ms 再重启监听。
* **所有动作收在一个 `dispatch`**：ToolBox 只做参数校验与护栏，真正执行的是 AppViewModel 里
  原本就有的 `startQemu()` / `stopQemu()` / `compileImages()` / `downloadFirmware()` / `exportLogs()`……
  「MCP 能做什么」和「界面按钮能做什么」因此不会分叉。

### 15.3 删链的三道保险

`delete_chain` 是这次新增的最高权限动作，也是「整个链路都能管、甚至删掉」的落点：

1. 只有**完全访问**档才看得到它；`inspect_chain` 先把各段目录的文件数与体积摊开。
2. `scope` 分级：`cache` / `run` / `images` / `firmware`（6.1 GB IPSW）/ `logs` / `exports` /
   `config` / `all`。目录只清内容不删自身，App 之后仍能往里写。
3. 默认 `dry_run=true` 只回预览；真删必须 `dry_run=false` + `confirm="DELETE-CHAIN"`，执行前
   自动 `stop_qemu`，执行后 `refresh_artifacts`。MCP 端口配置保留，不会把自己关掉。

### 15.4 验证与边界

* 160 个 JVM 单测全过，其中 `McpProtocolTest` 覆盖握手/工具表/错误码/batch/notification，
  `McpHttpServerTest` 在真实端口上跑完 initialize → tools/list → tools/call、旧版 SSE、鉴权
  401/200、CORS、stop 后端口关闭；`ToolBoxTest` 覆盖权限裁剪与删链三道保险。
* **未在 arm64 真机与真实 MCP 客户端联调**（沙箱不启模拟器）；端口随 App 进程存活，退到后台
  被系统回收时会一起关闭，排障需保持前台。
* 局域网模式强制 token，但这是**护栏不是沙箱**：完全访问档下客户端能改动设备上任何 App 有权
  改动的东西。

---

## 16. 0.24.0 → 0.26.0：多核解耦、启动提速、以及「细化错误而非堆日志」

本节记录的三个版本都遵循同一条纪律：**先云端实测证明，再写入记录**（用户要求）。所有云端
产物在 `cloud-verify-025/`，真机语料为荣耀 90 导出的 `hackios-logs-20261104-205600.txt`
（4022 行，含 459 行错误特征）。

### 16.1 0.24.0：六核 MTTCG（把「多核必慢」的旧结论纠正过来）

旧结论「TCG 下 1 核最稳」只在**没开 `thread=multi`** 时成立：不开它，QEMU 用一条宿主线程
轮询所有 vCPU，多给核心只会放大锁竞争。新增 `engine/QemuTopology.kt` 统一生成
`-smp N,sockets=1,cores=N,threads=1` + `-accel tcg,thread=multi,tb-size=...`，TB 缓存随核数
放大。云端 QEMU 8.2.2 实测 1/2/4/6/8 核全部稳定、QMP `query-cpus-fast` 报出对应 vCPU 数。
`QemuPreflight` 把 ram/payload 分离缓存，改核数只重建 payload、不重拷 2.5 GB `ram.img`。

### 16.2 0.25.0：FAST 启动 + 自适应取帧（针对真机「慢、空转」）

核对 APK 内 `hackios-scripts.zip` 后确认：**链路本身几乎完整**，之前 `tools/cloud_verify.py`
的「缺 rootfs / 进不了桌面」是它自己用 `HACKIOS-NO-ROOT-APFS` 假占位导致的 panic，不是产品
缺陷。真机日志证明已进入用户态（Darwin Kernel 22.5.0 → launchd → SpringBoard →
System shell connected → OpenGL ES draws）。慢的根因是：`REST=1` 的作业风暴、`cpu_test 240 &`
压测、固定 8 FPS × 8 MB PPM 的 `screendump` 与 TCG 抢核（真机单次取帧 76430 ms）。

* `FAST=1`：精简桌面作业集（16 个 com.apple.* 必需作业）+ `QUIET=1` + `rc.jobs.fast`（去掉
  `cpu_test`）+ 不跑 `mkjobs.py --rest`。
* **修复真实产品 bug**：`mkjobs.py` 的 rest 分支里 `for name in ('RWA_PACKED16',)` 覆盖了
  循环变量，导致 `open(dest/name)` 把 ~60 个按需作业反复写进同一个叫 `RWA_PACKED16` 的文件，
  `--rest` 静默失效。修后 REST 档实打实生成 82 个 plist（22 主 + 60 按需）。真机日志里的
  `<Error>: Failed to bootstrap path: .../RWA_PACKED16, error = 108: Invalid path` 正是这个 bug
  的可见证据。修复随 `hackios-scripts.zip`（sha256 `f84f74f2…`、157 条）重新打包。
* `engine/FramePacer.kt`：引导期 2 FPS / 活跃 8 FPS / 静止 1 FPS / 慢取帧退避；
  `QemuControl` 复用 PPM/Bitmap 缓冲，消除每帧 8 MB + 11 MB 分配造成的 GC 抢核。
* 阶段 7 门槛从 `changedFrames >= 3` 放宽到 `>= 1`（2 FPS 下等 3 次变化会永远停在 6/7）。

### 16.3 0.26.0 阶段 A：Guest vCPU 与宿主算力池解耦

用户提出「挂八个处理器、只告诉 QEMU 两个、但这两个有五核以上性能」。精确化后是**两个正交
维度**（0.24.0 把它们绑死了）：

| 维度 | 术语 | 谁在看 | 作用 |
|---|---|---|---|
| Guest vCPU | `-smp 2,sockets=1,cores=2,threads=1` | XNU | SMP bringup 最稳 |
| 宿主算力池 | `tb-size` 规格 + CPU 亲和性掩码 | 宿主调度器 | 吞吐、减少 App/TCG 抢核 |

诚实的物理边界：MTTCG 下每条 vCPU 恰好一条翻译线程，**2 个 vCPU 不能并行吃满 8 核**。真实
等价实现是「vCPU 降到 2 空出 6 核给 App 取帧 + TB 缓存按 8 核规格放大（384→1360 MiB）+ 亲和性
池放开到 0-7」。设置页新增两个滑块（Guest vCPU / 宿主算力池），`QemuAffinity` 做**先探测再
启用**的降级（Android bundle 里没有 `taskset`，cpuset 也可能不允许普通 app 调
`sched_setaffinity`，探测失败就跳过包装、如实记 `affinity_applied=false`，绝不让 QEMU 起不来）。

云端 `qemu_decoupled_probe.py` 16/16 PASS：两档 vCPU 恒为 2、亲和性掩码 0-1 / 0-7 落到全部
QEMU 线程、TB 缓存随算力池放大、串口走到 XNULOADER/Darwin Kernel、帧缓冲可取真实帧。

### 16.4 0.26.0 阶段 B：启动诊断日志（结构化、密度 ≥3 倍）

`engine/BootDiagnostics.kt` + `BootDiagnosticLogger.kt`：每轮采样输出 PHASE / SERIAL / FRAME /
CPU / TIMELINE / GUEST / HEALTH 七个维度行 + 事件行，落 `run/boot-diagnostics.log`，日志页
`LogSource.Boot` 可看、导出与 MCP `read_log(source=boot)` 可读。密度对照由 `legacyLines()`
精确复刻旧行为作基线，`density()` 用真实 `render()` 结果计算，单测断言 ≥3 倍且每行平均
≥4 个 `key=value` 字段（证明不是灌水）。

### 16.5 0.26.0 阶段 C：错误细化目录（**本轮，针对「不要只一味添加，要仔细细化错误」**）

用户明确指出：不要只堆日志，要**把错误讲透**，「不管出错在哪里都要仔细表示出来，而不是像
lsd 那样只有个 CPU 出错之类的」。核对真机日志后确认这个问题真实存在：459 行错误特征横跨
launchd persona、lsd entitlement、failed lookup、plist importer、bootstrap 无效路径、共享内存、
BiometricKit、HID、Android App 崩溃、取帧超时等十几类，却被 `BootPhase` 的 7 个 `String?`
压成寥寥几句；`lsd` 的 16 次 `denied attempt to escape CPU monitor` 被盖成一句「已知非阻塞
噪音」（用户看到的「CPU 出错之类」）；QEMU 退出甚至直接写「没有匹配到已知错误特征」。

`engine/BootErrorCatalog.kt`（纯 Kotlin、无 android 依赖、可 JVM 单测）：

* **27 条规则**，每条带稳定 id + 层级（FIRMWARE/KERNEL/LAUNCHD/DAEMON/GUI/HOST_APP/
  CAPTURE/QEMU/BUILD）+ 严重度（FATAL/BLOCKING/DEGRADED/NOISE/INFO）+ 是否阻塞 + 具体组件 +
  **WHERE / CAUSE / IMPACT / ACTION** 四段人话（根因讲机制、影响说挡不挡桌面、处置给可执行
  结论）+ 可选参数抽取。
* `lsd-cpu-monitor-entitlement` 的 CAUSE 明确写「**不是 CPU 故障**：iOS 用 CPU monitor 限制
  守护进程配额，lsd 反复查询缺失的 XPC 服务后试图 escape CPU monitor 放宽配额继续重试，但镜像
  未给它签 entitlement，内核拒绝 → 服务缺失/权限拒绝的连锁，与 CPU 算力或核数无关」；ACTION
  写「不要因此降核或重编镜像，FAST=1 已去掉大部分触发源」。
* `failed-lookup-missing-service` 抽出 `name=`（缺失服务名）、`requestor=`（发起进程）、
  `error=`（errno），聚合成清单，而不是一句「按需作业缺失」。
* `bootstrap-invalid-path-rwa-packed16` 直接关联 16.2 修的 mkjobs 变量覆盖 bug。
* `Session` 逐行 `ingest`，**一行可命中多条规则**（旧 `when` 只取第一个正是含糊的根源）；
  同类聚合计数、只在首次出现时落完整档案；含错误特征但没命中规则的行进 `unclassified`
  兜底，**逐条保留原文 + 层级猜测**，绝不再说「未知」。
* `classifyExit()` 把 QEMU 退出从「没有匹配到已知错误特征」换成观测到的类别数、最高严重度、
  阻塞点与处置；`QemuFailureClassifier` 的兜底分支同步改为按链路逐层排查清单。

接线：watcher 每轮把串口/崩溃/QEMU 增量喂给 Session → 新档案实时落诊断日志、推日志页、
刷新 `run/error-catalog.log` 完整快照；UI notes 显式置顶阻塞性错误；MCP 新增只读 `errors`
工具与 `read_log(source=errors)`；导出把错误档案排在最前；`perf-stats.json` 加错误摘要字段。
**做减法而非堆量**：459 行错误聚合成 24 类精炼档案，每类只在首次出现时写一份。

**验证**（全部在证明可用后才写入本节）：

| 证据 | 结果 |
|---|---|
| JVM 单测 | 254 项 / 32 类全绿（新增 `BootErrorCatalogTest` 13 项、`BootDiagnosticsTest` 6 项、`QemuFailureClassifierTest` +2、`ToolBoxTest` +2） |
| 云端链路验证 `verify_chain.py` | 28/28 PASS，assets sha256 `f84f74f2…`、157 条 |
| 云端错误细化 `verify_error_catalog.py` | **15/15 PASS，真机 4022 行日志覆盖率 99.5%（216/217）**，24 类、7 层级，报告见 `error-report.md` |
| 云端解耦探针 `qemu_decoupled_probe.py` | 16/16 PASS |
| APK | `/workspace/hackios-0.26.0-debug.apk`，`versionCode=30 / versionName=0.26.0`，`targetSdk=28`，133.6 MB，dex 含 `BootErrorCatalog` |

覆盖率首轮只有 77%，51 行错误只能兜底保留原文；逐条看完后补了 6 类规则（minisandbox 权限拒绝、
虚拟硬件服务不可用、输入属性缺失、显示/传感器硬件缺口、launchd plist lint、作业激活失败），
并把「setup assistant refused / GL_KHR_no_error / ListDetail_Exceptions」这些**正向进度日志**
从错误特征里排除（不把成功误判为错误），才到 99.5%。**没有为了让报告好看而降判定或改阈值**。

### 16.6 已知边界（如实记录，不用模拟结果替代）

* 桌面 / 设置 / 版本号的最终画面只能由用户真机实测：云端**没有真实 `system.apfs` /
  `root.apfs`**（只有脚本与 QEMU 骨架），参数层（6 核 MTTCG、解耦探测、帧缓冲）已实测，
  但完整 iOS 桌面不伪造帧。
* 本轮错误细化是**纯 Kotlin 代码**，随 APK 覆盖安装立即生效，**不依赖 `root.apfs` 重建**；
  这一点恰好绕开了 16.2 里 `IMAGE_REV=3` 的遗留风险（老用户升级后 FAST 作业集是否随镜像
  重建生效仍需真机确认——`StaleImages` 是唯一能强制重编的机制）。
* 云端为 x86_64 / QEMU 8.2.2 / 无 `/dev/kvm`，不是 Android/aarch64 补丁版 QEMU 11；
  `sched_setaffinity` 在真机普通 app 上可能被 cpuset 收紧，降级路径已在代码里如实记录。

---

## 17. 0.26.1：黑屏逐层诊断——「为什么有 logo 却没有桌面」

### 17.1 真机证据链（荣耀 90，日志 hackios-logs-20261104-225630 / -230859）

用户实测 0.26.0：屏幕页只显示 loader 画的 Apple logo，此后永久黑帧（亮度 0~2/765），
WindowServer 报告「屏幕 1125×2436 与 ramfb 一致」。逐层核对串口后，断点被钉死在三段证据上：

1. **显示协商是通的**：内核打印 `display 1125 x 2436 at 1fb6bb000`（boot framebuffer 的 guest
   物理地址，每行 4500 字节 = 1125×4 BGRA）；MiniIOMFB 打印 `display 1125x2436 on a panel of
   1125x2436 (4500 bytes per row)`，与 ramfb 一致。screendump 能看到 logo 也证明 ramfb → 宿主
   取帧 → UI 全链路通。
2. **SpringBoard 只提交了 2 帧**：`MiniIOMobileFramebuffer: swap 1/2 ... surface 3/4
   (1125x2436 'BGRA')`，然后 `2 frames in 11739 ms (0 a second)`——之后 7 分钟无一帧。
   backboardd 调用的 IOMFB selector 57/15/22/29/33/27/17/84/50/68/55 全部 not implemented。
3. **已提交的帧没进 ramfb**：swap 内容停在 IOSurface；QEMU screendump 读的 ramfb surface
   自 loader 画完 logo 后再无人写。

结论：这不是「没渲染」（IOSurface 80 项 0 失败、EAGL/OSMesa 正常），而是
**WindowServer swap 停摆 + 内核 MiniIOMFB 缺 IOSurface→boot framebuffer 的搬运**。
宿主侧（App/QEMU 参数）无法绕过这条链路。

### 17.2 0.26.1 落地的三件事

* **错误目录新增 2+1 条规则**（Kotlin + Python 同步）：`iomfb-swap-stalled`（BLOCKING，
  完整归因见上）、`iomfb-selector-not-implemented`（DEGRADED）、`xnu-swapfile-failed`
  （DEGRADED，`vm_swap_create_file failed` 属预期）。两份真机日志串口段覆盖率
  **224/224、217/217 = 100%**（含撕裂行 matcher、ps 进程列表白名单修正）。
* **黑屏直读探针** `engine/FramebufferProbe.kt`（纯 Kotlin，9 项 JVM 单测）：从串口解析
  display 行 → 桌面阶段连续黑帧时，自动用 monitor `pmemsave` 导出 boot framebuffer 内存 →
  BGRA 稀疏采样判读 → 结论写进 boot-diagnostics 的 `framebuffer-probe` 事件：
  - 非黑 → 画面就在内存里，可走直读显示（实验路径）；
  - 全黑 → 坐实内核缺 blit，需内核侧实现（`iokit/Kernel/MiniIOMobileFramebuffer.cpp`，
    swap complete + 拷贝），不在 App 仓库内。
  MCP `hmp` 工具同步放开 `pmemsave`（读写档 + 路径锁 run 目录）。
* **云端实测抓到真机必踩的坑**：HMP 对裸参数做表达式求值，`pmemsave 0x… /data/…` 里的
  `/` 被当除号，报 `invalid char 't' in expression`。**路径必须用双引号包裹**。
  `cloud-verify-025/fb-probe/verify_fb_probe.py` 在云端 QEMU 8.2.2 实测 5/5 PASS
  （导出长度、`xp` 读回一致、BGRA 采样口径、全黑判定、坏参数）。

### 17.3 如实记录的边界

* 内核源码不在 App 仓库，本轮**没有**实现内核侧 blit——如果探针判「全黑」，黑屏不会因此
  消失；探针的价值是把「往哪个方向修」从猜测变成判定。
* `app-glasmio-crash`（点「停止 iOS」闪退）：反编译 glasmio-glass 0.1.1 确认 LocalBlurBackdrop
  默认值读取即抛；0.25.0 已把 3 处对话框调用移进 Host 覆盖内，真机 0.26.0 会话未复现
  （hits=9 全为历史会话）。规则 ACTION 已更新为「结构修复就位、复现时移调用点」。
* 版本：versionCode=31 / versionName=0.26.1；263 项 JVM 单测全绿（33 类）；
  云端 verify_error_catalog 15/15、fb-probe 5/5。

---

## 18. 0.26.2：黑屏探针的两处缺陷修复（纪律：不堆日志、触发要准）

### 18.1 修的两个缺陷（来自真机教训）

0.26.1 的直读探针逻辑是对的，但有两个会被「荣耀 90 长期挂机」放大的缺陷，违背
「不管出错在哪里都仔细表示、不一味堆日志」的纪律：

1. **`fbProbeNote` 每轮重复刷**：探针只跑一次、结论只算一次，但 note 在 `emitBootDiagnostics`
   里每轮都被注入到 `events`，导致同一句结论在诊断日志里重复 465 条（每采样轮一条）。
   → 加 `fbProbeNoted` 标志：note 只在第一次产出时注入一次（`events += listOf(
   "framebuffer-probe $fbProbeNote")`、`fbProbeNoted=true`），后续轮次跳过。
2. **触发时机太早**：原条件 `stage >= 5 && mean<=BLACK_MEAN && blackStreak>=5 && guestDisplay!=null`
   在 16.6s 就可能触发——那时 SpringBoard 可能还没画第一帧，黑是正常启动过程，会误报。
   → 收紧为 `blackStreak >= 10` 且「当前阶段已停留 ≥30s」（`now - diagStageSince >= 30_000`），
   把判定窗口推到「该画而不画」的区间。
3. **重启状态漏清**：`fbProbeNoted` 没在每次启动重置，停止再启动后探针会重算但 note 永不重发。
   → 在启动重置段补 `fbProbeNoted = false`（与 `fbProbeDone=false`、`fbProbeNote=null` 并列）。

### 18.2 验证（先实测再落档）

* `./gradlew :app:testDebugUnitTest --tests …FramebufferProbeTest`（JDK 21）：**BUILD SUCCESSFUL**，
  `compileDebugKotlin` 对 `AppViewModel.kt` 改动零报错，探针 9 项 JVM 单测通过。
  > 环境注意：系统默认 `java` 是 Zulu 20，而项目 toolchain 要求 21；测试 JVM 用 20 跑 21 编出的
  > 类会报 `class file version 65.0 … up to 64.0`。必须用
  > `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` 运行 gradle，否则单测必挂（与代码无关）。
* 云端 `cloud-verify-025`：`verify_error_catalog.py` **15/15 PASS**、`fb-probe/verify_fb_probe.py`
  **5/5 PASS**（pmemsave 往返、BGRA 口径、全黑判定与 Kotlin 同步）。

### 18.3 诚实边界（不变）

探针仍是**判定工具**不是**修复工具**：它把「黑屏是 ramfb 没接上」还是「内核 MiniIOMFB 没 blit」
钉成一条结论，但黑屏本身要内核侧 `MiniIOMobileFramebuffer.cpp` 实现 swap complete + 拷贝才能消失，
不在 App 仓库内。本仓库能交付的是「干净、单次、可复核」的诊断，而非绕过内核的显示。

* 版本：versionCode=31 / versionName=0.26.2；交付 `hackios-0.26.2-debug.apk`。

---

## 19. 0.26.3：lsd 卡死的真根因——mkjobs.py RWA_PACKED16 变量覆盖 bug

### 19.1 用户现象与一句话结论

用户反馈「为什么一直卡在 lsd」。结论：**lsd 不是根因，是症状**。真正的根因是 `mkjobs.py`
的 rest 分支里 `for name in ('RWA_PACKED16',):` 把外层循环变量 `name`（本该是 plist 文件名）
覆盖成字面量 `'RWA_PACKED16'`，随后 `open(f'{dest}/{name}')` 把**每一个**按需守护进程都写进
同一个非法文件 `/redwood/LaunchDaemons/RWA_PACKED16`。launchd 试图把这个非法文件名当 plist
bootstrap，报 `error=108 Invalid path`；而 mobileactivationd / securityd / personad / mobileassetd /
configd / commcenter / accountsd 等**真正的 plist 压根没生成** → 它们在 launchd 里从不注册 →
lsd 查询 `com.apple.mobileactivationd` 永远 `No such process`，反复重试形成风暴 —— 这就是用户看到的
「卡在 lsd」。

### 19.2 实测证明（先证明再落档）

`cloud-verify-025/mkjobs_fix/verify_mkjobs_fix.py` 真实运行 mkjobs.py `--rest`：
- **带 bug 版**：只生成 `RWA_PACKED16` 单个非法文件，per-daemon plist 一个都没有（`plists=[]`）。
- **修复版**：正确生成 `com.apple.mobileactivationd.plist / personad.plist / securityd.plist`，
  不再生成 `RWA_PACKED16`。
行为差异与真机 23:47 日志**完全一致**（日志里 `ls -l /redwood/LaunchDaemons/RWA_PACKED16` 与
`Failed to bootstrap path ... error = 108` 是铁证）。

### 19.3 关键事实：修复早就写进仓库资产，但没进用户测的那版 APK

- 仓库 `app/src/main/assets/hackios-scripts.zip` 内的 mkjobs.py **早已是修复版**
  （rest 分支写 `{label}.plist`），build.sh 也带了 0.25.0 的 `FAST→REST=` 处理。
- 但用户 23:47 测的 0.26.1 APK 是用**更早带 bug 的资产**打的包（apkpeek 提取与 artifact 脚本树
  md5 均为 `15035f15…` 的带 bug 版），所以 `RWA_PACKED16` 仍在。
- 0.26.2 APK 已含修复版脚本（md5 `d3608fe…`，与仓库资产一致）—— 也就是说「fix」其实在 0.26.2
  就交付了，用户卡住是因为**旧缓存镜像（用 bug 脚本构建的 root.apfs）仍被复用**。

### 19.4 本版做的三件事（交付侧确保修复真正落地）

1. **强制镜像重建**：`Constants.IMAGE_REV` 3→4。已发布的旧镜像被打上 `RWA_PACKED16` 缓存，
   不升 REV 用户装了新 APK 也可能继续复用旧 root.apfs；升 REV 后旧镜像被拒绝、用修复版脚本重建。
2. **部署期自愈补丁**：`AssetDeployer.patchMkJobs()` 在脚本解压后就地修一行
   （写 `{label}.plist` 而非被覆盖的 `{name}`），`LAYOUT_VERSION` 12→13 强制旧安装重新解压+自修。
   幂等：已修复的文件里没有那行 bug，补丁是 no-op，不会破坏正确版本。
3. **错误目录如实改写**（此前是虚假判定）：
   - `bootstrap-invalid-path-rwa-packed16`：严重度 DEGRADED/非阻塞 → **BLOCKING/阻塞**；
     cause 去掉「0.25.0 已修」的过期说法，改为「bug 在打进 0.26.1 的脚本里仍在，0.26.3 真修」，
     并把「lsd 卡死」的整条链（RWA_PACKED16 → 守护进程未注册 → lsd 查 mobileactivationd 失败）讲透。
   - `failed-lookup-missing-service`：去掉「FAST 精简掉非必需服务」的误导，明确与 RWA_PACKED16
     同现时是 BLOCKING 回退、否则才可能是良性。
   - `lsd-cpu-monitor-entitlement`：去掉「已知噪音/不阻塞」的敷衍，定位为缺失守护进程链的**次生
     症状**——根子在 mobileactivationd 没注册，不是权限本身，更不是「CPU 出错」。

### 19.5 诚实边界（不变）

- lsd 卡死 ≠ 黑屏。黑屏是另一条独立链路：内核 MiniIOMFB 没有把 IOSurface 内容 blit 到 boot
  framebuffer（见第 17 节）。两条链路不能混为一谈：修好 mkjobs 让按需守护进程正常注册，lsd 风暴
  消失、激活/安全/persona 链完整，但黑屏仍需内核侧实现 swap complete + 拷贝。
- 本仓库修的是**用户态镜像构建脚本**；内核不在 App 仓库内。0.26.3 让「启动该跑起来的守护进程
  都跑起来、不再卡在 lsd」，但桌面能否真正出画仍受限于第 17 节的内核 blit 缺口。

* 版本：versionCode=33 / versionName=0.26.3；交付 `hackios-0.26.3-debug.apk`。

---

## 20. 0.26.4：lsd 风暴没有真正修掉（诚实更正第 19 节）

### 20.1 用户实测打脸：风暴还在
0.26.3 交付后用户在荣耀 90 上仍看到串口刷屏，截图证据是连续十几行：
`process lsd[21] denied attempt to escape CPU monitor (missing required entitlement).`
0.26.3 日志原始串口计数：**`escape CPU monitor` 22 行、 `failed lookup com.apple.mobileactivationd` 5 行**，
镜像 LaunchDaemons listing 里**搜不到 mobileactivationd.plist**。

### 20.2 根因（纠正第 19 节的乐观结论）
- 第 19 节说「升级 0.26.3 重编镜像，mobileactivationd 注册后 lsd 查询与拒绝自然消失」——**这是错的**。
  0.26.3 日志证伪了它：RWA_PACKED16 bootstrap 失败确为 0（mkjobs.py 的 plist 生成 bug 修好了），
  但 mobileactivationd **仍不注册**，lsd[21] 照样查不到、照样忙等自旋、照样每轮吐一条 CPU monitor 拒绝。
- 所以 lsd 不是「修好了」，只是从「全守护进程缺失风暴」换成了「mobileactivationd 缺失引发的
  CPU 限额拒绝风暴」。这是真实可见的刷屏，不是无害噪音——第 19 节把它轻描淡写成「次生症状/非阻塞」是失实的。
- 真正的根子：该精简镜像（FAST=1，仅 16 个桌面必需作业）**根本没把 mobileactivationd 纳入**，
  而 activation 链缺失让 lsd 永远重试。

### 20.3 本版做的（App 侧能做的）
1. **串口渲染去重折叠**（`ui/screens/LogsScreen.kt`）：新增 `collapseRepeats()`，
   把连续相同行合并为 `原始行  (×N)`。只折叠「显示用列表」，`_logLines` 状态与磁盘 `serial.log`
   不动，复制/导出仍是完整原始行。视觉风暴消除，且不丢信息。
2. **错误目录如实改写**（`engine/BootErrorCatalog.kt` 的 `lsd-cpu-monitor-entitlement`）：
   去掉「0.26.3 即消失」的虚假断言，改为「App 侧折叠消风暴 + 根除需 guest 侧提供 mobileactivationd」。

### 20.4 诚实边界（不变，且更重要）
- App 侧只能**消视觉风暴**，不能让 lsd 停止自旋——自旋是 guest 行为，根在 mobileactivationd 未提供。
- **根除这条拒绝必须改 guest 镜像**：把激活链守护进程（mobileactivationd 等）纳入精简作业集 / 补对应
  plist 与 entitlement。那是 guest 侧改动，**不在本 App 仓库内**。
- 不要为「降刷屏」给 lsd 补 entitlement：那只让它自旋更快，不修复激活链断。
- 0.26.4 不解决黑屏（内核 MiniIOMFB 未 blit，第 17 节）；它只解决「串口刷屏看不清」这一层。

* 版本：versionCode=34 / versionName=0.26.4；交付 `hackios-0.26.4-debug.apk`。

---

## 21. 跨仓库排查：黑屏与「其他问题」的真相（HackiOS / eShard / ChefKissInc）

> 本轮只做调研与日志排查，**未重编镜像、未重编内核**。用户明确要求「不想再一直编译镜像」。

### 21.1 先纠正一个我自己的误判
一度怀疑 `virtual-hw-service-unavailable`（47 次）是黑屏元凶。重读目录 IMPACT 后确认是**误报**：那些是 CoreAccessories / oscard / Pearl(TouchID) / wifid PPM 等配件 / TouchID / Wi-Fi 虚拟服务 stub，**不参与桌面绘制**。目录判定（非阻塞）正确，黑屏与它们无关。

### 21.2 上游三仓库对照结论
1. **HackiOS 参考实现（OOSEX GANG，redwood/POCO X5 Pro，与本工程同根）**：锁屏 + 完整 UI **能出来**。显示链路 = Mesa/freedreno(Adreno) → OpenGL ES → CoreAnimation → IOSurface → IOMFB 替代驱动 → boot fb。结论：**黑屏不是无解，是「本构建的 IOMFB 替代驱动实现不完整」**。
2. **eShard（alephsecurity/t8030，iOS 14）**：渲染栈活 + 黑屏 = 合成器没把画面写到 framebuffer；framebuffer 物理地址**可写**（写 ARGB 能上屏）。他们的修法：把 DTB chip-id 从 t8030 改成 t8015，规避「iPhone 11+ 把 surface 压缩传给 GPU」导致 framebuffer 收不到未压缩帧。**单点失效 = WindowServer 的 IOSurface → boot fb 的 swap/blit 路径**。
3. **ChefKissInc/Inferno**：现代 iOS 在 QEMU 出画面要靠软件渲染 / 帧缓冲回退（关掉 GPU 路径）。**本工程已用 `gl=1` + OSMesa 做到**（`EAGL/Open GL ES (OSMesa) 正常`）。

### 21.3 本工程黑屏的精确定位（来自 01:01 真机日志）
- boot-args：`debug=0x8 serial=3 rd=md0 loader_return_after=1200 cpus=2 virtio_input=1 gl=1 fbscale=3 plain_vibrancy=1` —— **gl=1 已开，OSMesa 软件 GL 正常**；`redwood-gl` 的 `EAGLContext` 方法替换已加载（GLES 渲染路径由注入库接管且工作）。
- 渲染栈全活：IOSurface 80 项 0 失败、EAGL/Open GL ES (OSMesa) 正常、WindowServer 活着、`SpringBoard 正在用 OpenGL ES 绘制`。
- **单点失效**：WindowServer 只提交 2 次 swap（`2 frames in 16916 ms`）就停摆；backboardd 调用的 **MiniIOMFB user-client selector 未实现**：`15, 17, 22, 27, 29, 33, 50, 55(×2), 57(×5), 68, 84`。合成好的 IOSurface 从未被拷到 boot fb（0x1fb6bb000），FramebufferProbe 读 mean=0 → BLACK。

### 21.4 日志里「其他问题」的排查结论
| 规则 | 次数 | 结论 |
|---|---|---|
| virtual-hw-service-unavailable | 47 | 误报，非阻塞（配件/TouchID/WiFi stub，不参与绘制） |
| persona-type6 | 64 | 已知噪音（本移植无 kpersona），非阻塞 |
| input-property-missing | 32 | **真问题（轻）**：事件服务器要 `Hidden/MaxHoverHeight/QueueSize/ReportInterval` 等属性，虚拟输入驱动没有 → 触屏属性缺口 |
| xnu-swapfile-failed | 2 | 交换文件失败，潜在内存压力，当前非阻塞 |
| job-activation-failed | 1 | 有 1 个 launchd 作业激活失败，需查具体 job |
| entitlement | 38 | 多为 lsd 风暴附带 |
| iomfb-selector-not-implemented | 12 | 黑屏根因的一部分（见 21.3） |

### 21.5 诚实的「不重编」评估
- **黑屏 = 内核驱动缺口**（hackios-xnu-kernel 的 `MiniIOMobileFramebuffer.cpp` swap 路径）。**没有任何 boot-arg / QEMU 开关能「实现」一个未实现的内核驱动函数。** 必须改内核并编译一次。
- **lsd 风暴 = 镜像缺口**（FAST 镜像没含 mobileactivationd）。需重编镜像（用户不想做 → 0.26.4 已用视觉折叠兜底）。
- 两者最终都要「编点什么」。用户「不想一直编译镜像」的合理诉求 = **停止「编→测→又编」的循环**，改为**一次性 consolidated 构建**：内核补完 IOMFB swap + 镜像纳入 mobileactivationd，**编一次、校验一次**。
- 本工程 OSMesa 软件 GL 路径已通、GLES 渲染已活，缺的只是内核侧把 IOSurface→boot fb 的搬运补上。

### 21.6 推荐下一步（不需再编镜像即可先做）
1. **App 侧 pmemsave 写测试图案到 0x1fb6bb000**：FramebufferProbe 已能读（mean=0），加一个写变体，证明 App→QEMU→ramfb→屏幕 的写入通路是活的（不依赖内核驱动）。图案上屏 → 写入通路通，黑屏纯粹是内核未搬运；不上屏 → ramfb surface 地址不匹配，是另一类问题。纯 App 侧、零重编。
2. **把 21.3 的 selector 清单 + boot-args 写进 `iomfb-swap-stalled` 的 ACTION**，给内核侧一份精确待实现列表（`15,17,22,27,29,33,50,55,57,68,84`）。
3. **一次性内核构建**：把参考 HackiOS 的完整 IOMFB 替代驱动的 swap 实现移植进 hackios-xnu-kernel，与「镜像纳入 mobileactivationd」合并为一次构建校验，终结重编循环。

* 本节约等于调查结论；未做任何镜像 / 内核重编。

---

## 22. 0.26.5：扩大上游参考面 + 把「非常完善的 QEMU」落到施工单

> 用户要求「去参考更多项目，下一轮我要见到非常完善的 qemu」。本轮把参考面从 HackiOS/eShard/Inferno
> 扩到 **dipa-studio/ios-sweet**（同代 iOS 16.7、已出主屏、开源内核补丁）、qemu-t8030、alephsecurity、
> ChefKissInc/Inferno、AsahiLinux(IOMFB)，并把结论落成两份施工单 + 一份 App 侧改动。

### 22.1 关键新发现：ios-sweet 的 `SweetFramebuffer.cpp` 就是本工程缺的那段

- ios-sweet 用 `AppleCLCD : IOMobileFramebuffer`（`iokit/Kernel/arm/SweetFramebuffer.cpp` + `SweetSurface.cpp`）
  把**面板物理内存**注册成默认 IOSurface，swap 时把启用图层的 surface **CPU memcpy** 到面板内存；
  vsync 定时器按 `sweetfb_hz`（默认 10Hz）节流呈现，避免 60Hz 全帧拷贝拖垮单核。
- 其 swap record 布局与本工程**逐字节一致**：`rec +0x50`=交换ID、`+0x54+4*layer`=图层 surface id、
  `+0x64+16*layer`=源矩形、`+0xc4+16*layer`=目标矩形、`+0x104`=启用图层掩码、总长 `0x46c`(1132B)——
  与 01:01 日志 `SwapEnd (5, 1132-byte struct)` 完全吻合。**这坐实了黑屏根因就是缺这段 memcpy**。
- 本工程的 ramfb 物理内存正是 `0x1fb6bb000`，内核一旦把合成帧拷进去，现有 `screendump` 抓帧链路
  自动恢复，**App 侧显示代码无需改**。即：缺的是内核里一个 memcpy，不是 QEMU 配置。

### 22.2 本轮落地的 App 侧改动（0.26.5，零重编即生效）

1. **错误目录 `iomfb-swap-stalled` 的 ACTION 改写为精确内核移植清单**：含 swap record 布局、按 id 取
   surface→`memcpy` 到 `fPixels`、面板内存发布为默认 IOSurface、vsync 节流、需补齐的 selector 全表
   （3/4/5/6/12/74/79 + 已报 not-implemented 的 57,15,22,29,33,27,17,84,50,68,55）。cause 段补上
   ios-sweet 同代反证。
2. **稳定性 boot-args（经 ios-sweet 验证）写入托管 cmdline**：`wdt=-1 wdt_test=no_user_checkin`
   （关看门狗，避免慢启动被误杀）、`launchd_unsecure_cache=1`（让 mobileactivationd 等守护进程在精简
   镜像里也能注册，直指 lsd 风暴根因）。均为 XNU 忽略未知键的安全参数。
3. **`QemuPreflight.CMDLINE_PROFILE` 3→4**：让旧 payload 失效、重烘新 cmdline（对应 QemuRunner 改动）。
4. **版本 0.26.4→0.26.5**（versionCode 34→35）。

### 22.3 本轮产出的两份施工单（给下一轮一次性构建）

- `docs/kernel-iomfb-port.md`：**代码级内核移植指南**。含 ios-sweet `AppleCLCD::swap()` 的逐行拷贝逻辑、
  surface 表查表范式、vsync 节流、selector 补齐表、显示尺寸勿回退说明、镜像侧 mobileactivationd 纳入、
  以及一次性构建计划。这是让屏幕出画的唯一硬施工单。
- `docs/research-very-complete-qemu.md`：跨仓库调研综合（6 个项目对照表 + 黑屏单点失效定位 +
  「非常完善的 QEMU」组件清单 + 合并构建建议）。

### 22.4 诚实边界（不变）

- 内核 swap/blit 与镜像 mobileactivationd 纳入**必须编译一次 iOS 内核/镜像**，属于 hackios-xnu-kernel
  仓库，本 Android 仓库与当前沙箱都无法代编（无 macOS CI / Apple SDK）。本轮交付的是精确施工单 +
  App 侧已全部能做的部分；「下一轮一次性构建」按两份施工单执行即可终结重编循环、见到完整 QEMU。
- App 0.26.5 本身不会让黑屏消失（那要内核补丁）；它的价值是：boot-args 让下一轮内核构建的镜像更容易
  存活与注册守护进程，且报错目录已能给内核构建方一份可逐条勾选的清单。

* 版本：versionCode=35 / versionName=0.26.5。

## 23. 0.27.0：跨仓库调研落成一次性合并构建 + 4 项 UI 全部落地

（2026-11-05，用户要求「不想经常重新编译，一次性做好并合并入所有修改；继续扩大搜索范围，借鉴更多项目」）

### 23.1 决定性发现：上游 HackiOS 是双仓库，且本地就有其真实构建脚本

* 上游结构：`hackios-xnu-kernel`（内核补丁/EFI loader/UEFI/QEMU 脚本/预编内核）+ `hackios-xnu-userland`
  （init、注入库、launchd 作业、APFS 镜像构建器）。本 App 只是把它们的产物当 CDN 资产部署。
* `/workspace/cloud-verify-025/work/userland/` 存有上游 `build.sh`(67KB)/`mkjobs.py`(30KB)/`rc.jobs.*` 的
  **真实本地副本**，逐行核查后拿到两个此前只能推测的答案。

### 23.2 lsd 风暴的最终根因与一行修复（修正第 19/20 节的认知）

* `build.sh` 的 **FAST `JOBS` 列表（139–146 行）漏列 `com.apple.mobileactivationd`**。
* `mkjobs.py` 的 DENY 名单早已把 mobileactivationd 放出来（注释原文：「mobileactivationd came out of this
  tuple … With it in, started on demand: no failed lookups」）——即 plist 生成能力一直在，只是 FAST 模式
  清空 `REST` 后**没有任何调用方把它传给 mkjobs.py**。
* 非 FAST 模式靠 `--rest` 把全部 iOS 守护进程（含它）拉进来，所以**只有 FAST 缺它**。
* 修复 = FAST `JOBS` 列表加一行 `com.apple.mobileactivationd`（已写入 `docs/one-shot-build.md` §1 的 diff）。
  这在 guest 内根治风暴，远优于 App 侧折叠（0.26.4 的 `collapseRepeats` 保留为兜底）。
* 教训：第 19 节说「0.26.3 重编即消失」、第 20 节说「根除需 guest 侧提供」都对了方向但没落到**哪一行**；
  这次落到了文件与行号。

### 23.3 黑屏的正确修法改为「rebase 上游」，废弃「从 ios-sweet 重写」

* 上游文档明说 QEMU(virt) 能出锁屏（「lock screen appears after 2–3 minutes」），即上游内核已有可用的
  MiniIOMFB ramfb swap 提交。我方黑屏应视为**相对上游的落后/回归**。
* 行动改为：`hackios-xnu-kernel` rebase 到上游带 QEMU 显示的提交；`build.sh:902` 也确认了驱动源码位置
  （`iokit/Kernel/MiniIOMobileFramebuffer.cpp`）。ios-sweet 的 swap record 布局（0x46c/1132B）降级为兜底。
* 这正对应用户的判断：「人家做好的比你重新开始做要好的多」。

### 23.4 新增参考项目（本轮扩面）

* **samhenrigold/qemu-ios**（Light Touch）：主屏+显示+触屏+音频+OpenGL ES 宿主 GL 桥全通的范本；
  其 guest 值边界检查纪律（IT_IDX/IT_SIZE、guest 可达路径禁 exit/abort）值得抄进任何新增的 guest↔host 通路。
* **UTM/beybeyzk qemu**：`virtio-ramfb` 把帧缓冲 VRAM 暴露进 BAR——取帧性能的可选优化，不阻塞出画。
* 以上与既有 ios-sweet/eShard/Inferno/qemu-t8030/alephsecurity 的对照表见 `docs/compare-reference-projects.md`。

### 23.5 一次性合并构建清单（本轮核心交付）

`docs/one-shot-build.md`：四个组件（App / userland 脚本 / 内核 / QEMU bundle）的全部修改列成一份施工单 +
一次性构建顺序。诚实边界：本沙箱只能编组件 1 与验证组件 2 的 Python 逻辑；组件 3/4 需 HackiOS 的
macOS/NDK CI。此后不再反复重编——出问题按 `framebuffer-probe` 与 `iomfb-swap-stalled` 的 ACTION 定点回查。

### 23.6 App 0.27.0：4 项 UI 全部实现（用户最早提出，本轮合并）

1. **资源页**：无「所有文件访问」权限时，下载按钮变为「授予储存权限」（橙色），点击直接拉起授权
   （`LibraryActions.requestStorage` → `vm.requestStoragePermission()` → 既有 `permissionPending` 链路）。
   权限在手则照旧「下载固件/校验」。
2. **MCP 权限弹窗**：「允许修改与执行」与「完全访问」两个确认框的**取消按钮改为不关闭**（保持打开，
   仍可点弹窗外关闭）——授权必须显式做出决定。
3. **日志页**：新增「清空」按钮；QEMU 运行时弹窗警告「这一轮启动的过程日志将看不到，建议先导出」，
   确认后内存五个环形缓冲 + `_logLines` + 磁盘 `serial.log`/`qemu.log`/`app.log`/`crash.log`/
   `error-catalog.log`/`boot-diagnostics.log` 与 `build-logs/*` 一并归零（`vm.clearLogs()`）。
4. **构建后清理**：`BuildUi.Done` 置 `cleanupPrompt=true`，立即弹窗询问是否清除 5+GB 中间产物
   （rootfs/rootfs.slt/system/prebuilt/payload/ram.img/build-logs）；**最终 system.apfs/root.apfs 不动**；
   「保留」与「清除」都在 ViewModel 落状态，不再静默。

* 版本：versionCode=36 / versionName=0.27.0。编译通过、JUnit 263/263 全过（0 失败）。
* 诚实边界：UI 行为未经真机手测，四项均已按现有组件（GlassActionButton/GlasmioAlertDialog）实现，
  上真机后如有交互细节需微调，属样式层而非逻辑层。
