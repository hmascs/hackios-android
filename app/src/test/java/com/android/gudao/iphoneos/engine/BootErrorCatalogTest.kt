package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误细化目录的单测：全部输入都取自荣耀 90 真机导出日志
 * hackios-logs-20261104-205600.txt 的原始行，断言「不管出错在哪里都仔细表示出来」，
 * 而不是像 lsd 那样压成一句「CPU 出错之类」。
 */
class BootErrorCatalogTest {

    // ---- 真机原文（逐字，含串口把两行插碎拼在一起的形态）----------------------------------
    private val lsdCpuMonitor =
        "process lsd[25] denied attempt to escape CPU monitor (missing required entitlement)."
    private val lsdCpuMonitorBroken =
        "com.apple.xpc.launchd|2026-11-04 12:54:02.698288 (user/501/com.apple.iconserviceprocess lsd[25] denied attempt to escape CPU monitor (missing required entitlement)."
    private val bootstrapPacked =
        "com.apple.xpc.launchd|2026-11-04 12:51:25.343241 (user/501) <Error>: Failed to bootstrap path: path = /redwood/LaunchDaemons/RWA_PACKED16, error = 108: Invalid path"
    private val lookupOscard =
        "com.apple.xpc.launchd|2026-11-04 12:52:39.040383 (user/501) <Warning>: failed lookup: name = com.apple.oscard, flags = 0x8, requestor = backboardd[10], error = 3: No such process"
    private val lookupMobileactivationd =
        "com.apple.xpc.launchd|2026-11-04 12:54:58.275653 (user/501) <Warning>: failed lookup: name = com.apple.mobileactivationd, flags = 0x8, requestor = lsd[25], error = 3: No such process"
    private val personaType6 =
        "com.apple.xpc.launchd|2026-11-04 12:51:24.770128 (user/501) <Error>: Unable to find persona with type 6: 3 - No such process"
    private val plistUnknownKey =
        "com.apple.xpc.launchd|2026-11-04 12:51:24.971063 (com.apple.backboardd) <Error>: Unknown key for plist importer (key: HighPriorityIO type: bool)"
    private val glasmioCrash =
        "java.lang.IllegalStateException: Blur glass must be hosted by the app backdrop provider"
    private val crashGuard =
        "17:35:45.523 E/CrashGuard: 未捕获异常 IllegalStateException: Blur glass must be hosted by the app backdrop provider"
    private val captureSlow =
        "17:14:37.865 W/QemuControl: 取帧耗时 76430ms（guest 忙或 PPM 8031KB）"
    private val qemuExit =
        "17:09:17.882 E/AppViewModel: QEMU 进程退出：exit=0，串口存在=true，判定=启动未完成 — QEMU 进程不在运行，且没有匹配到已知错误特征。"
    private val launchdPlist =
        "com.apple.xpc.launchd|2026-11-04 12:51:24.770128 <Error>: Unable to open /System/Library/xpc/launchd.plist [2:No such file or directory]"
    private val biometrickit =
        "backboardd[10] [Framework-Internal] AssertMacros: 0 (value = 0x0), file: /Library/Caches/com.apple.xbs/Sources/BiometricKit/BiometricKit/BiometricKit.m"

    private fun byId(s: BootErrorCatalog.Session, id: String) =
        s.findings().firstOrNull { it.rule.id == id }

