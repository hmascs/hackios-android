# 原分辨率图形传输的 CPU 开销与候选方案

范围：1125×2436、双 vCPU、delta2 同步回写。用户现正式目标为实际内容10 FPS、真实宿主GPU路径且无抽搐/闪烁/黑块，并加快启动；2–5 FPS仅为中间点。源码/patch/hash可恢复快照仍是交付要求。本文区分代码核查、历史测量、现有日志离线复算和待证伪推断；没有新增 QEMU 长负载试验，没有性能改动进入 App assets。

## 先修正确性：DELT 多行回写丢失 destination stride

代码核查发现原 [eagl-guest-surface.c](/root/workspace/hackios-next/qemu/gpu/eagl-guest-surface.c) `apply_reply()` 将一带 `rows*row` 紧凑字节直接 `memcpy(base+y*stride, ...)`。实际 `row=4500`，目标 stride 为 4544 或 4608；从第二行开始便写进前一行 padding，后续行整体错位。FULL 通过逐行 copy，不受此错误影响。

原 [C 测试](/root/workspace/hackios-next/qemu/gpu/test-eagl-guest-surface.c) 的 DELT 只有一行；原 [worker Python 测试](/root/workspace/hackios-next/qemu/gpu/test-eagl-delta.py) 的 surface model 是紧凑存储，虽然请求携带 padded stride，也不能覆盖实际 C 解包器的 padded 写入。测试通过不能说明此边界正确。

lead 授权扩展 task-4 scope 后，本队员只修改上述 C 解包器及其 C 测试：DELT 每行写到 `(y+r)*stride`，整个 table 与 payload 仍在任何写入之前完成校验。新增 4500-byte 行、4544/4608 双 stride、35 行、两面交替、16 行首带与 3 行尾带、中间 16 未变行、padding 与末尾 canary 全字节比较。另验证坏第二带、截断 payload 都不修改第一带，以及合法零带不修改 surface。

执行 `bash qemu/gpu/test-eagl-guest-surface.sh`：新增测试在原实现上 exit=134（首个多行 DELT 全字节比较失败）；逐行修复后 exit=0。validation 已独立运行 C fixture 通过，并确认既有 stride/capacity 上界足以约束逐行写，完整table检查仍在写前。此为 Linux 本地 C 模拟回读，不是 iOS 真机/完整动画验证。后续授权的隔离交叉编译与worker smoke已完成，见文末结果；尚未更换发货资产或完成生产尺寸连续动画。

该错误使此前某些 DELT 后的图像与下次纹理上传不可信。必须先修复并完成端到端验证，再建立优化基线，不能将旧低字节量或发布率当作正确帧率证据。

额外尝试 `clang -fsanitize=address,undefined` 的本地回归链接失败：环境缺 `libclang_rt.asan_static-aarch64.a` 与 `libclang_rt.asan-aarch64.a`，exit=1。没有安装新依赖或声称 sanitizer 已通过；普通 `-O2 -Wall -Wextra -Werror` 测试已通过。修复保持原协议，但发货需要重编 guest dylib（新 hash 会改变 Adreno root cache key），并验证 stage 恢复/重建路径，不覆盖用户已有镜像。

## 当前路径与可量化事实

[guest transport](/root/workspace/hackios-next/qemu/gpu/eagl-guest-client.c) 同步执行：20-byte 请求头、请求 body 两次 `send_exact()`；读取20-byte回复头，再 `read_exact()` 收全部 body。遇短读/短写继续，EINTR 重试；不是每行一个 socket read（scatter 实验另论）。每个 GL 事务都等回复，不能任意跨越 query/fence/readback 合并。

[wire 定义](/root/workspace/hackios-next/qemu/gpu/eagl-wire.h) 的 delta 请求24 bytes，指定 opaque surface VA key、stride、width、height、转换 flags。回复是 `FULL+packed pixels` 或 `DELT+bandCount+{y,rows}表+packed bands`。带高16行，宽覆盖整幅；1125×2436 的 packed BGRA 是 10,962,000 bytes，一整16行带是72,000 bytes。

[host worker](/root/workspace/hackios-next/qemu/gpu/eagl-host-session.c) 每次仍做整屏 GPU readback、BGRA转换、ring发布、按 surface 三槽 LRU 的全屏 band 比较，再选择 wire reply。ring发布早于 guest 回写完成，不能拿它证明 guest 的同步正确性。cache 在 `send_reply()` 成功后提交；这只证明字节被写入 stream，并非 guest 已应用的显式 ACK。当前同步下一请求只能在上一回复读取与应用后发出，提供有限的顺序保证；连接失败须丢弃 base。

历史已记录的全帧路径：glReadPixels 约15.7ms，host写10.96MB约1.535s，guest读约1.569s，guest字节换色约47.6ms。这些读写时间重叠，不能相加；也不是各阶段 CPU time。来源与当时二进制见 [历史测量记录](/root/workspace/hackios-next/docs/fps-20-target.md)。文档中的“220µs/包”是以墙钟/估计包数倒推，不是逐包 CPU 采样；“每包中断”与下面所读 driver 实现不符。

来宾 [virtio-net 实现](/root/workspace/hackios-run/xnu-bootlog-build.qzbibE/tree/bsd/dev/hakios.virtio_net.c) 的结构证据：queue=64、buffer=2048，每包复制；只协商 MAC，不用 checksum/GSO/merged RX；不使用 RX 中断，由 `virtio_poll()` drain used ring。来宾 [network thread](/root/workspace/hackios-run/xnu-bootlog-build.qzbibE/tree/bsd/dev/hakios.net.c) active 时2ms、安静后10ms delay 轮询，逐包提交 `ifnet_input()`；MTU上限显式是 ETHERMTU。读取到的 [Inferno slirp glue](/root/workspace/hackios-run/qemu-inferno.tfi5zu/src/net/slirp.c) 只把 chardev 与 `slirp_socket_recv/can_recv` 桥接，无 vnet_hdr 协商。

