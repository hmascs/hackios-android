package com.android.gudao.iphoneos.engine

import com.android.gudao.iphoneos.QemuControl
import org.junit.Assert.assertEquals
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
    fun `initial snapshot is idle and empty`() {
        val s = pipeline().snapshot.value
        assertEquals(CapturePipeline.Health.Idle, s.health)
        assertEquals(0L, s.frames)
        assertEquals("—", s.resolution)
    }
}
