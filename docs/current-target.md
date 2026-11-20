# 当前主线与重启恢复点

## 用户要求（优先于旧阶段目标）

- 原分辨率 **1125×2436**，不通过降分辨率提速。
- **实际内容 10 FPS** 为正式目标；2–5 FPS 仅为中间里程碑。
- 用户称 VKGL 的图形路径须真实运行于宿主 GPU；当前实现身份是自研 remoteGL → EGL/GLES → Zink → Vulkan loader → Turnip/Freedreno → KGSL，尚未发现名为 VKGL 的特定库集成。两者不可混称。
- 连续画面无异常抽搐、闪烁、黑块或逐块补画，并在该基础上缩短启动时间。用户明确指出 GPU 在部分情况下仍渲染异常；局部测试通过不能当作整条渲染链无问题。
- GitHub 远端上传、push、PR、写 API 或仓库编辑均未获授权；密钥不额外落盘、不写源码/日志/产物、不传给队员。继续已授权的本地开发和测试。
- 始终保留正确的来宾 IOSurface 回写；publication count、局部 GL 耗时倒数不能冒充内容 FPS。
- 根据用户后续明确要求，原五名队员 tcg / kernel / guest-load / transport / validation 保留，并新增唯一固定队员 **ui-review** 专审 HackiOS UI 与操作逻辑；现为六名队员加 lead。以后不再增减，不另开普通或 Codex 子代理，新任务重分工。
- 单机重负载使用同一 `flock /tmp/hackios-qemu-trial.lock` 串行，等待时推进离线实现、测试与审查。引擎重启后先核对真实进程和输出，不能只凭 job registry 为空重跑。

## 已完成，但尚未发货的工作

- 启动准备计时、等待进度与预算说明修正；原完整单测 355 项通过。后续计时标签区分“准备已用”和“运行已用”。
- DELT 多行紧凑像素跨 padded stride 的错误已修正；生产 4544 / 4608 双 stride、双 surface、多 band、padding/canary 测试先红后绿并经独立复核。
- HFR2 帧环读写前后 guard 防撕裂；独立 ARM 跨进程 C 检查点测试通过；23 项 JVM `FrameRingTest` 通过。新 App、worker 协议混配会禁用环回退采集，保留来宾像素回写。
- 新来宾 dylib（含 stride 修复）：`613e706c6ebf7ccf2508054863c1114f600539121bd1d1353b2225f9894403c9`。
- 新 HFR2 worker：`e12f9a0fefb637aa06093e41c6d9c85e59934aadb8471b664714a449b79037a1`。
- 上述独立产物及 ring v2 / 旧 magic / 错尺寸 / no-ring / delta 冒烟验证在 `hackios-run/transport-release-hkt2a6wu/manifest-built.json`，全部通过；未写入 App assets。
- TCG 已知指令短校准恢复结果通过：mode1 / mode2 三次 EL0 均为 **30,000,004 = 3×10M+4**，覆盖 direct chaining、完整运行时地址、空闲第二 CPU 与 data abort 尾部修正。
- 计量 binary SHA256：`7772701930f5cdc177cd82ebc05a0a8a5b42d026de18280a4eac939238670b01`。极短 TB 循环的 mode1 中位开销约 +42%，mode2 约 7.06 倍；这不是代表负载数字，但二者都不能直接充当无扰动帧率基线。

## 实验限制与已否决归因

- 发行 QEMU 11.1.2 + 旧 delta2 图形场景下，原内核与精简组合都在 840 秒未达到桌面测量条件；仅有 3 / 4 次回读。不能判断精简内核快慢或内容 FPS。
- `-M virt` 不是固定机型：发行 QEMU 解析为 `virt-11.1`，旧成功 Inferno 则为 `virt-10.1`；发行版也支持显式 10.1。下一轮要先恢复可操作正确图形基线，而不是宣称已证明机器类型提速。
- 旧 `ui_lite` 采样子窗为 199 publications / 121.3 s ≈ 1.64056 次/秒。没有实际 hook 生效和可见变化证据，不能归因为效果或称内容 FPS。
- 旧 `info registers` PC 采样、旧 dispatcher TB 计量以及不同二进制间 540→382 ms 的提升归因作废。未验证的 timer 补丁不进入发货产物。

## 当前接续顺序

