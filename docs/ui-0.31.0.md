# 0.31.0 · 首页驱动与存储、Adreno 门禁、去掉画面日志叠层

0.31.0 只改 App 层：首页承接驱动与存储，系统渲染后端换成两档下拉，导入驱动功能整体删除，
guest 画面不再叠加串口日志。镜像（`root.apfs` / `system.apfs`）、数据盘与已发布 APK 都没动。

## 首页（设备）结构

顺序固定为：状态横幅 → 启动卡（状态 / 阶段 / 启停 / 实时画面）→ 驱动 → 存储 → 引导阶段
（运行时）→ 环境 → 页脚。

- **驱动**
  - `系统渲染后端`：下拉栏，两个选项 —— `OSMesa`（CPU 软件光栅化，默认）与 `Adreno`（高通
    GPU，Turnip/Zink）。运行中或启动中禁用。
  - 行下方一行实时识别结果，例如 `本机 Adreno 830 · 高通 8xx 系列 · 已实测`。
  - `虚拟网络`：原在设置页，挪到这里；Adreno 档位固定开启（启动时网络驱动被强制为
    `virtio-network-user`），开关置灰。
- **存储**：实验数据盘容量（1–128 GiB）、四种模式（可写实验盘 / 只读私有盘 / 原有公共盘 /
  不挂载）、`固件与镜像位置`（点按复制**路径**）。

设置页相应只剩：性能、启动计时、MCP 服务与权限、QEMU 调试、外观、日志跟随、维护（清理残留 +
工具链自检）、关于。

## GPU 识别与门禁

`GpuProbe` 在 IO 线程建一个 1×1 的 EGL pbuffer + GLES2 上下文，读 `GL_RENDERER` / `GL_VENDOR`
后立即释放；失败返回「未识别」，不抛异常、不阻塞界面。**不从 `Build.SOC_MODEL` 猜 GPU 型号**
——SoC 到 GPU 的映射会漂移，猜错会让用户以为后端可用、到启动才失败。

判定在 `GpuIdentity`（纯函数，JVM 单测覆盖）：

| 识别结果 | 可选 Adreno | 行为 |
| --- | --- | --- |
| `Adreno (TM) 830` | 是 | 直接切换 |
| `Adreno 8xx`（810/820/829/840/899…） | 是 | 先弹提示：「理论上 8XX 系列 GPU 通用，但仅在 830 上使用过」，确认后切换 |
| `Adreno 6xx/7xx…` | 否 | 弹「这台设备的 GPU 不支持」，保持 OSMesa |
| 非高通 / 未识别 | 否 | 同上，附识别结果 |
| 安装包不含 Adreno 运行时 | 否 | 弹「当前安装包不含 Adreno」，保持 OSMesa |

`AppViewModel.setExperimentalGpu` 也做同一道门禁（不依赖界面），保存过的 Adreno 选择只在识别
通过后才恢复；否则写回 `false`，避免升级后带着一个当前设备不支持的档位启动。

## 删除导入驱动

`导入驱动配置或 ZIP 包` 这一整条功能删除：设置页入口、文件选择器、`importDrivers` /
`selectDriver`、`DriverPackageRegistry` 与其单测、`SettingsUi.driverSelections`、
`AppUiState.driverProfiles/driverBusy` 全部移除。启动时的驱动集合只剩内置三件套，网络按开关与
Adreno 档位选，输入与显示固定 `virtio-touch-keyboard` / `ramfb-2d`。

`docs/driver-packages.md` 保留为历史说明，并在开头标注已移除。

## 去掉 guest 画面上的日志叠层

- `QemuRunner` 的托管 cmdline 不再拼 `bootlog=1`，画面就是干净的 Apple Logo → 桌面。
- `QemuPreflight.CMDLINE_PROFILE` 6 → 7：payload 里烘着旧的 cmdline，不换号的话升级用户仍会
  看到日志面板。
- 内核资产不换（叠层只在带 `bootlog=1` 时绘制）；`AssetKernelTest` 的资产标记照旧，profile
  断言改成 ≥ 7。
- 串口日志本身不受影响：`serial.log` 照写，App 的「日志」页仍能看全过程。

## 存储：新增可写且持久的盘

`IosDataStorage.Mode.PRIVATE_RW`：QEMU 以读写方式挂一块独立的 QCOW2，文件在公共目录
`Download/hackios/disks/ios-data-rw-<N>gib.qcow2`（公共目录不可用时退回应用私有
`files/ios-disks/`），跨重启保留、清 App 数据只影响私有回退。容量不同就是不同的文件；已有
文件尺寸不符时明确报错，不重建、不覆盖。virtio-blk 仍用 `write-cache=off`，让 guest 的 flush
落到宿主文件。默认仍是不挂载。

**边界（必须说清楚）**：QEMU 侧以读写方式挂载、boot-arg 传 `virtio_blk=2`；打包内核的资产里能
查到可写分支的格式串 `", writable"`，说明可写实现已经编进现用内核（静态检查，未做启动实测）。
仍未做到的是 iOS 自己的数据落盘：APFS 驱动固定只读挂载，iOS 也不会自动格式化/挂载空的块设备；
要让它真正可用还需要 guest 侧的可写文件系统与挂载，并做两次开机写入验收。
详见 [iOS 持久数据盘](ios-data-disk.md) 的两次开机写入验收。所以在内核就绪前，UI 里这一档
的说明写着「guest 写入会被拒绝」，不把它当成已经可用的存储。

## 顺带修复

- `固件与镜像位置` 原来点按复制的是串口日志（接到了 `copyLog(Serial)`），现在复制路径本身。
- 编辑弹层（容量 / 端口 / 参数）从设置页抽成共用组件 `EditorSheet`，首页与设置页共用。
- 设置页删除驱动与存储后少了几处「运行中能点、点了没反应」的入口；`固件与镜像位置` 的复制路径
  修好后，排障时复制出来的是真实路径，而不是一串串口日志。

## 可写盘的 QEMU 侧实测

用 App 自带的那个 QEMU（11.1.2）以我们生成的 blockdev JSON 起一个 `-S` 空机器，QMP
`query-block` 返回：`format=qcow2`、`virtual-size=8589934592`、`actual-size=262144`、
`corrupt=false`，`ro` 未置位。这只证明参数与镜像被接受为可写，**没有**在 guest 里挂载写入。

## 验证

- JVM 单测：`AssetKernelTest`（资产、标记、profile ≥ 7）、新增 `GpuIdentityTest`（830 / 其它
  8xx / 6xx-7xx / 非高通 / 未识别 / 四位版本号不误判 / vendor 回退）。
- 已编译 `:app:compileDebugKotlin` 通过。
- **未验证**：没在真机安装这份 APK 实测；EGL 查询在部分厂商 ROM 上可能返回 ANGLE 包装后的
  renderer 字符串，届时会落进「非高通 / 未识别」分支（表现为 Adreno 不可选），需要真机日志再调。

## 产物

- `HackiOS-0.31.0-ui-gpu.apk`（versionCode 58，versionName 0.31.0-ui-gpu，188,750,749 字节，
  SHA-256 `b2a19efbb553c11c545677fc14d98e4081fcfc181de095cbb06faf88ee37cac3`），放在
  `/sdcard/Download/DSH-Go/`。debug 签名，与之前的 GPU 测试包证书一致覆盖不了旧包时会提示，
  需要先卸载（清 App 数据；镜像、数据盘、GPU 材料都在公共目录，不受影响）。
- 单测 332 项通过（含新增 `GpuIdentityTest` 7 项、`IosDataStorageTest` 3 项）。
