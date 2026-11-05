# 跨仓库调研：如何让 QEMU「非常完善」（出主屏、可交互）

> 本轮按用户要求扩大上游参考面，目标是给「下一轮一次性构建出非常完善的 QEMU」提供完整蓝图。
> 结论先行：**黑屏不是 QEMU 配置问题，是内核帧缓冲驱动缺口**；已有的同代范本
> **dipa-studio/ios-sweet**（iOS 16.7 已出主屏）给出了可直接照抄的 swap/blit 实现。

---

## 1. 参考项目清单与各自价值

| 项目 | 代际 / 平台 | 与本工程关系 | 关键启示 |
|---|---|---|---|
| **dipa-studio/ios-sweet** | iOS 16.7 / XNU xnu-8796（同代） | **最直接范本**：同样是 iOS 16 + QEMU/virt + 软件渲染 | `SweetFramebuffer.cpp`(AppleCLCD) 即「IOSurface→面板内存」的 CPU 拷贝实现，swap record 布局与本工程**逐字节一致** |
| TrungNguyen1909/qemu-t8030 | iOS 14 / t8030 机器 | QEMU fork + `xnu-ramfb=on` 的 ramfb 显示范式 | 启动命令范式：`-M t8030,kernel-filename=…,dtb-filename=…,kern-cmd-args=…,ramdisk-filename=…,xnu-ramfb=on`；`trustcache`/`ramdisk` 配套 |
| alephsecurity/xnu-qemu-arm64 | iOS 12.1 / macOS 研究 | XNU 在 QEMU 的研究起点 | PongoOS + checkra1n KPF 动态打补丁的干净范式；`-gdb` 调试链路 |
| eShard（alephsecurity/t8030 衍生） | iOS 14 | 把 iOS 14 跑出 UI + 多点触控 + 网络 | 软件渲染路线：QuartzCore 改走软件后端；framebuffer 物理地址**可写**，写 ARGB 能上屏 |
| ChefKissInc/Inferno（原 QEMUAppleSilicon） | iOS 14 | 现代 iOS 在 QEMU 出画面 | **最小补丁法**：<10 条指令补丁，聚焦软件渲染 + framebuffer 回退，最易移植到新版本 |
| AsahiLinux（IOMFB 逆向） | Linux on Apple Silicon | IOMFB 寄存器/选择器语义 | 理解 IOMFB user-client selector 含义的权威来源（selector 名称见 ios-sweet NOTES） |

**最值得抄的是 ios-sweet**：它是唯一一个**同代（iOS 16）+ 已出主屏**且**开源了内核补丁**的项目，
其 `xnu/patches/0046-sweet-iosurface-iomfb.patch` 就是本工程 `MiniIOMobileFramebuffer.cpp` 缺的那段。

---

## 2. 黑屏的精确定位（所有项目共同的单点失效）

每个项目都卡在同一个地方：**渲染栈全活，但画面没进 framebuffer**。

- ios-sweet：backboardd 调 IOMFB selector 后，靠 `AppleCLCD::swap()` 把图层 surface **CPU memcpy** 到面板内存（0x9c000000）。
- 本工程：同样调了 selector，但 `MiniIOMFB` 的 swap 路径**没有这段 memcpy**，IOSurface 停在内存里，
  ramfb（0x1fb6bb000）永远停在 loader 画的 Apple logo。
- eShard/Inferno 的修法一致：要么补 framebuffer 驱动，要么走软件渲染 + framebuffer 回退。

> 没有任何 boot-arg / QEMU 开关能「实现」一个未实现的内核驱动函数。必须改内核并编一次。

---

## 3. 「非常完善的 QEMU」组件清单（照此逐项补齐）

| 组件 | 状态 | 来自 | 备注 |
|---|---|---|---|
| 内核帧缓冲 swap/blit | **缺（根因）** | ios-sweet 0046 | 见 `kernel-iomfb-port.md` |
| 显示尺寸/倍率 | ✅ 已对（fbscale=3） | 本工程 | 勿回退到 1 |
| 软件渲染 GL | ✅ 已对（gl=1 + OSMesa） | 本工程 / Inferno | CoreAnimation 走 CPU |
| 激活链守护进程 | ❌ 缺（lsd 风暴根因） | 本工程 §20 | 镜像纳入 mobileactivationd |
| 看门狗误杀慢启动 | ✅ App 0.26.5 已加 `wdt=-1` | ios-sweet | boot-args |
| launchd 注册守护进程 | ✅ App 0.26.5 已加 `launchd_unsecure_cache=1` | ios-sweet | boot-args |
| 触屏属性 | ⚠️ 缺口 | 本工程 §21.4 | 虚拟输入驱动补属性 |
| 多点触控 | 🔲 未做 | eShard / ios-sweet 0058 | HID digitizer |
| 网络 | ✅ App 已有 user-mode slirp | qemu-t8030 范式 | 已接 |
| 音量/电源键 | 🔲 未做 | ios-sweet 0060 | HID consumer |
| 电池/设备身份 | 🔲 未做 | ios-sweet 0059/0062 | MobileGestalt |

---

## 4. 给「下一轮」的合并构建建议

1. **内核**：移植 `kernel-iomfb-port.md` 的 §2.1–2.4（swap 拷贝 + selector 补齐）。这是唯一硬阻塞。
2. **镜像**：mobileactivationd 纳入 FAST 作业集；触屏属性补齐。
3. **App**：0.26.5 已就绪（稳定性 boot-args + 更精确的报错目录 + payload 缓存失效）。
4. **构建一次**，真机验证 `framebuffer-probe`：非黑即出画，黑则按 `iomfb-swap-stalled` ACTION 回查。

> 诚实边界：内核/镜像构建需 macOS CI 与 Apple SDK（hackios-xnu-kernel 仓库），不在此 Android 仓库内，
> 本环境无法代编。本文与 `kernel-iomfb-port.md` 是给那次构建的精确施工单。
