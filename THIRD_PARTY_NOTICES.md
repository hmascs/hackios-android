# 第三方组件与源码范围

本文件区分 Android App 源码、配套 XNU 内核源码与运行资源。根目录 [LICENSE](LICENSE) 保留 App 项目的 GPL v2 文本，不覆盖第三方组件的原有许可。

| 组件 | 范围与来源 | 许可/材料 |
|---|---|---|
| Android App | 本仓库的 Kotlin、测试、资源与构建脚本；版本以发布源码清单为准 | 根目录 GPL v2；文件已有第三方声明保留 |
| XNU 内核及修改 | 配套独立源码档案；基于 Apple XNU `xnu-8796.121.2`，上游基点 `c54f35ca767986246321eb901baf8f5ff7923f6a`；HakiOS 补丁、驱动及实际构建树由对应档案清单列出 | APSL 2.0 及各文件原有许可；内核档案应保留 `APPLE_LICENSE`、`LICENSE.APSL`、`NOTICE` 与第三方清单，不能统称为 App 的 GPL v2 |
| HakiOS 辅助程序与引导工具 | 独立上游项目的 `userland`、loader、工具等；具体采用文件及修改见源码清单 | 该项目自有非内核部分声明为 MIT；第三方部分按原文件许可，不覆盖其待核条目 |
| QEMU | CPU/设备模拟器，CPU 运行采用 TCG；相关补丁与构建脚本在 `qemu/` | QEMU 的 GPL 及各文件原有许可；发行二进制应匹配对应版本源码、补丁与构建信息 |
| Mesa / Zink / Turnip | 实验性 Adreno GLES→Vulkan 后端及其运行依赖 | Mesa 和依赖组件各自许可；GPU 包内保留的许可说明及发布清单应一并提供 |
| EDK2 / UEFI 与 loader | 引导资源，按实际构建输入区分项目源码与设备厂商固件 | 保留各自许可；不要把设备专有固件、Apple SDK 或系统镜像当作本仓库自有源码 |
| Compose、Glasmio 等 Android 依赖 | Gradle 声明的依赖及版本 | 遵循各依赖的许可证；依赖声明不是对全部资源的重新授权 |

## 0.4 发布身份

- App：`com.android.gudao.iphoneos`，版本 `0.4`，versionCode `68`。
- 发布的 GPU guest dylib：SHA-256 `13d6431ce875ba2c3efb3ef139882e6a614ff3aada0d21ebddf333da3959e74b`。
- 发布的 GPU worker：SHA-256 `cc7ffe7cfa00afa921298aac0f4f2ce1821ff05d5a4776f5096a82a03645c4a3`。
- App 源码包须包含生成上述组件的 HTB1 源码版本及其来源说明；旧目录中的历史 GPU asset 或旧 main 分支不能替代该身份。
- 配套内核源码档案须标出实际发行内核 SHA-256 `7b1f908324fa13154c065f917555852406ddfbfc654f6c247c78c6d6a0644f17` 与对应构建来源。源码准备完成、构建可复现与某次启动验收是不同证据，不互相替代。

## 不包含的专有材料

公开源码材料不应包含 iPhoneOS/macOS SDK、IPSW/系统镜像、dyld 共享缓存、系统应用、设备密钥、签名私钥或用户数据。构建所需但不随源码包提供的输入，应在构建说明中列出取得方式、版本及校验要求。已存在的固件或来源待核条目应从实际归档清单单独审查，不能因为位于上游工作目录就默认纳入发布。

本说明是组件归属与发布清单说明，不替代各组件的完整许可文本。
