package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 帧环读端（docs/gl-frame-ring.md）的协议钉子：
 * 一个字节写错，App 就会把红蓝反过来或者把半帧当完整帧显示，所以这里按文件字节逐项验证。
 */
class FrameRingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val headerBytes = 64

    /** 按协议 v2 写一个环：[pixels] 返回 ARGB，落盘时拆成内存里的 B,G,R,A 字节。 */
    private fun writeRing(
        width: Int,
        height: Int,
        stride: Int = width * 4,
        seq: Long = 1L,
        magic: String = "HFR2",
        version: Int = 2,
        format: Int = 1,
        name: String = "frame.ring",
        pixels: (x: Int, y: Int) -> Int = { _, _ -> 0xff000000.toInt() },
    ): File {
        val file = File(tmp.root, name)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(headerBytes + stride.toLong() * height)
            val header = ByteBuffer.allocate(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            header.put(0, magic[0].code.toByte())
            header.put(1, magic[1].code.toByte())
            header.put(2, magic[2].code.toByte())
            header.put(3, magic[3].code.toByte())
            header.putInt(4, version)
            header.putInt(8, width)
            header.putInt(12, height)
            header.putInt(16, stride)
            header.putInt(20, format)
            header.putLong(24, seq)
            header.putLong(40, seq * 2)
            header.putLong(32, 123_456_789L)
            raf.seek(0)
            raf.write(header.array())

            val row = ByteArray(stride) { 0x7f } // padding 用显眼的字节，拷进来就能看出来
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val argb = pixels(x, y)
                    row[x * 4] = (argb and 0xFF).toByte()               // B
                    row[x * 4 + 1] = ((argb ushr 8) and 0xFF).toByte()  // G
                    row[x * 4 + 2] = ((argb ushr 16) and 0xFF).toByte() // R
                    row[x * 4 + 3] = ((argb ushr 24) and 0xFF).toByte() // A
                }
                raf.seek(headerBytes + y.toLong() * stride)
                raf.write(row)
            }
        }
        return file
    }

    private fun setSeq(file: File, seq: Long) {
        RandomAccessFile(file, "rw").use {
            it.seek(24)
            it.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(seq).array())
            it.seek(40)
            it.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(seq * 2).array())
        }
    }

    private fun setHeaderInt(file: File, offset: Int, value: Int) {
        RandomAccessFile(file, "rw").use {
            it.seek(offset.toLong())
            it.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }
    }

    private fun setPixel(file: File, x: Int, y: Int, stride: Int, argb: Int) {
        RandomAccessFile(file, "rw").use {
            it.seek(headerBytes + y.toLong() * stride + x * 4L)
            it.write(
                byteArrayOf(
                    (argb and 0xFF).toByte(),
                    ((argb ushr 8) and 0xFF).toByte(),
                    ((argb ushr 16) and 0xFF).toByte(),
                    ((argb ushr 24) and 0xFF).toByte(),
                )
            )
        }
    }

    @Test
    fun `open accepts a valid v2 header`() {
        val file = writeRing(width = 4, height = 2)
        val ring = requireNotNull(FrameRing.open(file))
        assertEquals(4, ring.width)
        assertEquals(2, ring.height)
        assertEquals(123_456_789L, ring.timestampNs)
    }

    @Test
    fun `create writes a seq zero header that open accepts`() {
        val file = File(tmp.root, "created.ring")
        assertTrue(FrameRing.create(file, width = 4, height = 2, stride = 4 * 4))
        assertEquals((headerBytes + 4 * 4 * 2).toLong(), file.length())
        val ring = requireNotNull(FrameRing.open(file))
        // seq=0 表示「worker 还没有出过帧」：这是正常的引导早期状态，必须回退 screendump。
        assertFalse(ring.latestInto(IntArray(8), 4, 2))
    }

    @Test
    fun `rejects a file whose magic is not HFR2`() {
        assertNull(FrameRing.open(writeRing(width = 2, height = 2, magic = "HFRX")))
    }

    @Test
    fun `rejects an unsupported protocol version`() {
        assertNull(FrameRing.open(writeRing(width = 2, height = 2, version = 3)))
    }

    @Test
    fun `rejects a non bgra pixel format`() {
        assertNull(FrameRing.open(writeRing(width = 2, height = 2, format = 3)))
    }

    @Test
    fun `rejects zero dimensions`() {
        // 头字段是 u32，0 是合法整数但不是合法几何；不能让它变成空目标或除零。
        assertNull(FrameRing.open(writeRing(width = 0, height = 2, stride = 8)))
        assertNull(FrameRing.open(writeRing(width = 2, height = 0, stride = 8)))
    }

    @Test
    fun `missing file fails safely`() {
        // OTA 后旧 bundle / 没有 worker 的机器就是这个状态：必须静默回退 screendump。
        assertNull(FrameRing.open(File(tmp.root, "absent.ring")))
    }

    @Test
    fun `rejects a file shorter than the declared pixel area`() {
        val file = writeRing(width = 4, height = 2, stride = 20)
        RandomAccessFile(file, "rw").use { it.setLength(headerBytes + 20L * 2 - 8) }
        assertNull(FrameRing.open(file))
    }

    @Test
    fun `rejects a stride smaller than one row`() {
        // 先按合法 stride 写，再改头里的 stride：这样能单独验证 open 的几何校验。
        val file = writeRing(width = 4, height = 2, stride = 4 * 4)
        setHeaderInt(file, 16, 4 * 4 - 4)
        assertNull(FrameRing.open(file))
    }

    @Test
    fun `copies every row while skipping stride padding`() {
        val width = 2
        val height = 2
        val stride = width * 4 + 4
        // 每行两个像素：左上 (x+y*2) 的 R 通道递增，便于确认行序。
        val file = writeRing(width, height, stride) { x, y ->
            0xff000000.toInt() or ((x + y * 2 + 1) shl 16) or 0x00000203
        }
        val ring = requireNotNull(FrameRing.open(file))
        val out = IntArray(width * height)
        assertTrue(ring.latestInto(out, width, height))
        assertArrayEquals(
            intArrayOf(
                0xff010203.toInt(), 0xff020203.toInt(),
                0xff030203.toInt(), 0xff040203.toInt(),
            ),
            out,
        )
        // padding 的 0x7f 字节一个都不能被当成像素。
        assertFalse(out.any { (it and 0x00ff0000) == 0x007f0000 })
    }

    @Test
    fun `supports byte padding when a row is not word aligned`() {
        val width = 2
        val height = 3
        val stride = width * 4 + 1
        val expected = IntArray(width * height) { 0xff010203.toInt() + (it shl 16) }
        val file = writeRing(width, height, stride) { x, y -> expected[y * width + x] }
        val ring = requireNotNull(FrameRing.open(file))
        val out = IntArray(expected.size + 2) { 0x12345678 }
        assertTrue(ring.latestInto(out, width, height))
        assertArrayEquals(expected, out.copyOf(expected.size))
        assertEquals(0x12345678, out[expected.size])
        assertEquals(0x12345678, out[expected.size + 1])
    }

    @Test
    fun `converts the bgra byte order into argb words`() {
        val file = writeRing(width = 1, height = 1) { _, _ -> 0x44332211 }
        val ring = requireNotNull(FrameRing.open(file))
        val out = IntArray(1)
        assertTrue(ring.latestInto(out, 1, 1))
        // 内存里是 B=0x11, G=0x22, R=0x33, A=0x44；setPixels 要的是 0xAARRGGBB。
        assertEquals(0x44332211, out.single())
    }

    @Test
    fun `returns false while seq does not advance`() {
        val file = writeRing(width = 1, height = 1, seq = 1) { _, _ -> 0xff102030.toInt() }
        val ring = requireNotNull(FrameRing.open(file))
        val out = IntArray(1)
        assertTrue(ring.latestInto(out, 1, 1))
        assertEquals(0xff102030.toInt(), out.single())

        // 像素变了但 seq 没变：worker 还没发布新帧，读者必须当作「没有新帧」。
        setPixel(file, 0, 0, 4, 0xffaabbcc.toInt())
        assertFalse(ring.latestInto(out, 1, 1))
        assertEquals(0xff102030.toInt(), out.single())
    }

    @Test
    fun `returns true when seq advances and copies the new pixels`() {
        val file = writeRing(width = 1, height = 1, seq = 1) { _, _ -> 0xff102030.toInt() }
        val ring = requireNotNull(FrameRing.open(file))
        val out = IntArray(1)
        assertTrue(ring.latestInto(out, 1, 1))

        setPixel(file, 0, 0, 4, 0xffaabbcc.toInt())
        setSeq(file, 2)
        assertTrue(ring.latestInto(out, 1, 1))
        assertEquals(0xffaabbcc.toInt(), out.single())
    }

    @Test
    fun `discards a frame torn by a publish during the copy`() {
        val width = 2
        val height = 1
        val stride = width * 4
        val file = writeRing(width, height, stride, seq = 1) { _, _ -> 0xff102030.toInt() }
        val ring = requireNotNull(FrameRing.open(file))
        val out = IntArray(width * height)
        val torn = ring.latestIntoForTest(out, width, height) {
            // 拷贝进行到一半：worker 走到下一帧，seq 已推进（像素也开始被覆盖）。
            setPixel(file, 0, 0, stride, 0xffaabbcc.toInt())
            setSeq(file, 2)
        }
        assertFalse("拷到一半被发布的帧必须整帧丢弃，不能把撕裂画面提交出去", torn)

        // 复核失败时不能推进 lastSeq：把 seq 退回原值再取一次，若内部已记成 1 这里会返回 false。
        setSeq(file, 1)
        assertTrue("丢弃后必须允许下一拍重取同一帧", ring.latestInto(out, width, height))
        assertEquals(0xffaabbcc.toInt(), out[0])
    }

    @Test
    fun `seq zero means no published frame yet`() {
        val file = writeRing(width = 2, height = 1, seq = 0)
        val ring = requireNotNull(FrameRing.open(file))
        assertFalse(ring.latestInto(IntArray(2), 2, 1))
    }

    private fun setGuard(file: File, guard: Long) {
        RandomAccessFile(file, "rw").use {
            it.seek(40)
            it.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(guard).array())
        }
    }

    @Test
    fun `rejects legacy writer even when it ignores version`() {
        assertNull(FrameRing.open(writeRing(width = 2, height = 1, magic = "HFRM", version = 1)))
    }

    @Test
    fun `writer in progress is rejected before touching target`() {
        val file = writeRing(width = 2, height = 1)
        setGuard(file, 3)
        val ring = requireNotNull(FrameRing.open(file))
        val out = IntArray(2) { 0x12345678 }
        assertFalse(ring.latestInto(out, 2, 1))
        assertArrayEquals(IntArray(2) { 0x12345678 }, out)
    }

    @Test
    fun `unfinished overwrite with unchanged publication count is discarded`() {
        val file = writeRing(width = 2, height = 1) { _, _ -> 0xff102030.toInt() }
        val ring = requireNotNull(FrameRing.open(file))
        val out = IntArray(2)
        val shown = ring.latestIntoForTest(out, 2, 1, beforeCopy = {
            setGuard(file, 3) // producer starts N+1 but has not published seq=2
            setPixel(file, 0, 0, 8, 0xffaabbcc.toInt())
        }) {}
        assertFalse("a mixed slot must not be accepted just because seq is still 1", shown)
        setPixel(file, 1, 0, 8, 0xffaabbcc.toInt())
        setSeq(file, 2)
        assertTrue(ring.latestInto(out, 2, 1))
        assertArrayEquals(IntArray(2) { 0xffaabbcc.toInt() }, out)
    }

    @Test
    fun `writer starting after pixel copy but before final guard is discarded`() {
        val file = writeRing(width = 2, height = 1)
        val ring = requireNotNull(FrameRing.open(file))
        assertFalse(ring.latestIntoForTest(IntArray(2), 2, 1) { setGuard(file, 3) })
    }

    @Test
    fun `geometry is checked on every publication`() {
        val file = writeRing(width = 2, height = 1)
        val ring = requireNotNull(FrameRing.open(file))
        setHeaderInt(file, 16, 4)
        val out = IntArray(2) { 0x12345678 }
        assertFalse(ring.latestInto(out, 2, 1))
        assertArrayEquals(IntArray(2) { 0x12345678 }, out)
    }

    @Test
    fun `dimension mismatch fails safely and leaves the target alone`() {
        val file = writeRing(width = 2, height = 2, seq = 1)
        val ring = requireNotNull(FrameRing.open(file))
        val out = IntArray(8) { 0x11223344 }
        assertFalse(ring.latestInto(out, 4, 2)) // 宽不符
        assertFalse(ring.latestInto(out, 2, 4))   // 高不符
        assertArrayEquals(IntArray(8) { 0x11223344 }, out)
    }

    @Test
    fun `target smaller than the frame fails safely`() {
        val file = writeRing(width = 2, height = 2, seq = 1)
        val ring = requireNotNull(FrameRing.open(file))
        assertFalse(ring.latestInto(IntArray(3), 2, 2))
    }
}
