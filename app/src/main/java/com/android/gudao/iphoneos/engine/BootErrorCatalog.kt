package com.android.gudao.iphoneos.engine

/**
 * 0.26.0 **分层错误细化目录**：把「启动失败/变慢」从一句含糊的概括，变成每一类错误都能
 * 被定位、被解释、被处置的结构化档案。
 *
 * ## 为什么要做（针对「不要只一味添加密度，要仔细细化错误」）
 * 0.25.0 之前，guest 串口里形形色色的错误被 [com.android.gudao.iphoneos.BootPhase] 压成
 * 7 个 `String?`：`noise` 一句「lsd 反复申请脱离 CPU 限额被拒…已知非阻塞噪音」就把
 * `process lsd[25] denied attempt to escape CPU monitor (missing required entitlement)` 打发了
 * —— 用户看到的就是「只有个 CPU 出错之类」，既不知道它属于哪个组件、为什么发生，也不知道
 * 挡不挡启动、要不要管。更糟的是 QEMU 退出时直接写「没有匹配到已知错误特征」。
 *
 * 荣耀 90 真机导出日志 4022 行里有 459 行错误特征，横跨 launchd persona、lsd entitlement、
 * failed lookup、plist importer、bootstrap 无效路径、共享内存区、BiometricKit 断言、HID、
 * Android App 崩溃、取帧超时等至少 12 类；它们被一句 `noise` 全部盖掉。
 *
 * ## 怎么做
 * [RULES] 是一张规则库，每条规则 = 稳定 id + 层级 [Layer] + 严重度 [Severity] + 是否阻塞
 * [blocking] + 具体组件 + 命中特征 + **根因 [Rule.cause] / 影响 [Rule.impact] / 处置
 * [Rule.action]**（三句话把「是什么 / 会不会挡 / 怎么办」讲透）+ 可选的参数抽取（例如
 * failed lookup 抽出缺失服务名、发起进程、errno）。
 *
 * [Session] 是有状态累积器：逐行 `ingest`，**一行可命中多条规则**（这是关键 —— BootPhase 用
 * when 只取第一个，正是含糊的根源）；同类错误聚合计数，只在**首次出现**时返回新档案供实时
 * 落盘；含错误特征但没命中任何规则的行进 [unclassified] 兜底并保留原文，绝不再写「没有
 * 匹配到已知错误特征」。每条档案渲染成 WHERE / CAUSE / IMPACT / ACTION / EVID / PARAMS 多行块。
 *
 * 纯 Kotlin（不引用 android.*），整条分类、聚合、渲染逻辑可在 JVM 单测里逐行断言；云端脚本
 * 用同一份规则语料喂真实日志复算覆盖率。落盘 / UI / MCP 接线由 AppViewModel 负责。
 */
object BootErrorCatalog {

    /** 错误发生在链路的哪一层 —— 「不管出错在哪里」先要能说清位置。 */
    enum class Layer(val label: String) {
        FIRMWARE("固件/引导 (UEFI·XNULOADER)"),
        KERNEL("XNU 内核"),
        LAUNCHD("launchd 启动域"),
        DAEMON("系统守护进程"),
        GUI("界面层 (SpringBoard/backboardd)"),
        HOST_APP("宿主 App (Android)"),
        CAPTURE("取帧管道 (HMP/PPM)"),
        QEMU("QEMU 进程"),
        BUILD("镜像构建"),
    }

    /** 严重度。rank 越大越该优先看。 */
    enum class Severity(val rank: Int, val label: String) {
        FATAL(5, "致命"),        // App/内核直接崩，桌面不可能出现
        BLOCKING(4, "阻塞"),     // 会挡住进桌面，必须修
        DEGRADED(3, "降速"),     // 不挡桌面，但刷屏/占 vCPU/拖慢
        NOISE(2, "已知噪音"),    // 移植固有、无害
        INFO(1, "信息"),         // 提示性，不是错误
    }

    /**
     * 一条错误规则。`matchers` 是字面量子串（OR，命中任一即可），刻意不用正则做匹配判定 ——
     * 串口日志会把两行插碎拼在一起（如 `...iconserviceprocess lsd[25] denied attempt...`），
     * 字面量比锚定正则稳。参数抽取才用带一个捕获组的正则。
     */
    data class Rule(
        val id: String,
        val layer: Layer,
        val severity: Severity,
        val blocking: Boolean,
        val component: String,
        val matchers: List<String>,
        val where: String,
        val cause: String,
        val impact: String,
        val action: String,
        val paramExtractors: Map<String, Regex> = emptyMap(),
    )

    /** 一次聚合结果：某类错误出现了多少次、首次/末次原文、抽取出的参数集合。 */
    class Finding(val rule: Rule, firstEvidence: String) {
        var count: Int = 1
            private set
        var firstEvidence: String = firstEvidence
            private set
        var lastEvidence: String = firstEvidence
            private set
        val params: MutableMap<String, MutableSet<String>> = linkedMapOf()

        internal fun hit(line: String) {
            count++
            lastEvidence = line
            for ((key, re) in rule.paramExtractors) {
                val v = re.find(line)?.groupValues?.getOrNull(1)?.trim()
                if (!v.isNullOrEmpty()) params.getOrPut(key) { linkedSetOf() }.add(v)
            }
        }
    }

    /** 含错误特征、但没命中任何规则的行，逐条保留原文（含所属层级的猜测）。 */
    data class Unclassified(val line: String, val guessedLayer: Layer)

    // ---- 规则库 --------------------------------------------------------------------------------
    // 每一条 cause 都要讲「机制」，不写「CPU 出错之类」；action 要给可执行结论。
    // 覆盖来源：荣耀 90 真机日志 hackios-logs-20261104-205600.txt 的 459 行错误特征 + QemuFailure。

