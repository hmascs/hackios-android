package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「1 分半亮 logo / 3 分钟进桌面」的验收口径。用注入时钟精确控制时间，不 sleep。
 */
class BootTimelineTest {

    /** 可手动推进的时钟。 */
    private class FakeClock : BootTimeline.Clock {
        var now = 1_000_000L
        override fun now(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    private fun timeline(clock: FakeClock): BootTimeline = BootTimeline(clock)

    @Test
    fun `budgets match the measured boot on 0_29_6`() {
        // 实测：第一帧非黑 ~20s、第 2 帧 55s；桌面在第 10 分钟。旧值 90/180 从来没有参考意义。
        assertEquals(60_000L, BootTimeline.LOGO_BUDGET_MS)
        assertEquals(600_000L, BootTimeline.DESKTOP_BUDGET_MS)
    }

    @Test
    fun `marks are relative to start and idempotent`() {
        val c = FakeClock()
        val t = timeline(c)
        assertFalse(t.isStarted)
        t.start(6, true)
        assertTrue(t.isStarted)
        c.advance(500)
        assertTrue(t.mark(BootTimeline.Stage.DEPLOY))
        assertEquals(500L, t.elapsed(BootTimeline.Stage.DEPLOY))
        c.advance(9_500)
        assertFalse("重复打点应被忽略", t.mark(BootTimeline.Stage.DEPLOY))
        assertEquals(500L, t.elapsed(BootTimeline.Stage.DEPLOY))
        assertTrue(t.mark(BootTimeline.Stage.QEMU_SPAWN))
        assertEquals(10_000L, t.elapsed(BootTimeline.Stage.QEMU_SPAWN))
    }

    @Test
    fun `new start resets all marks`() {
        val c = FakeClock()
        val t = timeline(c)
        t.start(6, true)
        c.advance(1000)
        t.mark(BootTimeline.Stage.DEPLOY)
        t.start(4, false)
        assertEquals(0, t.markCount())
        assertNull(t.elapsed(BootTimeline.Stage.DEPLOY))
        assertFalse(t.reached(BootTimeline.Stage.DEPLOY))
    }

    @Test
    fun `advanceFromPhase marks 1 through n`() {
        val c = FakeClock()
        val t = timeline(c)
        t.start(6, true)
        t.advanceFromPhase(3)
        assertTrue(t.reached(BootTimeline.Stage.UEFI))
        assertTrue(t.reached(BootTimeline.Stage.LOADER))
        assertTrue(t.reached(BootTimeline.Stage.KERNEL))
        assertFalse(t.reached(BootTimeline.Stage.LAUNCHD))
        // 阶段 7（桌面/画面）不能靠文本推进
        assertFalse(t.reached(BootTimeline.Stage.DESKTOP))
        t.advanceFromPhase(6)
        assertTrue(t.reached(BootTimeline.Stage.GUI))
        assertFalse(t.reached(BootTimeline.Stage.DESKTOP))
    }

    @Test
    fun `logo within 60s is on budget`() {
        val c = FakeClock()
        val t = timeline(c)
        t.start(6, true)
        c.advance(50_000)
        t.noteLogo()
        assertEquals(50_000L, t.elapsed(BootTimeline.Stage.LOGO))
        assertEquals(true, t.logoVerdict)
        assertNull(t.desktopVerdict)
    }

    @Test
    fun `logo over 60s busts the budget but desktop can still make 600s`() {
        val c = FakeClock()
        val t = timeline(c)
        t.start(6, true)
        c.advance(70_000)
        t.noteLogo()
        assertEquals(false, t.logoVerdict)
        c.advance(60_000)     // t=130s
        t.noteDesktop()
        assertEquals(true, t.desktopVerdict)
        assertEquals(130_000L, t.elapsed(BootTimeline.Stage.DESKTOP))
    }

    @Test
    fun `desktop over 600s busts the budget`() {
        val c = FakeClock()
        val t = timeline(c)
        t.start(6, true)
        c.advance(620_000)
        t.noteDesktop()
        assertEquals(false, t.desktopVerdict)
        // noteDesktop 会连带补齐 logo
        assertTrue(t.reached(BootTimeline.Stage.LOGO))
    }

    @Test
    fun `segments report deltas and cumulative times`() {
        val c = FakeClock()
        val t = timeline(c)
        t.start(6, true)
        c.advance(2_000); t.mark(BootTimeline.Stage.DEPLOY)
        c.advance(3_000); t.mark(BootTimeline.Stage.PREPARE)
        c.advance(5_000); t.mark(BootTimeline.Stage.QEMU_SPAWN)
        val segs = t.segments()
        assertEquals(3, segs.size)
        assertEquals(2_000L, segs[0].second)
        assertEquals(3_000L, segs[1].second)
        assertEquals(5_000L, segs[2].second)
        assertEquals(10_000L, segs[2].third)
        assertEquals(BootTimeline.Stage.QEMU_SPAWN to 5_000L, t.slowestSegment)
    }

    @Test
    fun `payload reuse is recorded in report and serialization`() {
        val c = FakeClock()
        val t = timeline(c)
        t.start(6, true)
        t.notePayloadReused()
        c.advance(1_000); t.mark(BootTimeline.Stage.DEPLOY)
        assertTrue(t.report().contains("复用缓存"))
        assertTrue(t.serialize().contains("payload_reused=true"))
    }

    @Test
    fun `serialization carries verdicts and budgets`() {
        val c = FakeClock()
        val t = timeline(c)
        t.start(6, true)
        c.advance(50_000); t.noteLogo()
        c.advance(70_000); t.noteDesktop()
        val s = t.serialize()
        assertTrue(s.contains("profile_smp=6"))
        assertTrue(s.contains("profile_mttcg=true"))
        assertTrue(s.contains("logo_budget_ms=60000"))
        assertTrue(s.contains("desktop_budget_ms=600000"))
        assertTrue(s.contains("logo_at_ms=50000"))
        assertTrue(s.contains("desktop_at_ms=120000"))
        assertTrue(s.contains("logo_verdict=true"))
        assertTrue(s.contains("desktop_verdict=true"))
        assertTrue(s.contains("slowest") || t.report().contains("最慢一段"))
    }

    @Test
    fun `report shows pending milestones with elapsed time`() {
        val c = FakeClock()
        val t = timeline(c)
        t.start(6, true)
        c.advance(30_000); t.mark(BootTimeline.Stage.QEMU_SPAWN)
        val r = t.report()
        assertTrue(r.contains("未到达"))
        assertTrue(r.contains("30.0s") || r.contains("30s"))
        assertTrue(r.contains("预算"))
    }
}
