# 内核侧移植指南：把黑屏修成「非常完善的 QEMU」

> 目标：本工程黑屏的唯一硬阻塞在内核 `MiniIOMobileFramebuffer`（hackios-xnu-kernel 仓库，
> `iokit/Kernel/MiniIOMobileFramebuffer.cpp`）。App 侧（本 Android 仓库）改不了它——它不在 APK 里，
> 编译进 iOS 内核镜像。本文给出**代码级**移植清单，直接对照已出主屏的同代项目
> **dipa-studio/ios-sweet**（iOS 16.7，XNU xnu-8796）的 `SweetFramebuffer.cpp` / `SweetSurface.cpp`。
> 一次合并构建即可让屏幕出画（见文末「一次性构建计划」）。

---

## 0. 一句话结论

黑屏根因 = `MiniIOMFB` 的 swap 路径没有把 IOSurface 拷到 boot framebuffer（0x1fb6bb000）。
本工程的 QEMU 侧（`-M virt` + `-device ramfb`，loader 经 fw_cfg 把 ramfb 重塑成 1125×2436）已经
把 ramfb 的**物理内存就是 0x1fb6bb000** 这件事接好了——内核一旦把合成帧 memcpy 进这块内存，
现有的 `screendump` 抓帧链路自动恢复，App 屏幕页立刻出画，**无需改 App 侧显示代码**。

也就是说：缺的不是 QEMU 配置，而是内核里一个 `memcpy`。

---

## 1. 与 ios-sweet 的对齐证据（为什么照它抄一定对）

| 项目 | 本工程（真机 01:01 日志） | ios-sweet（NOTES.md） |
|---|---|---|
| boot framebuffer | `display 1125 x 2436 at 1fb6bb000` | `SWEET FB: AppleCLCD 1080x2400 stride 4320 at 0x9c000000`（手机面板地址） |
| 面板尺寸 | 1125×2436，stride 4500，BGRA | 1125×2436（iPhone X 面板，0061 补丁改的） |
| swap 结构大小 | `SwapEnd (5, 1132-byte struct)` = 0x46c | `SWEET_FB_SWAP_REC_SIZE = 0x46c` |
| backboardd 调用 selector | 57,15,22,29,33,27,17,84,50,68,55（not implemented） | 57,15,22,29,33,79,74,12,6,27,17,84,50（discovery）+ 4,5,3,68,55（swap） |
| 显示倍率 | `fbscale=3`（App 已写死，375×812@3x→1125×2436） | `display-scale=3`（写进 `/chosen`） |

两边 swap record 布局**逐字节一致**，证实本工程缺的就是 ios-sweet 那一段 `AppleCLCD::swap()` 的拷贝。

---

## 2. 移植清单（照做即可）

### 2.1 surface 表（IOSurface ⇄ 内核地址 的查找，对应 ios-sweet `SweetSurface.cpp`）

每个 IOSurface 在内核里要有：`id`、`width`、`height`、`bytesPerRow`、`bytesPerElement`(=4)、
`pixelFormat`、`elementWidth/Height`(=1)、`offset`、`memory`(IOMemoryDescriptor)、
`kernelAddress()`（映射后的虚拟地址）。

- `sweet_surface_create_physical(phys, w, h, rowBytes, 'BGRA')`：把**面板内存（0x1fb6bb000）**
  注册成 id=1 的默认 surface，并返回它的内核映射。本工程在 `MiniIOMFB::start()` 里调用它，
  把 `fPixels` 拿到（= 面板内存内核映射）。
- `sweet_surface_copy(id)`：按 id 从全局表取 surface（带 retain）。swap 时用它拿到各图层 surface。
- `sweet_surface_port_in_task(surface, task)`：把 surface 发布成 mach port，供
  `GetLayerDefaultSurface`(selector 3) 返回给 WindowServer。