这些是源码结构，不是已用新计量器证明的 CPU 百分比。Inferno 源码不等于发货 QEMU11.1.2源码；不得混为同版本测量。没有GSO也不能普遍推导“所有 slirp 永远1500MTU”；这里 MTU/2KB buffer/无协商三者共同限制，单改 QEMU CLI 不够。

现有 [ui_lite worker日志](/root/workspace/hackios-run/glpass-uilite/worker.log) 的只读离线复算（全运行，包含启动，不是稳态150s窗口）：256 readbacks，3个full-sized回复，9个零band回复；累计225,869,476 wire bytes，单回复中位576,072 bytes；256条宿主计时的均值为 dispatch18,952.4µs、glRead12,144.9µs、convert1,783.9µs、socket_write90,697.7µs。日志SHA256 `b4e9faeccc3c77b4b63a590738f85923fc15ee878793ddd4dcb656d4fbf112cf`。dispatch包含glRead/convert，不能重复相加；socket write含backpressure，不能称CPU占用。旧日志没有worker精确窗口时间戳，不把上述均值移植到精确测量窗口。

同次旧 harness 的可追溯子窗口424.7→546.0s，199次ring/readback发布 /121.3s=1.64056次每秒、QEMU约1.82275核（tcg工具复算）。此为吞吐，不证明不同可见内容FPS；不能由这次结果单独证明 ui_lite 有益。

## 不变量与上界口径

所有候选必须保留同步语义：渲染完成 → 合法、完整回复 → 全部像素按目标 stride 写入仍锁定的 IOSurface → 成功/解锁；不能用 NO_REPLY 绕过。历史跳过导致图标逐个出现、闪烁、黑块，不再重走。

base identity 目前只有 VA/stride/shape，不能证明 allocation generation 相同，也不能证明 guest CPU 没改 surface。任何更细差分都须承认此旧前提；建议接入可证明的 generation/写入生命周期，无法证明时 FULL 回退，不靠忽略 guest 写入获得提速。全帧 guest hash 可能贵，必须计量其额外 TCG 成本。

令同一已完成交互的墙钟为 T、可消除且在关键路径上的传输墙钟为 R，理想加速上界 `T/(T-R)`；它不是实测提升。若示例 T=600ms、R=100ms，则即使R全消也只有1.20倍（1.67→2.00次/s，仍不自动等于内容FPS），无法凭传输单独承诺5FPS。host write与guest read重叠，只计一次；任何新解码/driver成本必须减回收益。

## 候选一：按真实字节成本选择 FULL/DELT

当前 worker 用 `changed_rows*2<=height`，超过半屏便FULL；旁边“超过半屏FULL更小”的注释不成立。DELT长度是 `8+8*k+changed_rows*4500`，FULL长度为 `4+10,962,000`。额外band table顶多约1.2KB，真正字节交叉点接近整屏，而非50%。

实现复杂度低：在同一协议与base可信前提下，以实际长度及可测的CPU成本选择；FULL保留给无base、conversion/generation变化、接近整屏或解包成本更贵时。正确性不新增格式，先完成stride修复。单帧最理想省约半个全帧回复（刚超过50%那档），但当前ui_lite只有3/256 full-sized，且无法区分cache miss与阈值触发，所以不推断全运行显著收益。

最小证伪：离线构造同一surface改变49%、51%、75%、100%行的回复，比较候选字节长与重建像素；真实worker增加FULL reason/changedRows原值，不改变输出，测有多少FULL确实由阈值触发。若稳态几乎无阈值FULL，直接降优先级；无需先跑整个桌面。之后同boot固定动画ABBA比较总事务耗时与guest逐行apply指令成本，防“更少字节反而更多TCG”。

## 候选二：更细的 x-span / tile 无损差分

现有任何一个像素变化就发送全宽16行。可保留16行分组、每组取变化像素的 min/max x，或者32×16 tiles，再合并相邻块；只传无损BGRA，明确rectangle bounds与payload长度，先完整验证所有rect再写逐行。需要新capability/格式标记，旧端拒绝或退FULL，不能悄悄复用DELT。

上界：若一个16行带只有32像素宽发生变化，现有72KB对32×16×4=2048bytes，像素部分理论约35倍缩小；这是示例几何上界，不是实际画面收益。对全屏滚动/随机噪声、细碎块多的画面几乎不省，table/扫描更贵。应用到总体T的上界仍仅是删掉当前R的一部分。

复杂度中等：宿主扫描/分块编码、guest每rect bounds/stride写回、capability、base generation、bad payload原子校验。host native扫描可能便宜，guest多小copy/分支在TCG下可能贵，不能只看压缩比。

最小证伪：采producer连续raw完整帧，以每surface各自上次已提交版本为base，按窄时钟/图标、拖动、全屏滚动三类离线编码，输出rowband/xspan/tile的bytes、块数、FULL率、编码CPU；逐帧重建与raw逐byte比对。字节数不与解码毫秒直接相减：先用相同payload规模/包数测得可省的critical-path传输wall，再减去host编码与guest解码新增wall；净收益不正则停止。再做小guest端到端buffer事务，测相同图像/交互的CPU及wall。不能用相邻ring两帧替代每surface base来估“生产delta”的字节率。

## 候选三：FULL/DELT body 的无损快速压缩

