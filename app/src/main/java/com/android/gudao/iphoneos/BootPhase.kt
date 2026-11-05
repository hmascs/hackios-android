package com.android.gudao.iphoneos

/**
 * 从 XNU/iOS 串口日志（serial.log）推断当前启动阶段，给一个"现在到哪了"的实时指示。
 *
 * iOS 16.5 在 QEMU(virt, TCG) 上起来的顺序（见 README 的 boot 时间线）：
 *   1 UEFI/EDK2 固件初始化
 *   2 XNULOADER 加载内核 + 通过 fw_cfg "etc/ramfb" 协商 1125x2436 显示
 *   3 XNU 内核启动（Apple ARM，xnu-8796）
 *   4 launchd 用户态启动
 *   5 系统自检（launchd 的 36 checks）
 *   6 守护进程 / SpringBoard 界面层
 *   7 进入锁屏 —— **这一级不由文本判定**：backboardd 打印 "startup complete" 只说明渲染服务
 *     起来了，2026-11-04 的 8 核日志里它之后就彻底安静、屏幕一帧都没画过，旧规则却因为看到
 *     "backboardd" 就报「阶段 7/7 · 进入锁屏」。现在 7 由调用方按真实帧决定（见 AppViewModel）。
 *     文本分析最多给到 6。
 *
 * 若检测到界面层在 root 目录上撞了 EACCES（`failed to open root directory: /: 13`），
 * 则标记 [stuck]=true：那是镜像里 "/" 的权限问题（Android 侧 umask 0077 留下的 0700），
 * 不是 QEMU 参数 / loader / 分辨率的问题。
 *
 * 第二种 EACCES 是 cryptex 的文件 mode：`couldn't dlopen libobjc-trampolines.dylib ...
 * errno=13`。cryptex 没有 manifest 兜底 mode，Android 侧 umask 0077 把 7z 提取的文件留成
 * 0600，mobile 进程 dlopen 即被拒，SpringBoard 以 OS_REASON_OBJC 退出。0.17.1 起 fetch.sh
 * 设 umask 022、build.sh 强制改回 0755/0644，日志分析据此给出「重编镜像」的结论。
 *
 * 特别注意 `kpersona_find_by_type() failed ... type 6` 那一串：它每个 job 都打一次，包括
 * 后面活得好好的 notifyd / fseventsd / usermanagerd，所以它是**背景噪音**而不是死因 ——
 * 上游移植本身就不实现 persona，容器管理器还主动回答「不支持」（inject.c:
 * personasAreSupported → NO）。0.17.0 之前的界面把它当成死因，指错了方向。
 */
object BootPhase {

    data class Result(
        val stage: Int,          // 0=未启动, 1..7 见上
        val label: String,       // 人类可读的阶段描述
        val stuck: Boolean = false, // 是否卡在界面层的 EACCES
        // surf_test 的结论（渲染链路里唯一不依赖窗口服务器的一段）。null 表示还没跑到。
        val surface: String? = null,
        // 根目录 / 的权限，以及 SpringBoard 撞上它时的结论。null 表示日志里还没有相关行。
        val rootDir: String? = null,
        // cryptex 的 dylib mode（第二种 EACCES）的结论。null 表示日志里还没有相关行。
        val trampoline: String? = null,
        // persona 缺失的观察（上游已知、非阻塞）。null 表示日志里还没有相关行。
        val persona: String? = null,
        // 渲染倍率与 ramfb 是否自洽。null 表示日志里还没有出现相关行。
        val display: String? = null,
        /**
         * 0.25.0：桌面层的**文本**证据。真机串口里 SpringBoard 起来后会打印
         * `System shell connected: ... com.apple.springboard`、
         * `redwood: SpringBoard: OpenGL ES draws`、`[BootUI] systemShellChangedWithPrimary`。
         * 这些不是「屏幕亮了」的证明（第 7 级仍然必须配真实帧），但足以说明
         * 窗口服务器已经接上，可用来把 `changedFrames >= 3` 的死等放宽。
         */
        val desktopSignal: String? = null,
        /** lsd / analyticsd 的重试风暴：已知非阻塞噪音，用于给用户准确解释而不是误判卡死。 */
        val noise: String? = null,
        /**
         * Guest 侧网络状态（合并上游 36a08e7）。判据全部来自 `mach.development.bcm2837`
         * 打印的 `net: ...` 行；null 表示日志里还没跑到网络初始化。
         */
        val net: String? = null,
    )