    val RULES: List<Rule> = listOf(
        // ===== KERNEL 层 =====
        Rule(
            id = "kernel-panic",
            layer = Layer.KERNEL, severity = Severity.FATAL, blocking = true,
            component = "XNU kernel",
            matchers = listOf("Kernel panic", "panic(cpu", "panic: We are hanging here", "Debugger synchronized"),
            where = "XNU 内核态，阶段 3",
            cause = "内核触发 panic 并挂起：通常是缺 rootfs/resident image、驱动或 CPU 特性不匹配。" +
                "早期云端验证曾用假 ramdisk（HACKIOS-NO-ROOT-APFS）导致 /sbin/launchd panic——那是验证脚本占位，不是真实链路。",
            impact = "内核不再前进，后面 launchd / SpringBoard 全部不会出现，桌面不可能亮。",
            action = "确认 run/ram.img 与 system.apfs 是真实镜像而非占位；查串口 panic 前最后 20 行定位缺失的镜像/驱动。",
        ),
        Rule(
            id = "shared-region-check",
            layer = Layer.KERNEL, severity = Severity.NOISE, blocking = false,
            component = "shared_region / hello_dyn (rc.jobs 自检)",
            matchers = listOf("vm_shared_region_start_address() failed", "check_np"),
            where = "XNU 共享内存区，hello_dyn 用户态自检阶段",
            cause = "rc.jobs 里的 hello_dyn 自检程序查询共享缓存区起始地址，本移植未预建 shared region cache，" +
                "check_np 返回失败。这是自检探测，不是内核故障。",
            impact = "无 —— 后续 notifyd / fseventsd / SpringBoard 都正常起来。",
            action = "忽略。FAST=1 已去掉这条自检（cpu_test/surf_test/hello_dyn 一类诊断作业）。",
        ),
        Rule(
            id = "root-dir-eacces",
            layer = Layer.KERNEL, severity = Severity.BLOCKING, blocking = true,
            component = "SpringBoard / mobile 进程 (uid 501)",
            matchers = listOf("failed to open root directory", "EACCES returned by vnode_lookup"),
            where = "界面层访问根卷 \"/\"，阶段 6",
            cause = "镜像根目录 / 被构建成 0700（Android 侧 umask 0077），uid 501 的 mobile 进程 open(\"/\") 即 EACCES。" +
                "system.manifest 只描述卷内路径、不描述卷自己的根，所以历史上一直漏改。",
            impact = "SpringBoard 起不来，卡在阶段 6，屏幕全黑（串口看着却像「正常」）。",
            action = "0.17.0 起 mkapfs.py 强制把 / 设为 0755；点「部署预构件 + 编译镜像」重编第 3 版镜像后即消。",
        ),

        // ===== LAUNCHD 层 =====
        Rule(
            id = "persona-type6",
            layer = Layer.LAUNCHD, severity = Severity.NOISE, blocking = false,
            component = "com.apple.xpc.launchd",
            matchers = listOf("Unable to find persona with type 6", "kpersona_find_by_type() failed", "kpersona_find_by_typ", "failed with ret -1 errno", "container manager: no personas"),
            where = "launchd 每次 spawn 一个 job 的 persona 查询，阶段 4~6",
            cause = "上游移植不实现 kpersona（内核树里只有 redwood.patch 一句注释），容器管理器主动回答「不支持」。" +
                "每个 job 都报一次，报完该活的守护进程都活着 —— 是背景噪音，不是死因。0.17.0 前曾被误判成卡死根因。",
            impact = "无阻塞；但每 spawn 一次打一行，是日志刷屏的主要来源之一（真机 84+ 行）。",
            action = "忽略。FAST=1 精简作业集从源头减少 spawn 数量；QUIET=1 已压掉 launchd 的 stderr 洪水。",
        ),
        Rule(
            id = "launchd-plist-missing",
            layer = Layer.LAUNCHD, severity = Severity.NOISE, blocking = false,
            component = "com.apple.xpc.launchd",
            matchers = listOf("Unable to open /System/Library/xpc/launchd.plist", "No MRM cache found", "Unable to load cache"),
            where = "launchd 冷启动读配置/缓存，阶段 4",
            cause = "精简镜像不含 /System/Library/xpc/launchd.plist 与 MRM 缓存；launchd 回退到内建默认并继续。" +
                "这是裁剪移植的预期行为，不是配置损坏。",
            impact = "无；launchd 用默认配置继续 bootstrap。",
            action = "忽略，无需补齐文件或清缓存。",
        ),
        Rule(
            id = "plist-importer-unknown-key",
            layer = Layer.LAUNCHD, severity = Severity.NOISE, blocking = false,
            component = "launchd plist importer (backboardd/SpringBoard/logd/fseventsd 的 plist)",
            matchers = listOf("Unknown key for plist importer", "key is no longer respected"),
            where = "launchd 解析 mkjobs.py 生成的 LaunchDaemon plist，阶段 4",
            cause = "mkjobs.py 生成的 plist 带了本移植 launchd 版本不认的键（HighPriorityIO、BeginTransactionAtShutdown），" +
                "或已被弃用的键（HopefullyExitsLast）。导入器跳过该键、按默认值处理。",
            impact = "无阻塞；被忽略的键只影响优先级/退出顺序等细枝末节。",
            action = "忽略。若要彻底静默可在 mkjobs.py 的 plist 生成里去掉这些键，但不影响启动。",
        ),
        Rule(
            id = "bootstrap-invalid-path-rwa-packed16",
            layer = Layer.LAUNCHD, severity = Severity.BLOCKING, blocking = true,
            component = "launchd bootstrap · /redwood/LaunchDaemons",
            matchers = listOf("RWA_PACKED16, error = 108"),
            where = "launchd 扫描 /redwood/LaunchDaemons 目录做 bootstrap，阶段 4",
            cause = "mkjobs.py 的 rest 分支里 `for name in ('RWA_PACKED16',)` 把外层循环变量 name（文件名）" +
                "覆盖成字面量 'RWA_PACKED16'，随后 `open(dest/name)` 把 ~60 个按需作业全部写进同一个名为 " +
                "RWA_PACKED16 的**非法文件**（本该写 {label}.plist）。launchd 试图把这个非法文件名当 plist 路径 " +
                "bootstrap，报 error=108 Invalid path。后果：mobileactivationd / securityd / personad / mobileassetd / " +
                "configd / commcenter / accountsd 等所有按需守护进程的 plist 压根没生成，launchd 里它们从不注册。" +
                "这正是 lsd「一直卡住」的真根因（见下方 failed-lookup 与 lsd 规则）。真机 23:47 日志里 " +
                "`ls -l /redwood/LaunchDaemons/RWA_PACKED16` 与 `Failed to bootstrap path ... error = 108` 是铁证——" +
                "该 bug 在打进 0.26.1 APK 的脚本里仍然存在。",
            impact = "按需守护进程一个都没注册 → lsd 查询 com.apple.mobileactivationd 永远 No such process（设备激活链断），" +
                "wifid/backboardd 查询 securityd/personad/configd 同样全失败。SpringBoard 可能仍拉起（logo 后几帧），" +
                "但用户态激活/安全/persona 链不完整，lsd 反复重试形成风暴，观感就是「卡在 lsd」。这是真实启动回退、" +
                "不是噪音；黑屏是另一条独立的内核 MiniIOMFB 未 blit 链路，二者别混为一谈。",
            action = "0.26.3 的 hackios-scripts.zip 已含修复版 mkjobs.py（循环变量改 env_key、写文件改 {label}.plist），" +
                "并加 AssetDeployer 部署期自愈补丁（即便旧资产回退也会就地修）+ 提升 IMAGE_REV 强制丢弃旧缓存镜像重建。" +
                "装 0.26.3 后让 App 重新构建镜像（root.apfs 每轮无条件重建），RWA_PACKED16 行即消失、按需守护进程恢复正常注册。",
        ),
        Rule(
            id = "bootstrap-appleinternal-missing",
            layer = Layer.LAUNCHD, severity = Severity.NOISE, blocking = false,
            component = "launchd bootstrap · /AppleInternal",
            matchers = listOf("/AppleInternal/Library/LaunchDaemons", "/AppleInternal/Library/LaunchAngels"),
            where = "launchd 扫描可选内部目录，阶段 4",
            cause = "AppleInternal 是 Apple 内部测试专用的 LaunchDaemons / LaunchAngels 目录，" +
                "精简移植镜像本就不构建它；launchd 按固定清单扫描时找不到，报 error=2 (ENOENT) 属预期路径缺失。",
            impact = "无阻塞：这只是可选目录扫描失败，系统 LaunchDaemons 仍从 /redwood/LaunchDaemons 正常加载，" +
                "不代表镜像缺了启动必需的作业。",
            action = "无需补齐目录或改动镜像；只有当这条与 failed-lookup 的必需服务缺口同时出现时才需查作业集。" +
                "FAST=1 精简集下可完全忽略。",
        ),
        Rule(
            id = "failed-lookup-missing-service",
            layer = Layer.LAUNCHD, severity = Severity.DEGRADED, blocking = false,
            component = "launchd XPC 服务查询（发起者见 requestors）",
            matchers = listOf("failed lookup:", "error = 3: No such process"),
            where = "守护进程向 launchd 查询 XPC 服务，阶段 6",
            cause = "发起进程（wifid/lsd/powerd/analyticsd/backboardd 等）查询的 XPC 服务在本镜像里没有对应 " +
                "LaunchDaemon plist，launchd 回 error=3 No such process。两种来源，必须区分： " +
                "(a) 与 `RWA_PACKED16, error = 108` 同现时——mkjobs.py 的变量覆盖 bug 把全部按需作业写进了同一个非法文件，" +
                "mobileactivationd/securityd/personad 等 plist 压根没生成，这是 BLOCKING 的启动回退（见 bootstrap 规则）； " +
                "(b) FAST=1 精简集主动不含某些非桌面必需服务时的正常按需查询失败，属良性。",
            impact = "若属 (a)：lsd 查 com.apple.mobileactivationd 永远 No such process，激活链断、lsd 反复重试形成风暴，" +
                "这正是「卡在 lsd」的可见表现，并非「启动慢」那么轻。若属 (b)：每个发起者会重试刷串口，但不挡桌面。",
            action = "先看同份日志里有没有 `RWA_PACKED16, error = 108`：有则按 bootstrap 规则处理（升级 0.26.3 重编镜像），" +
                "不要把它当良性噪音忽略；只有确认无 RWA_PACKED16、且缺口都是非必需服务时，才属 FAST 精简的正常现象，无需降核重启。",
            paramExtractors = mapOf(
                "missingServices" to Regex("""name\s*=\s*([^,]+)"""),
                "requestors" to Regex("""requestor\s*=\s*([A-Za-z0-9_.\[\]]+)"""),
                "errno" to Regex("""error\s*=\s*(\d+)"""),
            ),
        ),
        Rule(
            id = "on-demand-spawn",
            layer = Layer.LAUNCHD, severity = Severity.INFO, blocking = false,
            component = "launchd on-demand 域",
            matchers = listOf("pending spawn, domain in on-demand-only mode", "detect-installed-roots"),
            where = "launchd 把 job 标记为按需域，阶段 4~6",
            cause = "REST=1 全量集把作业注册成 on-demand-only：只有真正被查询时才 spawn。" +
                "这是设计行为，不是失败。",
            impact = "无阻塞：作业被标成按需是设计行为，不影响桌面；配合上面的 failed-lookup 可解释" +
                "「为何某些服务查不到」—— 按需域里的服务只有在被查询时才拉起，查不到就会报 No such process。",
            action = "信息性，无需处理：不要把它当成注册失败或镜像缺作业。只有当某个桌面必需服务始终查不到时，" +
                "才需要检查它是否被误标成 on-demand-only；FAST=1 精简集下这类提示会明显减少。",
        ),

        // ===== DAEMON 层（用户点名的 lsd，重点讲透）=====
        Rule(
            id = "lsd-cpu-monitor-entitlement",
            layer = Layer.DAEMON, severity = Severity.DEGRADED, blocking = false,
            component = "com.apple.lsd (LaunchServices daemon, pid 见证据)",
            matchers = listOf("denied attempt to escape CPU monitor", "missing required entitlement"),
            where = "lsd 注册/枚举服务时的 CPU 资源监控，阶段 6",
            cause = "这不是「CPU 算错 / CPU 故障」（用户点名批评过的那种含糊结论）：iOS 用 CPU monitor 限制守护进程的 " +
                "CPU 配额，lsd 在反复查询缺失的 XPC 服务（见 failed-lookup，尤其 com.apple.mobileactivationd）后试图调用 " +
                "escape CPU monitor 放宽配额继续重试，但本移植镜像未给 lsd 签 com.apple.private 系 entitlement，内核拒绝该请求。" +
                "本质是「服务缺失 → 重试 → 越权放宽配额被拒」的连锁，属于权限/服务缺失，和 CPU 算力或核数无关。",
            impact = "lsd 每自旋一轮就吐一行 denied，于是串口里出现几十上百行完全相同的 " +
                "`process lsd[21] denied attempt to escape CPU monitor`——这是**真实可见的刷屏风暴**，不是无害噪音。 " +
                "0.26.3 修掉了 RWA_PACKED16 那个把全部按需守护进程 plist 吞掉的 bug，但 mobileactivationd " +
                "在该精简镜像里**仍未注册**（0.26.3 真机日志里 `failed lookup: name = com.apple.mobileactivationd ... " +
                "No such process` 仍在），所以 lsd 照样查不到、照样自旋、照样被拒。根子在未提供的激活链守护进程，" +
                "不在权限本身。SpringBoard 可能已拉起，但激活/安全/persona 链因此不完整。",
            action = "App 侧：日志视图已对连续重复行做折叠（相同行合并为 `行 (×N)`），消除视觉风暴，" +
                "但不改磁盘 serial.log（导出/复制仍是完整原始行）。要**根除**这条拒绝，需要 guest 镜像真正提供并拉起 " +
                "mobileactivationd（把激活链守护进程纳入精简作业集、或补对应 plist/entitlement）——属 guest 侧改动，不在本 App 仓库内。" +
                "不要为「降刷屏」给 lsd 补 entitlement：那只让它自旋更快，不修复激活链断。",
        ),
        Rule(
            id = "wifi-assets-missing",
            layer = Layer.DAEMON, severity = Severity.NOISE, blocking = false,
            component = "wifid / WiFiCloudAssets / WCAFetch",
            matchers = listOf(
                "shouldMonitorVisits", "WiFiCloudAssets", "WCAFetchWiFiBehaviorParameters",
                "WCAClient", "WiFiUsageAccessPointProfileConfiguration", "getMobileAssets",
            ),
            where = "wifid 定位/云服务子系统，阶段 6",
            cause = "wifid / WiFiCloudAssets 在 QEMU virt 机器上找不到真实的 Wi-Fi 芯片、定位硬件与 Apple 云端服务，" +
                "相关 XPC 查询（mobileassetd、位置登记等）回落失败或打警告。QEMU 不虚拟 Wi-Fi 设备，属预期。",
            impact = "无阻塞：wifid 守护进程本身能起来并留在运行，只是联网/定位能力不可用；" +
                "不影响 SpringBoard 或桌面绘制，属裁剪移植的功能缺口而非故障。",
            action = "无需处理：这不是镜像损坏，也不该为它重编或降核。若日志里 failed-lookup 的 requestor 集中是 " +
                "wifid，可用 FAST=1 精简集减少这些无意义的重试；真机需要网络时另配 virtio-net。",
        ),

        // ===== GUI 层 =====
        Rule(
            id = "biometrickit-assert",
            layer = Layer.GUI, severity = Severity.NOISE, blocking = false,
            component = "backboardd · BiometricKit",
            matchers = listOf("BiometricKit", "AssertMacros: 0"),
            where = "backboardd 初始化生物识别框架，阶段 6",
            cause = "QEMU virt 机器没有 Secure Enclave / TouchID 硬件，BiometricKit 初始化时 AssertMacros " +
                "断言拿到空值并失败。框架内部有容错分支，不会中断 backboardd 主流程，属硬件缺失导致的预期断言。",
            impact = "无阻塞：backboardd 继续运行，随后的 SystemShell 连接、Now monitoring com.apple.springboard、" +
                "SpringBoard OpenGL ES 绘制都正常打印；只是生物识别能力不可用，不影响桌面出现。",
            action = "无需处理：这不是崩溃或镜像缺陷，也不要据此判定界面层卡住。" +
                "只有当后面缺少 System shell connected / 真实帧时才需按 GUI 阻塞项另查。",
        ),
        Rule(
            id = "hid-bundleid-missing",
            layer = Layer.GUI, severity = Severity.NOISE, blocking = false,
            component = "backboardd · IOHIDEventSystem",
            matchers = listOf("ignoring connection; bundleID is missing", "IOHIDEventSystemConnection"),
            where = "backboardd 接受 HID 事件连接，阶段 6",
            cause = "某个 IOHIDEventSystem 连接未提供（或提供了无效的）bundleID，事件系统按策略忽略该连接。" +
                "QEMU 的 virtio-input 设备不携带 bundle 属性，属虚拟设备与 iOS HID 校验的预期不匹配。",
            impact = "无阻塞：被忽略的只是这一条连接，真正承载触屏/按键事件的是另一条已认证连接；" +
                "桌面与交互不受影响，不会导致黑屏或输入失效。",
            action = "无需处理：不要把它当成输入子系统故障。若真机实测触屏完全无响应，再单独查 virtio-input " +
                "设备与 rwtouch 手势注入，而不是看这条忽略日志。",
        ),
        Rule(
            id = "springboard-objc-crash",
            layer = Layer.GUI, severity = Severity.BLOCKING, blocking = true,
            component = "SpringBoard",
            matchers = listOf("OS_REASON_OBJC", "couldn't dlopen libobjc-trampolines.dylib"),
            where = "SpringBoard 加载 objc 运行时，阶段 6",
            cause = "cryptex 里的 libobjc-trampolines.dylib 被 Android umask 0077 提取成 0600（manifest 不覆盖 cryptex 路径），" +
                "mobile 进程 dlopen 撞 errno=13，SpringBoard 以 OS_REASON_OBJC 退出。症状与根目录 0700 不同：这里 / 能打开。",
            impact = "SpringBoard 退出，界面层起不来，屏幕全黑。",
            action = "0.17.1 起 fetch.sh 设 umask 022、build.sh 强制 0755/0644；重新「部署预构件」并重编镜像后即消。",
        ),
        Rule(
            id = "iosurface-failed",
            layer = Layer.GUI, severity = Severity.DEGRADED, blocking = false,
            component = "IOSurface / surf_test",
            matchers = listOf("surf_test:", "no IOSurface service"),
            where = "渲染内存侧自检（无窗口服务器时能走的最远一步）",
            cause = "surf_test 检查 IOSurface 分配/共享/双平面；若有 failed 项或服务缺失，说明渲染内存链路有问题。" +
                "0 failed 是**成功**信号，不是错误。",
            impact = "failed>0 会让 CoreAnimation 上屏缺 surface；0 failed 无影响。",
            action = "看具体 failed 数；FAST=1 已去掉该自检作业。",
        ),
        Rule(
            id = "iomfb-swap-stalled",
            layer = Layer.GUI, severity = Severity.DEGRADED, blocking = false,
            component = "MiniIOMobileFramebuffer / WindowServer",
            matchers = listOf("MiniIOMobileFramebuffer: swap", "frames in "),
            where = "backboardd → MiniIOMFB → QEMU ramfb 的显示提交链路，阶段 6",
            cause = "0.29.5 用同一份 kernel + root.apfs + system.apfs 连续跑了 30 分钟，把 0.29.4 " +
                "「只出 2 帧就停摆」的结论**推翻**了，证据链现在是完整的：" +
                "①MiniIOMFB 的 swap 路径**是工作的**——串口每次都有 `swap 2 layer 0: surface 2 " +
                "(1125x2436 'BGRA') from 0,0 1125x2436 to 0,0 1125x2436`，IOSurface 确实逐层拷进 " +
                "boot framebuffer；②合成器**没有死锁，只是极慢**：`redwood-gl: frame 1` 到 frame 6 " +
                "依次出现，单帧 7–43 秒（`2 frames in 9357 ms`、`3 frames in 69451 ms`）；③guest 的 GL " +
                "是 Mesa **softpipe**（`asked for string 0x1f01: softpipe`），1125x2436 约 274 万像素" +
                "全部靠 CPU 光栅化，再叠一层 TCG 翻译，单帧秒级是必然；④跑满 19 分钟后 QEMU " +
                "screendump 上真的出现了合成器画的 Apple logo（位置/尺寸与 loader 那份不同），" +
                "说明可见 ramfb surface 最终还是被更新了——中途看到的「黑」只是还没画到那一帧；" +
                "⑤2026-11-05 的长跑（tb-size=1360、不钉核）跑到第 10 分钟时帧号从 7 直接跳到 30 并" +
                "持续增长到 60+，screendump 出现完整 iOS 主屏（图标、Dock、搜索、状态栏），主屏" +
                "时钟从 11:36 走到 11:38——**桌面是能到的**。结论：既不是缺 memcpy，也不是合成器" +
                "停摆，而是软件渲染 + TCG 的吞吐问题；它是「慢」，不是「坏」。",
            impact = "不阻塞启动：主屏约 10 分钟会出来，之后出帧正常（1–2 帧/秒）。代价只是前 10 分钟" +
                "屏幕停在启动图，App 的 90 秒帧停滞看门狗容易在这段时间误报「卡在界面层」。",
            action = "先分清「慢」和「死」，不要重编内核去补 memcpy：\n" +
                "  1) `grep -c 'redwood-gl: frame' serial.log`：帧号在增长（哪怕一分钟一帧）就是**慢**，" +
                "不是死锁；本机 30 分钟出了 6 帧；\n" +
                "  2) `MiniIOMobileFramebuffer: swap N ... layer` 有这行就证明内核在拷，别再补拷贝；\n" +
                "  3) 对比 framebuffer-probe 与 screendump：两者都黑只是「这一帧还没画」，不是地址错位；\n" +
                "  4) 耐心：0.29.5 长跑实测约 10 分钟出主屏，中途别重启。\n" +
                "提速实测（0.29.5，同一份镜像）：`-cpu max`、tb-size 680→2048、vCPU 2→3 都**没有**显著" +
                "收益（到第 2 帧均为 70–80 秒）；唯一理论上限是降低 guest 渲染像素数，但只改 loader 的" +
                "面板尺寸无效——串口会显示 `display 1125x2436 on a panel of 750x1334`，渲染尺寸来自 " +
                "guest 侧按机型（iPhone10,3）的决定，不在本 Android 仓库。真实修复要动 redwood-gl / " +
                "SpringBoard 或机型映射，App 侧负责把「慢」如实报出来、不要误判成卡死。",
        ),
        Rule(
            id = "guest-touch-node-missing",
            layer = Layer.DAEMON, severity = Severity.DEGRADED, blocking = false,
            component = "redwood 输入 / rwtouch",
            matchers = listOf(
                "/dev/redwood-touch: No such file or directory",
                "redwood: input: /dev/redwood-touch",
            ),
            where = "guest init 的 rc.jobs 自动上滑 + libredwood 输入初始化，阶段 6",
            cause = "kernel 的 virtio-input 驱动把触摸设备建成 `/dev/hakios-touch`（启动时 " +
                "`crw-rw-rw- /dev/hakios-touch` 可见），但 prebuilt 的 rwtouch 与 " +
                "libredwood.dylib 仍硬编码 `/dev/redwood-touch`。于是 rc.jobs 的 " +
                "`rwtouch wait 30 swipe ...` 和 libredwood 的 input 初始化都报 No such file or directory。",
            impact = "不直接造成黑屏，但会让自动上滑解锁失效、redwood 输入库起不来；用户手动上滑/" +
                "屏幕触控也可能收不到，锁屏会一直停在那里。",
            action = "0.29.4（IMAGE_REV 9）已把两个预构件里的字符串等长改成 `/dev/hakios-touch`，" +
                "并在本机复跑确认串口出现 `redwood: input: sample ...`、QMP 手势能被收到。" +
                "升级后重建一次 root.apfs（system.apfs 复用）即可；不需要重编内核。",
        ),
        Rule(
            id = "iomfb-selector-not-implemented",
            layer = Layer.GUI, severity = Severity.DEGRADED, blocking = false,
            component = "MiniIOMobileFramebuffer selector",
            matchers = listOf("not implemented (scalars"),
            where = "backboardd ↔ MiniIOMFB 用户态显示接口，阶段 6",
            cause = "本移植的 IOMFB 替身只实现了显示协商与 swap 入口所需的最小 selector 集；backboardd 探测到的" +
                "其余 selector（供电、亮度、_gamma、显示参数等）直接回 not implemented。属移植面缺口，非故障。",
            impact = "单看每条不阻塞。0.29.4 复跑确认 swap 主路径（3/4/5/6）已实现并有 " +
                "`swap N layer ...` 日志，57/15/22/29/33/27/17/84/50/68/55 这类供电/亮度/gamma " +
                "selector 的 not implemented 不是这两帧停摆的原因。",
            action = "忽略这些 not-implemented；不要为了让它们闭嘴去补 selector。真正的停摆看 " +
                "iomfb-swap-stalled 里的 `redwood-gl: frame` 计数与 swap 行。",
        ),

        Rule(
            id = "xnu-swapfile-failed",
            layer = Layer.KERNEL, severity = Severity.DEGRADED, blocking = false,
            component = "XNU 虚拟内存 / swap 文件",
            matchers = listOf("Failed to open swap file", "vm_swap_create_file failed"),
            where = "XNU 动态分页器建 /private/var/vm/swapfileN，启动后 ~16s",
            cause = "内核按台式机语义动态创建交换文件，但本镜像的 /private/var/vm 所在卷不支持它需要的" +
                "按需增长语义（或磁盘配额/卷属性限制），open 失败。7 GB 固定 ram.img 下物理内存已由宿主侧" +
                "memory-backend-file 承担，guest 内 swap 缺失只影响极端内存压力时的行为。",
            impact = "正常负载无影响（两份真机日志都只出现 1 次且系统继续跑）；内存吃紧时会先杀进程而不是换页。",
            action = "忽略。不要为此扩磁盘或改卷属性；真出现内存压力表现（进程被 jetsam 杀）时再回来查。",
        ),

        // ===== 0.26.0 第二轮补齐：真机日志里原本被「未归类」吞掉的 6 类错误 =====
        // 云端 verify_error_catalog.py 首轮覆盖率只有 77%，51 行错误只能兜底保留原文。
        // 逐条看完这 51 行后沉淀为下面 6 条规则 —— 都是「出错在哪里」的真实位置，不再含糊。
        Rule(
            id = "minisandbox-privilege-refused",
            layer = Layer.DAEMON, severity = Severity.NOISE, blocking = false,
            component = "minisandbox (logd / lsd 等)",
            matchers = listOf("minisandbox: privilege", "privilege"),
            where = "守护进程向 minisandbox 申请特权，阶段 6",
            cause = "本移植用精简的 minisandbox 替代完整沙箱，守护进程（logd/lsd）申请的某项特权" +
                "在裁剪实现里没有对应授权，被拒绝一次并记 (first time)。是移植授权表不全，不是进程越权攻击。",
            impact = "无阻塞：被拒的是可选特权，守护进程以受限权限继续运行，System shell connected 仍会出现；" +
                "只在首次申请时打一行，不会刷屏。",
            action = "无需处理：不要当成崩溃或安全故障。若某个守护进程反复因此退出，再单独补该特权的沙箱 profile；" +
                "FAST=1 精简作业集下这类申请本就更少。",
        ),
        Rule(
            id = "virtual-hw-service-unavailable",
            layer = Layer.GUI, severity = Severity.NOISE, blocking = false,
            component = "backboardd · CoreAccessories / oscard / Pearl(TouchID) / wifid PPM",
            matchers = listOf(
                "Connection invalid", "Failed to open service", "PPM Error:No matching driver",
                "xpc connection got invalidated", "pearlConnect", "deviceAvailableWithFailure",
                "MKBUserTypeDeviceMode returned error", "Unable to open 0x", "Service stub created",
            ),
            where = "backboardd / wifid 连接虚拟硬件与配件服务，阶段 6",
            cause = "QEMU virt 机器上没有 CoreAccessories（CarPlay）、Pearl/TouchID、ALS 环境光、Wi-Fi 芯片等真实硬件，" +
                "相关 XPC 服务要么没有驱动（No matching driver）、要么连接随即失效（invalidated），" +
                "launchd 只能创建空 stub。属虚拟设备能力缺口，不是镜像或内核故障。",
            impact = "无阻塞：这些服务都不参与桌面绘制；backboardd 后续 SystemShell / OpenGL ES draws 正常打印。" +
                "只是 CarPlay / 生物识别 / 无线等能力不可用。",
            action = "无需处理：不要据此重编镜像或降核。真机只需要桌面与触屏时，这类 stub/invalid 属预期噪音；" +
                "若确需某项能力再单独加对应 virtio 设备。",
        ),
        Rule(
            id = "input-property-missing",
            layer = Layer.GUI, severity = Severity.NOISE, blocking = false,
            component = "redwood input / IOHIDEventSystem 属性协商",
            matchers = listOf("event server asks for property", "which we do not have", "missing accel HID service plugin"),
            where = "backboardd 向输入事件服务器查询按键/传感器属性，阶段 6",
            cause = "iOS 事件服务器会查询双击/三击/长按超时、加速度计 HID 插件等属性，本移植的 redwood 输入层" +
                "只实现了触屏必需的一小部分，其余属性没有实现，逐条回「which we do not have」。属功能裁剪，非报错崩溃。",
            impact = "无阻塞：单击/滑动等主输入链路照常工作，桌面与 rwtouch 手势不受影响；" +
                "缺的只是双击/三击/长按之外的边角手势与加速度计。",
            action = "无需处理：不要据此判定输入子系统故障。若实测某个具体手势失效，再在 redwood input 属性表里补该键。",
        ),
        Rule(
            id = "display-sensor-hardware-gap",
            layer = Layer.GUI, severity = Severity.NOISE, blocking = false,
            component = "backboardd · WindowServer / ALS / OrientationDevice",
            matchers = listOf(
                "kIOMFBSourceLimitsKey missing", "failed to retrieve registry ID for display service",
                "timeout waiting for ALS arrival", "no samples", "no accel HID",
            ),
            where = "backboardd 读取显示/环境光/方向传感器属性，阶段 6",
            cause = "ramfb 是纯帧缓冲，不提供 IOMFBSourceLimits、display registry ID；QEMU 也不虚拟 ALS 环境光、" +
                "加速度计与后置传感器，WindowServer 查询这些属性即返回 missing / timeout / no samples。",
            impact = "无阻塞：这些是自动亮度、随方向旋转、环境光采样所需的辅助数据，缺了不影响帧缓冲出图；" +
                "真机日志里 SpringBoard 的 OpenGL ES draws 与 System shell connected 都在其后正常出现。",
            action = "无需处理：不要当成显示链路故障或黑屏根因。真正的显示问题看 fbscale/ramfb（BootPhase.display），" +
                "而不是这几行硬件属性缺失。",
        ),
        Rule(
            id = "launchd-plist-lint",
            layer = Layer.LAUNCHD, severity = Severity.NOISE, blocking = false,
            component = "launchd plist lint / 服务导入",
            matchers = listOf(
                "(lint):", "Did not import service from caller", "System logger socket was not opened",
                "Skipping remaining service endpoints", "Skipping remaining event endpoints",
                "Skipping remaining instance endpoints",
            ),
            where = "launchd 校验并导入 mkjobs.py 生成的 plist，阶段 4~6",
            cause = "mkjobs.py 生成的 plist 带了旧键（HideUntilCheckIn、_PanicOnCrash）或引用了镜像里没有的 " +
                "Developer/PrivateFrameworks 路径，launchd 的 lint 给出警告、跳过导入；early boot 时系统 logger " +
                "socket 尚未建立也属时序正常。都是裁剪作业集与真实 iOS plist 集的差异，不是配置损坏。",
            impact = "无阻塞：被跳过的是非桌面必需的 service endpoint 与 importance boosting；" +
                "backboardd / SpringBoard 的主作业正常注册。",
            action = "无需处理：若要彻底静默可在 mkjobs.py 里去掉旧键与无效路径引用，但不影响启动；" +
                "FAST=1 精简集已减少这类 lint 数量。",
        ),
        Rule(
            id = "job-activation-failed",
            layer = Layer.LAUNCHD, severity = Severity.DEGRADED, blocking = false,
            component = "launchd XPC 作业激活（activation）",
            matchers = listOf("failed activation:"),
            where = "守护进程请求激活某个 XPC 作业时，阶段 6",
            cause = "发起者请求的作业（如 com.apple.mobile.usermanagerd.xpc、alloy.wifi.networksync）在本镜像里" +
                "没有对应 LaunchDaemon plist 或被标记为 on-demand 不可激活，launchd 回 failed activation。机制与 " +
                "failed-lookup 相同：REST 全量集因 mkjobs.py bug 静默失效，FAST 精简集又主动不含这些非必需作业。",
            impact = "不挡桌面（System shell connected 仍出现）；但每次失败会重试并刷串口，与 lsd/failed-lookup " +
                "一起构成 CPU 空转风暴，是启动变慢的来源之一。",
            action = "看 EVID 里 name= 的具体作业：桌面必需的用 FAST 的 com.apple.* 集合补齐；非必需的重试属已知噪音，" +
                "0.25.0 精简集已大幅减少。不要因此降核重启。",
            paramExtractors = mapOf("activationNames" to Regex("""failed activation:\s*name\s*=\s*([^,]+)""")),
        ),
        Rule(
            id = "userspace-selftest-exit",
            layer = Layer.KERNEL, severity = Severity.INFO, blocking = false,
            component = "rc.jobs 用户态自检 (hello_dyn / surf_test / mg_test)",
            matchers = listOf("hello_dyn exited with status", "exited with status"),
            where = "rc.jobs 跑用户态自检程序并收集退出码",
            cause = "rc.jobs 用 /redwood/bin/hello_dyn 等自检程序验证动态链接与 exec，程序按设计返回一个非零退出码" +
                "（如 status 7）表示某个探测分支的结果，不是崩溃或 panic。",
            impact = "无：后续内核与 launchd 正常推进；这一行只是自检输出。",
            action = "无需处理：FAST=1 已去掉 hello_dyn / cpu_test / surf_test 一类诊断作业，新镜像不再打印。",
        ),

        // ===== HOST_APP 层（Android App 自身崩）=====
        Rule(
            id = "app-glasmio-crash",
            layer = Layer.HOST_APP, severity = Severity.FATAL, blocking = false,
            component = "GlasmioAlertDialog / GlasmioBackdropHost (Compose)",
            matchers = listOf("Blur glass must be hosted by the app backdrop provider", "未捕获异常 IllegalStateException"),
            where = "Android App 弹「停止 iOS」确认框时（宿主进程，非 guest）",
            cause = "反编译 glasmio-glass 0.1.1 确认：LocalBlurBackdrop 的默认值是「读取即抛」的 lambda，" +
                "只有 GlasmioBackdropHost 覆盖范围内的组合能取到 backdrop。0.22.0 把对话框放在 Host 之外，" +
                "用户点「停止 iOS」即崩（真机 17:35 / 18:00 / 18:09 各一次）。0.25.0 已把全部 3 处调用" +
                "（停止 iOS、重新生成令牌、开启完全访问）移进 Host 的 overlayContent 内；0.26.0 真机" +
                "（22:52–23:08 会话）未再复现——真机 17:35:45 那次崩在旧 APK 会话里。",
            impact = "崩的是 Android App 前台，guest QEMU 可能仍在跑；用户会看到「闪退」，日志导出中断。",
            action = "结构修复已就位，先观察：若再次出现，说明某处新调用点在 backdrop 覆盖之外——" +
                "把该调用点移进 GlasmioBackdropHost 的 overlayContent{} 即可，不要给库外再包 try-catch" +
                "（Compose 组合期的异常接不住）。",
        ),
        Rule(
            id = "app-uncaught-exception",
            layer = Layer.HOST_APP, severity = Severity.FATAL, blocking = false,
            component = "CrashGuard (未捕获异常)",
            matchers = listOf("E/CrashGuard", "DiagnosticCoroutineContextException", "FATAL EXCEPTION"),
            where = "Android App 任意线程的未捕获异常",
            cause = "宿主 App 抛出未被捕获的异常，CrashGuard 写入 run/crash.log。具体类型看 EVID 行。",
            impact = "App 前台崩溃；guest 可能仍在后台运行。",
            action = "读 run/crash.log 的完整堆栈；若是 Blur glass 见 app-glasmio-crash（已修）。",
        ),

        // ===== CAPTURE 层（取帧/抢核）=====
        Rule(
            id = "capture-slow",
            layer = Layer.CAPTURE, severity = Severity.DEGRADED, blocking = false,
            component = "QemuControl.screendump / HMP",
            matchers = listOf("取帧耗时", "screendump 第", "无提示符（超时）"),
            where = "Android App 通过 HMP screendump 抓 ramfb 帧",
            cause = "固定 8 FPS × 每次 8 MB PPM 的取帧会与 QEMU TCG 线程抢宿主 CPU；guest 忙时 HMP 阻塞，" +
                "真机出现过 76430ms 的单次取帧。不是 guest 卡死，是 App 侧抢核。",
            impact = "画面更新停滞、观感像「卡在界面层」；拖慢整体启动。",
            action = "0.25.0 的 FramePacer（引导期 2 FPS / 静止 1 FPS / 慢取帧退避）+ PPM/Bitmap 缓冲复用 + " +
                "0.26.0 解耦算力池已把争抢降到最低；perf-stats.json 可核对实际档位。",
            paramExtractors = mapOf("captureMs" to Regex("""取帧耗时\s*(\d+)ms""")),
        ),

        // ===== QEMU 层 =====
        Rule(
            id = "qemu-exit-unfinished",
            layer = Layer.QEMU, severity = Severity.BLOCKING, blocking = true,
            component = "QEMU 进程退出",
            matchers = listOf("QEMU 进程退出", "启动未完成"),
            where = "QEMU 进程已退出，但还没进桌面",
            cause = "QEMU 收尾（loader_return 到时 / UEFI exit / -no-reboot 正常退出）或异常退出。" +
                "旧版 App 在这里没有做任何错误归因、只给出一句泛泛的兜底文案，用户无法判断卡在哪一层；" +
                "现在由错误目录按本轮观测到的全部错误给出具体归因（见 classifyExit）。",
            impact = "桌面不会出现。要看串口最后阶段与最高严重度错误判断到底卡在哪一层。",
            action = "看本档案尾部附的错误汇总；按其中最高严重度（FATAL/BLOCKING）那条的 ACTION 处理。" +
                "若全是 NOISE/INFO，多为 loader_return 到时正常收尾，需延长会话或查 payload/ram.img。",
        ),
    )