可先对FULL或大DELT做LZ4式低复杂度块压缩；每块有rawLen/encodedLen、严格上限，若压不小则原样。压缩能力协商；先解到有界scratch、完整校验再按stride写，不能解了一半就污染surface。独立块/无跨帧字典可简化重连和base失效，不引入有损视频/色差或分辨率变化。

收益上界：假定原body B、压缩后比率q，最多删 `(1-q)*B` 对应的传输时间，减去宿主压缩与guest解压时间；若body受包数限制且q=0.25，理论剩余body时间约四分之一，但完整事务还有GL/readback/固定往返。实际壁纸与全帧滚动可能近不可压，不能预言q。

复杂度中等；主要风险是guest TCG解压把TCP节省吃掉，膨胀长度/损坏块、版本不匹配与部分提交。JNI/native宿主快不代表来宾快。

最小证伪：与候选二复用相同raw帧序列，离线测压缩比/host CPU；在来宾短测试进程对固定buffer实际解压（冷/热各测，固定2vCPU）并校验hash，求 `saved transport wall - decode wall - encode wall`。对高熵buffer设负控；若CPU净收益不正，拒绝。先不要做跨帧复杂字典。

## 候选四：由 QEMU 搬运的独立 bulk 设备通道

增加 bounded bulk scratch 与事务门铃，或virtio-serial式有界队列：worker把回复给QEMU，QEMU写guest注册的DMA buffer，guest检查序号/长度再复制到仍锁定的IOSurface并确认完成。TCP可以先保留小控制命令，仅替大pixel body；仍不能跳过回写。不是让worker mmap当前RAM文件：MAP_PRIVATE/CoW使外部写不可见。

第一版只注册明确生命周期的guest kernel scratch pages；先别让QEMU直接写应用IOSurface VA。VA不是GPA、物理页可能不连续，必须pin/注册scatter-gather、bounds、guest reset generation与取消；取消后收到旧DMA不得写到新allocation。guest读到完成前不能解锁，worker写入队列不等于guest应用ACK。

潜在上界是整个当前TCP body与相应协议/调度成本减去新队列、复制、确认成本；不能消除CA/WindowServer逻辑与整屏GPU readback。历史QMP pmemsave40–55ms是旧QEMU8.2的读取/文件路径，不是当前11.1.2 DMA写回测量，更不能外推当前必然10ms。

复杂度高：QEMU设备+guest内核驱动+user API+worker桥接+可靠降级。最小证伪先不启动完整桌面：已知scratch buffer做1KB/64KB/1MB/10.96MB双向序号事务，逐byte校验，测完整submit→guest可见→ACK的wall和CPU，与同机同buffer的现有slirp通道比较。若小payload比TCP更慢或大payload收益小于目标成本，停止。通过后才接surface，测double buffer、reset/断连/超时/取消和重复提交。

## 候选五：轮询与内核网络投递策略（暂次优）

结构上2ms/10ms网络轮询、64描述符队列、逐包mbuf复制/ifnet_input可能造成调度与批次开销。可研究按活动自适应poll预算、批量上送或接收通知，或更大MTU路径，但不是单改buffer=64KB就安全：现实现假定buffer不跨guest页、kvtophys单地址、没有merged RX/GSO，host slirp能力也须同步支持。

收益上界受可归因的poll/网络处理份额约束，目前没有可信百分比。更短poll可能降低少数ms延迟却增加CPU/发热，反而拖慢CA；单凭旧timebase PC热点不能选它。最小证伪先记录不额外逐包打印的窗口计数：poll次数/空poll/每次drain包数、队列满/丢包、等待时长、每事务wall及两vCPU CPU。固定同步buffer压测再改变一项poll参数，同bootABBA；没有净CPU与wall收益便拒绝。此项需要lead另授guest driver scope，不混入当前精简内核首轮。

## 不优先做的“省系统调用”

host回复头+body、guest请求头+body可改writev/有界一次write，或试TCP_NODELAY/收发buffer，但这些最多影响固定小包/调用成本，不会使10.96MB TCP绕过按MTU处理。现header等待还含GL readback/worker调度，不能叫纯RTT。既有GL handing只有数ms，批处理的全帧收益上界也只是这几ms，不应当主线。

scatter按行直接读surface会把一次body的socket循环变成最多2436行读请求，反而增加guest syscall；低变化下apply本来小，必须先测copy占比。禁止未校验表就边读边写，以免半帧污染。

## 与队员互审与共同验收

validation独立确认stride错误，要求候选一/二先离线相同raw frame序列，按窄变化/滚动/全屏分层报告wire+扫描CPU+guest解包；bulk设备先做完整提交/确认短事务，不先跑整个桌面。要求所有路线覆盖双buffer、尺寸/stride变化、重连、FULL回退、取消与生命周期。

tcg建议xspan/tile优先于共享设备；wire下降必须同时看guest apply成本，待可信指令计量校准后可给每次已完成交互的guest指令变化。bulk先用固定scratch，不直接映射用户VA。

guest-load指出VA key/stride不足以证明base未被guest CPU修改或重新分配；所有增量协议应以generation/按序写入证明为前提，不满足便FULL。共享设备只能搬字节，不应把CA surface强换成共享映射；持锁复制并确认后再publish/unlock。

validation还指出当前ring v1 writer覆写pixels前没有独立in-progress guard，reader前后相同seq可能仍接受混合帧。此项已报告lead，超出本次修改scope；候选验证使用producer同步raw采样与guest写回校验，不能仅拿App截图/旧ring hash当完整帧证据。

