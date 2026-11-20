# GL 直通 / 零拷贝上屏：可行性验证报告

**口径**：本文每个数字都标「实测」或「推断」，并注明二进制。实测 = 本轮真跑出来的
（日志在 `hackios-run/glpass-b1`、`glpass-b2`，脚本在 `qemu/gpu/glpass-desktop.py`）；
推断 = 按实测比例算的，没有单独测过。

| 二进制 | 说明 |
| --- | --- |
| `libredwood-gl.dylib`（未改源码重编，`/tmp/eagl-a1`，SHA-256 `4e9221a7…`） | 与 App/客体根镜像里装的那份**逐字节相同**，所以「重编成功」不是推断 |
| `libredwood-gl.dylib`（插桩版，`/tmp/eagl-b1`、`b2`） | 加了每帧计时与帧环模式；像素路径与原版等价（有单元测试） |
| `hackios-zink-session`（NDK 重编，`hackios-run/glpass-worker.b1`、`b2`） | 加了宿主侧 `glReadPixels`/转换/`write()` 计时与 A6 帧环 |
| 客体 | iOS 16.5，`-M virt` + `ramfb`，1125×2436，payload 与镜像沿用 0.30 的那套；QEMU 是 `hackios-run/qemu-android` 里 stock 11.1.2 |

## 0. 结论先行

1. **1.18 s 的 copy-out 不是来宾的逐像素换字节，是「10.9 MB 穿过 slirp/TCP 边界」**
   （实测，见 §2）。来宾侧 `swap_red_blue` 只占 **47.6 ms = 2.9%**；把它字数化只省
   12 ms（0.7%）。宿主 `glReadPixels` 15.7 ms、`malloc` 0.34 ms、协议头 23–29 ms。
   **宿主 `write()` 1.535 s 与来宾 `read()` 1.569 s 几乎相等 ⇒ 两端都在等同一条管线，
   吞吐约 7 MB/s。**
2. 反方向（来宾→宿主，纹理上传）更慢：同样 10.9 MB 的 `glTexImage2D` 上传
   **send 3.28 s**（实测），所以启动时那次 `making textures of IOSurfaces` 的 2.4–4.9 s
   也主要是传输，不是像素打包（pack 167 ms）。
3. 因此「省字节」「省指令」这一层最多动 1–3%，**唯一有效的是让像素不跨那条边界**。
   本轮把 A6 帧环做出来了（worker → App，协议 `docs/gl-frame-ring.md`），并给
   `EA_READ_RGBA` 加了 `NO_REPLY` 位（来宾不收像素）。§5 的实测显示：跳过之后单帧
   2.86 s → 0.754 s、内容帧率 0.35 → 1.33 FPS，环里的画面正确。
   **但 `NO_REPLY` 不能直接发货**：0.31.4 真机出现了「图标逐个冒出、闪烁、黑块」——
   CA 会把合成结果写回 IOSurface、下一帧再把它当纹理上传（`N textures refilled from
   IOSurfaces`），surface 不再更新就等于喂旧内容。**单张静帧 PNG 不足以验收渲染改动，
   必须看连续帧。** 详见 §4.3。
4. **20 FPS 不可达**（与 `docs/fps-20-target.md` §3.5 一致，本轮补上了剩余成分）；
   但 **2–5 FPS 的关键不在 GL，而在来宾 CA/TCG 的每帧固定成本**：跳过 copy-out 后
   单帧仍有 **0.754 s（QEMU 只有 1.0 核利用率 ⇒ 单线程在 TCG 里真跑，不是在等）**，
   其中 ~650 ms 与像素面积无关（见 §5、§5.1）。
5. **A5（宿主直写 ramfb）在 App 现有 QEMU 参数下不可用**：`share=off` ⇒ QEMU 的 guest RAM
   是 MAP_PRIVATE，外部进程写文件来宾看不见；`readonly=on` 下也不能改成共享写。
   真正可行的是 P3（QEMU 侧收帧后自己 `address_space_write`）或 A6（App 侧直接显示）。
   另外，来宾内核**每帧**都会把 IOSurface 拷进 panel framebuffer（`MiniIOMobileFramebuffer.cpp`
   的 `drawRows()`，`fBase = vinfo.v_baseaddr` = `0x1fb6bb000`），所以即便能写 RAM，
   只写 panel 也会被下一次 swap 覆盖；要写就得写到 IOSurface 的物理页，而来宾用户态
   **拿不到** IOSurface 的物理地址（`IOSurfaceGetBaseAddress` 是虚拟地址）。

## 1. A 步：来宾 dylib 能在本机重编（实测）

