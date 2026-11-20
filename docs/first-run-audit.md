# 即开即用审查：从安装到「看得见 iOS 桌面」

审查对象：`/root/workspace/hackios-next`（0.31.7，minSdk 33 / targetSdk 28，无 root）。
只读审查，未修改任何源码；唯一新增文件是本报告。

**证据口径**

- **实测（读过代码）**：结论直接来自下面列出的 `文件:行号`，我逐行读过。
- **实测（跑过）**：`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 ./gradlew :app:testDebugUnitTest --offline`
  → **355 项单测全通过**（默认 JDK 17 会因 class 65 直接报错，属开发环境问题，不影响真机）。
- **推断**：需要真机或进程生命周期才能证实，已单独标注。
- 按要求**没有**跑 10 分钟级的真机启动（`glpass-desktop.py` 那条线在用机器），所以所有「几秒到几分钟」
  的耗时数字都来自代码/文档里已有的真机记录，不是我这次测的。

**一句话结论**：这条链路的**单点工程质量很高**（6 GB 下载的续传/看门狗、编译的阶段续跑、启动的分类归因
都做得比同类项目细），但**没有一个「整条链」的产品层**：没有人对「下载→编译→启动」这 40–115 分钟负责，
没有总进度、没有自动接力、没有自动恢复；失败文案普遍指向**不存在的按钮**或**内部术语**，而真正的恢复
动作（切 OSMesa、重新编译）藏在另一页。最伤即开即用的不是慢，是「慢的时候看不出在等什么」和「失败了
要用户自己猜下一步」。

---

## 0. 首次到桌面：用户要点几次、等几次、可能失败几次

| # | 动作 | 位置 | 用户感知 |
|---|---|---|---|
| 1 | 关「性能提示」弹窗 | `ui/HackIosApp.kt:210` | 点 1 次 |
| 2 | 点「准备镜像」 | `ui/screens/DeviceScreen.kt:268` | 跳到资源页 |
| 3 | 点「授予存储权限」+ 系统弹窗（未授权时） | `LibraryScreen.kt:240`、`MainActivity.kt:44` | 点 1–2 次 |
| 4 | 点「下载固件」→ **等 6.17 GB** | `LibraryScreen.kt:243` | 无总时长预期 |
| 5 | 点「下一步：编译」→ **等 20–40 分钟** | `LibraryScreen.kt:233` | 卡片上才有分钟数 |
| 6 | 关「清除构建残留？」弹窗 | `ui/HackIosApp.kt:236` | 点 1 次 |
| 7 | 点「启动 iOS」→ **等约 10–15 分钟** | `LibraryScreen.kt:352` | 首次还含 2.5 GB ram.img 拷贝 |
| 8 | 等桌面（无完成提示） | — | 见第 18 条 |

**合计：点击 5–8 次，长等待 3 段（下载 / 烘镜像 / 首次启动），可能失败的决策点 ≥ 7 个**
（权限、下载中断、编译失败、镜像 rev 不匹配、Adreno 材料缺失、端口残留、QEMU 早退）。
「点击 5–8 次」是实测（逐行读的调用链）；「≥7 个失败点」是实测（对应错误分支都在代码里）；
其中真正的**时间**只有「物理上只能等」，其余全部可以自动化或至少变透明。

---

## 1. 没有「一键准备」：整条链路是三个各管各的按钮，没人对总时长负责

- **现象**：装好后落到「设备」页，只有「准备镜像」；进了「资源」页要先下载、再点「下一步：编译」、
  编完再点「启动 iOS」。中间任何一步失败，另外两步的界面不会告诉你。
- **证据**：`ui/screens/DeviceScreen.kt:264-271`（`!imagesReady -> actions.openLibrary`）；
  `ui/screens/LibraryScreen.kt:231-234`（`"下一步：编译"`）、`:352-358`（`"启动 iOS"`）；
  `state/AppViewModel.kt:561`/`:738`/`:858` 是三个互不调用的入口，没有任何 `downloadFirmware → compileImages → startQemu`
  的编排；`grep -rn "首次" ui/` 只命中编译卡里的一句 20–40 分钟（`LibraryScreen.kt:425`）。