建议每事务观测字段：session/surface generation、op seq、read id、FULL/DELT与fallback reason、raw/wire bytes、band/rect数、host encode/readback/write墙钟与CPU、guest recv/apply wall与可校准指令数、队列深度/丢包/ACK完成、producer像素hash及显示序号。hash用于验证需计入观测扰动；稳态性能模式不逐帧磁盘写10.96MB。

最小视觉观测组合：producer发布时采完整packed BGRA hash及相邻changed-pixel/tile数，guest frame日志纹理refill count/KB与draw/call数，以及固定交互动画的连续可见帧与input→首变化/最终稳定延迟。DELT参考的是同surface上次内容，不等于相邻可见帧变化；零band也不代表相邻画面没变。ring seq每秒增长只表示发布，有覆盖与读漏帧，不当内容FPS。对dl_max等请求率优化，必须证明只减少无效回调/重复发布，而有效动画步骤与延迟未退化。

优先次序：正确性stride修复与baseline重建 → 候选一统计阈值FULL是否存在 → 候选二离线几何差分和候选三实际guest解码证伪 → 若仍有明显TCP墙再立项bulk设备。任何性能实现须由lead指定新scope；本次不修改host协议/network driver/App assets或镜像。lead 后续另授的 HFR2 worker 交叉编译/集成测试在独立 transport-release 目录执行，结果以该目录实际 manifest 为准。

## 新要求核对：VKGL 与真实宿主 GPU

对当前项目源码/脚本/文档执行大小写 `VKGL|vkgl` 检索没有发现该具体库的集成或引用。当前代码与打包链明确是：guest Redwood EAGL replacement（自研remote GLES协议）→ host [eagl-host-session.c](/root/workspace/hackios-next/qemu/gpu/eagl-host-session.c) 的 EGL/GLES → Mesa Zink → Vulkan loader → Mesa Turnip/Freedreno ICD → KGSL/Adreno。这个链路不能改名宣称“已使用VKGL”；如果用户指的是具体VKGL项目，必须先澄清仓库/版本/API兼容性，再独立评估接入，不把等价功能当具体依赖已经满足。

[打包脚本](/root/workspace/hackios-next/qemu/gpu/package-android-gpu.py) 从指定Termux前缀追踪ELF依赖，把 `libEGL_mesa`、`libGLESv2`、`dri/zink_dri`、Vulkan loader、`libvulkan_freedreno` 等打包，并固定 ICD/vendor manifest。它未引入名为VKGL的库。生产 [GpuRuntime](/root/workspace/hackios-next/app/src/main/java/com/android/gudao/iphoneos/GpuRuntime.kt) 运行的是 `hackios-zink-session Adreno`，不是 `host-vulkan.c` 的独立三角形模块。

历史 [GPU设备验收记录](/root/workspace/hackios-next/docs/gpu-backends.md) 报告 `Adreno (TM) 830 [vendor=0x5143 device=0x44050001 type=1]` 与Turnip/Mesa、KGSL设备。它是历史探针记录，不是本次生产worker的全链重新验证。[host-vulkan.c](/root/workspace/hackios-next/qemu/gpu/host-vulkan.c) 确有physical-device枚举、vendor/type/graphics-queue选择、`vkCmdDraw`、`vkQueueSubmit`、fence等待与回读；但它是独立Vulkan探针，不能据此证明当前Zink worker每个实际iOS帧都在该device执行。生产worker目前只用 `glGetString(GL_RENDERER)` 的字符串包含匹配作为初始化闸门，证据不足以单独满足新验收。

建议验收最小闭环（先只读核查/设计，本scope未新增这些探针）：

1. **身份**：在与生产worker相同Bionic前缀/ICD/环境枚举 `vkGetPhysicalDeviceProperties2`，记录vendorID、deviceID、deviceType、api/driverVersion、driverID/name/info、deviceUUID/driverUUID、实际graphics queue。拒绝CPU/software device；记录实际加载的ICD与ELF哈希。再将生产进程的已加载库映射、设备句柄与该身份关联，不能只展示另一进程vulkaninfo的结果。
2. **实际执行**：在该worker一次带唯一颜色/纹理的draw→readback→guest回写事务中，关联实际Vulkan submit/fence完成与输出像素hash。若能用诊断layer记录Zink内部的 `vkQueueSubmit/2`、物理device对应关系及完成事件则更直接；另收同进程 `/dev/kgsl-3d0` 的context与提交/同步ioctl证据。仅打开KGSL fd不证明有GPU draw；仅submit成功也不证明已完成，需fence/时间戳及正确像素。
3. **时间与归属**：GPU timestamp queries（若设备支持）与host事务seq关联；设备busy/频率只能作辅助，因为其它App也会用GPU。若权限或proot/ptrace限制不能采KGSL事件，明确缺失，不改用renderer字符串假装通过。可选在隔离构建里增加轻量driver/loader诊断，不覆盖系统库、不要求root。
4. **反例**：在隔离负控禁用/替换ICD，确认生产worker拒绝软件fallback或明确报错，不能静默通过同一个“GPU已连接”结论；同序列验证双surface、回写、纹理重上传与连续动画无黑块/撕帧。App自动切OSMesa必须明示，这是恢复启动，不算GPU验收通过。

10 FPS预算每次不同可见内容更新最多100ms。完整10.96MB若每帧都传需约109.62MB/s纯像素、现例中位0.576MB若维持该变化量仍约5.76MB/s（只作带宽算式，不是动画实际需求）；更少字节只能降低传输段，不能绕过CA/TCG其余时间。按示例600ms周期，达到100ms须至少删掉500ms；若可删传输只有100ms，即使整段归零也远不足。候选二/三/四值得证伪，但10FPS需要可信CPU计量与guest工作量/内核优化联合，不能因宿主GPU画得快便承诺整条链10FPS。

