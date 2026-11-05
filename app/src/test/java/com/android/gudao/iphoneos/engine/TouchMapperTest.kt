package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the two touch bugs: letterbox mis-mapping and gesture classification.
 */
class TouchMapperTest {

    @Test
    fun `viewport letterboxes on a wider view`() {
        // 1080x1920 view, 1125x2436 guest: view is wider than the guest, so bars go left/right.
        val vp = TouchMapper.viewport(1080f, 1920f, 1125, 2436)
        assertEquals(1920f, vp.height)
        assertEquals(1920f * 1125f / 2436f, vp.width, 0.5f)
        assertEquals((1080f - vp.width) / 2f, vp.left, 0.5f)
        assertEquals(0f, vp.top, 0.01f)
    }

    @Test
    fun `viewport letterboxes on a taller view`() {
        val vp = TouchMapper.viewport(1080f, 2400f, 1125, 2436)
        assertEquals(1080f, vp.width)
        assertEquals(1080f * 2436f / 1125f, vp.height, 0.5f)
        assertEquals(0f, vp.left, 0.01f)
        assert(vp.top > 0f)
    }

    @Test
    fun `touch inside the frame maps to guest fractions`() {
        val p = TouchMapper.toGuest(1080f, 1920f, 1125, 2436, 540f, 960f)
        requireNotNull(p)
        assertEquals(0.5f, p.x, 0.01f)
        assertEquals(0.5f, p.y, 0.01f)
    }

    @Test
    fun `touch in the letterbox is rejected instead of drifting`() {
        // x = 5 is inside the view but outside the centred guest rect.
        assertNull(TouchMapper.toGuest(1080f, 1920f, 1125, 2436, 5f, 960f))
    }

    @Test
    fun `corners map to the edges of the guest screen`() {
        val vp = TouchMapper.viewport(1080f, 1920f, 1125, 2436)
        val topLeft = TouchMapper.toGuest(1080f, 1920f, 1125, 2436, vp.left, vp.top)!!
        assertEquals(0f, topLeft.x, 0.001f)
        assertEquals(0f, topLeft.y, 0.001f)
        val bottomRight = TouchMapper.toGuest(1080f, 1920f, 1125, 2436, vp.right, vp.bottom)!!
        assertEquals(1f, bottomRight.x, 0.001f)
        assertEquals(1f, bottomRight.y, 0.001f)
    }

    @Test
    fun `gesture classification follows the iOS thresholds`() {
        assertEquals(GestureKind.Tap, TouchMapper.classify(80, 4f, slopPx = 20f))
        assertEquals(GestureKind.LongPress, TouchMapper.classify(700, 4f, slopPx = 20f))
        assertEquals(GestureKind.Drag, TouchMapper.classify(120, 40f, slopPx = 20f))
        // A long, slow drag is still a drag: distance wins over duration.
        assertEquals(GestureKind.Drag, TouchMapper.classify(2000, 300f, slopPx = 20f))
    }
}