    /** 命中「错误特征」但规则库未覆盖的行，用来兜底，绝不再写「没有匹配到已知错误特征」。 */
    val ERROR_FEATURE = Regex(
        "(denied|fail|error|errno|EACCES|panic|refus|missing|abort|timed?.?out|killed|jetsam|" +
            "No such|<Error>|<Warning>|exception|exited with status|invalid|unable|cannot|couldn|unknown key)",
        RegexOption.IGNORE_CASE,
    )

    /** 「其实成功」的行，不该被当成错误：0 failed / [ ok ] / === 分隔线 / 正常里程碑。 */
    private val NOT_AN_ERROR = listOf(
        "[ ok ]", "0 failed", "checks, 0 failed", "=== ", "system bootstrapper finished",
        "System shell connected", "Now monitoring", "OpenGL ES draws", "startup complete",
        "[ ok ] missing file gives ENOENT",
        // 下面这些是**正向进度/枚举输出**，只是文案里带了 refused / error / Exception 等字样，
        // 不该算错误特征（否则会虚增分母、把覆盖率拉低）。逐条来自荣耀 90 真机日志核对：
        "setup assistant refused",        // redwood: SpringBoard: activation answered here, setup assistant refused …（跳过设置助手是成功）
        "start CoreAccessories",          // [Common] start CoreAccessories...（进度开始）
        "CoreAccessories ready",          // [Common] CoreAccessories ready in 5.78s（进度完成）
        "activation answered here",       // 同上，SpringBoard 激活成功
        "redwood-gl:",                    // OpenGL ES / Mesa 扩展字符串枚举，含 GL_KHR_no_error 等字样
        ".loctable",                      // 7z 解包进度条里的资源文件名
        "ListDetail_Exceptions",          // 资源文件名里的 Exceptions 不是异常
        "/CoreAccessories.fram",          // ps 进程列表行里被截断的框架路径，不是错误
    )

