# 对照参考项目：当前 hackios-ios27 可分叉借鉴的修改点

> 本轮在上一轮（ios-sweet / eShard / Inferno / qemu-t8030 / AsahiLinux / alephsecurity）基础上，
> **新增**深挖了 `samhenrigold/qemu-ios`（Light Touch）、UTM 的 `virtio-ramfb`、以及**上游 HackiOS 自身
> 的双仓库结构与本地脚本副本**（`cloud-verify-025/work/userland/`）。结论：**最大增量不在「重写」，而在
> 「对齐上游已可用的提交 + 补一行作业集」**——别人做好的确实比我们从零重写更好。

## 1. 我们相对上游 HackiOS 的真实差距（最关键）

本 App 不是从零造 QEMU，而是 **HackiOS 的安卓部署端**。上游文档明确：

> 「QEMU (virt, aarch64) — the same thing on any Linux computer」「lock screen appears after 2–3 minutes」

即上游 `hackios-xnu-kernel` 的某次提交**已经让 QEMU ramfb 路径出锁屏/主屏**。我们分叉黑屏，最可能是
**相对该提交的落后或回归**，而不是需要新写一套 swap。本地脚本副本证实：

| 现象 | 上游已具备 | 我们分叉缺的 | 修改点（文件级） |
|------|-----------|-------------|------------------|
| lsd 风暴 | `mkjobs.py` 已把 `mobileactivationd` 移出 DENY（注释：「With it in, started on demand: no failed lookups」） | `build.sh` 的 FAST `JOBS` 列表（139–146 行）**漏列** `com.apple.mobileactivationd`，FAST 模式清空 `REST` 后它彻底不被 bootstrap | `hackios-xnu-userland/userland/build.sh` 加一行 `com.apple.mobileactivationd` |
| 黑屏 | 上游 QEMU 路径能出画；`eagl.c` 把 OpenGL ES 桥到 Mesa（`gl=1` 软件渲染） | 可能落后了 `MiniIOMobileFramebuffer.cpp` 的 ramfb swap 提交 | `hackios-xnu-kernel` rebase 到上游带 QEMU 显示的提交 |
| 触屏 | `rc.jobs.fast` 末尾 `rwtouch wait 30 swipe …` 解锁划动 | 同上游，无需改 | 保持 |
| 音频 | `src/` 里的 CoreAudio 输出设备 + `RedwoodAudio.driver` | 同上游 | 保持 |

**一句话**：把分叉 `rebase` 到上游可用显示提交 + 补一行 FAST 作业集，就同时解决黑屏与 lsd 风暴——
比从 ios-sweet 重写 swap、比 App 侧折叠风暴都更对。

## 2. 逐项目对照：别人做好了什么，我们能借什么

### 2.1 samhenrigold/qemu-ios（Light Touch）— 最完整的「主屏 + 显示 + 触屏 + 音频 + GL 桥」
- **架构**：`hw/arm/gles-host*.c`（宿主 GL 执行器）、`hw/arm/guest-services.c` / `guest-gles.c` /
  `guest-pasteboard.c` / `guest-package.c`（guest → host 超调用）、`hw/arm/ipod-agent.c`（类型化 guest agent
  RPC）、`hw/misc/ios_baseband*.c`（基带模型）。
- **可借**：
  1. **宿主 GL 桥模式**：iOS 用户态渲染经 hypercall 交给宿主 GL 执行。我们走的是内核 MiniIOMFB → ramfb
     的 CPU 拷贝路径（QEMU virt + ramfb），与它的 gles-host 路径不同，但**边界安全纪律值得抄**：
  2. **guest 值边界检查纪律**：每个 guest 写的索引/长度/偏移先用 `IT_IDX`/`IT_SIZE`/`IT_RANGE` 校验再用作
     数组下标或拷贝长度；guest 可达路径上不调用 `exit/abort/hw_error/g_assert`，只 `LOG_GUEST_ERROR` 并拒绝。
     ——若我们后续给帧缓冲加任何 guest↔host 通路，应照此约束（见 §3 可选项）。
- **不借**：它是 legacy iOS（S5L8900/A4），机器类型/驱动与我们的 iOS 16.5 + virt 完全不同，仅取架构与纪律。