- **为什么破坏即开即用**：用户得自己记住「现在到哪一步、下一步点哪」，而这正是「装完要等很久」的体感来源。
- **建议最小修法**：在 `AppViewModel` 加一个 `prepareAndStart()`：`downloadFirmware()` 的 `onOutcome.Success`
  里直接 `compileImages()`，`compileImages` 的 Done 分支直接 `startQemu()`（同一函数内串联，不弹残留清理框）；
  `DeviceScreen` 的 hero 只留一个主按钮「准备好并启动 iOS」，副文案写「约 40–115 分钟，可随时离开本页」。
- **规模**：中。**必须人工**：否（等待本身只能等，但接力可以自动）。 **必须联网**：是（下载那一段；编译与启动不需要网络）

## 2. 失败提示让人点一个**不存在**的按钮：「重新部署资源 / 部署预构件」

- **现象**：多张错误卡写「点『重新部署资源』」，但资源页、设置页、设备页都没有这个按钮；用户翻遍 App
  找不到，只能重装。
- **证据**：`engine/QemuFailure.kt:72`（`"点「重新部署资源」让 App 从 APK 里重新解压 QEMU 与固件。"`）、
  `:100`、`:113`、`:193`；`state/AppViewModel.kt:904`、`:917`、`:1095`；
  `ImageBuilder.kt:511`（`请先点「部署预构件」`）、`:787`（`请重新部署资源`）。
  UI 里唯一叫这个名字的按钮只在 `AssetsMissing` 这一种失败上出现（`DeviceScreen.kt:162`，`onPrimary = actions.retry`），
  其余失败卡只有「重试 / 查看日志」（`:158-173`）。全仓 `grep "部署" app/src/main/java/com/android/gudao/iphoneos/ui`
  **零命中**。
- **为什么破坏即开即用**：文案把用户导向一个不存在的控件，比不给提示更糟——他会认为 App 坏了。
- **建议最小修法**：把所有「重新部署资源」文案改成实际存在的按钮名，或反过来：在「资源」页
  「工具链自检」那一行（`LibraryScreen.kt:452-457`）同时承担部署（`runDiagnostics` 里已经先调
  `deployer.deploy()`，见 `AppViewModel.kt:1892`），文案改「重新部署资源 / 自检」；并为
  `BundleIncomplete`、`MissingRom`、`LinkerError` 的失败卡统一加一个 `deployer.deploy()` 主按钮。
- **规模**：小。**必须人工**：否。 **必须联网**：否

## 3. Adreno 根镜像是**死循环式**失败：主按钮「重试」必然再次失败，恢复动作在两页之外

- **现象**（就是 0.31.4 那次事故的当前形态）：卡片标题「Adreno 根镜像不可用」，详情是「没有与当前镜像匹配的
  Adreno 备份或构建材料；请确认共享下载目录权限，或在『资源』页重新编译一次」，主按钮却是「重试」。
  这个条件是**静态的**，再点一次只会再抛同一句；正确动作（切回 OSMesa / 去资源页重编）没有任何按钮直达。
- **证据**：`state/AppViewModel.kt:986-991`（`prepareGpuRoot(root)` 失败 → `failQemu(...)`，`kind = Unknown` →
  默认 `retryable = true`）；`DeviceScreen.kt:163-170`（主按钮「重试」→ `actions.retry` → `retryQemu()`，
  `AppViewModel.kt:1268-1271` 重新走整个 `startQemu`）；`ImageBuilder.kt:781` 抛的是静态判断失败；
  `AppViewModel.kt:95` 的 `GPU_RECOVERY` 只是**文案**里说「可在首页切回 OSMesa」，
  `DeviceScreen.kt:172-173` 的次按钮是「查看日志」，没有「去切 OSMesa」。
  `retryQemu` 每次还会重跑 `GpuRuntime.prepare`（内含最长 25 s 自检，`GpuRuntime.kt:90-100`）。
- **为什么破坏即开即用**：这是「必须我自己点重试才继续」的根因，而且点了也没用——用户被卡在唯一的主按钮上。
- **建议最小修法**：`prepareGpuRoot` 抛 `BuildError` 时不要走 `failQemu`，改为直接
  `setExperimentalGpu(false)` + `toast("Adreno 根镜像缺失，本次改用 OSMesa 启动")` + 继续以 OSMesa 启动
  （此刻 QEMU 还没起，回退是安全的，也不会「静默」——有 toast + 失败卡历史）。若坚持不自动回退，
  至少把这张卡的主按钮换成「用 OSMesa 启动」，次按钮「重新编译镜像（约 20–40 分钟）」，把「重试」去掉。