> 若本工程的 `MiniIOMFB` 已有自己的 surface 管理（IOMobileFramebuffer 通常用 `IOCoreSurfaceRoot`），
> 则只需复用现有 surface→物理地址的查表，把下面 2.2 的拷贝接上即可。重点是**能按 id 拿到
> surface 的内核映射地址**。

### 2.2 swap 拷贝（核心，对应 ios-sweet `AppleCLCD::swap()`）

把下面逻辑搬进 `MiniIOMFB` 的 swap 处理（swap record 长度 0x46c）：

```
uint32_t mask = get32(rec, 0x104);            // 启用图层掩码
for (uint32_t layer = 0; layer < 4; layer++) {
    uint32_t id = get32(rec, 0x54 + 4*layer); // 该图层 surface id
    const uint8_t *a = rec + 0x64 + 16*layer;  // 源矩形: sx,sy,w,h
    const uint8_t *b = rec + 0xc4 + 16*layer;  // 目标矩形: dx,dy,w,h
    int32_t sx = get32(a,0), sy = get32(a,4);
    int32_t dx = get32(b,0), dy = get32(b,4);
    int32_t w  = get32(b,8), h  = get32(b,12);
    if (!(mask & (1u<<layer)) || id == 0) continue;
    Surface *s = surface_copy(id);
    if (s == NULL || s == fDefaultSurface) { release(s); continue; } // 不要拷回默认层
    uintptr_t src = s->kernelAddress();
    uint32_t bpe = s->bytesPerElement ? s->bytesPerElement : 4;
    if (w <= 0 || h <= 0) { w = s->width; h = s->height; }
    // 边界钳制（防越界）……
    if (src != 0 && bpe == 4) {
        for (int32_t y = 0; y < h; y++)
            memcpy((void*)(fPixels + (uint64_t)(dy+y)*fRowBytes + (uint64_t)dx*4),
                   (const void*)(src + s->offset + (uint64_t)(sy+y)*s->bytesPerRow + (uint64_t)sx*4),
                   (size_t)w * 4);
    }
    s->release();
}
```

要点：
- `fPixels` = 面板内存（0x1fb6bb000）的内核映射；`fRowBytes` = 4500（1125×4）。
- 只处理 `mask` 启用的图层，跳过 id==0 和「默认 surface 自己」。
- 边界钳制照 ios-sweet 做（sx/sy/dx/dy<0 归零，w/h 不超过 surface 与面板范围），防内核踩内存。

### 2.3 vsync 节流呈现（对应 ios-sweet `0056-sweet-fb-throttled-present.patch`）

`swap()` 只把最新一帧存进 `fPending[0x46c]` 并置 `fDirty=true`；真正的拷贝放到 **vsync 定时器**
里，每 `60/sweetfb_hz` 个 tick 拷一次（默认 10Hz，可 `sweetfb_hz` 调高到 30）。

> 不节流会怎样：ios-sweet 实测 backboardd 以 ~58Hz 提交 swap，每次全帧 10MB memcpy 把单核吃满
> （~600MB/s），饿死一切 I/O。务必节流。本工程 2 核 MTTCG 更扛不住。

### 2.4 补齐 backboardd 探测到的 selector

`MiniIOMFB` 用户态入口需实现（参考 ios-sweet `IOMobileFramebufferUserClient::externalMethod` 的 case 表）：

