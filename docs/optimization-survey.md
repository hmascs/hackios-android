# 性能优化点清单（持续更新，2026-11-19）

目的：把「还能在哪里省时间」列成可执行清单，每项都标**依据**（实测 / 读码 / 推断）与
**预期量级**，并按「对实际内容帧率的影响」排序。当前基线见 `docs/fps-20-target.md`：
单帧 1.58 s = copy-out 1.18 s + 其余约 0.4 s。

## A. 来宾像素路径（最大头，子代理正在做）

| # | 优化 | 依据 | 预期 |
| --- | --- | --- | --- |
| A1 | `hios_pixel_unpack_bgra` 的逐像素换字节改成 64 位字交换 | 读码：2 743 500 px × 4 次字节读写，全在 TCG 里 | copy-out 可能降 50–80% |
| A2 | 宿主侧直接把 RGBA+自底向上 转成 BGRA+自顶向下（原生 NEON 几乎免费），来宾只剩按行 `memcpy` | 读码：`glReadPixels(GL_RGBA)` 输出与 IOSurface 布局差一次全幅转换 | 来宾侧再降一档 |
| A3 | 去掉每帧 `malloc(10.9 MB)`/`free`，缓冲复用；`stride == width*4 && invert == 0` 时 `read()` 直接进 IOSurface | 读码：`hios_eagl_read_surface` 每帧 malloc+free | 省一次全幅遍历 |
| A4 | 纹理上传方向（`hios_pixel_pack` 的 BGRA8 分支）同样有逐像素 swap | 读码：`eagl-guest-textures.c` 上传前 pack | 对应 `making textures of IOSurfaces` 的 25–490 ms |

## A5. 结构性方案：让宿主直接产出显示画面（真正的「直通」）

现在的链路是「宿主渲染 → 读回 10.9 MB → 来宾写进自己的 IOSurface → QEMU ramfb 扫描 IOSurface」。
其中「来宾写进自己的 IOSurface」这一步只对**显示**有用，对渲染结果没有影响
（日志：`0 drawn in place, 1 picture copied out` = 我们全程画在远端 framebuffer 上，最后才拷一份出来）。

如果让宿主 worker 把最后一帧**直接写进来宾 RAM 里 ramfb 正在扫描的那块内存**，并让来宾客户端
把 read-back 直接返回成功（不搬像素），那么：

- 来宾每帧省掉 1.18 s 的全幅搬运；
- 显示内容仍然正确，因为 QEMU 的 ramfb 扫的就是我们写的那块内存；
- **不需要改来宾内核**（内核没有 virtio-gpu 驱动，这条路本来也走不通）。

**前提 1 已在本机实测通过（2026-11-19 18:52）**：起一个 `-object memory-backend-file,mem-path=/tmp/ram.img,
share=on` 的 `-M virt` 空机，用**另一个进程** mmap 同一个文件、在文件偏移 0x01000000 写
`0xdeadbeef 0x12345678`，再用 QMP `human-monitor-command: xp /2xw 0x41000000` 读——
QEMU 读到的就是外部进程写进去的那两个值（`-M virt` 的 RAM 基址是 `0x40000000`，
所以 guest 物理地址 = 文件偏移 + 0x40000000）。顺带确认 `0x40000000` 处是设备树魔数
`0xd00dfeed`，说明映射的确实是 guest RAM 本体。

这条打通后，同一套机制还能让 **App 直接读画面**：App 自己 mmap 这块 RAM，就绕开
HMP `screendump`（30–41 ms/帧）和 PPM 解码（App 侧 40–56 ms/帧），采样上限从 17.8 次/秒
提到 60 次/秒，而且完全不占 QEMU 主循环。需要一次性找到 ramfb 在 RAM 里的偏移
（拿一张 `screendump` 当基准、在 RAM 里搜匹配的像素块，一次即可，之后缓存并定期校验）。

两个前提必须先验证：
1. QEMU 的 guest RAM 在 App 里能不能被 worker 进程 mmap（当前是 `-object memory-backend-file,
   mem-path=...`，`share=off`；要改成可共享，或者用 memfd + fd 传递）；
2. CoreAnimation 会不会在某些操作里**读回**那个 IOSurface（模糊/截图/合成），如果会读，
   跳过搬运就会出现脏画面——这决定方案能不能成立。

