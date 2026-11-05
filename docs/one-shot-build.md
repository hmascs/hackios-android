# 一次性合并构建清单（把所有修改并到一个构建里）

> 目标：终结「编→测→又编」的循环。本轮把所有已知修复按**四个组件**列成一份施工单，
> 在正确的 CI（macOS + Android NDK + iOS SDK）里**一次性**构建并发布，宿主机（本 App）侧改动独立可编。
> 本 Linux 沙箱**无法**重编内核 / QEMU / 镜像包（缺 macOS、NDK、iOS SDK 工具链），所以下文标注了
> 哪些是「本仓库可验证交付」、哪些「需在 HackiOS CI 里落」。

## 0. 四个组件与它们的仓库

| # | 组件 | 仓库 | 产物 | 本沙箱能否编 |
|---|------|------|------|--------------|
| 1 | **App（部署端）** | `hackios-ios27`（本仓库） | `app-debug.apk` | ✅ 能（JDK21 + Android SDK） |
| 2 | **镜像构建脚本** | `hackios-xnu-userland/userland/` | `hackios-scripts.zip`（`build.sh`/`mkjobs.py`/`rc.jobs*`/`*.plist`） | ❌ 脚本是 Python，可本地验证逻辑；但 `root.apfs`/`system.apfs` 需 IPSW + macOS 工具链 |
| 3 | **XNU 内核 + 帧缓冲驱动** | `hackios-xnu-kernel` | `mach.development.bcm2837` + `loader.efi` + `QEMU_EFI.fd` | ❌ 需 macOS + theos + iPhoneOS16.5 SDK |
| 4 | **QEMU 安卓分支** | `hackios-xnu-kernel`（`common/build.sh qemu-virt`） | `qemu-android-bundle.zip` | ❌ 需 Android NDK 交叉编 QEMU |

> 第 2/3/4 项产物都以**资产**形式被本 App 的 `AssetDeployer` 从 CDN 拉取（`Constants.kt` 的
> `ASSET_KERNEL` / `ASSET_SCRIPTS_BUNDLE` / `ASSET_QEMU_BUNDLE` 等）。所以「合并构建」= 在 HackiOS
> 一侧把 2/3/4 重编并重新发布资产，同时把本仓库（1）升到含全部 UI 与诊断改进的版本。

## 1. 组件 2（userland）— 真正的 lsd 风暴根因，一行修复

**问题（实测证据见 `docs/engineering-notes.md` §19–§20）**：FAST 启动模式清空了 `REST`，而 `build.sh`
的 FAST `JOBS` 列表（当前 `userland/build.sh` 第 139–146 行）**漏列 `com.apple.mobileactivationd`**。
`mkjobs.py` 的 DENY 名单已把 mobileactivationd 放出来（文末注释：「mobileactivationd came out of this
tuple … With it in, started on demand: no failed lookups」），所以它**能**被生成 plist —— 只是没人把它
传给 `mkjobs.py`。非 FAST 模式靠 `--rest` 把全部 iOS 守护进程（含它）拉进来，所以只有 FAST 模式缺它，
lsd 查 `com.apple.mobileactivationd` 永远 `No such process` → 自旋风暴。

**修复（`hackios-xnu-userland/userland/build.sh`，FAST 段，第 139–146 行）**：把
`com.apple.mobileactivationd` 加进 `JOBS` 列表。patch 示意：

```diff
 if [ -n "$FAST" ]; then
     JOBS=${JOBS-"
         com.apple.notifyd com.apple.cfprefsd.xpc.daemon com.apple.cfprefsd.xpc.daemon.system
         com.apple.logd com.apple.syslogd com.apple.distnoted.xpc.daemon com.apple.fseventsd
         com.apple.containermanagerd com.apple.containermanagerd.system com.apple.runningboardd
         com.apple.backboardd com.apple.mobile.usermanagerd com.apple.mobilegestalt.xpc
         com.apple.lsd com.apple.powerd com.apple.iconservices.iconservicesagent
-        com.apple.pluginkit.pkd com.apple.SpringBoard
+        com.apple.pluginkit.pkd com.apple.SpringBoard
+        com.apple.mobileactivationd
     "}
```

**为什么这比 App 侧「折叠风暴」更对**：App 折叠只是消视觉噪音，guest 仍在自旋；把 mobileactivationd 纳入
FAST 作业集，lsd 查询立刻命中，风暴在 guest 内消失，无需任何宿主 workaround，也无需再为「风暴」编造阈值。

**本地验证（本沙箱可做）**：用 `mkjobs.py` 的 DENY/ALLOW 逻辑确认 `com.apple.mobileactivationd` 不在
DENY，会被正常写出。脚本是纯 Python，`python3 -c "import ast; ..."` 或跑 `mkjobs.py --help` 可确认语法；
完整 plist 生成需真实 `rootfs/System/Library/LaunchDaemons/com.apple.mobileactivationd.plist`（来自 IPSW），
故最终校验在 HackiOS CI 的 `userland/build.sh FAST=1` 里完成。

**附带建议（同一文件）**：`rc.jobs.fast` 末尾的 `rwtouch wait 30 swipe …` 已能在桌面起来后划一下解锁，
保持即可；触屏属性由内核 / 设备树下发，不在此文件改。

## 2. 组件 3（kernel）— 黑屏：rebase 到上游可用显示提交，而非从零重写

**错误路径（上一轮的 `kernel-iomfb-port.md` 已降级）**：从 `dipa-studio/ios-sweet` 的
`SweetFramebuffer.cpp` 重写 `MiniIOMobileFramebuffer.cpp` 的 swap —— 工作量大、易错、且**与上游重复造轮子**。

