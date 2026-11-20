package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取帧调度的验收：引导和桌面都不再被人为降帧，只有「一次取帧过慢」才退避，
 * 且永远不返回忙循环的 0 间隔。实测帧率由 CapturePipeline 的 fpsEma 汇报，
 * 这里只断言「允许的最快节奏」。
 */
class FramePacerTest {

    @Test
    fun `target is 60 fps`() {
        assertEquals(16L, FramePacer.TARGET_INTERVAL_MS)
        assertTrue(1000f / FramePacer.TARGET_INTERVAL_MS >= 60f)
    }

    @Test
    fun `a fast capture is paced to the target, not slower`() {
        // 一次 40ms 的 screendump 已经比 16ms 慢；调度不该再额外等待。
        assertEquals(
            FramePacer.TARGET_INTERVAL_MS,
            FramePacer.nextIntervalMs(lastCaptureMs = 40),
        )
        assertEquals(
            FramePacer.TARGET_INTERVAL_MS,
            FramePacer.nextIntervalMs(lastCaptureMs = 0),
        )
    }

    @Test
    fun `slow screendump backs off instead of piling up`() {
        // 真机日志里出现过 76430ms 的一次取帧；旧循环 125ms 后又发一条，形成雪崩。
        val paced = FramePacer.nextIntervalMs(lastCaptureMs = 76_430)
        assertEquals(FramePacer.BACKOFF_INTERVAL_MS, paced)
        assertTrue(paced > FramePacer.TARGET_INTERVAL_MS)
    }

    @Test
    fun `backoff wins over the target`() {
        assertEquals(
            FramePacer.BACKOFF_INTERVAL_MS,
            FramePacer.nextIntervalMs(lastCaptureMs = 3_000),
        )
    }

    @Test
    fun `never returns a busy-loop interval`() {
        for (cost in listOf(0L, 100L, 1_499L, 1_500L, 90_000L)) {
            assertTrue(
                FramePacer.nextIntervalMs(lastCaptureMs = cost) >= FramePacer.MIN_INTERVAL_MS,
            )
        }
    }

    @Test
    fun `custom normal interval is respected`() {
        assertEquals(250L, FramePacer.nextIntervalMs(lastCaptureMs = 50, normalIntervalMs = 250L))
        // 自定义间隔也不能低到忙循环
        assertEquals(FramePacer.MIN_INTERVAL_MS, FramePacer.nextIntervalMs(lastCaptureMs = 1, normalIntervalMs = 1L))
    }

    @Test
    fun `describe covers every pace`() {
        assertTrue(FramePacer.describe(FramePacer.BACKOFF_INTERVAL_MS).contains("退避"))
        assertTrue(FramePacer.describe(FramePacer.TARGET_INTERVAL_MS).contains("60 FPS"))
        assertTrue(FramePacer.describe(FramePacer.TARGET_INTERVAL_MS).contains("实测"))
    }

    @Test
    fun `a static screen drops to four samples per second`() {
        // 画面 2 秒没变：把主循环让给 TCG，采样降到 250 ms。
        assertEquals(
            FramePacer.STATIC_INTERVAL_MS,
            FramePacer.nextIntervalMs(lastCaptureMs = 40, staticForMs = 2_000L),
        )
        assertEquals(
            FramePacer.STATIC_INTERVAL_MS,
            FramePacer.nextIntervalMs(lastCaptureMs = 40, staticForMs = 60_000L),
        )
    }

    @Test
    fun `a fresh change goes straight back to the target rate`() {
        // 刚检测到变化（staticForMs=0）就必须回到 16 ms，否则点了要等 250 ms 才看到反馈。
        assertEquals(
            FramePacer.TARGET_INTERVAL_MS,
            FramePacer.nextIntervalMs(lastCaptureMs = 40, staticForMs = 0L),
        )
        assertEquals(
            FramePacer.TARGET_INTERVAL_MS,
            FramePacer.nextIntervalMs(lastCaptureMs = 40, staticForMs = 1_999L),
        )
    }

    @Test
    fun `a slow capture still wins over a static screen`() {
        // 两条退避规则同时命中时，guest 忙的保护优先，避免 76 秒那种堆积。
        assertEquals(
            FramePacer.BACKOFF_INTERVAL_MS,
            FramePacer.nextIntervalMs(lastCaptureMs = 3_000, staticForMs = 60_000L),
        )
    }
}
