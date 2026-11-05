# HackiOS for Android

在 Android 手机上，通过 QEMU（TCG 纯软件模拟）跑起一套 iOS 风格的桌面环境。
App 本身是一个用 **Jetpack Compose + Glasmio（iOS 27 液态玻璃设计语言）** 重写的四标签原生界面，
负责下载/校验固件、拼装启动镜像、拉起 QEMU、把帧缓冲画到 SurfaceView、再把触屏和按键变成 QMP 输入事件。

> 包名 `com.android.gudao.iphoneos` · 版本 `0.29.9 (49)` · `minSdk 33` / `targetSdk 28` / `compileSdk 37`

---

## ⚠️ 免责声明（请务必先读）

**本项目是一个独立的、非营利的个人技术研究与学习项目。它不属于 Apple Inc.，也没有得到 Apple Inc. 的任何授权、认可或赞助。**

1. **不提供、不分发任何 Apple 软件。** 本仓库的源码与发布产物中**不包含** iOS / iPadOS / macOS 的系统镜像、
   XNU 内核、dyld 共享缓存、应用程序、图标或任何其他 Apple 拥有版权的二进制文件。运行时所需的一切 Apple
   软件材料，均由使用者自行从其合法拥有的设备或渠道取得，本项目只提供「装载」与「显示」的技术手段。
2. **不与 Apple 的现有产品或服务竞争**，不提供任何绕过激活、绕过授权、破解 DRM 或规避正版购买的功能。
   本项目**不是**越狱工具，**不能**用于激活任何设备，**不能**用于安装未经授权的 App。
3. **仅供个人学习、研究、教学与界面设计参考。** 严禁用于任何商业用途、二次销售、预装在待售设备中，
   或用于任何违反当地法律法规的用途。
4. **"iOS"、"iPhone"、"Apple" 等名称与商标归 Apple Inc. 所有。** 本项目中出现的相关名称仅用于
   客观描述被模拟的软件环境（合理使用 / 指明性使用），不表示任何关联或背书。
5. **风险自负。** 本项目按 GPLv2「按原样」提供，不附带任何明示或暗示的担保。使用本项目所导致的一切
   后果（包括但不限于数据丢失、设备损坏、账号封禁、法律责任、以及因使用非官方软件而违反
   Apple 软件许可协议的风险）由使用者自行承担。
6. **请优先在你自己合法拥有的硬件上做界面设计验证。** 如果你只是想学习 iOS 风格的 UI 实现，
   Apple 官方的 Human Interface Guidelines 与 Xcode 模拟器是更合适的途径。

**权利人下架请求**：如果你是有权主体，认为本仓库的任何内容侵犯了你的权利，请通过本仓库的
Issues 或 GitHub 的资料页联系作者；作者会在核实后 **24 小时内移除相关内容**，无需先行发起 DMCA 流程。

---

## 1. 这个项目能做什么

| 能力 | 说明 |
|---|---|
| 固件下载 | 内置可续传 / 可取消 / 可校验的分块下载器，带失败分类（网络、磁盘、校验、服务端） |
| 镜像构建 | 在手机本机上解包固件并重组成启动镜像（APFS 打包 + 载荷拼接），带阶段检查点，中断后可续 |
| 启动模拟 | 拉起 QEMU（`-M virt` + TCG 多线程），加载引导器与内核，等待桌面出现 |
| 画面显示 | 通过 HMP `screendump` 取帧 + 软件解码上屏，自动裁剪、等比、无黑边 |
| 输入注入 | 触摸 / 滑动 / 长按 / Home / 锁屏 / 音量，全部经 QMP `input-send-event` 送到虚机 |
| 画质与性能 | 多档性能预设、分辨率与缩放控制、帧率与耗时统计、CPU 亲和性绑定 |
| 日志系统 | 串口日志实时汇聚、分级、可暂停、可清除、可导出 |
| 界面 | iOS 27 液态玻璃风格的 Compose 界面：启动、控制、日志、设置四个标签 |

## 2. 界面与设计

- 设计语言：**Glasmio** —— 液态玻璃材质、动态模糊、半透明层叠、SF 风格的圆角与字重。
- 四标签：**启动 / 控制 / 日志 / 设置**，状态栏、底部 Home 指示条均按 iOS 的视觉规范实现。
- 完整的深浅色适配与 16 套强调色。
- 设计规范与组件清单见 [`docs/design-system.md`](docs/design-system.md)。

## 3. 技术架构

