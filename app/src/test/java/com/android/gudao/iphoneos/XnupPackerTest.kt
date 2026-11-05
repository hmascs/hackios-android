package com.android.gudao.iphoneos

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The payload writer was rewritten from "build it all in RAM" to "stream into the file". These
 * tests pin the bytes so the rewrite cannot silently change the on-disk format the XNU loader
 * expects.
 */
class XnupPackerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `streamed payload is byte identical to the in-memory one`() {
        val kernel = tmp.newFile("kernel").apply { writeBytes(ByteArray(37) { it.toByte() }) }
        val ramdisk = tmp.newFile("root.apfs").apply { writeBytes(ByteArray(1000) { (it % 251).toByte() }) }
        val out = File(tmp.root, "payload.bin")

        XnupPacker.packTo(
            dest = out,
            kernel = kernel,
            ramdisk = ramdisk,
            cmdline = "loader_return_after=1200 cpus=2",
            rdres = "1f000000",
        )
        val expected = XnupPacker.pack(
            mapOf("kernel" to kernel.readBytes(), "ramdisk" to ramdisk.readBytes()),
            cmdline = "loader_return_after=1200 cpus=2",
            rdres = "1f000000",
        )
        assertArrayEquals(expected, out.readBytes())
    }

    @Test
    fun `padded entry sizes are 16 byte aligned`() {
        val kernel = tmp.newFile("k").apply { writeBytes(ByteArray(16)) }
        val ramdisk = tmp.newFile("r").apply { writeBytes(ByteArray(17)) }
        val out = File(tmp.root, "p.bin")
        XnupPacker.packTo(out, kernel, ramdisk, cmdline = null, rdres = null)

        // header(8) + entry(24 + 16 padded) + entry(24 + 32 padded) + terminator(24)
        assertEquals(8 + 40 + 56 + 24L, out.length())
        val magic = out.readBytes().copyOfRange(0, 4)
        assertEquals("XNUP", String(magic))
    }

    @Test
    fun `command line and rdres are optional`() {
        val kernel = tmp.newFile("k2").apply { writeBytes(ByteArray(8)) }
        val ramdisk = tmp.newFile("r2").apply { writeBytes(ByteArray(8)) }
        val out = File(tmp.root, "p2.bin")
        XnupPacker.packTo(out, kernel, ramdisk, cmdline = " ", rdres = "")
        assertEquals(8 + 40 + 40 + 24L, out.length())
    }
}
