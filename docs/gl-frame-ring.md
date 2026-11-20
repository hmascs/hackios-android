# GL surface 回读诊断帧环协议 v2（HFR2）

当前用途：记录 worker 的完整 GL surface 回读，**不代表最终 panel scanout**，不替代来宾 IOSurface 回写，也不能证明实际内容帧率。App 已将 `FRAME_RING_ENABLED` 设为 false，显示继续使用原 1125×2436 framebuffer 的 screendump 路径；GPU 渲染与全部有效像素回写保持开启。

## 显示来源修正

Redwood 的 `frame_end()` 会遍历所有已绘制 render target，`target_to_surface()` 将各自纹理接到 reader FBO 回读；worker 在收到 `EA_READ` 时按尺寸尝试发布环。这发生在 guest 接收并应用像素之前。MiniFramebuffer 随后还会在 swap/show 中将多个 layer 合成到最终 `fBase`。因此即使回读宽高恰为 1125×2436，也不能证明它是最终屏幕；HFR2 guard 只保证这次副本一致，不赋予最终显示身份。

旧轮经正确 PPM pitch 解码的 framebuffer 与末端 GL 环有像素差异，但旧数据缺少严格配对时间括号，不能单靠该端点差异给黑块定因。新的离线配对工具仅在 screenshot 前后 seq/guard/hash 与稳定最终环完全一致时标记 worker publication bracket，仍不宣称 guest scanout 是原子快照。高速输出的后续工作必须从最终 layer 合成之后的呈现点取帧；不能继续把所有同尺寸离屏回读标为屏幕。

## 为什么 v1 不足以防撕裂

旧版 HFRM 只有发布计数 `seq`，先覆盖像素，再 `seq++`。读者即使在复制前后读到相同 `seq=N`，也可能遇到：

1. 读者读到 N。
2. 写者开始用 N+1 覆盖单槽，尚未发布新序号。
3. 读者复制到新旧混合的像素，复读序号仍为 N，于是接受半帧。

v2 在任何像素写入之前先把独立 guard 改成奇数，写完后再以偶数发布。**发布计数依旧每完整帧 +1**，不会因为奇偶保护而把吞吐翻倍。

## 布局（小端，单写者、单读者）

诊断 harness 在新会话、旧生产者已停止后创建新的 `gpu-frames.ring` 并传给 worker；生产 App 目前不创建、不接入此回读 tap。文件总长为 `64 + stride * height`，共享映射头如下：

| 偏移 | 类型 | 含义 |
| --- | --- | --- |
| 0 | char[4] | `HFR2` |
| 4 | u32 | 协议版本 2 |
| 8 / 12 | u32 | 宽 / 高 |
| 16 | u32 | stride，至少 width×4 |
| 20 | u32 | 1 = BGRA8888 |
| 24 | u64 | 完整帧发布计数，初值 0，每帧 +1 |
| 32 | u64 | CLOCK_MONOTONIC 纳秒，无法取得时为 0 |
| 40 | u64 | guard：`2*seq` 表示完整；`2*seq+1` 表示下一帧写入中 |
| 48..63 | u8[16] | 保留 0 |
| 64.. | bytes | 单槽像素，逐行有效部分为 width×4，余下为 padding |

几何参数每会话固定。不同几何的离屏 readback 不覆盖面板槽；不在原文件中改尺寸。不允许同时有两个 worker 写同一文件，不允许用新 worker 重置旧读者正在观察的计数。

## 写者顺序

实现隔离在 `qemu/gpu/eagl-frame-ring.h`，由 `eagl-host-session.c` 使用：

1. 校验几何、版本、BGRA 格式、容量和起始 `guard == 2*seq`；崩溃遗留的奇 guard 不接着写。
2. 用对齐 64 位 `atomic_exchange(..., ACQ_REL)` 将 guard 置为下一帧的奇数。acquire 部分约束后续像素写不得越过失效标记；单独 release 的奇数 store 不足。
3. 按目标 stride 写像素；不写行尾 padding。写时间戳。
4. 发布计数 +1，release store；最后 release store 偶 guard。

guard 会在覆盖开始时变化，而不只是覆盖完成后变化。仅在确实发布成功时，保留的实验 NO_REPLY 分支才允许返回空像素；生产来宾仍不请求跳过回写。