- **规模**：小（改一个 catch 分支 + 两个按钮）。**必须人工**：否。 **必须联网**：否

## 4. 「版本升级税」：IMAGE_REV 一升就全量重编 20–40 分钟，因为成功编译后把解包删了

- **现象**：装新版 APK，若脚本变了（`IMAGE_REV` 变了），启动被拦下提示「镜像版本不一致 → 编译镜像」，
  而这次「编译」会**重新解包 7.4 GB**——尽管 `system.apfs` 还在、且 `build.sh` 只会重打几十 MB 的 `root.apfs`。
- **证据**：`Constants.kt:252`（`IMAGE_REV = 9`，注释记录了 3→9 的每一次提升）；
  `ImageBuilder.kt:948-975`（`cleanup()` 删 `rootfs/cryptex/*.dmg/…` 并 `checkpoint.reset()`）；
  `ImageBuilder.kt:176`（成功后调用 `cleanup()`）；`:130-146`（stage 标记失效时
  `forget(STAGE_EXTRACT)` → 重新解包）；`runBuild` 的注释 `:637-660` 说 build.sh 只在 packer 更新时重打
  system.apfs；`app/src/main/assets/hackios-scripts.zip → userland/build.sh:806-807` 的实际判据是
  「system.apfs 缺失 / stage0、manifest、cryptex 更新 / SYSTEM=1」才重打 2.5 GB 的 system.apfs，
  而 root.apfs 每次都重打（几十 MB，受 `GpuImageStore.MAX_ROOT = 48 MiB` 约束）。
  **也就是说：这次 20–40 分钟里，除了最后的 mkjobs+mkapfs，剩下的解包/fetch 全是可跳过的。**
- **为什么破坏即开即用**：每次 APK 更新都可能换来一次 20–40 分钟的等待，而这个等待里绝大部分是**不必要的重复**。
- **建议最小修法**：(a) 成功后保留 `ios165/rootfs` + `cryptex*`（约 9 GB），或在「清除构建残留」弹窗里
  默认「保留（下次重编省掉 7.4 GB 解包）」；(b) 在 `ImageBuilder.build()` 加一条 `rootOnly` 路径：
  `userland/system.apfs` 与已发布镜像一致时跳过 extract/fetch，只跑 `build.sh`（不设 `SYSTEM=1`），
  并把这条挂到 `QemuUi.StaleImages` 卡的「只重编系统组件（预计 1–3 分钟，待实测）」按钮上；(c) 卡片写清「为什么要重编」。
- **规模**：中（(b) 需要 ImageBuilder 增加一个 `rootOnly` 分支）。**必须人工**：否（等待可缩短、可解释）。 **必须联网**：否（复用已下载的 IPSW）

## 5. 编译「成功」但 Adreno 根失败：只在日志里留一行，且顺手清掉了可续跑的阶段标记

- **现象**：GPU 测试包编译完成，弹「镜像编译完成」+ 绿色 Done 卡；用户切 Adreno 启动时才炸（见第 3 条）。
  更糟：这次 Adreno 失败还会 `cleanup(keepStagedRoot = true)` → `checkpoint.reset()`，下一次编译**从头解包**。
- **证据**：`ImageBuilder.kt:161-177`（catch 里 `cleanup(keepStagedRoot = true)`、`progress.onLog("Adreno 根镜像生成失败：…；OSMesa 仍可启动")`、
  `return images` 当成成功）；`AppViewModel.kt:814-828`（`result.onSuccess` → `BuildUi.Done` + `toast("镜像编译完成")`）。
- **为什么破坏即开即用**：用户得到「成功」的错误信号，故障延迟到下一次启动才暴露，而且惩罚翻倍（重编）。
- **建议最小修法**：把 Adreno 结果带进 `BuildUi.Done`（加 `adrenoReady: Boolean?`），Done 卡多一行
  「Adreno 缓存：未生成（切到 Adreno 时会现场烘一次，几分钟起步且目前没有进度）」；catch 里不要 `checkpoint.reset()`，
  改为只清 `sysroot/root` 等大目录。
- **规模**：小。**必须人工**：否。 **必须联网**：否

## 6. 时间预期三处互相矛盾：设备页说 2–3 分钟，设置页说 180 秒，实测约 10 分钟

