# 0.31.2：把「画面有多快」变成可测量的数

目标：用户要的是**实际帧率 20 FPS**。此前 App 只显示「采样 fps」，它衡量的是
App 每秒从 QEMU 抓了几次图，和画面真正更新了几次完全无关——截图 18 次/秒、
画面 5 秒才变一次时，旧界面会显示 18 fps，看起来像达标，其实是同一张图被抓了 90 次。
0.31.2 只改度量，不改 QEMU 参数、不改 payload、不改数据盘。

## 现在显示的三个数

| 数 | 含义 | 怎么算 |
| --- | --- | --- |
| 采样 fps | App 每秒成功取帧次数 | 相邻两帧时间差 `dt` → `1000/dt`，0.8/0.2 指数滑动 |
| 取帧 N ms | 一帧从发命令到变成 Bitmap 的墙钟时间 | 见下方拆分 |
| 内容 fps | **画面真正变了几次/秒**（20 FPS 目标看的就是它） | 5 秒窗口内画面指纹变化次数 ÷ 实际窗口时长 |

采样与内容必须同时显示，否则「抓得快」会被误当成「画得动」。

## 取帧耗时拆成两段

`QemuControl.captureFrame` 在三个时刻取 `elapsedRealtime()`：

```
start ──(dumpMs)──> hmpScreendump 写出 PPM ──(decodeMs)──> 读文件 + 解成 ARGB ──> Bitmap
```

- `dumpMs`：QEMU 自己把 1125×2436 画面写成 PPM（10.9 MB）的时间。这一段吃的是
  guest 侧 TCG 时间，优化 QEMU / guest 才有用。
- `decodeMs`：App 读 10.9 MB 文件 + 逐像素转 ARGB + `setPixels`。这一段纯 App 侧。

超过 400 ms 的取帧会写一条日志带上拆分数字，用来区分「QEMU 慢」和「解码慢」。

## 内容帧率的算法

`CapturePipeline.windowFps(changes, elapsedMs)`：

```
窗口 = min(elapsed, 5000 ms)        // 未满 5 秒按已过时长折算，避免刚启动永远显示 0
内容 fps = 窗口内变化帧数 × 1000 / 窗口
```

「变化」= `QemuPpm.decode` 的指纹（约 28000 个采样像素的 FNV 哈希）与上一帧不同。

为什么是 5 秒窗口，而不是指数滑动：

- 指数滑动在 0.2 Hz 的画面会让读数在 0 和采样率之间来回跳（看 9、再看 0）。
- 5 秒窗口把「5 秒一帧」稳定报成 0.2，且正好是「20 FPS」这种高档位需要的精度
  （5 秒 100 次变化 = 20.0）。
- 比 0.2 fps 更慢的画面这个数不可信，要看 `unchangedForMs`（画面已静止多久）——
  它才是「卡住了」的直接证据。

## 界面在哪看

- 屏幕页顶部状态条：`1125 × 2436 · X 采样 / Y 内容 fps`
- 屏幕页「更多」面板：`取帧 1234 ms（截屏 900 / 解码 334）` + 采样/内容两组 fps

## 单位与判读

- 目标 20 FPS = 每帧 50 ms。当前实测单帧 1.58 s（截屏+回读+传输+解码），差 31 倍。
- `dumpMs` 大 → QEMU/guest 侧慢（回读、合成、TCG）。
- `decodeMs` 大 → App 侧慢（文件 I/O、Bitmap 拷贝、GC），与 guest 无关。
- 内容 fps 0.0 而采样 15+ → 画面本身没在更新，不是取帧坏了。

## 测试

`CapturePipelineTest`：
- `content fps counts changes over the real window`：1 次/5 秒 = 0.2；3 次/1 秒 = 3.0；
  100 次/5 秒 = 20.0。
- `content fps never reports a rate without elapsed time or changes`：0 次或 0 时长必须报 0。

## 静止降采样（0.31.3）

画面静止 2 秒后，采样间隔从 16 ms 降到 250 ms（4 次/秒）；一旦检测到变化立刻回到 16 ms。

理由：每次 `screendump` 都是 QEMU 主循环的一次全幅写盘 + App 一次 8.2 MB 读回解码。
来宾只有 0.1–0.3 帧/秒时，99% 的采样拿到的是同一张图，纯属和 TCG 抢主循环、白烧 App CPU。
静止时 4 次/秒仍能让「点一下」在 250 ms 内被看到，手感不变。

规则优先级（`FramePacer.nextIntervalMs`）：
1. 上次取帧 ≥1500 ms → 1800 ms 退避（guest 忙，防止 screendump 堆积成雪崩）；
2. 画面静止 ≥2000 ms → 250 ms；
3. 其余 → 16 ms（60 FPS 请求预算）。