    private fun looksLikeError(line: String): Boolean =
        ERROR_FEATURE.containsMatchIn(line) && NOT_AN_ERROR.none { line.contains(it, ignoreCase = true) }

    /** 未匹配行按关键字猜层级，给个定位方向，而不是丢一句「未知」。 */
    private fun guessLayer(line: String): Layer = when {
        line.contains("java.") || line.contains("CrashGuard") || line.contains("Glasmio") -> Layer.HOST_APP
        line.contains("取帧") || line.contains("screendump") || line.contains("PPM") -> Layer.CAPTURE
        line.contains("qemu") || line.contains("QEMU 进程") -> Layer.QEMU
        line.contains("launchd") || line.contains("bootstrap") || line.contains("lookup") -> Layer.LAUNCHD
        line.contains("SpringBoard") || line.contains("backboardd") -> Layer.GUI
        line.contains("panic") || line.contains("shared_region") -> Layer.KERNEL
        else -> Layer.DAEMON
    }

    /**
     * 有状态累积器。与 AppViewModel 的 watcher 一一对应：每轮把串口增量、App 侧崩溃/取帧/
     * QEMU 退出行喂给 [ingest]，返回本轮**首次出现**的错误档案（用于实时落盘与 UI 提示）。
     */
    class Session {
        private val findings = linkedMapOf<String, Finding>()
        private val unclassified = mutableListOf<Unclassified>()
        private var totalLines = 0
        private var totalErrorLines = 0