## 隔离构建与小型集成结果（lead后续授权）

managed job `bash-d7ee0dd4137b4226` 已收齐输出，exit=0；全部重负载操作经同一实验锁串行，没有替换App assets/现运行镜像。[结果清单](/root/workspace/hackios-run/transport-release-hkt2a6wu/manifest-built.json) 含每步实际命令、日志、退出码与产物身份。

- [guest dylib](/root/workspace/hackios-run/transport-release-hkt2a6wu/guest-stridefix/libredwood-gl.dylib)：559,232 bytes，SHA256 `613e706c6ebf7ccf2508054863c1114f600539121bd1d1353b2225f9894403c9`；ARM64 Mach-O DYLIB，407个导出符号集合与当时App的068234…版本完全相同。[Mach-O依赖/头](/root/workspace/hackios-run/transport-release-hkt2a6wu/guest-mach-o.log) 已保存。此证明可交叉编译与ABI未删改，不证明iOS端运行已通过。
- [HFR2 worker](/root/workspace/hackios-run/transport-release-hkt2a6wu/worker-hfr2/hackios-zink-session)：46,456 bytes，SHA256 `e12f9a0fefb637aa06093e41c6d9c85e59934aadb8471b664714a449b79037a1`；AArch64/Bionic、LOAD 0x4000对齐。[ELF证据](/root/workspace/hackios-run/transport-release-hkt2a6wu/worker-elf.log) 已保存。
- [源输入哈希清单](/root/workspace/hackios-run/transport-release-hkt2a6wu/source-hashes-before-build.json) 包含未跟踪header `eagl-frame-ring.h` SHA256 `8309e0b64df642186596c4807a6df3f471ab6e1e4c9960b0e7440152cc245767` 和host source `3c9f9350a3e4ab631f5cc5797fee683d44e16c6ccd067c1c8800c185087ae19b`；guest编后、worker编后、smoke后重算都未变化。SDK目录名16.5，但SDKSettings实际元数据16.4，manifest如实记录，未擅换历史工具链。
- 新guest配当前stable-root raw digest重算的key为 `5941155e58dd515e64bab870a93aa14bc52bc2316dd07112e906dc12bd22d168`。后续隔离stage实际恢复/按字节验证已通过，见下节；尚未替换App私有cache或现有镜像。

通过的小验证：本地 [surface C回归日志](/root/workspace/hackios-run/transport-release-hkt2a6wu/surface-c.log)；[HFR2顺序集成](/root/workspace/hackios-run/transport-release-hkt2a6wu/ring-v2.log) 验证完整BGRA、pubcount与guard、错尺寸NO_REPLY返回像素且不改ring、旧HFRM对新worker不兼容时仍正常回像素且旧文件未变；[无ring回退](/root/workspace/hackios-run/transport-release-hkt2a6wu/no-ring.log)；[delta worker20帧](/root/workspace/hackios-run/transport-release-hkt2a6wu/delta-worker.log) 的首次FULL/变化band/零band/翻转/多surface/尺寸变化；[旧worker显式v1](/root/workspace/hackios-run/transport-release-hkt2a6wu/ring-legacy-v1.log) 兼容路径。均exit=0。小测试用32×32或64×100，不是生产视觉帧率验收；worker Python delta model仍是packed model，padding C实现由独立C fixture覆盖。

这些小worker测试确实在隔离Zink/Turnip环境得到预期像素，但本次未新增同生产worker的physical-device UUID/submit/fence/KGSL关联探针，所以不把renderer字符串当新GPU全链验收。尚需原1125×2436双vCPU的真实guest回写/纹理重上传/连续交互，以及无黑块闪烁和10内容FPS测试；目前没有新增性能提升结论。

## 自包含候选包与 stage 实际恢复

lead 另授权后，managed job `bash-6b91f04c691847d3` 经同锁串行完成，exit=0，未重编上述固定组件。所有步骤命令/退出码见[候选manifest](/root/workspace/hackios-run/transport-release-hkt2a6wu/candidate-package/manifest-candidate.json)。

- [独立候选GPU zip](/root/workspace/hackios-run/transport-release-hkt2a6wu/candidate-package/gpu-android-arm64-hfr2-candidate.zip)：54,181,547 bytes，32个依赖库，SHA256 `66821d52b1f32769ed3cdad6930076eb7cb96b9f6bf2d106938dda4a38c8e35d`。递归ELF依赖检查及路径限制由原打包器执行；仅在新目录解包，通过相对路径的[start-worker.sh](/root/workspace/hackios-run/transport-release-hkt2a6wu/candidate-package/self-contained/gpu-android-arm64/start-worker.sh) 成功运行HFR2、无ring、delta及GL shader/texture/FBO/indexed draw/fence/pixels四组smoke。包内worker仍为固定e12f9a0f…，没有借用旧绝对prefix wrapper。此证明包可相对路径运行，不自动等于physical-device/提交链完整证明。
- 从stable-root身份对应的15,360,000-byte stage快照恢复到独立副本，用APK内实际packer（SHA256 `8be9788785cb9c31b04e1b4b8b64e77ba4a9f490370e6aa097ae146c7ed49784`）以相同 `root.apfs` basename重打，得到的[root.apfs](/root/workspace/hackios-run/transport-release-hkt2a6wu/candidate-package/stage-restore/root.apfs) 与原公共OSMesa root **逐byte一致**，hash仍d4a6601b…；然后仅覆盖恢复树内的guest dylib并保留生产DHCP命令，生成新root。
- [新root-gpu.apfs](/root/workspace/hackios-run/transport-release-hkt2a6wu/candidate-package/stage-restore/root-gpu.apfs)：5,046,272 bytes，SHA256 `e30a021b30a7b13c95916f2b76e1fefd15fc56b65e0ce5cbdc23d124d5a7b4df`；APFS NXSB标记正常，cache key复算仍 `5941155e58dd515e64bab870a93aa14bc52bc2316dd07112e906dc12bd22d168`。这证明隔离材料恢复路径可用，**不是App内新key cache点击启动已验收**。