    /** 分析日志文本，返回当前所处的最高阶段。text 为空表示 guest 还没往串口写东西。 */
    fun analyze(text: String?): Result {
        if (text.isNullOrBlank()) {
            return Result(0, "等待启动：QEMU 已起，guest 还没往串口写东西（最多等一两秒）")
        }
        val l = text

        // 界面层真正卡住的标志：进程起来了，然后 open("/") 被判 EACCES。
        // 不再把 persona 那几行算进来 —— 它们对每个 job 都打，包括活得好好的一批守护进程。
        // 第二种卡法同价：mobile 进程 dlopen cryptex 的 dylib 撞 errno=13，SpringBoard 一样起不来。
        val guiCrash = (l.contains("failed to open root directory") ||
                (l.contains("couldn't dlopen libobjc-trampolines.dylib") && l.contains("errno=13"))) &&
                listOf("SpringBoard", "backboardd", "logd", "distnoted").any { l.contains(it) }

        // 根目录 "/" 的权限：rc.jobs 第一条 /bin/ls -ld 以 root 身份打印，所以这是模式本身。
        // ls 不在被裁剪的镜像里时拿不到，等于 null，不影响别的判断。
        // 串口是 \r\n 行尾，且 rc.jobs 的 stdout 会和内核打印互相插碎，所以按 token 匹配而不是
        // 整行锚定到行尾：任一以 d[rwx-]{9} 开头、且含独立 "/" token 的行，其首 token 就是
        // "/" 的 mode。（/System、/private/var 那些行没有恰好等于 "/" 的 token，不会误配。）
        val rootMode = l.lineSequence().firstNotNullOfOrNull { line ->
            val toks = line.trim().split(Regex("\\s+"))
            if (toks.firstOrNull()?.matches(Regex("d[rwx-]{9}")) == true && toks.contains("/")) {
                toks.first()
            } else {
                null
            }
        }

        var stage = 1
        if (has(l, "display", "XNULOADER", "boot-args", "loader_return", "XNU-LOADER")) stage = 2
        if (has(l, "Darwin Kernel", "xnu-", "Mach kernel", "AppleARM", "BSD process",
                "AppleARMPlatform", "ARM64_BCM2837")) stage = 3
        if (has(l, "launchd", "Bootstrapping", "system bootstrapper", "com.apple.launchd")) stage = 4
        if (has(l, "checks, 0 failed", "=== ", "system bootstrapper finished",
                "bootstrapper finished")) stage = 5
        if (has(l, "SpringBoard", "backboardd", "runningboardd", "SPAWNED", "spawned",
                "wifid", "powerd", "notifyd", "com.apple.logd")) stage = 6
        // 第 7 级（真的画出锁屏）留给帧判定，这里不再凭 "backboardd" 三个字升级。

        // surf_test（rc.jobs 里 ls 之后那一条）走通内核 MiniIOSurface 的全链路：分配、lock/unlock、
        // 读写、seed、两进程经 Mach port 共享同一页、双平面 YUV。CoreAnimation 每一帧要上屏的
        // 就是一个这样的 surface，所以这是"没有窗口服务器时能走的最远一步"——它过了只说明
        // 内存侧是好的，不能说明屏幕会亮；它 failed 了才是新信息。
        val surface = when {
            Regex("""surf_test: (\d+) checks, (\d+) failed""").find(l) != null -> {
                val m = Regex("""surf_test: (\d+) checks, (\d+) failed""").find(l)!!
                val checks = m.groupValues[1]
                val failed = m.groupValues[2]
                if (failed == "0") "IOSurface 检查 $checks 项，0 失败（渲染内存侧正常）"
                else "IOSurface 检查 $checks 项，$failed 项失败 ⚠ 见日志"
            }
            l.contains("surf_test: no IOSurface service") -> "IOSurface 服务缺失 ⚠"
            l.contains("surf_test: NOT MEASURED") -> "IOSurface 检查未测量"
            else -> null
        }

        // 根目录 / 的权限，以及它把界面层挡在门外的结论。Android 侧构建的 umask 是 0077
        // （启动日志里 /redwood 下每个目录都是 drwx------），而 system.manifest 只描述卷内的
        // 路径、不描述卷自己的根，所以镜像里 "/" 一直是 0700：任何以 uid 501 跑起来的程序 ——
        // SpringBoard 第一个 —— 都 open("/") 即 EACCES。这正是日志里那两行
        //   failed to open root directory: /: 13
        //   proc_pidpathinfo_internal : EACCES returned by vnode_lookup for uid 501
        // 的真正来源；它们一度被当成旁边那串 persona 报错，而 persona 连 notifyd 都放行了。
        val rootDir = when {
            l.contains("failed to open root directory") ->
                "打开 / 被拒（EACCES）：镜像根目录权限不是 0755 —— 0.17.0 起由 mkapfs.py 强制，" +
                    "点「部署预构件 + 编译镜像」重编后即消"
            rootMode != null -> "根目录 / 权限 $rootMode" +
                if (rootMode == "drwx------") " ⚠ 不是 0755，需重编镜像（第 3 版）" else "（0755，正常）"
            else -> null
        }

        // persona：上游移植本来就不实现它（kernel 树里只有 redwood.patch 里的一句注释，
        // 容器管理器还主动回答「不支持」）。每 spawn 一个 job 都报一次，报完该活的都活着，
        // 所以这是背景噪音，不是本轮要修的东西。
        // 渲染倍率：fbscale 决定 WindowServer 的帧缓冲有多大。3 = 1125×2436（与 ramfb 相同），
        // 1 = 375×812 —— 后者合成出来的画面落不进 ramfb，屏幕全黑，而串口里一切"正常"。
        // 这是 0.21.0 之前 App 自己写死 fbscale=1 留下的坑，解析出来是为了下次一眼看到。
        val scale = Regex("""main-screen-scale[^\n]*?value = \+([0-9.]+)""")
            .find(l)?.groupValues?.get(1)?.toDoubleOrNull()
        val screen = Regex("""initial main screen size:\{(\d+), (\d+)\} scale:(\d+)""").find(l)
            ?: Regex("""main display width (\d+) height (\d+) framebuffer width \d+ height \d+ scale (\d+)""")
                .find(l)
        val display = when {
            scale != null && scale < 3.0 ->
                "渲染倍率 ${scale.toInt()}x（main-screen-scale）：WindowServer 只建 ${(375 * scale).toInt()}×${(812 * scale).toInt()} " +
                    "的帧缓冲，而 ramfb 是 ${Constants.SCREEN_W}×${Constants.SCREEN_H} —— 合成结果落不进去，画面全黑。" +
                    "启动参数 fbscale 必须是 3（改动后 payload 自动重建）。"

            screen != null -> {
                val w = screen.groupValues[1]
                val h = screen.groupValues[2]
                val sc = screen.groupValues[3]
                if (sc != "3") "WindowServer 屏幕 ${w}×${h} @${sc}x，与 ramfb ${Constants.SCREEN_W}×${Constants.SCREEN_H} 不一致 ⚠"
                else "WindowServer 屏幕 ${w}×${h} @${sc}x，与 ramfb 一致"
            }
            else -> null
        }

        val persona = when {
            l.contains("container manager: no personas") ->
                "persona 表为空（上游已知：本移植无 persona 实现，容器管理器主动回答「不支持」，非阻塞）"
            l.contains("Unable to find persona with type 6") ->
                "launchd 每 spawn 一个 job 就报一次 persona type 6（上游已知，非阻塞）"
            else -> null
        }

        // cryptex 的 dylib mode：第二种 EACCES。graft 把 /usr/lib/libobjc-trampolines.dylib 放对
        // 了位置，但 Android 侧 umask 0077 下 7z 提取的 cryptex 文件是 0600（manifest 不覆盖
        // cryptex 路径），mobile 进程 dlopen 即 errno=13，SpringBoard 以 OS_REASON_OBJC 退出。
        // 症状与「/」0700 是两回事：这里 / 能打开，崩在 dlopen。
        val trampoline = when {
            l.contains("couldn't dlopen libobjc-trampolines.dylib") && l.contains("errno=13") ->
                "cryptex 文件 mode 0600（Android umask 0077 提取所致，manifest 不覆盖 cryptex）：" +
                    "mobile 进程 dlopen libobjc-trampolines.dylib 被拒 —— 0.17.1 起 fetch.sh 设 " +
                    "umask 022、build.sh 强制 0755/0644，重新「部署预构件」并重编镜像后即消"
            l.contains("couldn't dlopen libobjc-trampolines.dylib") ->
                "libobjc-trampolines.dylib dlopen 失败（见日志中的具体 errno）"
            else -> null
        }

        // 0.25.0：把「界面层已经接上」与「界面层正在重试缺失服务」区分开。后者在真机日志
        // 里会刷几百行，但它不是卡住，也不该让用户以为要降核重启。
        val desktopSignal = when {
            l.contains("System shell connected") && l.contains("com.apple.springboard") ->
                "SpringBoard 已接上系统 shell（窗口服务器就绪）"
            l.contains("redwood: SpringBoard: OpenGL ES draws") ->
                "SpringBoard 正在用 OpenGL ES 绘制"
            l.contains("systemShellChangedWithPrimary") ->
                "BootUI 已把 SpringBoard 设为主系统 app"
            else -> null
        }
        val noise = when {
            l.contains("denied attempt to escape CPU monitor") ->
                "lsd 反复申请脱离 CPU 限额被拒：这是 iOS 服务在查镜像里没有的守护进程，" +
                    "已知非阻塞噪音。0.25.0 的精简作业集（FAST=1）会去掉大部分来源。"
            l.contains("failed lookup") && l.contains("error = 3: No such process") ->
                "launchd 有查询未注册服务（No such process），属按需作业缺失，非内核故障"
            else -> null
        }

        // 网络是独立于启动阶段的一条线：Safari 报「未连接互联网」时，靠这三个判据区分是 QEMU
        // 没给设备、en0 没挂上，还是都正常只是 DNS 死了（DNS 由宿主 ConnectivityManager 注入，
        // 见 AppViewModel.hostDnsServers）。
        val net = run {
            var hasDevice = false
            var noDevice = false
            var en0 = false
            var noIface = false
            var frames: String? = null
            for (raw in l.lineSequence()) {
                val line = raw.trim()
                if (!line.startsWith("net:")) continue
                // 独立判断而非 when：内核可能把 "en0 attached" 与 "frames sent" 打在同一行，
                // when 只会命中第一条分支，收发统计就丢了。
                if (line.contains("no virtio network device")) noDevice = true
                if (line.contains("virtio network device in slot")) hasDevice = true
                if (line.contains("en0 attached")) en0 = true
                if (line.contains("no network interface")) noIface = true
                if (line.contains("frames sent")) frames = line.removePrefix("net:").trim().take(72)
            }
            when {
                en0 -> "网络：en0 已挂上" + (frames?.let { "（$it）" } ?: "（日志里还没有收发统计）")
                noDevice -> "网络：QEMU 未提供 virtio 网卡 —— 检查设置页「虚拟网络」是否开启，或装的是旧 APK"
                hasDevice -> "网络：网卡已被内核识别，但 en0 没挂上（链路未就绪）"
                noIface -> "网络：内核没有可用网络接口"
                else -> null
            }
        }

        val label = when (stage) {
            0 -> "等待启动"
            1 -> "阶段 1/7 · UEFI/EDK2 固件初始化"
            2 -> "阶段 2/7 · XNULOADER 加载内核 + ramfb 显示协商（1125x2436）"
            3 -> "阶段 3/7 · XNU 内核启动（Apple ARM, xnu-8796）"
            4 -> "阶段 4/7 · launchd 用户态启动"
            5 -> "阶段 5/7 · 系统自检（36 checks）"
            6 -> "阶段 6/7 · 守护进程 / SpringBoard 界面层"
            7 -> "阶段 7/7 · 进入锁屏"
            else -> "阶段 ?"
        }
        return Result(stage, label, guiCrash, surface, rootDir, trampoline, persona, display,
            desktopSignal, noise, net)
    }

    /** 大小写不敏感的"任一关键字命中"。 */
    private fun has(text: String, vararg keys: String): Boolean =
        keys.any { text.contains(it, ignoreCase = true) }
}