- **现象**：设备页脚注「性能达标的机型约 2–3 分钟到锁屏」；设置页「启动计时 · 预算 Logo 90s / 桌面 180s」，
  且未到达时写「未到达（预算 180s）」；而真实记录是「第 10 分钟才出主屏」，预算早已改成 60s / 600s。
  结果：**用户在第 3 分钟就认为卡死了**，第 10 分钟正常进桌面后，设置页还给他一个「✗ 超预算」的红色结论。
- **证据**：`DeviceScreen.kt:517-519`；`ui/screens/SettingsScreen.kt:231`（写死 `"预算 Logo 90s / 桌面 180s"`）、
  `:249`（写死 `"未到达（预算 180s）"`）；`engine/BootTimeline.kt:195-198`（`LOGO_BUDGET_MS = 60_000`、
  `DESKTOP_BUDGET_MS = 600_000`），`:17-19` 注释「0.29.6 实测：第 10 分钟才出主屏」；
  `app/build.gradle.kts` 0.29.7 注释「旧值 90s/180s 从未达成过」。设置页那两处是 0.29.7 改常量时漏改的硬编码。
- **为什么破坏即开即用**：错误预期会把「正常慢」变成「看起来坏了」，直接诱发用户杀进程/重试。
- **建议最小修法**：`SettingsScreen.kt:231/249` 改用 `BootTimeline.DESKTOP_BUDGET_MS` 与
  `LOGO_BUDGET_MS`（与 `:248` 已有的写法一致）；`DeviceScreen.kt:518` 改成
  「首次进入桌面通常 10–15 分钟（软件渲染，越弱的机器越久）」。
- **规模**：小（三行文案）。**必须人工**：否。 **必须联网**：否

## 7. 启动进度条是假的：7 个离散阶段，第 6 级一停 9 分钟；`Starting` 阶段既无时钟也无进度

- **现象**：启动时进度条 = `stage/7`，`阶段 6/7` 会占满 86% 约 9 分钟，用户看不到「还差多少」；
  更早的「准备引导负载（payload / ram.img）」阶段，进度条固定 6%，右侧连已用时间都没有。
- **证据**：`state/UiModels.kt:146-153`（`fraction = stage/7`）；`DeviceScreen.kt:253-257`
  （`phase?.fraction ?: if (starting) 0.06f else 0f`）；`DeviceScreen.kt:219-224`
  （只有 `running` 才显示 `Fmt.elapsed`，`Starting` 显示的是「TCG · OSMesa」）；
  `UiModels.kt:132`（`QemuUi.Starting` 只有一个 `step: String`，没有时间戳）。
  已有可用素材没用上：`BootTimeline` 记录了 deploy/prepare/spawn 三个 App 侧阶段与 600 s 桌面预算
  （`engine/BootTimeline.kt:34-45`）。
- **为什么破坏即开即用**：「看不出在干什么」的核心就是这个条——它既不前进，也不告诉你已经等了多久。
- **建议最小修法**：`BootPhaseUi` 加 `elapsedMs`/`etaHint`（用 `BootTimeline` 的 600 s 预算算
  「已等 6:12，通常 10–15 分钟」），`DeviceScreen` 的进度条在 `stage == 6` 时改成不确定态而不是 86%；
  `QemuUi.Starting` 加 `startedAtMs`，hero 右侧在启动中也显示 `Fmt.elapsed`。
- **规模**：小–中。**只能等**，但完全可透明。 **必须联网**：否

## 8. 切后台/锁屏：没有前台服务，只有下载有唤醒锁；编译卡自己在求用户「别离开本页」

- **现象**：6 GB 下载有 CPU+Wi-Fi 锁；**编译和 QEMU 运行没有**。编译卡片直接写「请插电并保持本页在前台：
  锁屏会让 CPU 降频，速度慢好几倍」。QEMU 用 `keepScreenOn` 顶着屏幕，但一旦按 Home / 锁屏、系统回收进程，
  QEMU 子进程会被留下占着 4444/4445，下次启动就变成「控制端口被占用」。
- **证据**：`app/src/main/AndroidManifest.xml` 全文只有 Activity + FileProvider，**没有 service、
  没有 `foregroundServiceType`**；`DownloadPowerLock` 只在 `AppViewModel.kt:638`/`:711` 的下载路径 acquire/release；
  编译路径（`:754-812`）与启动路径（`:901-1186`）都没有锁；`ui/screens/LibraryScreen.kt:330-335` 那句提示；
  `ui/HackIosApp.kt:84`（`keepScreenOn = running` 只在运行中生效）；
  `QemuFailure.kt:85-93`（端口被占用只给「强制清理」）。