所有公共输入、原guest/worker及脚本在步骤前后hash不变，无用户文件覆盖。候选组件已交validation做guest-writeback→texture真实draw/readback oracle，之后交kernel以显式virt10.1单boot使用；本队员不自行启动VM。

## 部分场景渲染问题：语义审查新增待证伪项

用户明确仍有部分场景渲染异常，前述两个正确性修复和小smoke不能背书全系统渲染正确。lead据此将GPU trace shim降为次优先，要求先审查生产remoteGL语义；旧候选包/root保持原样，不覆盖。

### D1：增量base缺少guest内容/分配代数验证（现已改为私有base重建）

旧delta2代码确定：worker只用VA key/stride/shape/convert认base；生产request无allocation generation、seed或guest内容token。合法反例为FULL A已提交后，guest CPU把未dirty像素改为X（或同VA重新分配），worker下一图仍A，返回零band，旧guest apply保持X而非A。逐行stride修复不能解决它。原 [Redwood EAGL](/root/workspace/hackios-redwood-reference/userland/src/eagl.c) `surface_changed()` 注释明确说明surface可以继续被CPU绘制，且seed不一定移动。

独立 [actual guest decoder负控](/root/workspace/hackios-run/patched-rendering/live-base-oracle.c) 已实际复现：旧decoder在CPU修改后的第二次回复零band时assert失败（[红日志](/root/workspace/hackios-run/patched-rendering/delta-red.log)，SIGABRT/return -6）；同fixture强制FULL对照exit0。没有宣称它已定位用户所有视觉异常。

最初尝试的“完整live memcmp+token”策略经guest-load/validation互审否决其排他前提：App对应MiniIOSurface的Lock/unlock只是seed通知，不排斥其它CPU writer；网络等待期或compare扫描后可被写，postmemcmp也不是原子快照。lead采用更稳策略：**private packed shadow是远端图像基线，仅从完整校验的FULL/DELT重建；每次成功回复后，把整幅有效像素逐行复制到live IOSurface。** 该语义与普通FULL回读写全图一致，不靠live内容/seed证明，可以覆盖CPU在请求前、网络等待期改写未dirtyband。最终copy与不遵守所有权的CPU同时写的普通race仍存在，与原FULL相同，不声称任意并发原子性。

guest保留3slot私有shadow，总预算64MiB，令牌只标识private base；无有效base、几何/转换改变、LRU淘汰或不能分配shadow时，用不重用的新64bit key请求首FULL；fresh key收到DELT（含零band）拒绝且不写live。无shadow预算时正常FULL直接逐行copy，不崩溃。失败使base失效；完整table/payload验证先于private或live修改。每次全copy保持padding及最终行最小capacity边界，完整shadow从不从live回读提交。

源码与回归暂冻结在 [D1快照](/root/workspace/hackios-run/patched-rendering/d1-private-base-v1/source-identity.json)，其中surface C SHA256 `81a4ab566d7d82c5905152b175b2498ac3a547bbd21d5ab9315c817993510e60`。普通及零shadow budget两套CPU回归通过，覆盖CPU调用前/网络等待期写、同VA新内容、静态delta、双真实stride、多band、末行capacity/canary、坏第二band/截断、LRU4surface、freshkey非FULL拒绝、I/O失败失效、两个pthread各20次readback。[完整回归日志](/root/workspace/hackios-run/patched-rendering/private-shadow-fullcopy.log) 保存；两个pthread smoke不等于所有外部CPU writer被同步。

guest-load另以冻结actual-helper做独立default/zero/budget32三套二进制测试，全部exit0；[独立结果](/root/workspace/hackios-run/patched-guest-load/shadow-review/review-run-v3/result.json) SHA256 `7c2dd9ff94e010e9a7b0f8e2eedc077b85d5ff4de8f83b51bca365675ba0ed57`。覆盖十类畸形回复的private/live逐byte不变且valid失效、malloc失败、tokenMAX不回绕、guardpage、最小容量与64MiB allocator-only预算/LRU记账。大块仅malloc/free未作渲染缓冲，不能证明高压实时内存性能；socket仍mock，不能证明所有GLcontext锁交互。原8文件快照遗漏间接include `eagl-scalars.def`，独立fixture明确另冻结该依赖，未偷改快照身份。

性能代价未隐藏：public函数进入即起total计时，包含surface mutex等待、shadow lookup/分配、compose与完整live copy；单条原日志末尾增加shadow_prepare_us、shadow_compose_us、full_copy_us/full_copy_bytes、mutex_wait_us及forced_full原因。原1125×2436每次完整写10,962,000 bytes；不能沿旧“约10ms”假定新TCG成本，后续实机测量。64MiB只是shadow预算，scratch另计且扩容malloc-before-free有额外瞬间峰值；3张生产shadow约31.36MiB再加约10.46MiB scratch。上述新源码尚未合并D2/D3重编发货，新613/e12候选保持冻结作startup诊断，不冒称最终渲染修复已验收。

### D2：原Redwood surface_changed尾部像素未哈希

