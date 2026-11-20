# Adreno 共享镜像备份与恢复

适用于带 Adreno 运行时的测试包。OSMesa、固件、现有系统镜像和用户数据盘的路径与行为不变。

## 持久化内容

有共享下载目录权限时，应用在 `Download/hackios/gpu/adreno-v2/<key>/` 保留两个普通数据文件：

- `stage.tar`：从可信构建树验证后导出的 Redwood 根卷重建材料，包含文件、目录和客体符号链接；不执行其中内容。
- `root-gpu.apfs`：Adreno 根镜像备份，与 OSMesa 根卷完全分离。`key = SHA-256(SHA-256(OSMesa root.apfs) || APK 内置 Adreno dylib 的字节)`。不同构建内容不会共用同一个目录。

运行时 Python、打包器、QEMU、GPU worker 和动态库不从共享目录加载或执行。应用用 APK 中的资产部署运行时，清数据后可重新部署；GPU 根镜像经验证后原子迁入应用私有目录供 QEMU 使用。没有共享权限时仍可完成私有构建，但无法保证下次清数据后保留 Adreno 备份；需要恢复共享目录权限后重试。

## 为什么不直接采用共享镜像

共享区中的 SHA 文件可以跟镜像一起被改写，不能作为信任来源。恢复会把匹配的 OSMesa 根卷及有界归档先复制到私有临时目录，使用 APK 内置解析器限制路径、类型、数量、大小，拒绝穿越、重复路径和宿主硬链接。用 APK 自带打包器将归档重建成 OSMesa 镜像，只有与当前选中的标准镜像字节一致，才应用 APK 内置 Adreno 库及固定启动修改。得到的镜像与共享 APFS 的私有快照校验一致才会采用该快照；如共享 GPU APFS 缺失或损坏，直接使用此次私有生成的镜像。这个校验绑定到当前已使用的 OSMesa 镜像，不为手动替换的原始 OSMesa 镜像提供独立真实性保证。已有共享备份不会被后台静默覆盖。

## 验收口径

- 本机当前稳定根卷已在隔离目录做确定性复打包，字节一致。不清除现有 App 数据。
- 使用实际共享根卷与 APK 内置 Android ARM64 Python 3.14、APFS 打包器，从空工作目录验证恢复；生成镜像与正常构建相同，SHA-256 `0fb9744f6f5c9cde346efc5de9a5db58d812680a7a4ee5ece991ef17c613804f`。
- 修改备份的 `etc/rc.jobs` 后，复打包不一致，恢复拒绝且不生成 GPU 根镜像。离线归档测试：`python3 -B tools/test-gpu-stage-archive.py`；Android 文件存储测试：`GpuImageStoreTest`。
- 普通 Android App 域的端到端清数据测试需在独立测试安装验证：重新授予共享目录权限 → 部署 APK 资源 → 选择 Adreno → 用同一 `images/root.apfs` 启动，确认出现恢复日志而非要求重编 IPSW，且实际进入主屏。不要拿现有 App 私有数据做破坏性试验。

## 0.30.1：以后生成的 Adreno 材料也落公共目录

- App 生成 Adreno 根时，除了原来的 `stage.tar` 与 `root-gpu.apfs`，现在还会把 `gpu-libredwood-gl.dylib` 和 `materials.txt` 写进 `Download/hackios/gpu/adreno-v2/<key>/`。清单不含时间戳，只有 key 与三个文件的 SHA-256，另有「`stage.tar` 必须以 `root.apfs` 为名打包才与 OSMesa 根逐字节一致」的说明。
- 写入仍走 `GpuImageStore.publish`/`publishManifest`：已存在的公共文件一律不覆盖，清单先写临时文件再原子改名；公共目录不可写时只记一行日志，私有镜像照常可用。
- 实测过的复现路径：用上游 `tools/apfs/apfs.py` 从当前 `root.apfs` 提取目录树，再用 APK 自带的 `mkapfs.py` + 工具链里的 Termux Python 3.14 打包成名为 `root.apfs` 的文件，与现行 `root.apfs` **逐字节一致**（mkapfs 的容器 UUID 以输出文件名为种子）。据此补出的 `2074230852…` 目录含 `stage.tar`（`2741bcfd…`）、`root-gpu.apfs`（`f6d58574…`）、`gpu-libredwood-gl.dylib`（`4e9221a7…`）与 `materials.txt`。
- 新增 2 项 `GpuImageStoreTest`（清单内容/非法 key、清单不覆盖），JVM 单测 327 项通过。APK：`HackiOS-0.30.1-bootlog-gpu.apk`，versionCode 57，SHA-256 `a1eafe14…563b`。