若成立，帧时间 ≈ 0.4 s（剩下的都是 Apple 自己的 CA/IPC 在 TCG 里跑），即 **≈2.5 FPS**，
比现在快约 4 倍；但仍到不了 20 FPS，因为那 0.4 s 不是我们能搬走的东西。

## A6. 把「显示」从 QEMU 里拿出来：App 直接显示 worker 渲染的帧

现在显示链路是：worker 用 Adreno 渲染 → 10.9 MB 送回来宾 → 来宾写进 IOSurface → QEMU ramfb 扫描 →
App 用 HMP `screendump` 拍下 8.2 MB PPM → App 解码。**这条链上做了三遍 10 MB 级的搬运，
而这三遍全都只为了「把那块像素送到屏幕上看」。**

worker 是我们自己的进程、由 App 拉起（`GpuRuntime`），所以可以在 worker 与 App 之间加一条
**独立的本地通道**（unix socket 或 memfd 共享内存）：worker 渲染完一帧，直接交给 App 显示，
App 根本不用 `screendump`、不用写/读 PPM、不用逐像素解码。

收益（都不依赖来宾配合）：
- App 每帧省掉 30–41 ms 的 QEMU `screendump`（**同时不再占 QEMU 主循环/BQL，来宾因此拿到更多 CPU**）；
- App 再省 40–56 ms 的 PPM 读回+解码；
- 显示没有抓帧延迟，画面就是 worker 刚渲染的那一帧；
- 采样上限从 17.8 次/秒提到 60 次/秒（只是把共享内存里的指针换一下）。

风险/边界：
- 早期引导（UEFI/内核/logo）还没有 GL，仍要走 ramfb + `screendump`，需要按「GL 是否已接管」切换；
- 若 P2（CA 是否读回 IOSurface）通过，来宾可以完全不收像素，这一条就和 A5 合成完整方案；
  若 P2 不通过，来宾仍要收像素，这条只优化「App 显示」这一段。

## B. 传输通道（结论：换协议这条路基本封死，只能优化现有 TCP）

| # | 事实 / 优化 | 依据 | 结论 |
| --- | --- | --- | --- |
| B1 | 来宾内核**没有 virtio-console**：`strings mach.development.bcm2837` 里 virtio 只有 sound/input/net | 实测 | 换成 virtio-serial/共享内存环需要**重编来宾内核**（重资产），不划算 |
| B2 | SLIRP 的 MTU 固定 1500，QEMU user netdev 不暴露 MTU | 读码/文档 | 想靠巨帧减少包数走不通 |
| B3 | 来宾 TCP 栈（校验和、拷贝）跑在 TCG 里 | 推断（与 A1 同源，待拆分实测） | 若实测占比高，可评估 UDP/调大窗口，但收益上限受限 |

## C. 宿主与 QEMU 配置

| # | 优化 | 依据 | 预期 |
| --- | --- | --- | --- |
| C1 | 宿主 `glReadPixels` 目前是同步读进客户端内存（无 PBO 双缓冲） | 读码：`host-egl.c:154`、`eagl-host-pixels.c:81` | worker CPU 只占 0.3 s/次启动 → 瓶颈不是宿主 CPU；PBO 只可能省掉 GPU 停顿，先量再改 |
| C2 | `screendump` 8 MB = 30–41 ms；静止时已降采样（0.31.3） | 实测 | 已吃掉的收益 |
| C3 | **来宾 vCPU 2 → 4 的实验**：Adreno 档当前强制 2 vCPU，而帧时间内只占 1.55–1.66/2 核 | 实测（CPU 利用率）+ 读码（`QemuTopology.ADRENO_GUEST_CORES = 2`） | 有其他可运行线程时可能提并发；需单独验稳定性（历史上 6 核曾引发 lsd 风暴） |
| C4 | `tb-size` 无收益（0 次 flush）、`-cpu max` 无差异、`-mem-prealloc` 更慢 | 实测（子代理） | 已排除 |

## D. App 侧

| # | 优化 | 依据 | 预期 |
| --- | --- | --- | --- |
| D1 | PPM 解码是 Kotlin 逐像素 2.74M 次（40–56 ms/帧）；18 次/秒 ≈ 吃满一个宿主核 | 读码：`QemuPpm.decode` 双重循环 | 原生（JNI）解码或让 QEMU 出 PNG，可降到 ~10–15 ms；省的是宿主 CPU/热，不直接提来宾帧率 |
| D2 | 静止降采样（0.31.3） | 已交付 | 省 QEMU 主循环与 App CPU |