1. transport：独立候选 zip 自包含核验、稳定 stage 原根逐字节恢复核验，再生成新 dylib 对应根镜像，不覆盖现有镜像。
2. validation：用修复组件验证 padded writeback → texture 再上传的连续像素 oracle，继续 Android 跨进程和显示端验收设计。
3. kernel：使用固定 stock 内核、发行 QEMU 显式 `virt-10.1`、正确 HFR2 图形组件、原尺寸双 vCPU，恢复有界可启动基线；identity 记录 requested / resolved 机器类型。
4. tcg：在代表负载单独量计数扰动和执行量，比较可执行 CPU 优化候选，不重复已通过的短校准。
5. guest-load：独立 AX 单键偏好探针的可逆性、boot/session 绑定与小产物编译；未在实际来宾改变设置。
6. lead：审查整合、版本配对、现场证据与实际内容帧率；正式目标达成后才保存可恢复源码、构建信息、补丁与 SHA256 清单，再进入更大目标。

## 新增渲染缺陷与当前恢复结果

- D1：旧 delta 以 live surface 内容作为隐含基线，CPU 改写未回传 band 时会保留错误像素。新代码以验证完整的 FULL/DELT 回复重建私有 packed base，再完整复制所有有效像素到 live，保留 padding。CPU 预算/失败/并发边界独立复核，以及 C 真实 opaque-token 请求→同 worker→回复→纹理重上传→shader 像素链已通过；旧 decoder 的 CPU 改写泄漏负控保留。新 D123 Mach-O `2d048201…` / root `dd60af05…` 已构建并实际启动。MiniIOSurfaceLock 非排他，修复只保证与普通 FULL 回写相同的外部同步契约。
- D2：原 Redwood `surface_changed` 遗漏不足 8 字节的尾部；47,362 项检查由 1,408 失败变为 0，已合入上述 D123 Mach-O/root。
- D3：带 attribute 且 first=7 的 gl_VertexID 在旧 encoder 被重基；CPU/llvmpipe/Adreno 同 shader 旧包红、保原索引包绿，均 GLerror=0。有界零前缀保索引实现已合入 D123；不读取不可访问的 guest 前缀，超预算明确拒绝。
- D4：普通 RGBA base upload 重设用户 texture swizzle；旧 e12 实际 shader 红测保留。新逻辑 swizzle 与 ALPHA/LA 存储映射分离 worker `c3719e7b…`，9 个实际 shader case、session/delta/HFR2/no-ring 及容量/错误/生命周期边界通过，validation 独立 GPU 与 CPU 复跑通过。尚不代表整个 iOS 动画全部正常；D123 完整系统 r3 仍使用旧 e12，未混称已应用 D4。
- D123 r3 已完整退出 exit0：首次 guest frame 55.000 秒；5-readback 门槛 468.400 秒只是计数启发。正式窗口 498.439→618.486 秒有 186 次 readback/publication，不能称内容 FPS。主屏最终 framebuffer 经正确参考 gate 误差 0.1504，通过；点击 Settings 后实际截图呈现 Settings。计划中的 Home 手势没有返回主屏，017/018 虽名 home-evidence/after，实际仍为 Settings 列表，不能把操作意图当结果。
- 同轮结束时 017/018 两组 screenshot 前后 HFR2 seq/guard/hash 均匹配稳定最终环；解码后的 final framebuffer 与环全部 2,740,500 RGB 像素相同，只证明该 Settings 状态一致，不推广到其他 drawn target。工具输出在 `hackios-run/lead-render-audit/d123-r3-frame-stage-v2/result.json`。下一计划改为真实底部 y2420 起点，仍单次有界并核实际内容，不另为补 Home 开一轮 VM。
- 同 worker Vulkan 诊断已观察真实物理 device UUID `3e09c2cfb3285f803dd12ba75c07f637`、driver UUID、created-device/queue 映射与 18 次提交、timeline 成功完成后 9 次 shader 像素回读；off/on 全像素一致，wrong-UUID/drop-submit/host-signal 和错误因果日志负控拒绝。初 checker 跨值配对错误已撤回，严格 v5 仅接受 exact signal/wait，validation 独立确认 read1..9 对应 timeline2/4/…/18。证据在 `hackios-run/patched-gpu-evidence/strict-v5/manifest.json`，仅限已执行 9 GLES 场景，不代表整个 iOS 最终 scanout 或总线级外部观测。
- App UI/触摸及帧环基础曾有 44 suites / 365 项完整单测通过；新增资源互斥 lease 与保留有效 NVRAM 的修改仍由 ui-review 实施，需另跑编译，不能沿用旧成绩。
- **显示来源已修正**：worker HFR2 是各 GL surface 的回读 tap，后续 MiniFramebuffer 还多 layer 合成到 `fBase`，尺寸相同不能当最终 panel。App 的 `FRAME_RING_ENABLED` 已默认 false，GPU 与正确 IOSurface 回写保留，显示改用原 1125×2436 framebuffer 采集；环保留独立诊断用途。HFR2 guard 解决副本一致性，不赋予最终显示身份。
- Python PPM 工具已修复 pixman 行填充：1125 宽有 3375 有效字节/3376 pitch。PPMfixed harness `f97d53e6…`、正确 PNG reference `62ba0ee0…` 已冻结，46 项工具回归通过；旧完整 framebuffer 对正确参考 PASS，旧缺块环对参考 FAIL，阈值未放宽。App 原生 QemuPpm 之前已支持此 padding。
- D123 首轮在约 66.5 秒无结束记录退出，原因未知；recovery2 在 QEMU 启动前 0.0028 秒因 AF_UNIX 路径过长退出，不算 guest 失败，两目录保留。recovery3 短路径 `hackios-run/d123-r3.6a9f3b2c` 已完整完成，真实 root 三 hash 均 dd60af05…，运行原尺寸双核/stock/virt10.1/PPMfixed/正确参考；不重复 D123-only 启动。
- 同 boot 成本分析表明完整有效像素回写均约 9.06 ms，steady 全复制总时长约窗口 1.41%，不能解释数百毫秒间隔。每次 readback 之间平均 93.57 条串行 wire 命令，所有 VOID setter 也同步 ACK；out 总时长均 178.83 ms，body 141.79 ms，gap 465.52 ms，计时相互嵌套不能重加。报告在 `hackios-run/patched-rendering/r3-transport-cost-5b0qzflx/report.md`，未把网络墙钟当 CPU 指令或 content FPS。
- 下一 CPU 计量包 guest `53f6b933…` / root `949055d7…` / 同 binary QEMU `777270…` / D4 worker `c371…` 已编好。显式 session2400 harness `793780b1…` 保原 payload，生成唯一 loader_return_after=2400，防诊断被旧 1200 秒 timer 截断；mode1 先跑，再同 binary mode0 量计量扰动。hashprofile 默认 off，诊断需 activation receipt 与全量计数窗口。batch 与 exact-snapshot 两个独立候选默认 off，正在 CPU/GL 语义与生命周期回归，尚未当实测提速。
- 离线 source-stage 工具 `hackios-run/lead-render-audit/analyze-frame-stage.py` 已验证 BGRA/stride、guard、seq/hash 配对拒绝；旧 run 的 ring/PPM 端点相差 273,817 像素，但没有严格时间括号，工具明确标记 unpaired，不能单凭端点给黑块定因。新 run 后优先找同一 stable publication bracket 内的 PPM；即使匹配，也不假称 guest scanout 原子或最终显示已通过。