    @Test
    fun `lsd CPU monitor is a full diagnosis not a vague CPU error`() {
        val s = BootErrorCatalog.Session()
        s.ingest(listOf(lsdCpuMonitor))
        val f = byId(s, "lsd-cpu-monitor-entitlement")
        assertNotNull("lsd 必须被单独识别成一类错误", f)
        f!!
        assertEquals(BootErrorCatalog.Layer.DAEMON, f.rule.layer)
        assertEquals(BootErrorCatalog.Severity.DEGRADED, f.rule.severity)
        assertFalse("lsd 不阻塞桌面", f.rule.blocking)
        // 根因要讲透「不是 CPU 出错」，而是服务缺失→重试→越权放宽配额被拒
        val cause = f.rule.cause
        assertTrue("根因必须点明不是 CPU 故障", cause.contains("不是") && cause.contains("CPU"))
        assertTrue("根因必须点明 entitlement/权限", cause.contains("entitlement"))
        assertTrue("根因必须点明服务缺失的连锁", cause.contains("XPC 服务") || cause.contains("重试"))
        // 影响与处置都必须具体、可执行
        assertTrue("影响要点明不阻塞 SpringBoard", f.rule.impact.contains("SpringBoard") || f.rule.impact.contains("桌面"))
        assertTrue("处置要给出明确动作", f.rule.action.contains("FAST=1") || f.rule.action.contains("不要"))
        // 档案渲染要含 WHERE/CAUSE/IMPACT/ACTION/EVID 五个维度
        val rendered = s.render().joinToString("\n")
        listOf("WHERE", "CAUSE", "IMPACT", "ACTION", "EVID").forEach {
            assertTrue("渲染缺 $it", rendered.contains(it))
        }
        assertTrue("证据要保留原文", rendered.contains("denied attempt to escape CPU monitor"))
        // 绝不能出现含糊的「CPU 出错之类」概括
        assertFalse("不许退化为「CPU 出错」含糊描述", rendered.contains("CPU 出错之类"))
    }

    @Test
    fun `aggregates repeated errors and keeps first and last evidence`() {
        val s = BootErrorCatalog.Session()
        val fresh = s.ingest(listOf(lsdCpuMonitor, lsdCpuMonitorBroken, lsdCpuMonitor))
        // 首次出现只返回一次「新档案」
        assertEquals(1, fresh.size)
        assertEquals("lsd-cpu-monitor-entitlement", fresh.first().rule.id)
        val f = byId(s, "lsd-cpu-monitor-entitlement")!!
        assertEquals("同类 3 次要聚合", 3, f.count)
        assertTrue(f.firstEvidence.contains("process lsd"))
        assertTrue(f.lastEvidence.contains("process lsd"))
        // 第二次 ingest 不再返回新档案
        assertTrue(s.ingest(listOf(lsdCpuMonitor)).isEmpty())
        assertEquals(4, byId(s, "lsd-cpu-monitor-entitlement")!!.count)
    }

    @Test
    fun `one line can match several rules - no first-match swallowing`() {
        // 这一行同时含 failed lookup 与 error=3 No such process；且 requestor=lsd，
        // 用 when 只取第一个的实现会把别的信息吞掉。这里验证 failed-lookup 规则抽取到参数。
        val s = BootErrorCatalog.Session()
        s.ingest(listOf(lookupMobileactivationd))
        val f = byId(s, "failed-lookup-missing-service")
        assertNotNull(f)
        f!!
        assertTrue("缺的服务名要被抽出", f.params["missingServices"]?.contains("com.apple.mobileactivationd") == true)
        assertTrue("发起者要被抽出", f.params["requestors"]?.any { it.contains("lsd") } == true)
        assertTrue("errno 要被抽出", f.params["errno"]?.contains("3") == true)
    }

    @Test
    fun `failed lookup aggregates distinct missing services and requestors`() {
        val s = BootErrorCatalog.Session()
        s.ingest(listOf(lookupOscard, lookupMobileactivationd, lookupMobileactivationd))
        val f = byId(s, "failed-lookup-missing-service")!!
        assertEquals(3, f.count)
        assertEquals(2, f.params["missingServices"]?.size)  // oscard + mobileactivationd
        assertEquals(2, f.params["requestors"]?.size)       // backboardd + lsd
        val rendered = s.render().joinToString("\n")
        assertTrue("PARAM 行要列出缺失服务", rendered.contains("missingServices=["))
        assertTrue("PARAM 行要列出发起者", rendered.contains("requestors=["))
    }

    @Test
    fun `RWA_PACKED16 bootstrap is traced to the mkjobs variable-shadowing bug`() {
        val s = BootErrorCatalog.Session()
        s.ingest(listOf(bootstrapPacked))
        val f = byId(s, "bootstrap-invalid-path-rwa-packed16")
        assertNotNull("RWA_PACKED16 无效路径必须单独成类", f)
        f!!
        assertEquals(BootErrorCatalog.Layer.LAUNCHD, f.rule.layer)
        assertTrue("根因要点名 mkjobs.py 变量覆盖", f.rule.cause.contains("mkjobs.py") && f.rule.cause.contains("变量"))
        assertTrue("根因要点明 --rest 静默失效", f.rule.cause.contains("rest") || f.rule.cause.contains("静默"))
        assertTrue("处置必须指向真实修复版本 0.26.3（修脚本 + 部署自愈 + 强编镜像），不得沿用已过期的 0.25.0 说法",
            f.rule.action.contains("0.26.3") && !f.rule.action.contains("0.25.0 已修"))
    }