- **为什么破坏即开即用**：40 分钟里用户不可能一直盯着同一页，一次后台就可能白等。
- **建议最小修法**：加一个前台服务（`dataSync` 类型，通知文案「正在编译镜像 / iOS 正在运行」），
  编译与 QEMU 期间 `startForeground`；无法加服务时至少给编译加 `PARTIAL_WAKE_LOCK`，并在通知/Toast 里
  提示「已转到后台，仍在继续」。
- **规模**：中。**必须人工**：否（但需要用户允许通知，属可接受的系统行为）。 **必须联网**：否
  进程被杀的后果是**推断**（没有真机进程死亡实验），manifest 里没有 FGS 是**实测**。

## 9. 下载断一次就得人工「继续下载」：一次换网就能打掉整单 6 GB

- **现象**：网络抖动/地铁隧道/Wi-Fi 切蜂窝超过约 1–2 分钟，某个分块耗尽 5 次重试 → 整个下载判定失败，
  UI 回到「继续下载」，人不在就永远停在那。它明明是断点续传的，却不肯自己续。
- **证据**：`FirmwareDownloader.kt:704`（`MAX_ATTEMPTS = 5`）、`:685-687`（退避 0.7/1.4/2.8/5.6/8 s）、
  `:511-520`（一个分块失败即抛出）、`:336-344`（`failure` 一旦被 CAS 设上就整体失败，其它 worker 白跑）；
  `state/AppViewModel.kt:694-707`（`Outcome.Failure` 只更新 UI，不重试）；
  好的一面：失败文案里带了「已完成 N/M 个分块并保留，可继续下载续传」（`:338-343`）。
- **为什么破坏即开即用**：把「网络问题」升级成「用户必须在场」。
- **建议最小修法**：`AppViewModel` 在 `Outcome.Failure` 且 `resumeBytes > 0` 时自动重试 3 次
  （退避 10/30/60 s），UI 显示「网络中断，30 秒后自动续传（第 2/3 次）」；注册
  `ConnectivityManager.NetworkCallback`，网络恢复立即重试而不是等退避。
- **规模**：小。**必须人工**：否（网络本身只能等）。 **必须联网**：是

## 10. 每次重编后都要重拷 2.5 GB 进 ram.img，因为脏标记用的是 mtime，且这一步没有百分比

- **现象**：重编后 `system.apfs` 即使逐字节没变（只改了 root.apfs），`ram.img` 也会被判脏 → 首启多花几分钟重拷；
  这几分钟里界面只有一句「准备引导负载（payload / ram.img）」和 6% 的假进度条。
- **证据**：`engine/QemuPreflight.kt:58-61`（`ramStampFor` 用 `length` + `lastModified()`）、`:92-99`；
  `ImageBuilder.kt:883-940`（`collect()` 每次都 `FileOutputStream(part)` 重写发布文件 → mtime 必变）；
  `QemuRunner.kt:109-113`（`needRam` → `RamImageBuilder.build` 整份 `transferFrom` 2.5 GB，无回调）；
  `AppViewModel.kt:1029-1034`（只更新一句 `step`）。
- **为什么破坏即开即用**：每次重编都额外收一次「看不见的 2.5 GB 税」。
- **建议最小修法**：`ramStampFor` 改为「`length` + `system.apfs` 内容的 sha256 前 1 MB 或全量 sha256」
  （全量更准；发布路径本来就会读一遍），或在 `collect()` 里字节相同时保留目标文件 mtime；
  `RamImageBuilder.build` 增加进度回调，接到 `QemuUi.Starting` 上按 MB 显示。
- **规模**：中（stamp 语义变更 + 一个进度回调）。**只能等**，但可以避免/变透明。 **必须联网**：否

## 11. 「Adreno 私有缓存损坏，请联系维护者」是硬死胡同：App 内没有任何清缓存入口

- **现象**：按 key 命名的 `root-gpu-v2-<key>.apfs` 只要存在但过不了 4096 对齐/NXSB 魔数校验，
  就永远抛「请联系维护者」，重试、重编都不会删它。用户唯一出路是「清除应用数据 / 卸载重装」，
  而这会把 ram.img、payload、私有 stage 一起清掉，再等一轮重编。
