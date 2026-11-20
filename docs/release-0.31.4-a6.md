# 0.31.4 — A6 帧环：像素不再跨进来宾

**目标已修订**：用户 2026-11-19 决定「2~5 帧也可以，先做到这儿」。原 20 FPS 目标经实测判定
不可达，依据与全部数据在 `docs/fps-20-target.md` §3.5/§4。这一版交付的是通往 2–5 FPS 的第一刀。

## 这一版改了什么

worker 每帧渲染完，**直接把 BGRA 帧写进 App 建的帧环文件**，来宾侧不再接收像素
（`EA_READ_RGBA` 带新的 `NO_REPLY`，回复只剩 64 字节头）。于是两段开销同时消失：

| 段 | 改前 | 改后 | 依据 |
| --- | --- | --- | --- |
| 来宾 copy-out（每帧） | **1.32 s**（占单帧 2.03 s 的 65%） | 0 | 实测，41 帧逐帧解析 |
| App 取帧（screendump 8 MB + 读写 PPM + 逐像素解码） | 30–41 + 40–56 ms | ~10–20 ms（拷贝 + `setPixels`） | 实测 / 推算 |

**为什么不是优化传输**：copy-out 那 1.55 s 里，逐字节 swap 只占 47.6 ms（2.9%），32 位字化只省
12 ms（0.7%）；宿主 `glReadPixels` 只 15.7 ms。真正的成本是 10.9 MB 要走
`guestfwd → slirp → virtio-net → 来宾内核 TCP`，约 **7000 个 1500 B 包、~220 µs/包**（TCG 里
跑来宾内核网络栈）。减少字节动不了它，只能让像素不跨界。

## 三条安全网（都有离线测试钉住）

A6 的本质风险是「像素没人收却还照样不问」= 静默冻屏。三处都做了处理，并且**不靠 App 状态猜**：

1. **worker 只在环真的打开时才接受跳帧**（`frame_ring.map != NULL`）。没环就照常回整幅像素。
   离线实测：`qemu/gpu/test-eagl-no-ring.py` —— 发 `NO_REPLY`、worker 无环 ⇒ 回复是完整画面
   （32×32 = 4096 字节，首像素 `BF 80 40 FF`），不是空回复。
2. **来宾按回复自协商**：回复 0 字节 ⇒ 以后继续跳；回复整幅像素 ⇒ 这一次读回直接按行拷进
   surface（worker 已换序并翻转），之后不再问、退回「worker 换序 + 来宾拷行」的普通路径。
   代价最多一帧。（源码已改，真机验收项。）
3. **App 侧任何异常都回退 screendump**：文件不存在/头非法/版本不符/尺寸不符/读越界/seq 连续
   3 秒不前进，一律回退，并只记一条日志。有环但没新帧时**不会**每 16 ms 去截屏（那等于把省下
   的 1.5 s 又还给 QEMU 主循环）。

离线验证（当前树重编的 worker）：`test-eagl-session.py` 全绿（普通路径像素逐字节校验）、
`test-eagl-frame-ring.py` 全绿（环头/BGRA/RELEASE 发布）、`test-eagl-no-ring.py` 全绿。

## 产物

| 文件 | sha256 | 说明 |
| --- | --- | --- |
| `HackiOS-0.31.4-a6-antutu.apk` | `1dfb14bf…` | 188 293 346 B，versionCode 62，**applicationId = `com.antutu.ABenchMark`**（用系统的性能白名单，属于推断，未单独 AB） |
| `HackiOS-0.31.4-a6-gpu.apk` | `4793a3e7…` | 同上但 applicationId 仍是 `com.android.gudao.iphoneos`，用于对照 |
| `gpu-libredwood-gl.dylib`（asset） | `45edd981…` | 559 296 B，来宾侧自协商 |
| `gpu-android-arm64.zip`（asset） | `e9b87acc…` | 54 179 824 B，worker 带 `--frame-ring` |

`GpuRuntime.REV` 1→2（解包目录变）、`FRAME_RING_ENABLED` false→true、`SCREEN_SCALE` 仍是 3
（与 0.31.3 逐字节一致的尺寸与启动参数）。

## 踩到的坑：换 dylib 后「Adreno 根镜像不可用」

换包名 + 换来宾 dylib 同时发生，两条材料路一起断：

- 私有 stage（`userlandDir/root` + `root.apfs`）随换包名被清掉 ⇒ `local = false`；
- 共享备份目录名是 `sha256(OSMesa 根 + dylib)` 的联合哈希，换了 dylib 就是新 key，
  新目录里没有 `stage.tar` ⇒ 也没有材料。