    @Test
    fun `every known class in the HONOR 90 log is classified across all layers`() {
        val s = BootErrorCatalog.Session()
        val fresh = s.ingest(listOf(
            lsdCpuMonitor, bootstrapPacked, lookupOscard, lookupMobileactivationd,
            personaType6, plistUnknownKey, glasmioCrash, crashGuard, captureSlow,
            qemuExit, launchdPlist, biometrickit,
        ))
        // 至少覆盖 KERNEL 之外的 6 个层级
        val layers = s.findings().map { it.rule.layer }.toSet()
        assertTrue("应横跨 LAUNCHD/DAEMON/GUI/HOST_APP/CAPTURE/QEMU 多层级，实际=$layers",
            layers.size >= 5)
        assertTrue(layers.contains(BootErrorCatalog.Layer.LAUNCHD))
        assertTrue(layers.contains(BootErrorCatalog.Layer.DAEMON))
        assertTrue(layers.contains(BootErrorCatalog.Layer.HOST_APP))
        assertTrue(layers.contains(BootErrorCatalog.Layer.CAPTURE))
        // 每一类都带齐 4 段解释
        s.findings().forEach { f ->
            assertTrue("[${f.rule.id}] 缺根因", f.rule.cause.length > 20)
            assertTrue("[${f.rule.id}] 缺影响", f.rule.impact.length > 10)
            assertTrue("[${f.rule.id}] 缺处置", f.rule.action.length > 10)
            assertTrue("[${f.rule.id}] 缺证据", f.firstEvidence.isNotBlank())
        }
        // 崩溃类（FATAL）排在噪音类之前
        val ordered = s.findings()
        val firstFatal = ordered.indexOfFirst { it.rule.severity == BootErrorCatalog.Severity.FATAL }
        val lastNoiseOrInfo = ordered.indexOfLast { it.rule.severity.rank <= BootErrorCatalog.Severity.NOISE.rank }
        assertTrue("FATAL 必须排在 NOISE 之前", firstFatal >= 0 && firstFatal < lastNoiseOrInfo)
        assertTrue("返回的新档案数应等于去重后的类别数（各首次一次）",
            fresh.size == s.distinctRules())
    }

    @Test
    fun `success lines are not misreported as errors`() {
        val s = BootErrorCatalog.Session()
        val fresh = s.ingest(listOf(
            "[ ok ] missing file gives ENOENT",
            "=== 36 checks, 0 failed ===",
            "System shell connected: <BKSystemShellClient: com.apple.springboard>",
            "redwood: SpringBoard: OpenGL ES draws",
        ))
        assertTrue("成功/里程碑行不应产生错误档案", fresh.isEmpty())
        assertEquals(0, s.distinctRules())
        // 也不该进未归类
        assertTrue(s.unclassified().isEmpty())
    }

    @Test
    fun `unknown error lines are kept verbatim with a guessed layer - never no known feature`() {
        val s = BootErrorCatalog.Session()
        val weird = "some.new.daemon[77] <Error>: frobnicate returned ESPIPE (invalid handle) on /dev/xyz"
        s.ingest(listOf(weird))
        assertEquals(0, s.distinctRules())
        val u = s.unclassified()
        assertEquals(1, u.size)
        assertEquals(weird, u.first().line)
        assertNotNull(u.first().guessedLayer)
        val rendered = s.render().joinToString("\n")
        assertTrue("未归类要逐条保留原文", rendered.contains("frobnicate"))
        assertTrue("未归类要标注层级", rendered.contains("[err?]"))
    }