- **证据**：`ImageBuilder.kt:718-719`（`if (Files.exists(target…)) throw BuildError("Adreno 私有缓存损坏，请联系维护者")`）；
  `grep -rn "清缓存\|缓存目录\|清除缓存" app/src/main/java/.../ui` 零命中；设置页「维护」只有
  「清理残留进程 / 工具链自检」（`SettingsScreen.kt:573-600`）。
- **为什么破坏即开即用**：这是两处「只能重装」的字面路径之一（另一处见第 16 条），而且它只是一份缓存。
- **建议最小修法**：`Files.deleteIfExists(target)` 后重建，文案改「Adreno 缓存损坏，已自动重建」。
  连带把 `GpuRuntime` 的 `gpu-runtime-$REV` 半解包也做成「探测失败就删目录重解」。
- **规模**：小。**必须人工**：否。 **必须联网**：否
  （触发概率低是**推断**；「没有任何删除入口」是**实测**。）

## 12. 第 15 分钟「自动降核」会杀掉一次正在正常渲染的启动，且判据与自己的文案矛盾

- **现象**：引导阶段的说明写着「画面 N 秒没变化不代表卡死，串口里 `redwood-gl: frame` 号在增长就不是卡死」，
  但自动降核只检查**像素指纹**，不读 frame 号。来宾在稳定画同一帧（比如停在 Apple Logo 慢慢画）时，
  15 分钟一到就整台杀掉重来——这恰好是 0.29.5 把阈值从 120 s 提到 15 min 想避免的事。
- **证据**：`state/AppViewModel.kt:375-391`（`maybeAutoFallback`：只看 `stalledForMs`、`autoFallback`、是否到过 DESKTOP）；
  `:2124-2133`（界面文案明确说「frame 号只要在增长就不是卡死」）；`:2153`（`if (cap.frames > 0 && stage < 7) maybeAutoFallback(cap.unchangedForMs)`）；
  `engine/CapturePipeline.kt:296-300`、`:340`（`unchangedForMs = lastFrameAt - lastChangeAt`，即**指纹未变**的时长，
  不是「没有新帧」）；`Constants.kt:91`（`STALL_FALLBACK_MS = 900_000`）。
- **为什么破坏即开即用**：用户等了 14 分钟，App 自己把它杀了，然后从头再来 10 分钟——体感就是「永远卡在开机」。
- **建议最小修法**：`maybeAutoFallback` 增加一个条件：最近 60 s 内串口出现过新的 `redwood-gl: frame` 就不降核；
  或在 `stage == 6` 时把阈值抬到桌面预算的 2 倍（1200 s）并给出倒计时提示「再 X 分钟无画面变化将自动重启一次」。
- **规模**：小。**必须人工**：否。 **必须联网**：否

## 13. 首页看不到下载/编译失败的原因，也没有入口

- **现象**：设备页「资源下载」一行只显示「下载失败」「下载中 42%」，没有原因、没有 ETA、失败时点进去
  还要自己往下滚到固件卡；编译失败更是完全不在首页出现。
- **证据**：`DeviceScreen.kt:622-631`（`downloadSubtitle`：`is DownloadUi.Failed -> "下载失败"`）；
  `:507-512`（`"资源下载"` 行只做 `openLibrary`）；`DeviceScreen.kt:146-194` 只处理 `QemuUi` 的失败，
  不处理 `BuildUi.Failed`/`DownloadUi.Failed`。
- **为什么破坏即开即用**：失败第一现场（用户正看着的那一页）什么都不说，用户只会以为「卡住了」。
- **建议最小修法**：`downloadSubtitle` 在 Failed 时带 `d.message` 首行；`QemuUi.Stopped` 且 `download/build` 失败时，
  在 hero 下面插一张与 `GlassBanner` 同款的小卡，主按钮「去看原因」（`openLibrary`），次按钮「查看日志」。
- **规模**：小。**必须人工**：否。 **必须联网**：否

## 14. 6.2 GB 蜂窝下载没有任何提示或确认

- **现象**：点「下载固件」就直接开跑，不区分 Wi-Fi/蜂窝，也没有「仅 Wi-Fi 下载」开关。用流量下完是 6.2 GB。
- **证据**：`AppViewModel.kt:724-734`（`preferredConnections()` 只是把蜂窝的连接数从 8 降到 4，
  不做任何提示/拦截）；`LibraryScreen.kt:200-206`（未下载时只写「从 Apple CDN 下载，支持断点续传与暂停」）；
  `downloadFirmware()`（`AppViewModel.kt:561-569`）只有权限判断，没有网络类型判断。