```sh
unzip -o hackios-next/app/src/main/assets/hackios-scripts.zip \
  prebuilt/userland/libOSMesa.8.dylib -d /tmp/eagl-base/
cd hackios-next/qemu/gpu
bash build-redwood-remote.sh \
  /root/workspace/hackios-redwood-reference/userland \
  /root/workspace/hackios-redwood-sdk.1orQnf/iPhoneOS16.5.sdk \
  /root/workspace/hackios-turnip.2GviKZ/mesa-26.2.6/src/mesa/glapi/glapi/registry/gl.xml \
  /tmp/eagl-base/prebuilt/userland/libOSMesa.8.dylib /tmp/eagl-a1
```

* 6.4 s 编完，产物 559,296 B（插桩版）/ 558,704 B（未改版）。
* 未改源码的重编产物与客体根镜像里的那份：**SHA-256 相同**（`4e9221a70c175efd…`），
  `llvm-nm -gUj` 402 个导出符号逐行相同，`diff` 0 行。
* 插桩版在真机启动里被加载并工作（`hackios-eagl: guest pid=11 context=1 remote opened`，
  每帧 `hackios-gl: out …` 行），所以 A 步不是「只在编译层面成立」。

## 2. B 步：1.18 s copy-out 的拆分（实测，`glpass-b1`）

单元帧（启动第 1、2 帧；`hackios-gl: out N` 是来宾自报，`EAGL readback timing` 是宿主自报）：

| 段 | 第 1 帧 (µs) | 第 2 帧 (µs) | 说明 |
| --- | ---: | ---: | --- |
| 宿主 `glReadPixels`（`gl_readback_us`） | 15,707 | 14,040 | 656 MB/s，正常 |
| 宿主 BGRA 转换（`convert_us`） | 0 | 0 | 该帧 flags=0，没转 |
| 宿主 `write()` 把 10.9 MB 压进 chardev | **1,535,114** | **1,669,016** | 阻塞写，等于管线吞吐 |
| 来宾 `send()`（请求 12 B） | 563 | 1,505 | 可忽略 |
| 来宾读回复头（`hdr`） | 29,325 | 23,490 | 含宿主 readback 的等待 |
| 来宾 `read()` 收 10.9 MB（`body`） | **1,568,769** | **1,703,634** | 与宿主 write 同时进行 |
| 来宾 `malloc(10.9 MB)` | 341 | 1,107 | 首次也不到 1 ms |
| 来宾 swap/落盘到 IOSurface（`unpack`） | **47,620** | 35,834 | v0 逐字节 / v1 32 位字 |
| 来宾合计 copy-out（`total`） | 1,651,274 | 1,765,818 | |
| eagl.c 自报 `copying out` | 1,690,043 | 1,785,678 | 与本表自洽 |

比例：传输 **94.9–96.5%**，来宾解包 **2.0–2.9%**，宿主 readback **0.9%**，协议头 1.4–1.8%。

同一轮里 10.9 MB 的**上传**（来宾→宿主，`hackios-gl: tex 1 …`）：

```
pack=167477  send=3276797  hdr=22361  total=3299226   (1125x2436, 10,962,000 B)
```

即 3.3 MB/s；eagl.c 同帧 `before it for the IOSurfaces` = 3.58 s / 4.88 s，与它一致。
**结论：不是来宾像素处理慢，是每个 1448 字节的段都要让来宾内核在 TCG 里跑一遍
virtio-net + TCP。7,550 段 × 208 µs = 1.57 s；上传方向每段 434 µs。**

## 3. 每一段的性质（哪些能靠「少字节」省，哪些不能）

| 成本 | 与像素面积相关？ | 实测值 | 能不能砍 |
| --- | --- | --- | --- |
| 宿主 `glReadPixels` | 是（线性） | 15.7 ms | 只能降面积/格式 |
| 宿主 BGRA 转换 | 是 | ~15 ms（推断：10.9 MB 一遍，原生大小写实测见 §4） | 可忽略 |
| 跨边界 10.9 MB | 是（按段数） | 1.54–1.70 s | **只有「不跨边界」才有效**；RGB565 减半仍是 ~0.8 s |
| 来宾解包 | 是 | 36–48 ms | 已可忽略 |
| 协议往返（头） | 否 | 23–29 ms | 与宿主 readback 重叠 |

## 4. C 步：试过的优化

### C1 来宾字数化 swap（实测否决）

`swap_red_blue` 改成一次一个 32 位像素（两个掩码移位 + 或），单元测试保证与原版
逐字节一致（`qemu/gpu/test-eagl-gl-variants.c`）：

