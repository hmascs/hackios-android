# Android 0.3.5 — optional iOS block device

> **0.31.0 起新增 `PRIVATE_RW` 档**（默认仍为 off）：盘以读写方式挂给 guest，文件放在公共目录
> `Download/hackios/disks/ios-data-rw-<N>gib.qcow2`（拿不到公共目录时退回应用私有
> `files/ios-disks/`），跨重启保留，清 App 数据只影响私有回退路径。挂载时 boot-arg 用
> `virtio_blk=2`（内核驱动的写开关）；打包内核的资产里已带可写实现（能查到 `", writable"`
> 格式串），不需要重编内核。**仍未做到的是 iOS 自己的数据落盘**：APFS 驱动固定只读挂载，
> iOS 也不会自动格式化/挂载空的块设备。验收仍以 [ios-data-disk.md](ios-data-disk.md) 的
> 两次开机写入测试为准。

The default is **off**. An enabled disk is a **read-only block probe**, not an iOS data volume: the shipped XNU driver only handles `virtio_blk=1`, and `/private/var` remains RAM-backed. Choosing a larger capacity does not increase iOS system storage or persist apps/settings.

| Mode | What the engine does |
| --- | --- |
| `DISABLED` | No data disk argument, storage boot argument, or file access. |
| `PRIVATE_QCOW2` | Select 1–128 GiB (default 8). A new size provisions a blank, standalone QCOW2 v3 image with 256 KiB of metadata in `Context.filesDir/ios-disks/ios-data-<N>gib.qcow2`. |
| `PRIVATE_RAW` | Same limits and isolation, using `ios-data-<N>gib.raw`. Logical size is set with a single write at EOF, leaving a hole on Android's normal internal filesystems. This is a backend option; the UI need not expose it. |
| `PRIVATE_RW` | 0.31.0：boot-arg `virtio_blk=2`，选 1–128 GiB，在公共目录 `Download/hackios/disks/ios-data-rw-<N>gib.qcow2` 生成独立的 QCOW2（公共目录不可用时退回 `Context.filesDir/ios-disks/`），两个 block 节点都是 `read-only: false`，virtio-blk 仍用 `write-cache=off` 让 guest flush 落到宿主文件。文件跨重启保留；不同容量是不同文件。 |
| `LEGACY_PUBLIC_READ_ONLY` | Use only the existing `Download/hackios/disks/ios-data.qcow2` returned by `StoragePaths.iosDataDiskLocation`. If unavailable, missing, or unsuitable, return an explicit error. Never create, repair, move, resize, convert, or fall back from it. |

The private directory must come from `Context.filesDir`, not the shared Downloads root or the external app fallback. Capacity changes select distinct files; switching back reuses that size's existing file. There is no resize or automatic deletion. Uninstalling the app removes its private volumes. Formatting, writability, and migration are intentionally out of scope.

## Engine contract

Call `IosDataStorage.prepare(filesDir, publicDiskLocation, mode, sizeGiB)` on an I/O dispatcher **before** building the XNU payload or launching QEMU. Look up `legacyPublicDisk` only for explicit legacy mode. A `null` attachment means disabled; an exception means configured but unusable (do not silently switch images). Append `attachment.bootArg` once to the payload and append `attachment.qemuArgs` as separate `ProcessBuilder` arguments. The `virt` command still needs `-global virtio-mmio.force-legacy=false` for XNU's modern MMIO transport. Do not shell-join these arguments or append a second writable data drive. Put the attachment snapshot in the launch plan rather than reading live settings mid-boot.

Provisioning writes a private temporary image, forces completed writes and publishes by same-directory atomic rename under a private provisioning lock, after checking that no target exists (Android may deny hard links). Existing files must be regular, not symlinks; their size and format must agree with the selection. The QCOW2 header check refuses backing files, external/encrypted data, snapshots and unknown incompatible/autoclear features. It is not a replacement for QEMU's image validator. QEMU receives a separate read-only file node and explicit read-only format node (no format probing or automatic fallback), with QCOW2 backing explicitly disabled. Explicit legacy attachment is for the user's known local disk: shared storage is not immutable and must not be edited or replaced by another app while QEMU is starting or running. It is not an untrusted-image import feature.

## Verification and write gate

Host unit tests cover no-op disabled mode, size bounds/distinct files, reuse, raw contents, no overwrite on mismatch, legacy non-mutation, rejecting linked/external-data files, concurrent provisioning, and (0.31.0) the writable mode: read-write block nodes, public placement and reuse across calls, capacity-specific files, and the private fallback. Host `qemu-img check` accepted generated 1 and 128 GiB QCOW2s; suspended `virt` instances reported both block nodes and the attached virtio device read-only via QMP. An independent raw 4 GiB fixture also attached read-only. All fixtures were scratch files; these checks do not establish App-domain deployment or guest writability.

Keep all data disks read-only until a rebuilt XNU with writable virtio-blk and HFS+ passes the independent fresh-disk two-boot mount, write, fsync, remount and reboot verification in [the data disk plan](<ios-data-disk.md>). Even afterward, attaching or migrating the original public volume requires a separate safe rollout; do not reinterpret the 0.3.5 probe setting as consent to write to it.
