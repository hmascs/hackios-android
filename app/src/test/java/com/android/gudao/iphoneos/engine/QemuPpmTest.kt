package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class QemuPpmTest {
    private val header = "P6\n1 2\n255\n".toByteArray()
    private val rgb = byteArrayOf(0x20, 0x0a, 0x0d, -1, 0, 127)
    private val padded = byteArrayOf(0x20, 0x0a, 0x0d, 99, -1, 0, 127, 99)

    @Test fun `both standard and legacy padded rows decode without losing initial whitespace bytes`() {
        var signature: Long? = null
        for ((body, stride) in listOf(rgb to 3, padded to 4)) {
            val data = header + body
            val layout = requireNotNull(QemuPpm.layout(data, data.size))
            assertEquals(QemuPpm.Layout(1, 2, header.size, stride), layout)
            val out = IntArray(2)
            val stats = QemuPpm.decode(data, layout, 1, 2, out)
            assertArrayEquals(intArrayOf(0xff200a0d.toInt(), 0xffff007f.toInt()), out)
            if (signature == null) signature = stats.signature
            assertEquals(signature, stats.signature)
        }
    }

    @Test fun `reject incomplete row and invalid headers without accessing stale buffer bytes`() {
        val data = header + rgb
        assertNull(QemuPpm.layout(data, data.size - 1))
        assertNull(QemuPpm.layout(data, data.size + 1))
        for (text in listOf("P6\n0 2\n255\n", "P6\n1 2\n0\n", "P6\n9999999999 2\n255\n")) {
            val bad = text.toByteArray() + rgb
            assertNull(QemuPpm.layout(bad, bad.size))
        }
    }

    @Test fun `black logo and boot log do not count as a guest interface`() {
        val w = 120
        val h = 224
        val pixels = IntArray(w * h)
        assertFalse(QemuPpm.sceneHasContent(pixels, w, h))
        // Bright central logo, and then a bright/wide panel below it.
        for (y in 96 until 120) pixels.fill(-1, y * w + 48, y * w + 72)
        assertFalse(QemuPpm.sceneHasContent(pixels, w, h))
        pixels.fill(-1, w * 199)
        assertFalse(QemuPpm.sceneHasContent(pixels, w, h))
    }

    @Test fun `dim screen with distributed visible content counts as a guest interface`() {
        val w = 120
        val h = 224
        val pixels = IntArray(w * h) { 0xff111111.toInt() }
        assertFalse(QemuPpm.sceneHasContent(pixels, w, h))
        for (y in 0 until 130) {
            pixels.fill(0xff202020.toInt(), y * w + 4, y * w + 32)
            pixels.fill(0xff202020.toInt(), y * w + 85, y * w + 112)
        }
        assertTrue(QemuPpm.sceneHasContent(pixels, w, h))
    }

    @Test fun `understands CRLF and decodes minimum geometry`() {
        val data = "P6\r\n1 1\r\n255\r\n".toByteArray() + byteArrayOf(0x0a, 2, 5)
        assertNotNull(QemuPpm.layout(data, data.size))
        val layout = requireNotNull(QemuPpm.layout(data, data.size))
        val out = IntArray(1)
        assertEquals(5, QemuPpm.decode(data, layout, 1, 1, out).mean)
        assertEquals(0xff0a0205.toInt(), out.single())
    }
}