        fun ingest(lines: Iterable<String>): List<Finding> {
            val fresh = mutableListOf<Finding>()
            for (raw in lines) {
                val line = raw.replace("\r", "").trimEnd()
                if (line.isEmpty()) continue
                totalLines++
                if (looksLikeError(line)) totalErrorLines++
                var matched = false
                for (rule in RULES) {
                    if (rule.matchers.any { line.contains(it, ignoreCase = true) }) {
                        matched = true
                        val existing = findings[rule.id]
                        if (existing == null) {
                            val f = Finding(rule, line.take(200))
                            // 首次也要抽参数
                            for ((key, re) in rule.paramExtractors) {
                                val v = re.find(line)?.groupValues?.getOrNull(1)?.trim()
                                if (!v.isNullOrEmpty()) f.params.getOrPut(key) { linkedSetOf() }.add(v)
                            }
                            findings[rule.id] = f
                            fresh.add(f)
                        } else {
                            existing.hit(line)
                        }
                    }
                }
                if (!matched && looksLikeError(line)) {
                    if (unclassified.size < UNCLASSIFIED_CAP) {
                        unclassified += Unclassified(line.take(200), guessLayer(line))
                    }
                }
            }
            return fresh
        }

        /** 全部错误档案，按严重度降序、其次按出现次数降序。 */
        fun findings(): List<Finding> = findings.values.sortedWith(
            compareByDescending<Finding> { it.rule.severity.rank }
                .thenByDescending { it.count }
                .thenBy { it.rule.id }
        )