## E. 引导期（影响「开机到能用」而不是帧率）

| # | 事实 | 依据 |
| --- | --- | --- |
| E1 | FAST=1 作业集已裁过一轮（0.25.0） | 项目历史 |
| E2 | `-m 7G` + `memory-backend-file,readonly=on`；`-mem-prealloc` 实测更慢 | 实测 |
| E3 | stock 11.1.2 首帧 60.5 s（ABBA 两次 66.5 / 54.4，极差 22%） | 实测 |

## 排序建议（按「对 20 FPS 的贡献 / 成本」）

1. **A1+A2+A3**（子代理进行中）：直接把 copy-out 从 1.18 s 往下压，是唯一能动 72% 的地方。
2. **C3**（vCPU 2→4）：一次启动实验就能知道有没有并发余量，成本极低。
3. **B3 拆分实测**：决定传输还能不能省，避免在错的地方优化。
4. **C1 / D1**：宿主与 App 的 CPU 效率，改善发热与采样上限，不直接提来宾帧率。
5. **B1 内核**：除非确认必须，否则不动（重编内核风险远大于收益）。

## G. 开机到桌面到底多久（用它自己的日志算，不需要重跑）

拿 `hackios-run/inferno-boot.bykOAn/serial.log`（2026-11-19 的一次成功启动）按来宾时间戳对齐：

| 里程碑 | 来宾时刻 | 相对首帧 |
| --- | --- | --- |
| 第 1、2 帧（logo） | 11:05:39.5 / .9 | 0 s |
| **第 3 帧** | 11:11:24.4 | **+345 s** |
| `SBHomeScreenWindow` 建立（桌面窗口） | 11:15:16.8 | **+577 s** |
| 第 400 帧统计（稳态） | 11:16:2x | +680 s |

两个结论：

1. **开机到桌面约 9.6 分钟**（从首帧算），不是「一两分钟」；期间只有 **3 帧** 被画出来。
2. 首帧到第 3 帧之间有 **345 秒一帧都没有**，这段里日志全是 launchd/XPC 的
   `failed lookup: name = com.apple.cloudd / audio.AudioSession / powerlog...` 风暴
   （缺服务的重复查找）。QEMU 在这段时间 CPU 是满的（见 `docs/cpu-translation-baseline.md`
   的 1.55–1.66 核），说明不是死等，是**在 TCG 里真跑这些失败的查找与日志**。

所以「开机到桌面」有三个可动的点，按证据强弱：
- 少做失败的 XPC 查找（补 stub 服务或裁 job，属于镜像/系统组件层，风险中等）；
- 少写串口日志（每一行都要经 emulated UART，一次 MMIO 陷入）；
- 把每帧 1.18 s 的 copy-out 压下去（GL 改造，正在做）——但注意**桌面之前只有 3 帧**，
  所以 copy-out 对「开机到桌面」的贡献没有我先前估的那么大（先前 180 s 的估算有误，已改）。

## F. 优化成果怎么装进 App（先把发布路径写清楚，免得改完不会发）

来宾侧的一切（`libredwood-gl.dylib`、`rwtouch` 等）都以**预构件**形式进镜像：

1. App 解包 `app/src/main/assets/hackios-scripts.zip`（`AssetDeployer.unzipBundle`）；
2. 镜像构建走 `PREBUILT=1`（`hackios-redwood-reference/userland/build.sh:143-151`）——
   设备上没有 `ld64`，所以**不编译**，直接把 `prebuilt/userland/*` 拷进 `obj/`，再打进 `root.apfs`；
3. `root.apfs` 按 `Constants.IMAGE_REV` 缓存（见 `Constants.kt` 的 8→9 注释：改预构件必须 +1，
   否则旧镜像不会被重建）。

所以换上一版更快的 GL 垫片 = 三件事：替换 zip 里的 `prebuilt/userland/libredwood-gl.dylib`、
`LAYOUT_VERSION` +1（强制重新解包）、`IMAGE_REV` +1（强制重建 root.apfs），然后发 APK。
**用户侧代价是一次镜像重建**（首次启动会重新烘 root.apfs），发布说明里要写清楚。