- **为什么破坏即开即用**：一次误点可能产生真实资费，用户下次就不敢点了。
- **建议最小修法**：`startDownload` 前若 `activeNetwork` 为 `TRANSPORT_CELLULAR`，弹一次
  「当前是移动数据，固件 6.17 GB」确认框（记住「本次不再提示」）；下载卡上显示当前网络类型。
- **规模**：小。**必须人工**：否。 **必须联网**：是

## 15. 报错与阶段标题大量内部术语，且不给「下一步点哪里」

- **现象**：启动时标题是「阶段 2/7 · XNULOADER 加载内核 + ramfb 显示协商（1125x2436）」、
  「阶段 6/7 · 守护进程 / SpringBoard 界面层」；失败详情里是 `payload.bin / ram.img`、
  `romfile`、`SELinux untrusted_app_27`、`qemu.log 末段`、`【启动预算】`。
- **证据**：`BootPhase.kt:238-245`（7 条阶段标题）；`QemuFailure.kt:99`（`option ROM`）、`:121-122`
  （`execve 返回 EACCES/EPERM，Android 10+ 会按 targetSdk 选择 SELinux 域`）、`:172-195`（`qemu.log` 原文直接进卡片）；
  `AppViewModel.kt:913-918`（`缺少：${dep.missing}`，直接把资产文件名给用户看）。
  反例（做得对的）：`AppViewModel.kt:936`「到『资源』页编译镜像（约 20–40 分钟，可中断续跑）」。
- **为什么破坏即开即用**：用户读不懂就无法自救，只能放弃或来问。
- **建议最小修法**：阶段标题改成用户语言（「正在启动 iOS 内核」「正在启动桌面」），技术名词移进
  notes 折叠区；`QemuFailure` 的 `detail` 保留原文，但 `hint` 强制成「一句话原因 + 一个可点按钮」，
  并给每个 `QemuFailureKind` 映射一个动作（切后端 / 去资源页 / 强制清理 / 导出日志）。
- **规模**：中（要过一遍 20+ 条文案）。**必须人工**：否。 **必须联网**：否

## 16. 「系统拒绝执行 QEMU」这类失败 `retryable = false`：卡片没有主按钮，只剩「查看日志」

- **现象**：一旦命中 `ExecDenied`，用户看到「本 App 固定 targetSdk=28 以留在 untrusted_app_27 域；
  若仍失败，多为安装包被改动」——没有重试、没有重装引导，只有一个「查看日志」。
- **证据**：`QemuFailure.kt:117-126`（`retryable = false`）；`DeviceScreen.kt:158-173`
  （`else -> null`：无 primaryLabel/onPrimary）；`:172`（secondary 固定为「查看日志」）。
- **为什么破坏即开即用**：这是另一个「只能重装」的字面路径，而用户连「该重装」都没被告知。
- **建议最小修法**：把这一档的 `hint` 改成「重新安装本 APK（覆盖安装即可，镜像与固件不会丢）」，
  并给主按钮「导出日志并重新安装」；或退一步允许「重试一次」。
- **规模**：小。**必须人工**：是（重装这一步只能人做，但要明确说出来）。 **必须联网**：否

## 17. 没有首次说明：要下多少、编多久、要多少空间，全靠用户自己撞

- **现象**：首启没有任何「这是干什么的、总共要多久、要占多少空间」的说明。空间不够时才在
  `preflight` 里报「私有存储不足：需要约 16 GB，当前可用 X GB」——在用户已经点了编译之后。
- **证据**：`ImageBuilder.kt:516-521`（`need = 12/16 GB`，`usableSpace` 检查发生在 `build()` 第一步）；
  `LibraryScreen.kt:423-431`（首次说明只写在编译卡里）；
  `grep -rn "首次" app/src/main/java/.../ui` 只有这一处（`LibraryScreen.kt:425`）。
- **为什么破坏即开即用**：用户不知道自己在进入一段 40–115 分钟、16 GB 的流程，中途放弃的成本最高。
- **建议最小修法**：资源页顶部固定一条（未就绪时才显示）：「首次准备：下载 6.2 GB + 编译 20–40 分钟 +
  启动 10–15 分钟；需要约 16 GB 可用空间（当前 X GB）」，并在 `refreshArtifacts()` 里带上 `filesDir.usableSpace`。