```sh
cc -std=c11 -O2 -I. test-eagl-gl-variants.c eagl-pixel-transfer.c -o /tmp/v && /tmp/v
```

结果：`unpack 47,620 µs → 35,834 µs`，即 **copy-out 的 2.9% → 2.0%**。
按这个比例，整个 1.6 s 帧只快 0.7%。**否决**（保留代码与测试，因为它零风险，可随其它改动一起进包）。

### C2 减字节（推断，未单独跑）

传输按段数收费：RGB565 把 7,550 段砍到一半 ⇒ copy-out 约 0.8 s，帧 1.6 s → 0.9 s。
方向对，但仍挡在 TCP 前面；如果 A6/P3 都不通，这只能算「止血」。脏矩形/分块差分在
静态桌面上收益接近 100%，但滚动/动画时回到全屏，且要给 CA 的 damage 区域建立上报通道。

### A6 帧环（已实现，见 §5 / `docs/gl-frame-ring.md`）

worker 每帧把渲染完的 BGRA 写进 App 建的环文件，来宾**不收回像素**。协议没改；
新增的只有 `EA_READ_RGBA` 请求的 12 字节形式与 `NO_REPLY` 位（老 worker 收到 8 字节
请求仍按 flags=0 处理）。测试（不需要来宾）：

```sh
cd hackios-run/glpass-worker.b2
python3 /root/workspace/hackios-next/qemu/gpu/test-eagl-frame-ring.py ./zink-session.sh Adreno
# EAGL frame ring: header, BGRA frame, release-ordered seq verified
```

### A5（宿主直写 ramfb）/ P3（QEMU 侧像素汇）的可行性判断

**不能做（在 App 现有参数下）**，依据：

1. `QemuRunner.kt:355`：`memory-backend-file,…,readonly=on,rom=off,share=off`。
   `share=off` ⇒ QEMU `MAP_PRIVATE`，外部进程 mmap 同一文件**看不到来宾运行时的写**；
   `readonly=on` ⇒ 也不能让 QEMU 共享写。所以 worker 直接写 ram.img 的方案**不成立**。
   （之前那条「P1 已验」的实验用的是 `share=on`，与 App 实际参数不同。）
2. 即使改成 `share=on`（每启动要一份可写的 12.8 GB 副本，`du` 实测 3.5 GB，
   copy 约 10–20 s），写哪里也是问题：来宾内核 `MiniIOMobileFramebuffer::show()` →
   `drawRows()` 每帧把 IOSurface 的行 `memcpy` 到 `fBase = vinfo.v_baseaddr`
   （`0x1fb6bb000`，串口 `display 1125 x 2436 at 1fb6bb000`）。**只写 panel 会被下一次
   swap 覆盖**；要写就得写 IOSurface 的物理页，而该地址只在 guest 内核里
   （`MiniIOSurface::kernelAddress()`），用户态 `IOSurfaceGetBaseAddress` 是虚拟地址，
   拿不到物理地址。可行路径只剩两条：改来宾内核（一行 `IOLog` 或用 ioctl 上报
   `getPhysicalSegment`）或让 QEMU 自己在 RAM 里按指纹搜 surface（脆弱）。
3. 不碰 RAM 的等价方案是 P3：QEMU 加一个 chardev，收 `{dir, guest_phys, len, …}`
   用 `address_space_write` 写。这要改 QEMU 并自维护二进制（stock 11.1.2 在
   `qemu-android-bundle.zip` 里）。

**结论：A5 需要一个内核改动或一个 QEMU 改动；A6 一个都不需要，且本轮已跑通写端。**

## 5. 跳过 read-back 之后：残差是什么（实测 `glpass-b2`）

同一次启动、同一台机器、同一个桌面，只改「来宾收不收像素」：

| | 段 A：来宾收像素（v0，36 帧） | 段 B：`NO_REPLY` 跳过（v4，100 帧） |
| --- | ---: | ---: |
| 来宾侧这次读回的总耗时（`total`） | 2,375,148 µs | **43,486 µs** |
| ├ 收 10.9 MB（`body`） | 2,271,390 | 14 |
| ├ 等待回复头（`hdr`，含宿主 glReadPixels+转换+写环） | 20,910 | 42,331 |
| └ 解包到 IOSurface（`unpack`） | 61,897 | 0 |
| 两帧之间的空闲（`gap`） | 484,862 | 710,092 |
| **单帧周期 ≈ total + gap** | **2,860,000 µs（0.35 FPS）** | **753,578 µs（1.33 FPS）** |
| 宿主 `glReadPixels` / BGRA 转换 / 环拷贝 | 16.0 / 9.2 / — ms | 16.0 / 9.2 / ~4 ms |
| eagl.c 自报 `copying out` | ~2.3 s | 77–96 ms |
| 内核 IOMFB（`N frames in T ms`，驱动内） | — | **9.8–10.3 ms/帧** |
| 环 seq | 0（不发布） | 0 → 100，与帧一一对应 |