```
┌─────────────────────────── Android App (Kotlin) ───────────────────────────┐
│  ui/         Compose + Glasmio 界面                                         │
│  engine/     ChunkPlan · BuildCheckpoint · QemuPreflight · TouchMapper      │
│              QemuFailure（失败分类）· CapturePipeline（取帧与退避）          │
│  QemuRunner  载荷构建 + QEMU 进程生命周期 + 参数下发                          │
│  ImageBuilder 设备端解包 + APFS 打包（阶段检查点）                            │
│  FramebufferView  SurfaceView 绘制 + 触摸事件采集                            │
└───────────────┬──────────────────────────────────┬─────────────────────────┘
                │ QMP  (控制 / 输入)                │ HMP (screendump 取帧)
        ┌───────▼──────────────────────────────────▼───────┐
        │            QEMU（aarch64, TCG multi-thread）      │
        │   virt + GICv3 + cortex-a76 + virtio-input        │
        │   virtio-tablet / virtio-keyboard / ramfb         │
        └───────────────────────┬──────────────────────────┘
                                │ 客户机
                    iOS 风格桌面环境（用户自行提供）
```

关键实现取舍（都写在 `docs/engineering-notes.md` 里，含被推翻的结论）：

- **不靠 GPU、不靠 KVM**：TCG 纯软件模拟 + ramfb 软件渲染，因此在没有 KVM 的普通手机上也能跑。
- **取帧走 `screendump` 而不是共享内存**：客户机显示没有 EGL 表面，帧缓冲是 QEMU 侧的 ramfb，
  `screendump` 是唯一稳定可得的像素来源；配合退避与健康状态避免把 CPU 打满。
- **输入走 `input-send-event`**：复用 QEMU 自带的 virtio 输入设备，不修改 QEMU。

## 4. 构建

需要 JDK 17 与 Android SDK（`compileSdk 37`）。

```bash
# 调试包（体积大、可调试）
./gradlew :app:assembleDebug

# 发布包（R8 压缩）
./gradlew :app:assembleRelease
```

产物在 `app/build/outputs/apk/`。已构建好的调试包见本仓库的 **Releases**。

### 资源（需要自行准备，未入库）

以下二进制体积大且涉及第三方版权，**不放在仓库里**，构建前请自行放入 `app/src/main/assets/`：

| 文件 | 说明 |
|---|---|
| `QEMU_EFI.fd` / `QEMU_VARS.fd` | UEFI 固件（EDK2，开源，可自行编译或由 App 内置流程生成） |
| `qemu-android-bundle.zip` | Android 平台用的 QEMU 可执行文件打包（QEMU 为 GPLv2，可自行编译） |
| `hackios-toolchain.zip` | 客户机侧辅助工具链 |
| `mach.development.bcm2837` | 客户机内核。**属于第三方版权材料，本仓库不提供、不分发**，请自行准备 |

缺少这些资源时，Gradle 仍可编译通过，但 App 无法完成启动流程。

## 5. 运行

1. 安装 APK（Android 13 及以上）。
2. 首次启动按提示授予存储权限与（如需要）通知权限。
3. 在「启动」页让 App 完成固件准备与镜像构建（首次较慢，支持中断续做）。
4. 构建完成后点击启动，等待桌面出现（软件模拟下通常需要数分钟，取决于手机性能）。
5. 在「控制」页或直接点画面进行触摸操作；「日志」页可查看串口输出。

## 6. 已知问题

| 问题 | 状态 |
|---|---|
| 客户机触摸事件在部分镜像上无界面反应 | **未解决**。已定位到「虚拟 HID 服务已注册并收到事件、但系统未响应该事件」这一段，详见 `docs/engineering-notes.md` |
| 首次启动耗时长 | 软件模拟固有代价；已做启动脚本精简与后台竞争去除 |
| 部分服务缺位导致日志刷屏 | 精简镜像未包含激活链等守护进程，属已知噪音 |

项目**不虚构任何测试结论**：没有在真机上验证过的功能，文档里一律标注为「未验证」。

## 7. 目录结构

```
app/                    Android 应用
  src/main/java/...      Kotlin 源码（ui / engine / 服务）
  src/main/res/         资源与图标
  src/test/             单元测试
docs/                   设计文档、工程笔记、排障记录
screenshots/            界面截图
tools/                  辅助脚本
.maestro/               端到端测试编排
.github/workflows/      CI 配置
```

## 8. 版本

当前 `0.29.9 (49)`。逐版本变更见 [`docs/README-legacy.md`](docs/README-legacy.md) 与
[`docs/engineering-notes.md`](docs/engineering-notes.md)。

## 9. 许可证

源码以 **GNU General Public License v2.0** 发布，全文见 [`LICENSE`](LICENSE)。
第三方组件（QEMU、EDK2、Glasmio 等）遵循其各自许可证。

再次提醒：**本项目不分发任何 Apple 软件，也不提供任何绕过授权的能力。**