        fun unclassified(): List<Unclassified> = unclassified.toList()
        fun totalLines(): Int = totalLines
        fun totalErrorLines(): Int = totalErrorLines
        fun totalHits(): Int = findings.values.sumOf { it.count }
        fun distinctRules(): Int = findings.size
        fun hasBlocking(): Boolean = findings.values.any { it.rule.blocking }
        fun topSeverity(): Severity? = findings().firstOrNull()?.rule?.severity

        /** 最高严重度的阻塞/致命错误，用于 UI notes 与退出归因。 */
        fun mostSerious(): Finding? = findings().firstOrNull {
            it.rule.severity == Severity.FATAL || it.rule.severity == Severity.BLOCKING
        }

        /** QEMU 退出归因：把「没有匹配到已知错误特征」替换成本轮观测到的具体错误清单。 */
        fun classifyExit(exitCode: Int?, serialLogExists: Boolean): String {
            val serious = mostSerious()
            val head = buildString {
                append("QEMU 退出 exit=$exitCode，串口存在=$serialLogExists；")
                append("本轮共观测 ${distinctRules()} 类错误 / ${totalHits()} 次命中，")
                append("最高严重度=${topSeverity()?.label ?: "无"}。")
            }
            return when {
                serious != null -> head + " 最可能的阻塞点：[${serious.rule.id}] ${serious.rule.component} — " +
                    "${serious.rule.cause} 处置：${serious.rule.action}"
                findings.isNotEmpty() -> head + " 均为不阻塞桌面的噪音/降速项（" +
                    findings().take(4).joinToString("、") { "${it.rule.id}×${it.count}" } +
                    "），更可能是 loader_return 到时正常收尾：延长会话时间或核对 payload.bin/ram.img，不要盲目降核。"
                else -> head + " 串口未出现任何已知错误特征，属于静默退出：" +
                    "按层排查 ①固件/ROM ②payload.bin/ram.img 是否真实 ③QEMU 端口/动态库 ④串口文件；" +
                    "把 serial.log 末 40 行与 qemu.log 末尾贴出来即可定位。"
            }
        }