读法（**实测**）：

1. 跳过像素之后，**单帧 2.86 s → 0.75 s，3.8×**；内容帧率 **0.35 → 1.33 FPS**。
   这一轮机器偏慢（引导期同样 10.9 MB 的 copy-out 在 `glpass-b1` 是 1.57 s，这里 5.38 s，
   串口有 `lsd[26] denied attempt to escape CPU monitor` 刷屏），所以绝对值偏悲观，
   **比值**才是结论。
2. 剩下的 0.75 s 拆开（实测 + 相减）：
   * 我们这条读回路径 **43 ms**（宿主 `glReadPixels` 16 + BGRA 转换 9 + 环拷贝 ~4 + 往返）；
   * eagl.c 那个块里**它周围的几次 GL 往返**再 ~35–55 ms（`copying out` 77–96 ms 减去 43 ms）；
   * 内核 IOMFB 把 surface 拷进 panel：**~10 ms/帧**（swap 记录是整屏
     `from 0,0 1125x2436 to 0,0 1125x2436`）；
   * **其余 ~650 ms 是来宾自己**：SpringBoard/CoreAnimation 的场景构建、XPC、CA 提交、
     GL 命令序列化（每帧 22–67 次 GL 调用、2–9 次 draw）在 TCG 里跑。
     这一段**与像素面积关系很小**（CA 的工作量由图层数/动画/提交次数决定，不由分辨率决定），
     fbscale 实验（§5.1）就是用来验证这一点的。
3. **跳过段 QEMU 只用 0.99–1.08 核**（2 vCPU 上限 2.0；同一条 `samples.jsonl` 按 30 s 差分算，
   段 A 是 1.65–1.69 核）。1.0 核 ≈ 一个 vCPU 被单线程打满：剩下的 700 ms 是**单线程在 TCG 里
   真跑**，不是等 GPU/IO，也不是等我们这条通道（我们只占 43 ms/帧）。多给 vCPU 不会改善
   （CA 的关键路径是一根线程），这与 `docs/fps-20-target.md` §3.10 的结论一致。
4. 环里的图是**完整的 SpringBoard 主屏**（`glpass-b2/ring-frame.png`，1125×2436，
   图标/时钟/Dock 都在），说明 A6 的显示路径可用：来宾没有收到任何像素，App 侧仍有正确画面。

### 5.1 fbscale=2 / fbscale=1（实测）

`fbscale` 是 payload cmdline 里的启动参数（原值 3）。

**`fbscale=2`（实测，本轮不完整）**：第一帧的读回尺寸就是 750×1624
（`bytes=4,872,000 stride=3072 row=3000`），`body=644,932 µs`（全尺寸 2,271,390 的 0.28 倍），
说明**来宾显示的 surface 真的跟着倍率变小、传输按字节线性降**。
桌面窗口只拿到 3 帧（`total=1,382,819 / body=1,336,800 / unpack=19,438 / gap=1,066,651`，
单帧 ≈2.45 s）——这台机器上这一轮跑到一半被我自己的会话中断杀掉（`fbscale.log` 停在
700 s、没有 `fbscale=2 done` 行），**样本太少，不能用来判残差是否随面积变**。
`fbscale=1` 没跑。

**倍率的机制验证（实测）**：帧环的尺寸跟随是代码层验证的，不需要真机——
`test-eagl-frame-ring.py` 现在把环建成 64×64、帧发 32×32，断言 worker 把环头改写成
32×32 并照样发布（`HFRM`/v1/32/32/128/1/seq=1/BGRA 像素）。所以 App 侧只要固定建
1125×2436 的环，倍率变化时 worker 会自己改头，App 读头即可。

**残差是否面积相关（推断，基于 §5 的拆分）**：B 段 754 ms 里与面积成比例的只有
「我们的读回 43 ms + 它周围的 GL 往返 35–55 ms + 内核 panel 拷贝 ~10 ms」≈ **90–110 ms**；
其余 ~650 ms 是 CA/SpringBoard 的单线程工作，与分辨率基本无关。所以
`残差(fbscale=1)/残差(fbscale=3)` 的期望值是 **0.85 左右，不会掉到 0.4**，
即倍率最多把 754 ms 压到 ~680 ms（1.45 FPS）。**这是推断，不是实测**——要定论必须把
上面那轮不完整的 fbscale 实验重跑完。

