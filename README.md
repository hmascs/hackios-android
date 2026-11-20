# HackiOS for Android

**在 Android 手机上探索 XNU 与 iOS 用户空间的运行。**

HackiOS 将 QEMU、自编 XNU 内核和 Android 原生前端整合在一起，提供固件准备、镜像构建、系统启动、画面显示与触摸控制。界面采用 Jetpack Compose 与 Glasmio，支持深浅色主题。

[下载 0.4](https://github.com/hmascs/hackios-android/releases/tag/v0.4) · [App 源码](https://github.com/hmascs/hackios-android/tree/v0.4) · [XNU 内核源码](https://github.com/hmascs/hackios-android/releases/download/v0.4/HackiOS-XNU-0.4-source.tar.gz) · [反馈问题](https://github.com/hmascs/hackios-android/issues)

> **0.4 · Android 13+ · ARM64**
>
> 包名：`com.android.gudao.iphoneos` · versionCode：`68`

## 主要功能

- **手机端镜像准备**：固件下载支持续传与校验；镜像构建带阶段记录，便于中断后继续。
- **两种图形后端**：OSMesa 软件渲染，以及实验性的 Adreno GPU 渲染。在首页选择后端、查看设备与运行资源状态。
- **原生分辨率显示**：保持 **1125 × 2436**，从最终帧缓冲获取画面。
- **触摸与控制**：通过 QMP 和 virtio 输入设备传递点击、滑动及按键事件，并保留串口日志供排查。
- **独立数据盘**：OSMesa 与 Adreno 均可选择数据盘模式、容量和是否接入；不同容量使用独立文件。
- **开放源码**：App、GPU 桥接实现及自编 XNU 内核源码分别提供，并附构建说明、来源与校验值。

## 开始使用

1. 从 [0.4 Release](https://github.com/hmascs/hackios-android/releases/tag/v0.4) 下载并安装 `HackiOS-0.4.apk`。
2. 首次打开时授予所需存储权限，按 App 引导准备固件与系统镜像。专有系统材料需由使用者自行合法取得。
3. 在首页选择图形后端。如需连接数据盘，在“存储”中选择模式与容量。
4. 构建完成后启动系统。首次构建和 TCG 启动可能需要较长时间，可在日志页查看实际进度。

**升级说明**：0.4 恢复原包名，并沿用原版签名。原包名的旧版可覆盖安装；临时使用其他包名的版本有独立的 Android 私有目录，跨包名安装不会自动迁移其中的数据。不要为升级而删除原有磁盘或镜像。

### 运行要求

| 项目 | 要求与当前范围 |
| --- | --- |
| Android | Android 13 及以上 |
| 处理器 | ARM64；客户机 CPU 通过 QEMU TCG 模拟，不要求 Root 或 KVM |
| 内存 | 当前客户机配置使用 7 GiB；宿主还需为 Android、App 与翻译缓存保留余量 |
| 存储 | 预留固件、解包镜像、构建产物与运行内存文件所需空间 |
| 软件渲染 | OSMesa 路径可用，速度取决于 CPU 与负载 |
| GPU 渲染 | 当前限定 Adreno 8xx；**已实测 Adreno 830**，其他 8xx 型号未完成验收 |

## 0.4 更新

- 恢复 `com.android.gudao.iphoneos` 包名，版本号更新为 **0.4**。
- 为 Adreno 后端接入数据盘，磁盘参数由 App 按实际选择生成，避免旧启动参数与界面设置冲突。
- 引入有界的 GPU 命令批处理，减少小命令逐条等待回复；遇到旧 worker 时保留串行回退。
- 修复 IOSurface 差分基线、尾字节指纹、顶点索引及纹理通道映射问题，保留完整有效像素回写。
- 使用最终 RAMFB 画面作为显示来源；离屏 GL 回读环保留诊断用途。
- 改善触摸释放、启动状态提示、资源操作互斥与有效 NVRAM 的保护。

完整变化与附件校验值见 [发布说明](https://github.com/hmascs/hackios-android/releases/tag/v0.4)。

## GPU 与数据盘的当前状态

**GPU 加速的是图形命令，客户机 CPU 仍使用 TCG。** 当前图形链路为：

```text
客户机 GLES → remoteGL → Zink → Vulkan → Turnip → Adreno GPU
```

Adreno 默认启用命令批处理。如需对照，可在“设置 → 内核启动参数”填入 `glbatch=0`，于下次启动关闭。

**数据盘接入与 iOS 用户数据持久化是两个阶段。** 0.4 已允许 GPU 后端连接所选块设备；独立临时盘已通过 HFS+ 写入、同步、卸载重挂和第二次启动读回。当前常规系统的 `/private/var` 仍在 RAM 中，App 不会自动格式化现有盘或将该目录迁移到磁盘，因此不能据此保证整个 iOS 状态跨重启保留。

## 已验证与已知限制

| 验证项 | 0.4 结果 |
| --- | --- |
| Android 单元测试 | 45 个测试套件、376 项通过，0 失败/错误 |
| 安装包身份 | 原包名、版本、签名证书与包内组件哈希已核验 |
| GPU 原生对照 | 9 组 shader 场景开关批处理后像素与 GL 错误一致；请求数由 201 降至 153 |
| 数据盘短验收 | 相同发行内核下，新建 128 MiB HFS+ 盘完成两次启动读回，网络和输入设备正常识别，QCOW2 检查通过 |

GPU 请求数变化描述的是原生测试负载，**不等于整机帧率提升**。原分辨率下实际可见内容 **10 FPS 仍是优化目标，尚未通过验收**。部分图形场景、完整系统中的 GPU 与存储组合、其他设备兼容性及连续动画仍需进一步测试。

数据盘测试使用专用最小来宾，未运行 SpringBoard 或 GPU 绘制，也未访问、格式化或覆盖用户已有磁盘。完整测试范围见 Release 中的 `HackiOS-0.4-storage-test.json`。

## 源码与构建

0.4 Release 提供以下材料：

| 文件 | 内容 |
| --- | --- |
| `HackiOS-0.4.apk` | 可安装的 0.4 版本 |
| `HackiOS-0.4-source.tar.gz` | 对应的 App、GPU 桥接源码与精确 GPU assets |
| `HackiOS-XNU-0.4-source.tar.gz` | 自编 XNU 与 HFS 源码、配置、构建脚本、来源及许可证 |
| `HackiOS-0.4-resource-manifest.json` | 较大启动资源的完整哈希与恢复位置 |
| `SHA256SUMS-0.4.txt` | 发布附件的 SHA-256 校验清单 |

App 构建需要 **JDK 21** 和 **Android SDK 37**。先按 [0.4 构建说明](https://github.com/hmascs/hackios-android/blob/v0.4/BUILD_0.4.md) 恢复源码树未包含的运行资源，再执行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug -PgpuTest=true
```

`-PgpuTest=true` 会将本版 GPU assets 纳入安装包。签名私钥、Apple SDK 与专有系统镜像不包含在源码中。

内核归档中的源码关联到已与 APK 核对一致的本地构建产物，并保留来源和排除项清单；**本次尚未完成干净环境逐字节重建验收**。构建所需的外部工具链与 SDK，以及被排除的可选硬件固件，均须按归档说明另行准备。

## 许可与项目说明

Android App 源码采用 [GPL v2](LICENSE)。XNU 及其修改保留 **APSL 2.0** 和各文件原有声明，其他依赖遵循各自许可证；详见 [第三方组件说明](https://github.com/hmascs/hackios-android/blob/v0.4/THIRD_PARTY_NOTICES.md)。

HackiOS 是独立的研究项目，与 Apple Inc. 无关联，未获其授权或认可。项目不提供专有系统镜像或绕过授权的能力。Apple、iOS、iPhone 等商标归各自权利人所有。使用与再分发请遵循相应组件和系统材料的许可，实验前备份重要数据。
