# iOS 持久数据盘：固定目录

- Android 公共目录：`/storage/emulated/0/Download/hackios`（常见别名 `/sdcard/Download/hackios`）。
- 数据盘固定为 `disks/ios-data.qcow2`，QCOW2 单文件，无 backing file，容量 8 GiB（8,589,934,592 字节）。空盘按需增长，并不预先占满 8 GiB。固件仍在 `ipsw/`，系统镜像仍在 `images/`，不将数据盘与启动内存映射 `ram.img` 混用。
- 未来 App/驱动整合使用 `StoragePaths.iosDataDiskLocation()`。如果无法访问公用目录，应提示权限问题；不得静默改用私有目录的新空盘，也不由安装、启动或镜像重建来覆盖/格式化已有盘。

## 验收步骤

首先检查宿主镜像容量与完整性，然后以只读方式向临时 XNU 测试 VM 添加 virtio-blk。运行器会在临时 payload 启用 `virtio_blk=1`，检查客户机真实的 `/dev/rrvblk0` 是否为 8 GiB 并读取一个扇区：

```sh
DISK=/storage/emulated/0/Download/hackios/disks/ios-data.qcow2
qemu-img info --output=json -f qcow2 "$DISK"
qemu-img check -f qcow2 "$DISK"
python3 qemu/gpu/test-xnu-guest.py QEMU_RUN RAM_IMG PAYLOAD ROOTFS MKAPFS NEW_TRIAL \
  --ld64 /path/to/ld64.lld --probe-data-disk "$DISK"
```

本机实测：固定 ESP 的 `bootindex` 并启用 `virtio_blk=1` 后，XNU 注册了 `/dev/rvblk0` 和 `/dev/rrvblk0`，报告 16,777,216 个 512 字节扇区；iOS 探针读取了完整的一个扇区，镜像 SHA-256 前后相同。原先查找 `/dev/disk0` 等通用节点不适用于该驱动。

0.31.0 已在 App/QEMU 侧加好 `PRIVATE_RW`：可写挂载（boot-arg `virtio_blk=2`）+ 公共目录持久文件。**这不等于存储验收通过**，下面这些内核/guest 前提仍然成立。

关于内核：打包的资产内核里能查到可写分支的格式串 `", writable"`，说明 `virtio_blk=2` 的可写实现**已经编进现用内核**（静态检查；尚未做启动实测），不需要为了允许写而重编内核。剩下的门槛是可写文件系统与 guest 侧挂载，不是 QEMU 后端。该磁盘目前**还不是 iOS 的可写数据卷**，`/private/var` 仍在 RAM。需在独立临时磁盘验证文件系统挂载、同步及重新开机读取后，才考虑迁移或正式启用用户磁盘。仅启用可写 QEMU 后端不是存储验收。

准备好含可写 virtio-blk 和 HFS+ 的 [HakiOS 内核构建](https://github.com/oosexgang/hackios-xnu-kernel/blob/63fc2b4633bac3af8e57dadab9f5a8d4cb940438/docs/getting-started/building.md) 后，可用两次启动验收器（须使用支持 `-static` 的 cctools `ld`，以及 `mkfs.hfsplus`）：

```sh
python3 qemu/gpu/test-xnu-storage.py QEMU_RUN KERNEL_TREE REBUILT_KERNEL NEW_PRIVATE_OUTPUT --ld64 CCTOOLS_LD
```

在本机 ARM64 Ubuntu，已从 [Theos ARM64 工具链发布包](https://github.com/L1ghtmann/llvm-project/releases/tag/test-210562a) 取出 cctools `ld64-609`，确认它链接出的测试 PID 1 为 ARM64 `LC_UNIXTHREAD`，无 dyld 和 `LC_MAIN`。Ubuntu ARM64 的 `hfsprogs` 也已在新的 128 MiB 私有镜像上格式化并校验卷头。上游固定提交携带的预构建内核与现有只读内核 SHA-256 完全相同；仍需重新构建含写驱动的内核。这些工具检查没有接触用户磁盘。

该工具拒绝旧的只读预构建内核，在全新目录创建单独的 8 GiB 临时盘；只有两次开机都报告可写块设备、HFS+ 文件写入同步和重新挂载校验，且第二次读到了上一轮的计数，才会通过。此测试目前尚未在重编内核上通过，不能用临时盘替换实际用户镜像。