## App 读者顺序

`FrameRing` 用 `byteBufferViewVarHandle(LongArray, LITTLE_ENDIAN)` 操作对齐的计数和 guard；不是普通 `ByteBuffer.getLong` 充当屏障。

1. acquire 读取 guard；奇数直接拒绝，不碰目标。
2. 读取 publication count；0、已显示、或与 guard 不符则不取。
3. 检查固定几何，按 stride 将 BGRA 小端整字读成 ARGB IntArray。
4. 读时间戳，执行 `VarHandle.loadLoadFence()`，再 acquire 读 guard。
5. guard 与起点相同才提交；变化则丢弃，不推进 lastSeq，也不把工作数组提交为画面。

末尾 acquire 自身只约束其后的读取，不能替代复制结束与最终 guard 读取之间的 load-load fence。映射/原子访问能力不足时禁用环，回退 screendump。

## 版本混配和失败回退

- 新 App 创建 HFR2 文件，旧 worker 因不认魔数而禁用环；它仍走正常 FULL/DELT 来宾回写，App 在环无发布时回退 screendump。
- 新 worker 遇到 HFRM v1 文件也禁用环；旧 App 继续用 screendump。
- 只改整数版本不足以失败关闭：旧 worker **没有检查 version**，所以必须改变魔数。
- 写者崩溃遗留 odd guard 时，读者不接受半帧；持续无新完整帧由 CapturePipeline 既有的 3 秒停滞逻辑回退 screendump。
- 当前隔离长试验显式使用 HFR2/v2；历史 v1 数据保持原样。试验脚本必须记录版本，不能只换文件头或混用组件做性能归因。

这组改动还未写入 APK assets。新 worker、App 和试验配置必须一起验收之后再发布；不能用带新读端而带旧生产资产的 APK 宣称帧环提速。

## 正确性与验证边界

本机 aarch64/proot 的 C 测试使用真正共享 mmap 与 fork/pipe 检查点，确定性停在“guard 已奇、publication count 未前进、像素尚未覆盖”的状态；覆盖紧凑和 padded stride、padding 哨兵、非面板尺寸、旧版本与崩溃奇 guard 拒绝。命令：

```sh
cc -std=c11 -O2 -Wall -Wextra -Werror \
  qemu/gpu/test-eagl-frame-ring.c -o /tmp/hios-frame-ring-check
/tmp/hios-frame-ring-check
```

Kotlin 测试覆盖写中提前拒绝、复制中开始但未发布、复制后开始、完整发布、padding、几何变化和旧版本拒绝。去掉依赖 sleep 的概率性撕裂测试，使用明确的 beforeCopy / afterCopy 检查点。最新目标单测执行 `:app:testDebugUnitTest --tests '*FrameRingTest'`，23 项通过，0 失败/错误/跳过（BUILD SUCCESSFUL，1m14s）。

App 的紧凑面板现在用复用的 IntBuffer 一次 bulk get 拷贝，4 字节对齐的 padded stride 逐行 bulk get，非对齐字节 padding 保留逐像素安全路径；所有路径仍处于同一 guard/fence 保护区。增加了非对齐 stride 和目标尾部哨兵回归。它减少 JVM 调用次数，不改变分辨率或像素格式；Android 实际耗时尚未测定，不能据此声称达到 10 FPS。

C 测试与 JVM fixture 不能代替 Android ART 上的跨进程连续帧验收。实际内容 FPS 还需要确认连续可见内容变化；publication count 只表示生产者完成了几次发布，单张截图也不能证明动画无闪烁、图标逐步补画或黑块。

## 来宾回写仍是必须项

0.31.4 跳过 IOSurface 回写曾出现图标逐个冒出、闪烁和黑块：CA 下一帧会把前次合成的 IOSurface 当作纹理上传。生产来宾继续保持 `HIOS_GL_SKIP_AFTER=0`。差分回写需要完整像素 oracle，尤其 **packed row 与 padded stride 不能混淆**；v2 差分多行 memcpy 错误在另一个修复中逐行处理。

因此帧环、来宾回写、测速计量分别验收；不把一条路径的正确性或吞吐当作另外两条已经通过。