[上游EAGL代码](/root/workspace/hackios-redwood-reference/userland/src/eagl.c) `surface_changed()` 仅循环 `at+8<=size` 的64-bit词，完全忽略最后1–7 bytes。size<8时不读任何像素，hash仅初始size。跨frame、seed未变化、仅尾像素变化的合法情形会错判未变，从而不重新上传纹理。

最小fixture：1×1 BGRA surface（size4），第一次记录sum，下一frame只改像素且seed固定；或奇数width的ALPHA8最后一个pixel。预期changed=true，现循环为false。此在原Redwood源/生成guest EAGL层，不是刚修的传输decoder；未授权前不改reference源码。需要独立fixture确认后按lead新scope修，不把它未经复现当用户症状归因。

### D3：带attributes的draw重基改变gl_VertexID

[数组encoder](/root/workspace/hackios-next/qemu/gpu/eagl-client-arrays.c) 把DrawArrays `first`、DrawElements min index消掉，wire HAA1没有原base；[host临时VAO回放](/root/workspace/hackios-next/qemu/gpu/eagl-host-arrays.c) 用重基indices的 `glDrawElements`。因此shader同时使用enabled attributes与 `gl_VertexID` 时，GL可见顶点ID变为0起点。源码注释承认不得用于这类shader，但当前路径没有明确拒绝或保留ID。

最小fixture：带一个enabled attribute的 `glDrawArrays(first=7,count=3)`，shader按 `gl_VertexID-7` 选择三角形位置，比较原生draw与wire回放；再加DrawElements indices7/8/9。已有[session测试](/root/workspace/hackios-next/qemu/gpu/test-eagl-session.py) 的procedural gl_VertexID只测**无arrays**的另一opcode，所以不能覆盖此缺口。修复需扩协议保留base/完整indices及attribute offset，或对不支持路径fail-closed（不能静默错画）；这不是先入为主batch或性能优化。

### D4：texture swizzle语义回归已获CPU状态fixture复现

[host texture upload](/root/workspace/hackios-next/qemu/gpu/eagl-host-pixels.c) base上传总会重设texture-wide swizzle（ALPHA/LA模拟所需，RGBA恢复identity）。GLES `glTexImage2D` 本身不应无条件抹去用户已有swizzle；guest scalar允许任意 `glTexParameteri`。lead独立CPU GL-state mock已复现：identity RGBA控制通过，而用户custom `(BLUE,GREEN,RED,ONE)` 经普通RGBA baseupload后变为identity；compile0、expected-negative test exit1，源码未变，hostpixels SHA256前缀9af84c6f…。证据在 [D4结果](/root/workspace/hackios-run/lead-render-audit/d4-2iuj2hlq/result.json)。这证明API-state回归，尚不是GPU实绘或用户症状归因。

后续必须分层追踪用户原swizzle与legacy ALPHA/LA底层format mapping，不能简单“RGBA永不reset”而破坏ALPHA→RGBA切换，也不能凭双向猜测恢复。lead后续授予D4独立任务后，已实现并构建下述新worker，旧e12与D123root保持冻结，不混系统复验归因。

#### D4真实shader红与显式metadata合成修复

[旧e12真实shader红结果](/root/workspace/hackios-run/patched-rendering/d4-worker-red-v1/result.json)：custom初设采样 `[211,63,17,255]` 正确；同RGBA base重传后采样变 `[17,63,211,97]`，期望仍为custom，两个case `GLerror=0`。测试本体非0；expected-negative调度runner的exit0仅表示复现成功，不是修复绿。

修复不靠查询physical swizzle猜user意图。新增 [纹理状态接口](/root/workspace/hackios-next/qemu/gpu/eagl-host-texture-state.h)，[hostpixels](/root/workspace/hackios-next/qemu/gpu/eagl-host-pixels.c) 用固定最多4096个`context/texture`条目显式记录logical user[4]和base format；ALPHA物理R对应logical `(0,0,0,A)`，LA物理RG对应 `(L,L,L,A)`，再复合user选择或ZERO/ONE。普通RGBA/BGRA/HALF恢复identity存储映射但保留用户选择；mip检查显式logical base格式，不再用最终physical A selector反推（userA=ONE是合法情况）。容量耗尽/无绑定texture0拒绝操作，不静默丢状态或淘汰仍live纹理。session仅接ctx选择/关闭、texture删除、TexParameteri scalar及SWIZZLE query；未动ring/delta行为。

当前worker EGL contexts明确以`EGL_NO_CONTEXT`创建不共享namespace，故per-slot metadata成立；未来shared context必须另定义sharegroup身份。texture删除清metadata、同name重绑默认identity、ctx关闭重开清，invalid enum通过原生GL形成错误但不改变logical state，不消费用户pending `glGetError`。现guest `glTexParameteri` scalar84与`glGetTexParameteriv` query24已有通用转发，worker新增SWIZZLE_R/G/B/A接受即可；不宣称扩展不支持的`glTexParameteriv`/float setters。

对比过将ALPHA/LA全展开RGBA的备选：省metadata但改变当前R8/RG8 storage/internalformat query与已有测试，最大4倍扩大宿主buffer/上传，需新预算与协议语义，故本轮选择保原存储的显式metadata方案。直接native调用helper者也须经新logical setter接口；生产worker已接线，不能把绕过setter直接调用GL的旧CPU mock当作新接线测试。

[新worker manifest](/root/workspace/hackios-run/patched-rendering/d4-worker-v1/manifest.json)：worker49,120bytes，SHA256 `c3719e7b1593181959e20fa9acdcc660e6c232f7a2b9cfd309539f25bb772703`，独立输出，未覆盖e12。同[test-eagl-swizzle.py](/root/workspace/hackios-next/qemu/gpu/test-eagl-swizzle.py) `--full` 的9个真shader采样case及getter/context/delete/enum/binding0断言全部通过；旧session/delta/HFR2/no-ring smoke全0。表明这些合法场景已通过，不宣称用户所有动画异常消除。

