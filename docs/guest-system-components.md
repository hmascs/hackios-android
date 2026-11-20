# 客户机系统组件（壁纸、小组件、锁屏）恢复记录

目标是让被模拟的 iOS 16 桌面重新具备壁纸、主屏小组件和锁屏，而不是用 App 侧图片伪装。所有试验只改隔离拷贝的 `root` 卷（payload 里的 `ramdisk` 条目），不动 `/sdcard/Download/hackios/images`、数据盘或已发布 APK。

## 结论（当前）

| 组件 | 镜像里的真实文件 | 已做 | 结果 |
|---|---|---|---|
| 小组件（chronod） | `ChronoCore.framework`，守护进程 `/System/Library/PrivateFrameworks/ChronoCore.framework/Support/chronod` 存在；**没有**对应 LaunchDaemon plist | 在 root 卷新增 `com.apple.chronod.plist`（注册 `com.apple.chronoservices`） | **实测 chronod 以 pid 8 启动并持续运行**；SpringBoard 的 `failed lookup: com.apple.chronoservices` 从有变 **0 次**。主屏两张小组件仍是白色占位，说明只是有了宿主进程，扩展还没画 |
| 壁纸 | `WallpaperKit.framework`（含 `Assets.car`、`PlugIns`）、`PosterBoard.framework`、`PosterBoardServices.framework`、`PosterKit`、`GradientPoster`、`UnityPoster`、`EmojiPoster` 都在；守护进程实际是 `/Applications/PosterBoard.app/PosterBoard` | 补 `com.apple.PosterBoard.plist`（注册 `com.apple.posterboardservices.services`） | **实测 PosterBoard 以 pid 9 启动并保持运行**，`com.apple.posterboardservices.services` 查找失败 **0 次**；它进一步拉起了壁纸扩展 `com.apple.WallpaperKit.CollectionsPoster`（pid 47），但该扩展 10 秒内没有 check-in，被 `XPC_EXIT_REASON_EXTENSION_CHECK_IN_TIMEOUT` 杀掉。主屏壁纸仍是黑色 |
| 锁屏 | `SpringBoard` 自带 CoverSheet；`SpringBoardPlugins/{PassesLockScreenPlugin,FindMyiPhoneLockScreen}` 在 | —— | 目前启动后由 `libredwood-sb` 自动解锁，未单独验收锁屏画面 |

## 方法与可复跑步骤

1. 取现有隔离镜像的 root 卷目录（例如 `hackios-run/redwood-settings-content.M5VER6/boot/rootfs`），复制到新的实验目录。
2. 根卷的 `/etc/rc.jobs` 是启动钩子，逐行执行（`&` 可后台）；`/LaunchDaemons/*.plist` 是随系统启动的作业。两者都在我们自己的 root 卷里，改它不属于篡改真实系统卷。
3. 探针用本机 clang 交叉编译成客户机可执行的 Mach-O：

   ```sh
   clang --target=arm64-apple-ios16.0 \
     -isysroot hackios-run/xnu-toolchain-v1/theos/sdks/iPhoneOS16.5.sdk \
     -O2 -fno-stack-protector -fuse-ld=lld probe.c -o root/bin/probe
   ```

   把 `<root>/bin/probe` 放进 root 卷，并在 `rc.jobs` 追加一行 `/redwood/bin/probe`，输出直接进串口。
4. 打包与装载（隔离、可丢弃）：

   ```sh
   python3 hackios-run/gpu-public-check.WSyfNt/mkapfs.py -z -o <dir>/root.apfs root=<dir>
   python3 hackios-next/tools/repack-xnu-test-payload.py <基线 payload> <dir>/payload.bin --ramdisk <dir>/root.apfs
   ```

   `repack-xnu-test-payload.py --ramdisk` 只替换 `ramdisk` 条目（校验 `NXSB` 魔数），其余条目逐字节保留。

## 已实测的盘点结果

- `chronod`：见上表，已启动。
- 根卷只有 65 个文件、25 个 LaunchDaemon；系统卷（约 45,142 个文件）在预载的 12 GB 稀疏 RAM 镜像里（`/root/workspace/ram-correct.img`），不是 payload 的一部分，因此系统卷本身没有改动。
- `/bin/sh` 在客户机里**不存在**，探针必须是自编译的 Mach-O，不能靠 shell 一行命令。
- `WallpaperKit.framework` 的 `PlugIns` 与 `Wallpaper-Lock-Screen-Tutorial-*.ca` 存在，说明还有可用的壁纸资源与扩展点。