### 5.2 这一轮的两个边界

* `glpass-b2` 的 worker 在测量第 400 s（共 480 s）时先退出（宿主 `worker.log` 最后一行是正常
  计时行，来宾串口一直记录到 19:31 才被我停掉，没有 panic、没有 SpringBoard 重启、`guest pid=11`
  只出现一次）。原因未定，倾向于「来宾侧连接被关掉」而不是环写坏：环 seq 停在 100，
  最后一帧完整、PNG 正常。A6 若进产品，worker 退出必须能从 App 重启（现有 GpuRuntime 已有）。
* 温度 60–74 ℃；同机不同启动之间的绝对值漂移很大（见 §5 第 1 点），**本文不做跨启动绝对值对比**。

## 4.3 「廉价回写」：跳过回写之后真正的产品方案（设计判断，未实现）

0.31.4 的教训把问题定死了：**surface 必须每帧等于宿主那边的完整帧**，否则 CA 的
surface→texture 上传、内核的 surface→panel 拷贝都会读到旧内容。所以目标不是「不回写」，
而是「回写得便宜」。两条候选：

| 方案 | 机制 | 省多少 | 正确性 | 风险/工作量 |
| --- | --- | --- | --- | --- |
| **A. 差分回写（推荐先做）** | worker 留上一帧，按行 `memcmp`，只发变化的行 + 行号表；来宾按 `y*stride` 拷行（worker 已换序翻转） | 桌面静态帧只变 5–20% 行 ⇒ 1.77 s → **0.1–0.4 s**；滚动/动画回到全帧 | **天然成立**：surface 始终等于完整帧，CA/内核读到的都是对的 | 新增一个 wire op（行号表），两端都是我们的；每 K 帧补一次全帧兜底；host 侧 memcmp 10.9 MB ≈ 3–10 ms |
| **B. 宿主影子纹理** | surface 的内容只活在我们的 GPU 上（按 surface id 建影子），回写=FBO→影子（免费），上传=绑影子（免费） | 两个方向都归零 | 只在「该 surface 只由我们 GL 写」时成立 | **CG 软件光栅化直接写进同一块 surface 的内容会丢**；需要逐条确认本移植里有没有这种写者；工作量比 A 大一个量级 |

判断：**先 A 后 B**。A 的增量小、正确性不依赖推断，且它顺手把「CA 上传旧内容」这个
真机 bug 从根上消掉（surface 永远是对的）；B 是下一步的结构性优化，要先枚举 guest 侧
所有写者（CG、IOSurface 的其它客户端）才能下结论。

**注意**：A 的收益取决于「一帧里变化的行占比」。本轮没有测真实滚动/动画的占比
（只在静态桌面上取了帧），这是 A 能不能把 copy-out 压到 0.1–0.4 s 的关键未知数。

## 6. 复跑命令

```sh
cd hackios-next/qemu/gpu
python3 glpass-desktop.py \
  /root/workspace/hackios-run /root/workspace/ram-correct.img \
  /root/workspace/hackios-run/redwood-settings-content.M5VER6/boot/payload.bin \
  /root/workspace/hackios-run/redwood-settings-content.M5VER6/boot/rootfs \
  /root/workspace/mkapfs.py \
  /tmp/eagl-final/libredwood-gl.dylib \
  /root/workspace/hackios-run/glpass-worker.final/zink-session.sh \
  /root/workspace/hackios-run/glpass-rerun \
  --settle-seconds 15 --measure-seconds 480 --boot-timeout 1400 [--fbscale N]
python3 glpass-analysis.py /root/workspace/hackios-run/glpass-b3
```

## 7. 未验证 / 边界

* 只跑了一次完整 A/B（同一次启动内的两段），没有 ABBA 二次确认；温度 61–75 ℃，未采频率。
* 跳过 read-back 后**来宾自己的屏幕**会冻在最后一帧（这是预期，也是 P2 的结论）；
  桌面可见性改由 App 读环提供，本轮用保存下来的环 PNG 验证。
* A6 的 App 读端不在本轮（另一个子代理按同一份文档做）。
* `fbscale=2` 只跑到一半（被会话中断杀掉），`fbscale=1` 没跑：**倍率对残差的影响仍是推断**。
* 差分回写（§4.3 A）没有实现，也没有测过真实滚动/动画的「变化行占比」。
* 0.31.4 真机上的「图标逐个冒出/黑块」症状我只做了机制归因（CA 把 surface 当纹理上传），
  没有在插桩版上复现过连续帧对比。
* 20 FPS 的否定结论沿用 `docs/fps-20-target.md` §3.5，本轮只补了残差成分。
