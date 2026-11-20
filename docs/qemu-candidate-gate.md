# iOS 专用 QEMU 候选验收

**结论：ChefKiss Inferno 通过隔离功能验收，可作为实验候选，不替换现有 APK 的默认 QEMU。** 专用设备模型不等于更快的 TCG。较老的 [alephsecurity/xnu-qemu-arm64](https://github.com/alephsecurity/xnu-qemu-arm64/tree/2694993f9281a8678d519704c3cd29d4c9d39415) 基于 QEMU 4.2.0，这次没有拿它试跑。

## 实测候选

- [Inferno 固定提交 `cc4302a`](https://github.com/ChefKissInc/Inferno/tree/cc4302a99167abec69b714cfd00c38caece7e7de)，版本 `ChefKissInferno-1.0.0rc1_QEMU-10.2.2_10.1.3_hybrid`，本次是手机上的 Linux ARM64/glibc 构建，仍使用双核 MTTCG。现版为 Android ARM64/Bionic QEMU 11.1.2。未测等 ABI 性能，未使用 KVM。
- 两边使用同一份 iOS 16.5 Adreno 载荷、已校验的 APFS RAM 背板（只读打开、写时复制）、新复制的 ESP 和 EFI 变量，不挂用户盘。保留通用 `virt`、GICv3、cortex-a76、virtio 输入、ramfb、受限 SLIRP 私有 GPU 转发和 QMP；不换为 Apple SoC 机型。
- 隔离早期对照各一次（秒，脚本启动至标志被观测）：

  | 里程碑 | 现版 11.1.2 | Inferno |
  | --- | ---: | ---: |
  | Darwin 横幅 | 11.46 | 9.17 |
  | launchd | 19.00 | 15.50 |
  | 首个有绘制的 1125×2436 Adreno 回读 | 86.69 | 69.57 |
  | 首个记录到的 BGRA IOSurface 交换 | 89.14 | 71.40 |

- Inferno 独立完整运行：`backboardd[11]` 打开远程 EAGL，宿主报告 Adreno 830/Turnip。QMP 连接后 **627.82 秒**获可识别 SpringBoard，随后确实打开 Settings（`Preferences[38]`，截图可见 Wi-Fi、通知等页面内容）；全尺寸回读计数 25 → 50。历史基线也有真桌面与设置，但不是在此轮同程序、交错多次测出的可比桌面时间。不得据此推算日常交互提速。

## 构建与复跑

1. 从固定提交拉取 `util/mlib` 和前置键码数据库；Meson ≥1.5。编译 `aarch64-softmmu`，最小配置显式启用 `slirp`、`tcg`、系统 FDT、`pixman`、`png`、`lzfse`、`nettle`（本地实测 3.10.2）、`vvfat` 和 `qcow1`。同时需要 `libtasn1`。`vvfat` 的可写 ESP 内部需要 qcow1 临时层，这不等于启用了用户盘。仅在候选源码的 `hw/arm/apple-silicon/meson.build` 补上漏声明的 `liblzfse` 链接依赖；未改 CPU 或显示逻辑。
2. 工作台 `hackios-run/qemu-inferno.tfi5zu` 中保留源码、`virt-smoke.py`、`boot-candidate.sh`、`compare-early.py`、`early-compare-verified.log`（现版）和 `early-inferno-qcow.log`（候选）。先跑最小 ARM64 来宾检查双 vCPU、私有 chardev、QMP 输入和 COW RAM；再用 `compare-early.py --seconds 220 CANDIDATE BASELINE`，候选动态库路径指向该工作台的独立依赖。这些脚本只复用本机已有的固件、ESP、已验 APFS 和 GPU 测试载荷，不含用户数据盘或可重新分发的 iOS 镜像。
3. 完整验收继续运行 `boot-candidate.sh CANDIDATE 960`，对其打印的全新隔离目录用 `tools/verify-ios-desktop.py QMP REFERENCE OUTPUT --gpu-log WORKER_LOG --interaction settings --timeout 650`。必须同时核验串口中的合成进程 PID、实绘回读、IOSurface、主屏和 Settings 截图，最后对该隔离 QMP 发 `quit`。

## Android 驱动部署与回退

候选当前是 Ubuntu/proot 内的兼容验证，不是可以直接导入的 Android 驱动包。下一道发布门槛是用 Android NDK 构建 ARM64/Bionic（保持同一编译器/优化参数构建对照版），校验可执行 ELF、其依赖与打包许可证，在普通 App 进程权限下重新测试 `virt`、Adreno 与 OSMesa、QMP、触摸和只读块设备。仅做成显式选择的隔离 QEMU 驱动，按版本单独缓存，不覆盖内置 11.1.2；运行时绑定 App 私有目录内经验证的二进制，不从共享目录执行。失败只提示用户选择内置 QEMU 并重新启动，不在同一次启动中自动切换或改变数据盘。正式默认切换还须在同机同 ABI 交错至少三组，把可交互主屏、固定 CPU 工作、输入到新帧的延迟分别测量，确认无明显回归。

优先评估把有收益的设备路径移植到现行 QEMU。若测试完整替代版，使用独立二进制和每次新建的 ESP、EFI 变量与测试磁盘，保持用户镜像不变。先通过相同 XNU/主屏/输入/只读块设备功能；GPU 接通后，两边还须跑同一系统渲染路径并校验输出，不能把 OSMesa 和 Adreno 的差异归因给模拟器。随后在同机交错运行，固定 vCPU、客体素材和分辨率，报告进入可操作主屏的时间、固定客体 CPU 工作量、系统帧完成和输入响应的中位数及尾延迟，不能用截图频率代替渲染性能。整套功能通过且收益可复现后再考虑切换；独立 GPU 接线不等待替代分支。