- 扩展不是独立守护进程：`/usr/libexec/extensionkitservice`、`extensionkitd`、`ExtensionKit.framework/Support/extensionkitservice` 全部不存在（试注册会报 `Missing executable detected`），而 `com.apple.WallpaperKit.CollectionsPoster` 仍能被 launchd 以 `launch job demand` 拉起，说明扩展宿主在 launchd 内部。
- `/usr/libexec/dasd` 存在（`com.apple.duetactivityscheduler` 的实际提供者）；旧路径 `/usr/libexec/duetactivityscheduler` 不存在。
- `/var/mobile/Library/PosterBoard` 不存在，`/var/mobile/Library/SpringBoard` 只有 `SBLastRestoreIdentifier`。

## 上游是怎么做的（hackios-xnu-userland）

- `userland/build.sh` 的 `INJECT` 默认只注入五个库/进程：`libredwood.dylib` → backboardd、containermanagerd、containermanagerd_system；`libredwood-sb.dylib` → SpringBoard；`libredwood-media.dylib` → mediaserverd；`libredwood-app.dylib` → Sileo；`libredwood-tweaks.dylib` → Preferences。**没有 PosterBoard、chronod。**
- `IOS_JOBS` 默认也是同一份精简集（notifyd、cfprefsd、logd、syslogd、distnoted、fseventsd、containermanagerd×2、runningboardd、backboardd、usermanagerd、mobilegestalt、lsd、powerd、iconservicesagent、pkd、SpringBoard、wifid），**没有小组件/壁纸守护进程**。
- `userland/mkjobs.py` 才是补作业的正规做法：从 iOS 根文件系统把**原厂** `System/Library/LaunchDaemons/<label>.plist` 复制进镜像并把 stderr 导向控制台；`--rest` 再补上其余作业和 `System/Library/LaunchAngels`（无图标、在独立进程里画系统界面的“天使”，文档点名包括**壁纸**、键盘、正在播放），但一律去掉 `RunAtLoad`/`KeepAlive`，只在有人查它的 Mach service 时才起。
- `userland/src/init.c` 在 flash-root 模式下把 `/redwood/LaunchDaemons` **bind 覆盖** `/System/Library/LaunchDaemons`，并把 `/System/Library/LaunchAngels` bind 到空目录 —— 也就是说上游默认既没有原厂 500 个作业，也没有天使。
- `userland/src/inject_sb.c` 明确写着：**壁纸由另一个进程里的 PosterBoard 实时绘制，再作为 hosted context 合进 SpringBoard 的图层树**，SpringBoard 手里没有壁纸位图，也不会烘焙；“switcher 的壁纸水洗”因此直接省略。

结论：上游默认镜像的桌面本来就没有壁纸，我们的黑底与之一致；要恢复壁纸，等于把上游默认**没有启用**的 PosterBoard/天使这条路自己打通。

## 尚未验证 / 下一步

- 已找到 `PosterBoard` 的真实可执行文件：`/Applications/PosterBoard.app/PosterBoard`（`PosterBoard.framework/Support/PosterBoard`、`/usr/libexec/PosterBoard` 都不存在）。已在 root 卷补 `com.apple.PosterBoard.plist` 并注册 `com.apple.posterboardservices.services`，正与 chronod 一起在同一次运行里验证。
- 根卷和 `/System/Library/LaunchDaemons` 是同一份目录：补进 root 的 plist 会直接出现在系统作业表里（实测 `com.apple.chronod.plist` 出现在该目录），不需要改系统卷。
- 小组件扩展的渲染依赖（`WidgetKit` 扩展宿主、快照缓存、`com.apple.duetactivityscheduler` 缺失）尚未逐个补齐。
- 壁纸扩展被 **5 秒 check-in 超时**杀掉：`CollectionsPoster[49]` 拉起后约 5 秒即 `Extension is hanging on launch. Killing.` → `XPC_EXIT_REASON_EXTENSION_CHECK_IN_TIMEOUT`。这个超时在 Apple 的 launchd 里，不在我们的 XNU 源码里；在“整个系统慢几十倍”的模拟下，扩展来不及 check-in。给 PosterBoard 注入 Redwood 库的试验没有产生任何可观察变化（它的图层由 backboardd 合成，不是自己直接画），已回退。
- 本机镜像的 `/System/Library/LaunchDaemons` 是**指向 `/redwood/LaunchDaemons` 的符号链接**（用上游 `tools/apfs/apfs.py` 离线读 `/storage/emulated/0/Download/hackios/images/system.apfs` 可直接看到），所以镜像里根本没有原厂 plist 可抄；要按上游 `mkjobs.py` 的方式补原厂作业，需要 iOS 16.5 IPSW 的根文件系统（约 7.4 GB 下载）。
- 可选路线：① 取 IPSW 根文件系统，用 `mkjobs.py` 的规则补原厂 plist 与天使；② 改 launchd 的扩展 check-in 超时（系统镜像内的 Apple 二进制）；③ 直接在数据卷放一份 poster 配置，绕开 CollectionsPoster 选择器。三条都还没做。