[冻结review清单](/root/workspace/hackios-run/patched-rendering/d4-worker-v1/freeze-review.json) 记录hostpixels `4608c2554dcf3d974de6f6a0cff0c6110600448fea6b247d66a99566fe684787`、header `602b2e4e…`、hostsession `ad1e4770…`。另[CPU状态fixture](/root/workspace/hackios-next/qemu/gpu/test-eagl-texture-state.c) 的cap2和实际4096entry上限测试均0，覆盖unknownquery/invalidenum/pendingerror/nullimage/legacy mip/namespace/删除重用，capacity失败不误改metadata。CPU GL mock不是GPU绘制证明。

已知边界：native `glTexImage2D` 的OOM/INVALID_OPERATION错误仍走正常GL错误/后续readback failclosed；metadata `have_base` 不可当作原生allocation已成功的证明。为查此类错误不能偷偷消费应用pending错误。实际iOS连续动画、原大片黑裁剪区域、10内容FPS与GPU UUID/submit关联仍待验收。

### 审查边界与下一个oracle

host pixel helper已保存恢复PACK/UNPACK alignment,rowlength,skiprows/skippixels并拒PBO，half/LA有明确转换路径；这是代码覆盖，不能替代真采样校验。guest侧未实现UNPACK_SKIP等会abort，部分API调用由生成unsupported wrapper明确abort；如果某场景需要这些功能，表现为worker/合成进程失败而非自动正确。应从实际失败日志提取最小合法调用，不猜测所有黑块都同因。

validation正把oracle终点改为guest生产pack(BGRA→RGBA)→同worker真实texture upload→实际shader采样draw→独立target readback；直接读上传texture附着FBO只能证明storage，不证明sampler/swizzle/mip状态。每个新增修复均须独立版本/hash与旧候选分开，生产连续动画依然待验收。

## D1/D2/D3统一新guest与隔离root（2026-11-20 01:00 +08）

managed job `bash-52a68ac516c24c17` 经同锁执行，已收齐exit=0。新[构建manifest](/root/workspace/hackios-run/transport-release-unified-jn2pazmm/manifest-unified.json) 及[实际源输入](/root/workspace/hackios-run/transport-release-unified-jn2pazmm/source-before.json) 区分本次guest编译与沿用worker来源。D1 source81a4ab…、D2 overlay8d3efd…及生成函数体、D3 encoder bc229d…/header229f91…均核对，编译/测试期间输入未变。

- [新guest dylib](/root/workspace/hackios-run/transport-release-unified-jn2pazmm/guest-d123/libredwood-gl.dylib)：559,440 bytes，SHA256 `2d0482010e9b84230a1c5054afa95f5a99a7f01783d06798d65e5ef1a26224c5`，407导出集合与旧候选一致，Mach-O arm64/依赖检查通过。
- [新root-gpu.apfs](/root/workspace/hackios-run/transport-release-unified-jn2pazmm/stage-d123/root-gpu.apfs)：5,046,272 bytes，SHA256 `dd60af0541c1cb9cd0c26ab01de371330e4ddba146fc8f74e3a7b453055ee54e`，cache key `b31d7e87360adf1d28f1d49b62e6587ce2a673c22512435527e59c59b58d05eb`。stable stage原root再次逐byte一致后才改独立恢复树，新旧root不互相覆盖。
- worker仍是原 `e12f9a0f…` HFR2产物，未为了host注释改变而重编；其原构建source来源manifest单列。本次现host source含纯注释同步“opaque reconstruction token/private base”，不是新worker行为变化。
- 本轮本地surface CPU/零预算、arrays CPU+llvmpipe、CC=clang guest handoff，以及固定e12的shader/texture/fence/session smoke全部exit0。D3真实Adreno绿+旧重基红证据由 [TCG结果](/root/workspace/hackios-run/patched-a/render-vertexid/verification.KBZCGX/production-fix-manifest.json) 单列，不把重复测试当同一次iOS执行。

validation的分层oracle可证明实际workerreply→Linux编译guest C compose/fullcopy/pack→worker pipe→同EGLcontext shader采样；它**不意味着guest decoder与Androidworker同进程**，也不是新iOS Mach-O已运行。曾回报的decoder f338…中间hash已撤回，最终worktree/snapshot实际81a4ab…；完整生产opaque-token协商callback联通正在补，未闭合前不宣称完整协议通过。

新基线[实际ring图](/root/workspace/hackios-run/patched-b/kernel-lean.NXABUN/compat-virt10.1-hfr2-stock-run/analysis/ring-final.png)（SHA256 `d83955f3fec543e7086a20cced4ec5536d4ce2c4af9c44bf0bc287bfb05960cd`）确有大片轴对齐黑区：Calendar widget左边/标签前半、FaceTime/Calendar/Photos、Notes/Reminders/Clock上半异常，右列Mail/News与底行相对正常。旧参考白widget本就空，不能混作新问题。D1/D3后的受控复验必须比较这些区域及连续帧；若仍异常再追scissor/viewport/FBO/纹理与cache恢复，不随机加状态reset，也不只凭黑区形状归因D1。

新guest/root已交kernel下次带postplan受控启动，D4用户swizzle回归仍**已知未修**。未覆盖APK assets，未发布；实际iOS动画、10不同内容FPS、D1整帧copy TCG成本与同worker GPU设备/submit闭环仍待验收。
