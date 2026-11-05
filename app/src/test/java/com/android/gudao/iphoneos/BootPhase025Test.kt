package com.android.gudao.iphoneos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.25.0：把真机日志里的两类行正确分类，避免「阶段 6 卡死」的误判。 */
class BootPhase025Test {

    @Test
    fun `real HONOR 90 desktop lines are recognised as a desktop signal not a stage bump`() {
        val log = """
            Successfully spawned SpringBoard[7] because system support
            redwood: SpringBoard: OpenGL ES draws, vibrant views are plain all the same (plain_vibrancy=1)
            2026-11-04 12:54:22.781596 backboardd[10:209] [SystemShell] System shell connected: <BKSystemShellClient: bundleIdentifier: com.apple.springboard; pid: 7>
        """.trimIndent()
        val r = BootPhase.analyze(log)
        assertNotNull(r.desktopSignal)
        assertTrue(r.desktopSignal!!.contains("shell") || r.desktopSignal!!.contains("绘制"))
        // 文本证据本身不能把阶段推到 7：仍然只到文本阶段 6，第 7 级由真实帧决定。
        assertTrue(r.stage <= 6)
    }

    @Test
    fun `lsd CPU monitor storm is reported as non-blocking noise`() {
        val log = """
            process lsd[25] denied attempt to escape CPU monitor (missing required entitlement).
            com.apple.xpc.launchd| (user/501) <Warning>: failed lookup: name = com.apple.mobileactivationd, requestor = lsd[25], error = 3: No such process
        """.trimIndent()
        val r = BootPhase.analyze(log)
        assertNotNull(r.noise)
        assertTrue(r.noise!!.contains("非阻塞"))
    }

    @Test
    fun `quiet log has neither signal nor noise`() {
        val r = BootPhase.analyze("UEFI firmware (version  built at 13:10:49 on Nov  5 2024)")
        assertNull(r.desktopSignal)
        assertNull(r.noise)
        assertEquals(1, r.stage)
    }

    @Test
    fun `real kernel and launchd lines still advance stages`() {
        val log = """
            == XNULOADER v3 ==
            Darwin Kernel Version 22.5.0: Thu Oct 22 09:28:22 CDT 2026
            com.apple.xpc.launchd| Bootstrapping
            Successfully spawned SpringBoard[7] because system support
            2026-11-04 12:54:22.781596 backboardd[10:209] [SystemShell] System shell connected: <BKSystemShellClient: bundleIdentifier: com.apple.springboard; pid: 7>
        """.trimIndent()
        val r = BootPhase.analyze(log)
        assertTrue("应至少到界面层 6，实际 ${r.stage}", r.stage >= 6)
        assertNotNull(r.desktopSignal)
    }
}