### 2.2 UTM / beybeyzk qemu — `virtio-ramfb` 把帧缓冲 VRAM 暴露进 BAR
- **可借（性能优化，非必需）**：`virtio-ramfb: expose framebuffer VRAM in BAR` 让宿主更直接地访问显存，
  可降低我们 `screendump` 取帧的开销。当前 ramfb 已能用，列为**可选增强**，不阻塞出画。

### 2.3 dipa-studio/ios-sweet — iOS 16.7 QEMU，swap record 布局范本
- **可借（兜底）**：若上游 rebase 后内核仍缺 ramfb swap，`SweetFramebuffer.cpp` 的 swap record 布局与我们
  日志 `SwapEnd (5, 1132-byte struct)` 逐字节吻合：`rec+0x50`=交换ID、`+0x54+4*layer`=图层 surface id、
  `+0x64/+0xc4+16*layer`=源/目标矩形、`+0x104`=启用掩码、总长 `0x46c`(1132B)。这是兜底移植的精确范本
  （详见 `kernel-iomfb-port.md`）。**优先级低于 rebase 上游**。

### 2.4 qemu-t8030（TrungNguyen1909）— 两种显示模式的证据
- **可借（认知）**：作者确认存在「**raw framebuffer at fixed hardware address**」（早期显示 / Pongo 用）与
  「**planes + DMA**」两种模式。我们的 ramfb 即 raw-fb 模式，方向正确；若需要早期 logo 之外的稳定路径，
  内核 swap 才是关键（§1）。

### 2.5 alephsecurity/xnu-qemu-arm64 + eShard — 软件渲染兜底法
- **可借（兜底）**：eShard 在真越狱机把 **QuartzCore 打补丁强制软件渲染**，确认 UIKit 全部走软件渲染可用
  （Metal 直绘的部分有瑕疵）。若 MiniIOMFB swap 短期难合，可在用户态注入一个强制软件渲染的 shim 作为
  临时出画手段，但它治标不治本，不作为主路径。

### 2.6 AsahiLinux IOMFB — 寄存器/plane 模型
- **可借（理解）**：逆向出的 IOMFB 寄存器与 plane/dma 模型，帮助核对我们内核 swap 是否覆盖了正确的 plane。

## 3. 当前项目「可以修改的地方」清单（按性价比排序）

| 优先级 | 修改点 | 落点 | 收益 | 本沙箱可验证 |
|--------|--------|------|------|--------------|
| **P0** | FAST 作业集补 `com.apple.mobileactivationd` | `hackios-xnu-userland/userland/build.sh` 139–146 行 | 根除 lsd 风暴（guest 内消失，非 App 折叠） | 脚本逻辑可跑（需 CI 出图） |
| **P0** | 内核 rebase 到上游 QEMU 显示提交（含 MiniIOMFB swap） | `hackios-xnu-kernel` | 黑屏→出画 | 否（需 macOS CI） |
| **P1** | App 4 项 UI 改进 + 稳定性 boot-args | `hackios-ios27`（本仓库）0.27.0 | 完整可交付 App | ✅ 可编可验 |
| **P2** | `FramebufferProbe` 探针地址对齐上游 ramfb 基址（若上游改地址） | `hackios-ios27/.../FramebufferProbe.kt` | 探针不误判 | ✅ |
| **P3** | ramfb VRAM 暴露进 BAR（性能） | `qemu-android-bundle`（UTM 借鉴） | 取帧更快 | 否 |
| **P3** | 若加 guest↔host 帧缓冲通路，套用 samhenrigold 边界检查纪律 | 内核/loader | 安全 | 否 |

## 4. 不建议做的事（避免重蹈覆辙）

- ❌ **不要从 ios-sweet 重写整套 MiniIOMFB swap**：上游已有可用提交，rebase 更稳更省。
- ❌ **不要在 App 侧用「降核 / 重编镜像 / 改阈值」掩盖黑屏或风暴**：那是治标，且违反诚实判定纪律。
- ❌ **不要反复重编镜像试错**：本轮一次性合并（见 `one-shot-build.md`）后，按 `framebuffer-probe` 与
  `iomfb-swap-stalled` 的 ACTION 定点回查即可。