**正确路径（契合「人家做好的比重新做的好」）**：上游 HackiOS 文档明确写道
「**QEMU (virt, aarch64) — the same thing on any Linux computer**」「**lock screen appears after 2–3
minutes**」。即上游 `hackios-xnu-kernel` 的某次提交**已经让 QEMU ramfb 路径出画**。我方分叉黑屏，最可能是
相对该提交的**回归或落后**（例如 `MiniIOMobileFramebuffer.cpp` 的 swap 提交未合入、或 `eagl.c`（userland
`src/eagl.c`，把 OpenGL ES 桥到 Mesa）未启用 `gl=1` 通路）。

**动作（`hackios-xnu-kernel`）**：
1. 把本分叉 `rebase` / `merge` 到上游带 QEMU 显示的提交（核对 `iokit/Kernel/MiniIOMobileFramebuffer.cpp`
   的 `swap`/`present` 是否把 IOSurface 拷到 boot framebuffer 物理地址）。
2. 确认 `loader` 把 ramfb 重塑成 `1125×2436`，内核报告的 boot framebuffer 落在 `0x1fb6bb000`（与我方
   `FramebufferProbe` 直读地址一致）；若上游用不同地址，同步更新 App 侧 `FramebufferProbe` 的探针地址。
3. 保留 `eagl.c` 的 Mesa 桥（`gl=1` 软件渲染），它是 QEMU 下 WindowServer 的图形通路。
4. 若上游确实缺 ramfb swap（与文档矛盾），再把 `kernel-iomfb-port.md` 的 ios-sweet swap record 布局
   （`rec+0x50`=交换ID、`+0x54+4*layer`=图层 surface id、`+0x104`=启用掩码、总长 `0x46c`）作为兜底移植，
   **不要**另起一套不一致的布局。

**本沙箱限制**：无法编译 XNU，故该步在 HackiOS macOS CI 完成；本仓库只负责把 `FramebufferProbe` 探针地址
与错误目录对齐（已在 0.26.5 落）。

## 3. 组件 4（qemu bundle）— 大概率无需改

`qemu-android-bundle` 提供 `-M virt -device ramfb`，loader 经 fw_cfg 把 ramfb 重塑成 1125×2436。只要内核
把合成帧拷进这块内存，现有 `screendump` 抓帧链路自动恢复（App 显示代码无需改）。**本轮不重编 QEMU**，除非
上游有对应的 ramfb 修复提交需要同步。

## 4. 组件 1（本 App / hackios-ios27）— 本沙箱可编、本次一并交付

合并进 0.27.0（含上轮 0.26.5 的稳定性 boot-args + 精确报错目录 + payload 缓存失效）：

1. **错误目录 `iomfb-swap-stalled`**：ACTION 改为「rebase 到上游可用显示提交」+ 兜底 swap record 布局，
   去掉任何「降核 / 重编镜像能绕过」的误导。
2. **稳定性 boot-args**（0.26.5 已注入）：`wdt=-1 wdt_test=no_user_checkin`
   （关看门狗，避免慢启动被误杀）、`launchd_unsecure_cache=1`（让精简镜像里 mobileactivationd 等也能注册）。
3. **4 项 UI 改进（用户早前提，本轮合并）**：
   - 资源页无储存权限时，下载按钮改为「授予储存权限」。
   - MCP 允许权限弹窗：点击取消保持打开（不关闭）。
   - 日志页：增加「清空日志」；QEMU 运行时点清空则弹窗提醒并询问是否清空。
   - 构建完成后立即弹窗询问是否清除 5+GB 构件残留。
4. `QemuPreflight.CMDLINE_PROFILE` 保持 4（payload 重建）；版本 0.26.5 → 0.27.0。

## 5. 一次性构建顺序（在 HackiOS CI 执行一次）

```bash
# ① 镜像构建脚本（组件2）：应用 §1 的 mobileactivationd 一行修复后重新打包
cd hackios-xnu-userland && git commit -m "userland: include mobileactivationd in FAST job set (fixes lsd storm)"
# ② 内核（组件3）：rebase 到上游 QEMU 显示提交（§2），确认 MiniIOMFB swap 存在
cd ../hackios-xnu-kernel && git merge upstream/working-qemu-display
common/build.sh qemu-virt        # 重编 qemu-android-bundle（组件4，若上游有 ramfb 修复）
# ③ 发布资产：kernel / loader / uefi / scripts / qemu bundle 重新上传 CDN
# ④ 本 App（组件1）：升 0.27.0（含 §4 全部），./gradlew :app:assembleRelease
# ⑤ 真机/云 QEMU 验证：装 0.27.0 → 看 framebuffer-probe：
#    - 非黑 = 出画，lsd 风暴消失（mobileactivationd 已注册）
#    - 仍黑 = 按 iomfb-swap-stalled ACTION 回查内核 swap 提交是否真的合入
```

## 6. 诚实边界

- 本沙箱**只能**交付并验证组件 1（App APK）与组件 2 脚本的**逻辑**（Python 可跑）。
- 组件 3/4 的 macOS/NDK 编译必须在 HackiOS 原 CI 完成；本仓库给出的是**精确、可复核的 patch 与 rebase 目标**，
  不是替代编译。
- 不再为「试一试」反复重编镜像 / 内核；上述清单是一次到位。任何一步未达预期，按 `framebuffer-probe` 与
  `iomfb-swap-stalled` 的 ACTION 定点回查，不盲目重编。