- **规模**：小。**必须人工**：否。 **必须联网**：否

## 18. 进桌面没有任何「完成了」的信号

- **现象**：阶段 7 只是把标题换成「图形界面可见」，没有 toast、没有通知、没有声音；用户在别的 App 里
  根本不知道已经好了。配合第 8 条（没有前台服务），「等 10 分钟」变成「等 10 分钟还得一直盯着」。
- **证据**：`AppViewModel.kt:2085`（`noteDesktop()`）、`:2135`（只改 label）；
  `AppViewModel.kt:1181-1185` 只有一个「QEMU 已启动」的 toast（启动瞬间，不是到桌面）；
  全仓 `grep NotificationManager|NotificationCompat|startForeground` **零命中**。
- **为什么破坏即开即用**：即开即用的最后一步是「知道它好了」，现在这一秒完全靠用户自己发现。
- **建议最小修法**：`stage == 7` 首次到达时 `toast("iOS 桌面已就绪")` + 发一条通知（与第 8 条的前台服务合并）。
- **规模**：小。**必须人工**：否。 **必须联网**：否

## 19. 换包名（`applicationId` → `com.antutu.ABenchMark`）的私有数据重建完全是静默的

- **现象**：换包名后公共目录的固件/镜像还在，但私有 `run/`（ram.img 2.5 GB、payload、qemu/toolchain 解包、
  GPU 客体缓存）全没了。用户看到的是「突然要重新部署 + 准备引导负载很久」，没有任何解释；
  如果同时又换了 GPU dylib，就正好触发第 3 条。
- **证据**：`app/build.gradle.kts:23-31`（注释写明「私有缓存会重建；公共目录不受影响」）；
  `Constants.kt:19`（`PACKAGE_NAME` 只是一份没被任何代码引用的副本，`grep -rn PACKAGE_NAME` 仅此一处，
  所以运行时**无法**知道自己「换过包名」）；`docs/release-0.31.4-a6.md:50-72`（换包名 + 换 dylib 同时发生的现场记录）。
- **为什么破坏即开即用**：同一次「装新版本」在不同人身上表现成完全不同的等待时间，无法预期。
- **建议最小修法**：首次启动时若「公共 `images/*.apfs` 存在」而「私有 `run/ram.img` 不存在」，
  在设备页显式说明「检测到已有镜像，正在重建本机启动缓存（约 2–5 分钟，只需一次）」，并接到
  `QemuUi.Starting` 的进度上。顺带删掉无用的 `Constants.PACKAGE_NAME` 或改成 `BuildConfig.APPLICATION_ID`。
- **规模**：小。**必须人工**：否。换包名本身是发布决策（**推断**其性能白名单收益，文档里也未 AB）。 **必须联网**：否

---

## 附：可以完全自动化 / 只能等的清单

| 只能等（物理约束） | 可以自动化或变透明 |
|---|---|
| 6.17 GB 下载（网速决定） | 自动接力、失败自动续传、蜂窝确认、网络类型显示 |
| 首次解包 7.4 GB + 打包 2.5 GB | 保留解包、只重编 root.apfs、精确百分比与「这一步在做什么」 |
| 首次 2.5 GB ram.img 拷贝 | 内容哈希代替 mtime、MB 级进度 |
| Mesa softpipe 烘一帧 7–43 s / 进桌面约 10 分钟 | 真实 ETA、阶段语义、完成通知、后台前台服务 |
| QEMU TCG 启动本身 | 自动回退 OSMesa、自动重试、失败后给「人话 + 一个按钮」 |

## 附：本次未能验证（需要真机/进程实验）

1. 前台服务缺失导致「按 Home 后被 LMK 杀掉」的实际概率（manifest 无 FGS 是实测，杀进程是推断）。
2. Adreno 根镜像「现场烘」一次的真实耗时（代码里没有计时，我也不允许跑长启动）；
   文档里 Adreno 从启动到主屏的记录是 **505.11 s**（`docs/gpu-backends.md`），OSMesa 是约 600 s。
3. 「重编后 ram.img 重拷」是否真的每次发生——取决于 `collect()` 写入后 mtime 是否必然变化，
   代码上是必然（重新 `FileOutputStream` 写），但没在真机上计时。
4. 单测全部通过（355 项），但它们不覆盖任何 UI 文案/编排路径，所以本报告的 UI 结论全部来自读代码。