    @Test
    fun `classifyExit replaces 'no known error feature' with concrete attribution`() {
        val s = BootErrorCatalog.Session()
        s.ingest(listOf(lsdCpuMonitor, lookupOscard, personaType6, qemuExit))
        val exit = s.classifyExit(exitCode = 0, serialLogExists = true)
        assertFalse("不许再出现「没有匹配到已知错误特征」", exit.contains("没有匹配到已知错误特征"))
        assertTrue("要给出观测到的类别数", exit.contains("类错误"))
        assertTrue("要给出最高严重度", exit.contains("最高严重度"))
        assertTrue("要给出具体下一步", exit.contains("降核") || exit.contains("payload") || exit.contains("噪音"))
        // 有阻塞项（qemu-exit-unfinished 本身 blocking）时应指向它
        assertTrue("应指向最可能的阻塞点", exit.contains("阻塞点") || exit.contains("静默"))
    }

    @Test
    fun `classifyExit on a silent boot still enumerates per-layer checks`() {
        val s = BootErrorCatalog.Session()   // 一行日志都没喂
        val exit = s.classifyExit(exitCode = 1, serialLogExists = false)
        assertTrue("静默退出也要按层排查", exit.contains("固件") || exit.contains("payload"))
        assertFalse(exit.contains("没有匹配到已知错误特征"))
    }

    @Test
    fun `capture slow extracts the millisecond cost as a param`() {
        val s = BootErrorCatalog.Session()
        s.ingest(listOf(captureSlow, "17:52:07.398 W/QemuControl: 取帧耗时 3254ms（guest 忙或 PPM 8031KB）"))
        val f = byId(s, "capture-slow")!!
        assertEquals(2, f.count)
        assertTrue("要抽出 76430ms", f.params["captureMs"]?.contains("76430") == true)
        assertTrue("要抽出 3254ms", f.params["captureMs"]?.contains("3254") == true)
    }

    @Test
    fun `second-round rules cover the previously unclassified hardware and launchd noise`() {
        val s = BootErrorCatalog.Session()
        s.ingest(listOf(
            "minisandbox: privilege 3 refused to logd[11] (first time)",
            "backboardd[10] [Common] oscard connection event: Connection invalid",
            "redwood: input: the event server asks for property \"LongPressTimeout\", which we do not have",
            "backboardd[10] [WindowServer] kIOMFBSourceLimitsKey missing!",
            "com.apple.xpc.launchd (user/501) <Warning>: (lint): The HideUntilCheckIn property is deprecated",
            "com.apple.xpc.launchd (user/501) <Warning>: failed activation: name = com.apple.mobile.usermanagerd.xpc, flags = 0x8, requestor = SpringBoard[7]",
            "/redwood/bin/hello_dyn exited with status 7",
        ))
        listOf(
            "minisandbox-privilege-refused", "virtual-hw-service-unavailable",
            "input-property-missing", "display-sensor-hardware-gap",
            "launchd-plist-lint", "job-activation-failed", "userspace-selftest-exit",
        ).forEach { id ->
            assertNotNull("[$id] 应被识别", byId(s, id))
            val f = byId(s, id)!!
            assertTrue("[$id] 根因要具体", f.rule.cause.length > 20)
            assertTrue("[$id] 影响要具体", f.rule.impact.length > 10)
            assertTrue("[$id] 处置要具体（不能只写忽略）", f.rule.action.length > 10)
        }
        // 作业激活失败要抽出作业名
        assertTrue(byId(s, "job-activation-failed")!!.params["activationNames"]
            ?.any { it.contains("com.apple.mobile.usermanagerd.xpc") } == true)
    }

    @Test
    fun `positive progress lines are not misclassified even when they contain error-like words`() {
        val s = BootErrorCatalog.Session()
        val fresh = s.ingest(listOf(
            "redwood: SpringBoard: activation answered here, setup assistant refused, application snapshots as JPEG",
            "backboardd[10] [Common] CoreAccessories ready in 5.78536s. overall 5.96881s",
            "redwood-gl: GL_EXT_debug_label GL_KHR_no_error GL_OES_texture_float",
            "98% 42972 - private/var/staged_system_apps/Reminders.app/ListDetail_Exceptions_PluralLocalizable.loctable",
        ))
        assertTrue("正向进度行不应产生任何错误档案", fresh.isEmpty())
        assertEquals(0, s.distinctRules())
        assertTrue("正向进度行也不应进未归类", s.unclassified().isEmpty())
    }

