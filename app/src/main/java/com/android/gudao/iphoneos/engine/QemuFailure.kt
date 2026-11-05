package com.android.gudao.iphoneos.engine

/**
 * Turns "QEMU exited" into something the user can act on.
 *
 * Previously a failed launch produced `QEMU 启动后立即退出（exit=1）。点「刷新日志」看 qemu.log 里
 * 的原因。` — accurate, but it made the user read a 4000-character log to discover that, say, port
 * 4444 was still held by the previous crash. Every rule below is derived from a message QEMU or the
 * Android linker actually prints, and each carries a concrete next step.
 */
enum class QemuFailureKind {
    AssetsMissing,
    BundleIncomplete,
    ExecDenied,
    MissingRom,
    LinkerError,
    PortInUse,
    MissingPayload,
    Storage,
    EarlyExit,
    /** exit=137：被 Android 的 LMK / Phantom Process Killer 杀掉，不是 QEMU 自己退出。 */
    ProcessKilled,
    /** exit=139 / 134：QEMU 自己崩在 TCG 或内部 assert 上。 */
    QemuCrash,
    Unknown,
}

data class QemuFailure(
    val kind: QemuFailureKind,
    val title: String,
    val detail: String,
    val hint: String? = null,
    val retryable: Boolean = true,
    val logTail: String = "",
)

object QemuFailureClassifier {

