package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 黑屏直读探针的判读逻辑（0.26.1）。样例全部来自荣耀 90 真机日志
 * hackios-logs-20261104-230859.txt 的串口原文，保证测试钉住的是真机形态而不是编造格式。
 */
class FramebufferProbeTest {

    @Test
    fun `parses the kernel display line from a real device log`() {
        val d = FramebufferProbe.parseDisplayLine("display 1125 x 2436 at 1fb6bb000")
        assertNotNull(d)
        d!!
        assertEquals(1125, d.width)
        assertEquals(2436, d.height)
        assertEquals(0x1fb6bb000L, d.fbAddr)
        // 未给 stride 时默认 BGRA 4 字节/像素
        assertEquals(4500, d.stride)
        assertEquals(4500L * 2436, d.byteSize)
    }

    @Test
    fun `parses the MiniIOMFB panel line with stride`() {
        val line = "MiniIOMobileFramebuffer: display 1125x2436 on a panel of 1125x2436 (4500 bytes per row)"
        val d = FramebufferProbe.parseDisplayLine(line)
        assertNotNull(d)
        d!!
        assertEquals(1125, d.width)
        assertEquals(2436, d.height)
        assertEquals(4500, d.stride)
        assertEquals(0L, d.fbAddr) // 该行不含地址
    }

    @Test
    fun `merges kernel address with MiniIOMFB stride`() {
        val a = FramebufferProbe.parseDisplayLine("display 1125 x 2436 at 1fb6bb000")!!
        val b = FramebufferProbe.parseDisplayLine(
            "MiniIOMobileFramebuffer: display 1125x2436 on a panel of 1125x2436 (4500 bytes per row)",
        )!!
        val m = FramebufferProbe.merge(a, b)!!
        assertEquals(0x1fb6bb000L, m.fbAddr)
        assertEquals(4500, m.stride)
        // 反向合并结果一致
        assertEquals(m, FramebufferProbe.merge(b, a))
        // 单侧 null
        assertEquals(a, FramebufferProbe.merge(a, null))
        assertEquals(b, FramebufferProbe.merge(null, b))
        assertNull(FramebufferProbe.merge(null, null))
    }

    @Test
    fun `rejects lines that are not display reports`() {
        assertNull(FramebufferProbe.parseDisplayLine("surf_test: surface 1: 64x48, 256 bytes per row"))
        assertNull(FramebufferProbe.parseDisplayLine("redwood-gl: a frame is waited for by the display's driver"))
        assertNull(FramebufferProbe.parseDisplayLine(""))
    }

    @Test
    fun `mean over a black dump is zero and verdict is BLACK`() {
        val d = FramebufferProbe.GuestDisplay(1125, 2436, 0x1fb6bb000L, 4500)
        val bytes = ByteArray(d.byteSize.toInt()) // 全 0
        assertEquals(0, FramebufferProbe.computeMeanBgra(bytes, d))
        assertEquals(FramebufferProbe.Verdict.BLACK, FramebufferProbe.verdict(0))
    }

    @Test
    fun `mean over a white dump is 765 and verdict is NON_BLACK`() {
        val d = FramebufferProbe.GuestDisplay(1125, 2436, 0x1fb6bb000L, 4500)
        val bytes = ByteArray(d.byteSize.toInt()) { 0xFF.toByte() } // BGRA 全满
        assertEquals(765, FramebufferProbe.computeMeanBgra(bytes, d))
        assertEquals(FramebufferProbe.Verdict.NON_BLACK, FramebufferProbe.verdict(765))
    }

    @Test
    fun `stride padding is skipped when row is wider than width`() {
        // 4x2 图，stride=20（宽 16 字节 + 4 字节 padding），仅第一像素白色
        val d = FramebufferProbe.GuestDisplay(4, 2, 0L, 20)
        val bytes = ByteArray(20 * 2)
        // BGRA: B=0,G=0,R=255,A=255
        bytes[2] = 0xFF.toByte(); bytes[3] = 0xFF.toByte()
        val mean = FramebufferProbe.computeMeanBgra(bytes, d)
        // 8 个采样像素里 1 个是 (255+0+0)=255，其余 0
        assertEquals(255 / 8, mean)
    }

    @Test
    fun `undersized dump is unreadable`() {
        val d = FramebufferProbe.GuestDisplay(1125, 2436, 0x1fb6bb000L, 4500)
        assertEquals(-1, FramebufferProbe.computeMeanBgra(ByteArray(100), d))
        assertEquals(FramebufferProbe.Verdict.UNREADABLE, FramebufferProbe.verdict(-1))
    }

    @Test
    fun `grey content around the logo threshold classifies sanely`() {
        val d = FramebufferProbe.GuestDisplay(2, 1, 0L, 8)
        // 每像素 RGB=4 → mean=12 > BLACK_MEAN(8)
        val bytes = byteArrayOf(4,4,4,(0xFF).toByte(), 4,4,4,(0xFF).toByte())
        val mean = FramebufferProbe.computeMeanBgra(bytes, d)
        assertEquals(12, mean)
        assertTrue(FramebufferProbe.verdict(mean) == FramebufferProbe.Verdict.NON_BLACK)
        // RGB=2 → mean=6 <= 8 → BLACK
        val dark = byteArrayOf(2,2,2,(0xFF).toByte(), 2,2,2,(0xFF).toByte())
        assertEquals(6, FramebufferProbe.computeMeanBgra(dark, d))
        assertEquals(FramebufferProbe.Verdict.BLACK, FramebufferProbe.verdict(6))
    }
}
