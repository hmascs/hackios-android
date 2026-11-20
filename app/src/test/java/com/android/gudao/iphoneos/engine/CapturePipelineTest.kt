package com.android.gudao.iphoneos.engine

import com.android.gudao.iphoneos.QemuControl
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** B6: a failing screendump must back off instead of spinning or giving up silently. */
class CapturePipelineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun pipeline() = CapturePipeline(
        control = QemuControl(),
        ppmFile = tmp.newFile("shot.ppm"),
        intervalMs = 125,
    )

    @Test
    fun `backoff grows and is capped`() {
        val p = pipeline()
        assertEquals(250L, p.backoffMs(1))
        assertEquals(500L, p.backoffMs(2))
        assertEquals(1_000L, p.backoffMs(3))
        assertEquals(2_000L, p.backoffMs(4))
        assertEquals(4_000L, p.backoffMs(5))
        // Never beyond the 4 s ceiling: the display must remain responsive to a manual retry.
        assertEquals(4_000L, p.backoffMs(12))
    }

    @Test
    fun `manual retry wakes a long pacing wait`() {
        val signal = CaptureRetrySignal()
        val entered = CountDownLatch(1)
        val wait = FutureTask {
            entered.countDown()
            signal.await(30_000)
        }
        val worker = Thread(wait).apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            signal.request()
            assertTrue(wait.get(3, TimeUnit.SECONDS))
            assertFalse(signal.consume())
        } finally {
            worker.interrupt()
        }
    }

    @Test
    fun `queued retry clicks coalesce and do not change idle waits`() {
        val signal = CaptureRetrySignal()
        repeat(10) { signal.request() }
        assertTrue(signal.consume())
        assertFalse(signal.consume())
        assertFalse(signal.await(0))
    }

    @Test
    fun `initial snapshot is idle and empty`() {
        val s = pipeline().snapshot.value
        assertEquals(CapturePipeline.Health.Idle, s.health)
        assertEquals(0L, s.frames)
        assertEquals("—", s.resolution)
    }

    @Test
    fun `content fps counts changes over the real window`() {
        // 变化发生在第一秒内 → 按已过去的 1 秒折算，不按 5 秒窗口空摊。
        assertEquals(3.0f, CapturePipeline.windowFps(3, 1_000L), 0.01f)
        // 窗口满了以后按 5 秒摊。
        assertEquals(0.2f, CapturePipeline.windowFps(1, 5_000L), 0.001f)
        assertEquals(0.6f, CapturePipeline.windowFps(3, 20_000L), 0.01f)
        // 20 FPS 目标：5 秒 100 次变化就是 20.0，这个数能直接和用户的目标比。
        assertEquals(20.0f, CapturePipeline.windowFps(100, 5_000L), 0.01f)
    }

    @Test
    fun `content fps never reports a rate without elapsed time or changes`() {
        assertEquals(0f, CapturePipeline.windowFps(0, 5_000L), 0f)
        assertEquals(0f, CapturePipeline.windowFps(5, 0L), 0f)
        assertEquals(0f, CapturePipeline.windowFps(5, -1L), 0f)
    }
}