    @Test
    fun `every rule in the catalog has full cause impact and action - no vague filler`() {
        BootErrorCatalog.RULES.forEach { r ->
            assertTrue("[${r.id}] 缺根因", r.cause.length > 20)
            assertTrue("[${r.id}] 缺影响", r.impact.length > 10)
            assertTrue("[${r.id}] 缺处置（不能只写忽略）", r.action.length > 10)
            assertTrue("[${r.id}] 缺组件", r.component.isNotBlank())
            assertTrue("[${r.id}] 缺位置", r.where.isNotBlank())
        }
        // 禁止任何规则退化成「CPU 出错之类」这种含糊措辞
        assertFalse(BootErrorCatalog.RULES.any { "CPU 出错之类" in it.cause || "CPU 出错之类" in it.action })
        assertTrue("规则库应 >= 24 类", BootErrorCatalog.RULES.size >= 24)
    }

    @Test
    fun `summary exposes blocking items for UI and MCP`() {
        val s = BootErrorCatalog.Session()
        s.ingest(listOf(lsdCpuMonitor, qemuExit))
        val sum = BootErrorCatalog.summary(s)
        assertTrue(sum.contains("类"))
        assertTrue("摘要要点名阻塞项", sum.contains("阻塞") || sum.contains("qemu-exit"))
        assertTrue(s.hasBlocking())
        assertNotNull(s.mostSerious())
    }
    // ---- 0.29.4：触摸节点名与帧缓冲误判 ------------------------------------------------------
    @Test
    fun `touch node mismatch is diagnosed with the exact guest path`() {
        val s = BootErrorCatalog.Session()
        s.ingest(
            listOf(
                "rwtouch: /dev/redwood-touch: No such file or directory",
                "redwood: input: /dev/redwood-touch: No such file or directory",
            )
        )
        val f = byId(s, "guest-touch-node-missing")
        assertNotNull("必须单独识别触摸节点缺失", f)
        f!!
        assertEquals(BootErrorCatalog.Severity.DEGRADED, f.rule.severity)
        assertFalse("触摸节点不阻塞桌面绘制", f.rule.blocking)
        assertTrue("根因要点明内核建的是 hakios-touch", f.rule.cause.contains("/dev/hakios-touch"))
        assertTrue("处置要给出 0.29.4 / IMAGE_REV 9", f.rule.action.contains("0.29.4") && f.rule.action.contains("IMAGE_REV 9"))
        assertTrue("处置要说明只重建 root.apfs", f.rule.action.contains("root.apfs"))
    }

    @Test
    fun `swap log no longer claims the kernel lacks the copy`() {
        val s = BootErrorCatalog.Session()
        s.ingest(
            listOf(
                "MiniIOMobileFramebuffer: swap 2 layer 0: surface 2 (1125x2436 'BGRA') from 0,0 1125x2436 to 0,0 1125x2436, layers set 0x1",
                "MiniIOMobileFramebuffer: 2 frames in 8898 ms (0 a second), 11894 us each in the driver",
            )
        )
        val f = byId(s, "iomfb-swap-stalled")
        assertNotNull("有 swap 行时必须给出显示停摆归因", f)
        f!!
        assertTrue("根因必须承认 swap 拷贝在工作", f.rule.cause.contains("是工作的"))
        assertTrue("根因必须否定「缺 memcpy」", f.rule.cause.contains("不是") && f.rule.cause.contains("memcpy"))
        assertTrue("处置不能再要求重编内核补拷贝", f.rule.action.contains("不要重编内核"))
        assertTrue("处置要指向 redwood-gl 帧计数", f.rule.action.contains("redwood-gl: frame"))
    }

    @Test
    fun `selector not implemented alone is not the swap stall rule`() {
        val s = BootErrorCatalog.Session()
        s.ingest(listOf("MiniIOMobileFramebuffer: selector 57 not implemented (scalars 0 in [0x0 0x0 0x0], 1 out; structure 0 in, 0 out)"))
        assertNotNull(byId(s, "iomfb-selector-not-implemented"))
        assertNull("单独 selector 不能冒充 swap 停摆", byId(s, "iomfb-swap-stalled"))
    }

}