    fun classify(
        exitCode: Int?,
        qemuLog: String?,
        serialLogExists: Boolean,
        missingAssets: List<String> = emptyList(),
        hasPayload: Boolean = true,
        portBusy: List<Int> = emptyList(),
        /**
         * 0.26.0：错误细化目录对本轮串口/崩溃/QEMU 日志的具体归因（见
         * BootErrorCatalog.Session.classifyExit）。传入后，兜底分支不再写「没有匹配到已知
         * 错误特征」，而是把观测到的错误类别、最高严重度、阻塞点与处置原样给出。
         * 默认 null 时行为与旧版完全一致（保证既有调用与单测零回归）。
         */
        catalogAttribution: String? = null,
        /**
         * 0.29.2：本次启动的资源预算（guest 内存 / TB 缓存 / MemAvailable / 存储余量），
         * 由 QemuRunner 在 spawn 前生成。QEMU 在打开 `-serial` 之前就 exit=1 时 qemu.log
         * 常常是空的，此时这张预算就是唯一能解释现场的证据。
         */
        runtimeNote: String? = null,
        /** 0.29.2：是否已用保守档（6G / TB 512 / 单线程）自动重试过一次。 */
        retriedWithSafeProfile: Boolean = false,
    ): QemuFailure {
        val log = qemuLog.orEmpty()
        val tail = log.takeLast(1200)
        val catalogNote = catalogAttribution?.let { "\n\n【启动错误细化】$it" } ?: ""
        val runtime = runtimeNote?.takeIf { it.isNotBlank() }?.let { "\n\n【启动预算】$it" } ?: ""

        if (missingAssets.isNotEmpty()) {
            return QemuFailure(
                QemuFailureKind.AssetsMissing,
                "运行资源未解压",
                "缺少：${missingAssets.take(4).joinToString("、")}",
                "点「重新部署资源」让 App 从 APK 里重新解压 QEMU 与固件。",
                logTail = tail,
            )
        }
        if (!hasPayload) {
            return QemuFailure(
                QemuFailureKind.MissingPayload,
                "缺少启动负载",
                "run/payload.bin 或 run/ram.img 不存在，QEMU 没有可引导的内核。",
                "先编译镜像，或点「启动」让 App 自动重建 payload（约 1–3 分钟）。",
                logTail = tail,
            )
        }
        if (portBusy.isNotEmpty()) {
            return QemuFailure(
                QemuFailureKind.PortInUse,
                "控制端口被占用",
                "端口 ${portBusy.joinToString(" / ")} 已被占用，通常是上一次 QEMU 崩溃后没有退出。",
                "点「强制清理」结束残留进程，再重新启动。",
                logTail = tail,
            )
        }
        if (log.contains("failed to find romfile", ignoreCase = true)) {
            val rom = Regex("""failed to find romfile "([^"]+)"""").find(log)?.groupValues?.get(1)
            return QemuFailure(
                QemuFailureKind.MissingRom,
                "缺少 QEMU 固件 ROM",
                "QEMU 在 -L 目录里找不到 ${rom ?: "所需的 option ROM"}。",
                "资源包解压不完整，点「重新部署资源」重试。",
                logTail = tail,
            )
        }
        if (log.contains("error while loading shared libraries", true) ||
            log.contains("cannot locate symbol", true) ||
            log.contains("library \"", true) && log.contains("not found", true)
        ) {
            return QemuFailure(
                QemuFailureKind.LinkerError,
                "QEMU 动态库缺失",
                log.lineSequence().firstOrNull { it.contains("error while loading", true) }?.take(180)
                    ?: "动态链接器无法加载 QEMU 依赖的 .so。",
                "多为资源包里的符号链接变成 0 字节文件：重新部署资源可修复。",
                logTail = tail,
            )
        }
        if (log.contains("Permission denied", true) || exitCode == 13 || exitCode == 126) {
            return QemuFailure(
                QemuFailureKind.ExecDenied,
                "系统拒绝执行 QEMU",
                "execve 返回 EACCES/EPERM，Android 10+ 会按 targetSdk 选择 SELinux 域。",
                "本 App 固定 targetSdk=28 以留在 untrusted_app_27 域；若仍失败，多为安装包被改动。",
                retryable = false,
                logTail = tail,
            )
        }
        if (log.contains("Address already in use", true)) {
            return QemuFailure(
                QemuFailureKind.PortInUse,
                "控制端口被占用",
                "QMP(4445) 或监视器(4444) 已被其它进程占用。",
                "结束残留的 QEMU 进程后重试。",
                logTail = tail,
            )
        }
        if (log.contains("No space left on device", true)) {
            return QemuFailure(
                QemuFailureKind.Storage,
                "存储空间不足",
                "QEMU 无法写入 ram.img / 日志。",
                "清理空间：镜像与固件约需 12 GB 可用。",
                logTail = tail,
            )
        }
        // 137 = 128 + SIGKILL：几乎都是 Android 侧杀的（LMK、Android 12+ Phantom Process Killer、
        // 后台子进程超限），不是 QEMU 崩溃。把它单列出来，避免用户去翻 qemu.log 找不存在的错误。
        if (exitCode == 137) {
            return QemuFailure(
                QemuFailureKind.ProcessKilled,
                "QEMU 被系统杀死（exit=137）",
                "进程收到 SIGKILL：Android 的内存回收（LMK）或 Android 12+ 的 Phantom Process Killer " +
                    "在宿主内存紧张 / 后台进程过多时杀掉了 QEMU。guest 固定要约 7 GB，这类机型很容易触发。",
                "保持 App 在前台并关闭其它应用后重试；必要时重启手机。若反复出现，降低 guest 内存/核数。",
                logTail = tail,
            )
        }
        // 139 = 128 + SIGSEGV，134 = 128 + SIGABRT：QEMU 自己崩了，不是被系统杀。
        if (exitCode == 139 || exitCode == 134) {
            val sig = if (exitCode == 139) "SIGSEGV（段错误）" else "SIGABRT（断言失败）"
            return QemuFailure(
                QemuFailureKind.QemuCrash,
                "QEMU 崩溃（exit=$exitCode，$sig）",
                "TCG 翻译 / 设备模型在启动过程中崩了。qemu.log 末尾通常有 assert 文本或最后一条命令。",
                if (exitCode == 134) "先看 qemu.log 里的 assert 原文，回退最近改动的 QEMU 参数再试。"
                else "先在设置里关闭 MTTCG 或把 tb-size/核数降一档再试，排除翻译缓存与并发问题。",
                logTail = tail,
            )
        }
        if (exitCode != null && exitCode != 0) {
            // 0.29.2：串口未建立时 qemu.log 里才是唯一的真话。以前这里只说「展开日志查看
            // 最后几行」，用户看到的是空面板。现在把 QEMU 自己的原文直接写进卡片。
            val evidence = if (log.isBlank()) {
                "qemu.log 为空：QEMU 在打开 -serial 之前就退出，没有留下错误文本。" +
                    "这种形态只可能发生在参数解析 / 机器初始化 / TCG 翻译缓存或内存分配阶段。"
            } else {
                "qemu.log 末段：\n" + log.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList().takeLast(8)
                    .joinToString("\n")
            }
            val retryNote = if (retriedWithSafeProfile) {
                "\n已用保守档（TB 512 MiB / 单线程 TCG）重试一次，仍然退出。"
            } else ""
            return QemuFailure(
                QemuFailureKind.EarlyExit,
                "QEMU 启动后立刻退出（exit=$exitCode）",
                (if (serialLogExists) "串口已建立但进程随即退出。" else "串口还没建立进程就退出了，说明失败发生在参数解析、链接或机器初始化阶段。") +
                    "\n" + evidence + retryNote + runtime + catalogNote,
                if (log.isBlank())
                    "空日志 + 无串口：先关掉其它应用/重启手机释放内存，再用更小的 guest 内存与 TB 缓存启动。"
                else
                    "按 qemu.log 最后一条错误处理：缺 romfile → 重新部署资源；"
                        + "'Invalid parameter'/'warning' → QEMU 选项写法错误（把原文发出来）；"
                        + "内存分配失败 → 降低 guest 内存/TB。",
                logTail = tail,
            )
        }
        // 0.26.0：兜底不再是「没有匹配到已知错误特征」这句含糊话。
        // 有错误目录时用它的具体归因（类别数/最高严重度/阻塞点/处置）；
        // 没有时也给一份按链路逐层排查的清单，绝不留一句「未知」把用户打发掉。
        val detail = if (catalogAttribution != null) {
            "QEMU 进程已退出且未进桌面。\n【启动错误细化】$catalogAttribution"
        } else {
            ("QEMU 进程已退出且未进桌面，串口" + (if (serialLogExists) "已建立" else "未建立") + "。" +
                "逐层排查：①固件/ROM 与 -L 目录 ②payload.bin / ram.img 是否为真实镜像而非占位 " +
                "③QEMU 控制端口(4444/4445)是否被上次崩溃占用 ④动态库/符号链接是否完整 " +
                "⑤串口日志末 40 行是否有内核 panic 或 /sbin/launchd 相关错误。" +
                "把 qemu.log 末尾与 serial.log 末 40 行贴出来即可定位，不要盲目降核重启。")
        }
        return QemuFailure(
            QemuFailureKind.Unknown,
            "启动未完成",
            detail,
            "按上面逐层清单核对；若曾观测到错误档案，按其中最高严重度那条的 ACTION 处理。",
            logTail = tail,
        )
    }
}