        /** 渲染完整错误档案块。每条错误一组 WHERE/CAUSE/IMPACT/ACTION/EVID/PARAMS 多行。 */
        fun render(): List<String> = buildList {
            add("# 启动错误细化档案 · 共 ${distinctRules()} 类 / ${totalHits()} 次命中 · 扫描 ${totalLines()} 行（其中 ${totalErrorLines()} 行含错误特征）")
            if (findings.isEmpty() && unclassified.isEmpty()) {
                add("# 未观测到任何错误特征：guest 串口干净，或会话在内核打印前就结束了。")
                return@buildList
            }
            for (f in findings()) {
                val r = f.rule
                add("")
                add("[err] ${r.id}  sev=${r.severity.label} blocking=${if (r.blocking) "YES" else "no"} layer=${r.layer.name} hits=${f.count}")
                add("[err]   WHERE   ${r.component} · ${r.where} · ${r.layer.label}")
                add("[err]   CAUSE   ${r.cause}")
                add("[err]   IMPACT  ${r.impact}")
                add("[err]   ACTION  ${r.action}")
                add("[err]   EVID    ${f.firstEvidence}")
                if (f.count > 1) add("[err]   LAST     ${f.lastEvidence}")
                if (f.params.isNotEmpty()) {
                    for ((k, vs) in f.params) {
                        val joined = vs.sorted().joinToString(",")
                        add("[err]   PARAM   $k=[${joined.take(400)}${if (joined.length > 400) ",…" else ""}] (${vs.size} 种)")
                    }
                }
            }
            if (unclassified.isNotEmpty()) {
                add("")
                add("# 未归类但含错误特征的日志（${unclassified.size} 行，逐条保留原文，按层级猜测；不是「无特征」）")
                for (u in unclassified) add("[err?] ${u.guessedLayer.name}  ${u.line}")
            }
        }
    }

    private const val UNCLASSIFIED_CAP = 60

    /** 给 QemuFailureClassifier / UI 用的单行摘要。 */
    fun summary(session: Session): String {
        val serious = session.mostSerious()
        val top = session.topSeverity()
        return "错误档案：${session.distinctRules()} 类 / ${session.totalHits()} 次，最高=${top?.label ?: "无"}" +
            (serious?.let { "，阻塞项=[${it.rule.id}] ${it.rule.component}" } ?: "，无阻塞项")
    }
}
