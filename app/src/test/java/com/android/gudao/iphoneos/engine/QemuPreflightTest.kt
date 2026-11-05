package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * B1: QEMU used to be started without ever rebuilding payload.bin / ram.img, so a cold start (or a
 * start after recompiling the images) died instantly with no explanation.
 */
class QemuPreflightTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun images(): Pair<File, File> {
        val sys = File(tmp.root, "system.apfs").apply { writeBytes(ByteArray(64)) }
        val root = File(tmp.root, "root.apfs").apply { writeBytes(ByteArray(32)) }
        return sys to root
    }

    @Test
    fun `payload is required when it does not exist`() {
        val (sys, root) = images()
        assertTrue(QemuPreflight.needsPrepare(tmp.root, sys, root, smp = 1, rev = 3))
    }

    @Test
    fun `stamp makes the payload reusable`() {
        val (sys, root) = images()
        File(tmp.root, "payload.bin").writeBytes(ByteArray(16))
        File(tmp.root, "ram.img").writeBytes(ByteArray(16))
        QemuPreflight.writeStamp(tmp.root, sys, root, smp = 1, rev = 3)
        assertFalse(QemuPreflight.needsPrepare(tmp.root, sys, root, smp = 1, rev = 3))
    }

    @Test
    fun `changing the core count invalidates the payload`() {
        val (sys, root) = images()
        File(tmp.root, "payload.bin").writeBytes(ByteArray(16))
        File(tmp.root, "ram.img").writeBytes(ByteArray(16))
        QemuPreflight.writeStamp(tmp.root, sys, root, smp = 1, rev = 3)
        // The cpus=N boot-arg is baked into payload.bin, so a different -smp needs a rebuild.
        assertTrue(QemuPreflight.needsPrepare(tmp.root, sys, root, smp = 4, rev = 3))
    }

    @Test
    fun `recompiled images invalidate the payload`() {
        val (sys, root) = images()
        File(tmp.root, "payload.bin").writeBytes(ByteArray(16))
        File(tmp.root, "ram.img").writeBytes(ByteArray(16))
        QemuPreflight.writeStamp(tmp.root, sys, root, smp = 1, rev = 3)
        root.writeBytes(ByteArray(48))          // new image content/size
        assertTrue(QemuPreflight.needsPrepare(tmp.root, sys, root, smp = 1, rev = 3))
    }

    @Test
    fun `stamp includes the image revision`() {
        val (sys, root) = images()
        File(tmp.root, "payload.bin").writeBytes(ByteArray(16))
        File(tmp.root, "ram.img").writeBytes(ByteArray(16))
        QemuPreflight.writeStamp(tmp.root, sys, root, smp = 1, rev = 3)
        assertTrue(QemuPreflight.needsPrepare(tmp.root, sys, root, smp = 1, rev = 4))
    }

    @Test
    fun `empty payload counts as missing`() {
        val (sys, root) = images()
        File(tmp.root, "payload.bin").writeBytes(ByteArray(0))
        File(tmp.root, "ram.img").writeBytes(ByteArray(16))
        QemuPreflight.writeStamp(tmp.root, sys, root, smp = 1, rev = 3)
        assertTrue(QemuPreflight.needsPrepare(tmp.root, sys, root, smp = 1, rev = 3))
        assertEquals(0L, File(tmp.root, "payload.bin").length())
    }

    @Test
    fun `a stamp from before the cmdline profile bump is rebuilt`() {
        // fbscale 这类托管参数烘在 payload.bin 里，老缓存必须失效，否则"改了代码设备上没变化"。
        val (sys, root) = images()
        File(tmp.root, "payload.bin").writeBytes(ByteArray(16))
        File(tmp.root, "ram.img").writeBytes(ByteArray(16))
        File(tmp.root, ".payload-stamp").writeText(
            "sys=${sys.length()}:${sys.lastModified()};root=${root.length()}:${root.lastModified()};smp=1;rev=3"
        )
        assertTrue(QemuPreflight.needsPrepare(tmp.root, sys, root, smp = 1, rev = 3))
        assertTrue(QemuPreflight.CMDLINE_PROFILE >= 2)
    }
}