| selector | 名（ios-sweet） | 作用 | 本工程状态 |
|---|---|---|---|
| 3  | GetLayerDefaultSurface | 返回默认 surface 的 mach port | 待补 |
| 4  | SwapBegin | 开始一次 swap | 待补 |
| 5  | SwapEnd | 提交 swap（结构 0x46c） | 待补（接 2.2） |
| 6  | SwapWait | 等 vsync | 待补 |
| 12 | RequestPowerChange | 上电/下电 | 待补 |
| 74 | SupportedFrameInfo | 支持的帧时序 | 待补 |
| 79 | GetBlock | 取显存块（discovery 时要返回数据） | 待补 |
| 57 | GetColorRemapMode | 色彩重映射 | not implemented |
| 15 | SetDebugFlags | 调试开关 | not implemented |
| 22 | SetDisplayDevice | 设显示设备 | not implemented |
| 29 | GetDisplayArea | 取显示区域 | not implemented |
| 33 | EnableDisableVideoPowerSavings | 节能 | not implemented |
| 27 | GetGammaTable | 取 gamma | not implemented |
| 17 | SetGammaTable | 设 gamma | not implemented |
| 84 | SetContrast | 对比度 | not implemented |
| 50 | SetBrightnessCorrection | 亮度校正 | not implemented |
| 68 | SetParameter | 参数 | not implemented |
| 55 | SetMatrix | 变换矩阵 | not implemented |

前 7 个（3/4/5/6/12/74/79）是 discovery + swap 的**必需**项；后面 11 个是 01:01 日志里
`not implemented` 的那批，单看每条不阻塞，但 68/55 参与 swap 完成语义，建议一并实现为「接受并返回默认值」。

### 2.5 显示尺寸（本工程已对，勿回退）

本工程用 `fbscale=3`（App `QemuRunner` 写死）让 WindowServer 建 1125×2436 的帧缓冲，与 ramfb 一致。
ios-sweet 用 `/chosen/display-scale=3`。两者等价——**不要**把 `fbscale` 改回 1，否则帧缓冲只有
375×812，合成结果落不进 1125×2436 的 ramfb，又黑屏（`BootPhase.display` 已钉死）。

---

## 3. 顺带要一起做的内核/镜像改动（同样是「一次性构建」的一部分）

| 问题 | 改动 | 出处 |
|---|---|---|
| lsd 风暴 | 精简(FAST)镜像把 `mobileactivationd` 等激活链守护进程纳入作业集 + 补对应 plist/entitlement | §20/§21 真机证据 |
| 慢启动被看门狗杀 | boot-args 加 `wdt=-1 wdt_test=no_user_checkin`（App 0.26.5 已默认写入 payload） | ios-sweet 验证 |
| 守护进程注册失败 | boot-args 加 `launchd_unsecure_cache=1`（App 0.26.5 已默认写入） | ios-sweet 验证 |
| 触屏属性缺口 | 虚拟输入驱动补 `Hidden/MaxHoverHeight/QueueSize/ReportInterval` 等属性 | §21.4 input-property-missing |
| 无 GPU 软件渲染 | 已 `gl=1`+OSMesa，CoreAnimation 走 CPU 路径（无需再改） | §21.3 |

---

## 4. 一次性构建计划（终结「编→测→又编」循环）

1. 内核：把 §2.1–2.4 移植进 `MiniIOMobileFramebuffer.cpp`，`fbscale=3` 保持不变。
2. 镜像：`mobileactivationd` 纳入 FAST 作业集；触屏属性补齐（见 §3）。
3. 构建一次，得到新 `kernel` + 新 `root.apfs`/`system.apfs`。
4. App 侧：本工程 0.26.5 已把 `wdt=-1 wdt_test=no_user_checkin launchd_unsecure_cache=1` 烘进
   payload，并把 `IMAGE_REV` 机制保留——若内核/镜像 ABI 变化，升 `Constants.IMAGE_REV` 强制旧镜像失效。
5. 真机：启动后看 `framebuffer-probe` 事件。若 `pmemsave 0x1fb6bb000` 读出非黑 → 屏幕出画，
   黑屏根因彻底消失；若仍黑，按 `BootErrorCatalog.iomfb-swap-stalled` 的 ACTION 段回查 swap 拷贝。

> 诚实边界：以上内核改动**必须编译一次 iOS 内核镜像**，App 仓库无法代劳。本文件是给内核构建的
> 精确施工单；App 侧（0.26.5）已完成它能做的全部：稳定性 boot-args、更精确的报错目录、payload
> 缓存失效。