## 0.31.9 APK 交付

- [HackiOS 0.31.9 Adreno APK](</sdcard/Download/DSH-Go/HackiOS-0.31.9-adreno-antutu.apk>) 使用原项目签名，包名 `com.antutu.ABenchMark`，版本号 67。SHA-256 `26a8cbe8457225b0c3f8cca1f282c45c0aba0ed7cd76b6d3a98f62d29e207101`。45 个单测套件的 373 项通过。包内解出的 Adreno 830 运行时自检通过。[APK 校验记录](</root/workspace/hackios-run/apk-0.31.9-8wqotjda/apk-verification.json>)
- APK 使用 guest `13d6431c…` 和 worker `cc7ffe7c…`。Adreno 默认向来宾传 `glbatch=1`，设置“内核启动参数”可用 `glbatch=0` 在下次启动关闭。九组原生 GPU 测试的请求总数由 201 减到 153，RGBA 哈希和 GL 错误一致。[原生验证](</root/workspace/hackios-run/patched-transport-batch/native-v2/manifest.json>)
- Adreno 使用 `virt-10.1,gic-version=3`、原生 1125×2436 和最终 framebuffer。整机对比、可见内容 10 FPS 与冷暖启动耗时仍待验收。较早的 mode1 在 657.815 秒中断，留下两次发布和启动期 hash 记录，缺少正式指令计数。[中断核查](</root/workspace/hackios-run/patched-a/mode1-interrupted-audit.jk6_kf4r/audit.json>)
- 复现 APK 构建时使用 `:app:testDebugUnitTest :app:assembleDebug -PgpuTest=true -PgpuAssetsDir=/root/workspace/hackios-run/apk-0.31.9-8wqotjda/gpu-assets`。[资产校验记录](</root/workspace/hackios-run/apk-0.31.9-8wqotjda/asset-receipt.json>)
