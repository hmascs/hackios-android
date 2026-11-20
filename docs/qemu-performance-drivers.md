# QEMU 显示性能与虚拟设备接口

## 本机测量（2026-11-18，V2463A / SM8750）

Android 宿主的 `ro.hardware.egl=adreno`，当前执行环境没有 `/dev/kvm`。Android QEMU 11.1.2 用 `virt`、2 vCPU、MTTCG 和 `ramfb` 跑到了真正的 iOS SpringBoard。实验用复制的固件变量、ESP、RAM/APFS，QMP 和 VNC 只监听私有 Unix socket；没有替换安装中的 APK 或运行时 QEMU。

| 层 | 实测 | 解释 |
|---|---|---|
| iOS / Redwood | 首两帧分别 7.905 s、6.267 s | 来自客户机启动期串口，不代表稳态 FPS |
| QMP 抓屏 | 1125×2436，约 17.79 个样本/秒；抓屏中位 41.14 ms，P95 51.49 ms | 10 秒屏幕稳定段，只有 1 次内容变化。样本数不等于客户机渲染速率 |
| Unix RFB Raw 对照 | 1125×2160，约 5.06 个样本/秒；中位 172.96 ms | 另一阶段的 8 秒观测，未经同步负载对照；裁掉底部 276 行，且慢，不集成到 App |
| QEMU 原生 PPM 函数 | 1125×2436：单帧写入调用 2,437 → 78；编码中位 17.76 → 10.06 ms | 本机 C 测试，编译原版和补丁版函数，用 Pixman 和写文件适配器替代完整 QEMU 构建；不是端到端 FPS |

### 为什么修 QEMU 的 PPM

QEMU 11.1.2 的 `ppm_save()` 按 Pixman 行跨度输出，每个 1,125 像素的 RGB 行多写一个字节。按标准 PPM 处理会让主屏出现明显横纹和斜移。补丁改为每 32 行转换和写入一次，并只输出每行有效的 RGB 字节。Android 解码器同时兼容旧版对齐的输出和修正后的 PPM，不会再当作头部空白丢掉首个像素的颜色字节。