于是 `prepareGpuRoot` 抛「没有与当前镜像匹配的 Adreno 备份或构建材料」。**`stage.tar` 是
输入（Redwood 用户态那棵树），只由 OSMesa 根镜像决定，与来宾 dylib 无关**——用 dylib 去
索引一个与它无关的输入，本身就是错的。

修法（已在 `ImageBuilder.prepareGpuRoot` 落地）：

1. 材料另存一份到**稳定位置** `gpu/adreno-v2/stages/<sha256(OSMesa 根)>/stage.tar`，
   按 OSMesa 根命名，换 dylib 不受影响；
2. 找材料的顺序：本 key 的 `stage.tar` → 稳定位置；只要有一个在就不必让用户重编镜像；
3. 每次成功构建后再写一份到稳定位置（`publish` 不覆盖已有文件），下一版 dylib 直接复用；
4. 复用稳定材料时在进度里说明「沿用 <hash> 的 Adreno 构建材料」，不是静默行为。

本次现场处置：把旧 key `20742308…` 的 `stage.tar`（sha256 `2741bcfd…`，与其
`materials.txt` 一致）复制到新 key `9f3ec349…` 目录，让 App 用新 dylib 重烘 `root-gpu.apfs`。
**`root-gpu.apfs` 不能照抄**——它里面嵌的是旧 dylib。

## 关于 applicationId 改成 AnTuTu

`applicationId` 是系统眼里的身份，`namespace`（`com.android.gudao.iphoneos`）只管 R 类与源码包名，
两者故意不同：改名不牵动任何源码。

- **动机**（推断，未单独 AB）：部分国产 ROM 的性能/调度白名单按包名匹配，基准测试类应用会拿到
  更激进的调频与更少的后台限制。对本机 2–5 FPS 的目标，这是一条「不改渲染代码」的潜在杠杆。
- **代价 1**：数据目录变成 `/data/user/0/com.antutu.ABenchMark`，私有缓存（run 目录、`root.apfs`、
  GPU 客体重）会在首次启动时重建；公共目录 `/sdcard/Download/hackios` 不变，固件不用重下。
  旧包名的那份（含 ~7 GB 私有数据）**不会自动删除**，不需要时手动卸载旧 App 释放。
- **代价 2**：与真正的安兔兔同 applicationId 且签名不同，**两者不能共存**，后装的会装不上。
  对外分发不要用这个包名——AnTuTu 是别人的商标。

## 材料机制的最终形态（0.31.5 起）

`key = sha256(sha256(OSMesa 根) ‖ dylib)` 是**推导出来的**（用公开的
`/sdcard/Download/hackios/images/root.apfs` 的 sha256 `d4a6601b…` 与两个 dylib 复算，
分别得到旧 key `20742308…` 与新 key `9f3ec349…`/`bb91eb65…`，全部吻合）。所以材料查找现在是三级：

1. 本 key 目录的 `stage.tar`（旧行为）；
2. **稳定位置** `gpu/adreno-v2/stages/<sha256(OSMesa 根)>/stage.tar`——按根命名，换 dylib 不受影响；
   每次成功构建后回写一份（`publish` 不覆盖），供以后所有 dylib 复用；
3. 兄弟 key 目录里"属于同一个根"的 `stage.tar`：判据是拿该目录留档的 `gpu-libredwood-gl.dylib`
   与当前根的 `stableDigest` **再算一遍 key**，等于该目录名才认。

第 3 级是给"稳定位置还没被写过、而本 key 又是新的"这个首次换 dylib 的空档兜底的。
本次现场就是把 `stage.tar`（`2741bcfd…`）种进 `stages/d4a6601b…/`，之后换多少版 dylib 都不会再问。

## 怎么验收

1. 装 APK、启动，等到桌面；
2. 看诊断面板：**取帧耗时**应从 ~70–97 ms 掉到 ~10–20 ms，`采样` 与 `内容 fps` 分开看；
3. 内容 fps 应从 0.05–0.7 升到 **1.5–2**（推算：单帧 1.88 s → 0.55 s）；worker 日志里应出现
   `EAGL worker: frame ring at …, 1125x2436`，来宾日志里 `hackios-gl: out N v4 skip …`；
4. 若仍显示 0.5 FPS 且日志里没有 `v4 skip`，说明自协商退回了普通路径，把 worker 日志发我。

## 下一步（未做，等这一版真机数据）

渲染倍率：`fbscale` 3→2 或 1（750×1624 / 375×812），把残差里与像素面积相关的部分再降
4/9 或 1/9，用来进 2–5 FPS 的上半区。前提是实测确认「ramfb 仍是 1125×2436、来宾按 750×1624
建图」不会让来宾停手（A6 之后 App 只读帧环、不读 ramfb，所以可能根本不需要改 loader）。
详见 `docs/render-scale.md`。