补丁目标是 [QEMU v11.1.2](https://github.com/qemu/qemu/tree/v11.1.2)，校验方法：

```sh
bash tools/test-qemu-ppm.sh /path/to/qemu/ui/ui-qmp-cmds.c
# 在 QEMU 源码根目录：
patch --dry-run --fuzz=0 -p1 < /path/to/hackios/qemu/patches/0001-ppm-batch-packed-pixman-rows.patch
```

整个 Android QEMU 二进制尚未重编或部署。补丁本身不改变 guest 分辨率、渲染速度或当前 App 取帧分档。

## App 侧虚拟设备模块 API v1

- `VmDriver` 的每个实现占一个 `VmDriverSlot`，提供唯一 ID、API 版本和启动参数。`VmDrivers` 拒绝重复 ID、错误版本和槽位错配。`QemuRunner` 记录选中的模块。
- 目前接入 `virtio-network-user` / `network-none`、`virtio-touch-keyboard`、`ramfb-2d`。三个位置在 QEMU 参数序列中不变，以免改变客户机 virtio-mmio 枚举次序。
- 新模块应在启动阶段注册，由 App 管理配置，配 argv、设备探测和客户机功能测试。当前是**源码级可替换、重启生效**，不是动态加载外部驱动或客户机内核热插拔。
- 声卡、传感器等不能只加 QEMU 设备就标为支持：音频必须验证从 iOS CoreAudio 到 Android 播放；输入要验证 QMP 注入后能到达 iOS；网络要验证 DHCP、数据包及 DNS。

## 取帧调度（0.32.0：取消人为帧率锁）

- App 侧原来的固定 8 FPS 和「引导期 1 FPS / 静止 1 FPS」已移除：`Constants.FB_FPS = 60`，`FramePacer.nextIntervalMs` 只返回目标间隔（16 ms）或慢取帧退避（1.8 s）。引导和桌面用同一节奏，不再因为「阶段低」降帧。
- 16 ms 是**请求预算**，不是实测帧率。本机早先测得 QMP `screendump` 约 17.79 个样本/秒（中位 41.14 ms），每次还要写 8.2 MB PPM 并解码；Adreno 全画幅回读在串口里是每帧 0.6–1.6 秒。管线只在「本次取帧比目标更快」时才补睡眠，慢的时候直接发下一条，所以目标值不会造成请求排队，也不会把实测值假装成 60。
- 因此「拔高到 60 帧」的已验证部分是：不再有 App 侧人工上限、引导期不降帧、诊断显示实测 fps；**未验证**在真机上达到 60 FPS 的观感——上限由 `screendump`/解码和来宾合成速度决定。若要在真机上确认，需要安装新 APK 后读 perf-stats 的实测 fps 与单帧耗时，本仓库环境没有做这一步。

## CPU 专核与辅助线程隔离实验

本机执行环境允许核 `0-7`；`6-7` 的最高频率为 4.32 GHz，`0-5` 为 3.53 GHz。实验先通过 `query-cpus-fast` 确认每个 vCPU 的宿主线程 ID，再从 `/proc/<qemu-pid>/task/<tid>/stat` 测实际 CPU 时间。`--isolate` 为每个 vCPU 分配不同的 CPU（快核优先），将当时存在的其他 QEMU 线程移到其余核，至少预留 2 核。每个掩码由内核回读验证，配置失败或实验结束时恢复；这不是系统级 CPU 独占，Android 的其他任务仍可在这些核上运行。

| 真实 iOS 试跑 | 调度器默认放行 0–7 | 实验逐线程隔离 |
|---|---|---|
| 2 vCPU，约 6 秒 | 1.911 核当量 CPU 时间，两个 vCPU 的最后运行 CPU 有 1/59 个间隔重复 | 分别只在 6、7 运行，合计 1.931 核当量，0/59 间隔重复；测试后回读 0–7 |
| 4 vCPU，约 5 秒 | XNU 报 `4 of 4 processors are running`；四线程都累积 CPU 时间（合计 1.402 核当量），21/49 间隔有最后运行 CPU 重复 | 分别只在 6、7、0、1 运行，QEMU 辅助线程允许 2–5；合计 1.245 核当量，0/49 间隔重复 |

“最后运行 CPU”是间隔末采样，不证明时间段内有多少同时争抢；核当量不是帧率，两个窗口的 guest 负载也非恒定。4 vCPU 的两轮自动滑页未观测到画面变化（第一轮黑屏，第二轮 QMP 虽确认动作但 scanout 无变化），不用于比较主界面速度。现有 App 默认档不变。

采用策略：先在同机、同渲染路线、同 TB 缓存和同一桌面状态下交错比较 2 / 4 / 6 vCPU，记录 XNU 核枚举、启动时间、重复滑页的帧时 P95、输入到画面变化延迟和持续运行温度；取通过无回退门槛的最大配置。8 vCPU 可以作为不绑核对照，但 8 核宿主不能同时满足每 vCPU 一核与辅助线程预留。最终若启用分核，也要在 Android 普通 App 域验证当前 cpuset、失败自动恢复及新建线程的亲和性，而非只在 PRoot 实验通过。

```sh
bash tools/boot-ios-cpu-trial.sh 4 /path/to/run /path/to/ram.img /path/to/payload.bin
python3 tools/measure-vcpu-scheduling.py /path/to/qmp.sock QEMU_PID --seconds 10
python3 tools/measure-vcpu-scheduling.py /path/to/qmp.sock QEMU_PID --seconds 10 --isolate
python3 tools/measure-framebuffer.py qmp-dump /path/to/qmp.sock --input-socket /path/to/input.sock --seconds 15
```

## iOS 固定 CPU 负载对照

在同一台手机按 2 → 4 → 6 → 2 vCPU 串行运行；每次启动运行 3 轮，每轮由 iOS 的 6 个 pthread 执行固定的 RGBA 通道混合与校验。保持系统镜像、Cortex-A76 模型、MTTCG 和 1360 MiB TB 缓存不变；四次启动均通过 Adreno 的 Vulkan 探针。12 个 CPU 样本的结果摘要均为 `defb1e6d620fe8f4`。

| vCPU | 样本数 | 耗时中位数 | 最小–最大 |
|---|---:|---:|---:|
| 2 | 6 | 974 ms | 272–11,935 ms |
| 4 | 3 | 165 ms | 95–294 ms |
| 6 | 3 | 199 ms | 141–209 ms |

四核是这个负载的提速候选，不是新的 App 默认档。双核样本波动较大（测试发生在系统启动期），不能把比例推广到稳态、单线程或桌面性能。双核和四核都在 720 秒窗口内通过了同一套主屏验收；从 GPU 探针 ACK 后的 QMP 连接到主屏匹配，双核为 458.23 秒，四核为 528.97 秒。各只采一次，这不是冷启动计时，也不足以证明哪档整体更快。App 继续默认 2 核，四核作为并行任务的可选档。

复测：在 [XNU 启动命令](gpu-backends.md) 增加 `--vcpus 2|4|6 --cpu-samples 3`，不同试验使用新目录，不要并发运行。桌面验收使用同一运行器的 `--desktop-reference PATH`，必须使用此前真实的 SpringBoard 主屏图作为参照；QMP 捕获只证明桌面状态，不证明 GPU 参与。

## CPU 转译候选（第 5 阶段，尚未开始）

目标要求的 CPU 实验只能在本阶段全部门槛之后进行；以下是已经核对过、但**没有运行也没有提速数据**的候选，不能当作计划收益：

- 当前 Inferno 构建已有 `max`、`cortex-a76` 和 Apple 专用模型（`apple-a9-cpu`、`apple-a13-cpu`、`apple-gxf`）。`pauth` 默认属性为开，`pauth-impdef`/`pauth-qarma3`/`pauth-qarma5` 必须显式加 `pauth=on`，三者互斥，且只有模型声明支持 PAC 时才存在这些属性。`pauth-impdef` 把指针认证换成实现定义的快速模拟，只在来宾真的执行 PAC 指令时才有收益；外部流传的「约 50%」不是本机测量。
- 换 `-cpu max` 或 Apple 模型会改变来宾可见的 ISA/寄存器/缓存描述，属于系统协同设计，不是等价加速档位。必须固定镜像、运行时 QEMU、GPU 配置与 vCPU 数，交错重复测启动、固定 CPU 工作、桌面交互和输入到新帧延迟，并同时记录 TCG 翻译/执行、来宾工作、GPU 等待和热降频；任何提升只按对照结果认定。
- TB 缓存（`tb-size`）、大页、AArch64 指令复用、LLVM 热块/寄存器映射和受限 `libSystem` 混合执行都还只是待验证设想。

## GPU 与 60–90 FPS 的验收边界

当前 iOS 画面是 Redwood 将 CoreAnimation 的 GLES 提交交给客户机 OSMesa 后再写入 ramfb。公开 [Redwood EAGL 实现](https://github.com/b1ackm4x/hackios-xnu-userland/blob/main/userland/src/eagl.c) 已有 context/函数表/IOSurface 接口，下一环是在该 GLES 后端引入独立传输层、保留 OSMesa 回退。

GL 和 Vulkan 宿主模块已在 Adreno 830 上通过真实 iOS 探针的像素验收，尚未接入 SpringBoard。构建、复测、Adreno 硬件路径及测试协议的范围见 [GPU 后端与设备验收](gpu-backends.md)。不能把 Android Vulkan/EGL 能力、ramfb 传帧或样本数当作 iOS 桌面已获得 GPU 加速。

验收至少分三组：客户机提交/完成的帧和帧时、QEMU scanout 的内容变化和搬运耗时、Android 上屏间隔。对 SpringBoard 持续滑页/系统动画分别记录 60 FPS（16.7 ms/帧）和 90 FPS（11.1 ms/帧）预算的达成情况，不能用静止画面抓取次数代替。

```sh
python3 tools/measure-framebuffer.py qmp-dump /path/to/qmp.sock --seconds 10
python3 tools/measure-framebuffer.py unix-rfb-raw /path/to/vnc.sock --seconds 10
```

QMP 截图从 QEMU 进程写文件，工具应和 QEMU 共享同一文件系统命名空间。两种模式分别输出抓取采样率、内容变化率、分辨率和中位/P95；后者仍可能漏掉两次采样之间的 guest 帧。
