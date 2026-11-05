package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 0.24.0 的**启动提速核心**：把 ram.img（2.5 GB 拷贝）与 payload.bin（几十字节的 cpus=N）
 * 分开缓存。改核数只该让 payload 变脏，绝不能让 ram.img 变脏 —— 这正是「反复调参 / 自动降核
 * 重启」从「每次重拷 2.5 GB」变成「重打包一个 payload」的依据。
 */
class QemuPreflightSplitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun images(): Pair<File, File> {
        val sys = File(tmp.root, "system.apfs").apply { writeBytes(ByteArray(64)) }
        val root = File(tmp.root, "root.apfs").apply { writeBytes(ByteArray(32)) }
        return sys to root
    }

    private fun seedBuilt(sys: File, root: File, smp: Int = 6, rev: Int = 3) {
        File(tmp.root, "payload.bin").writeBytes(ByteArray(16))
        File(tmp.root, "ram.img").writeBytes(ByteArray(16))
        QemuPreflight.writeStamp(tmp.root, sys, root, smp, rev)
    }

    @Test
    fun `writeStamp writes both stamps`() {
        val (sys, root) = images()
        seedBuilt(sys, root)
        assertTrue(File(tmp.root, ".ram-stamp").isFile)
        assertTrue(File(tmp.root, ".payload-stamp").isFile)
    }

    @Test
    fun `changing cores dirties payload but NOT the 2_5GB ram image`() {
        val (sys, root) = images()
        seedBuilt(sys, root, smp = 6)
        assertTrue(QemuPreflight.needsPayload(tmp.root, sys, root, 3, 3))
        assertFalse("改核数不该重拷 ram.img", QemuPreflight.needsRamImage(tmp.root, sys, 3))
        // 总开关仍为 true（payload 要重建），但 QemuRunner 只重建脏的那一个。
        assertTrue(QemuPreflight.needsPrepare(tmp.root, sys, root, 3, 3))
    }

    @Test
    fun `changing boot args dirties payload only`() {
        val (sys, root) = images()
        seedBuilt(sys, root)
        assertTrue(QemuPreflight.needsPayload(tmp.root, sys, root, 6, 3, "-v"))
        assertFalse(QemuPreflight.needsRamImage(tmp.root, sys, 3))
    }

    @Test
    fun `system image change dirties BOTH`() {
        val (sys, root) = images()
        seedBuilt(sys, root)
        sys.writeBytes(ByteArray(96))   // 重编镜像
        assertTrue(QemuPreflight.needsRamImage(tmp.root, sys, 3))
        assertTrue(QemuPreflight.needsPayload(tmp.root, sys, root, 6, 3))
    }

    @Test
    fun `image revision bump dirties both`() {
        val (sys, root) = images()
        seedBuilt(sys, root, rev = 3)
        assertTrue(QemuPreflight.needsRamImage(tmp.root, sys, 4))
        assertTrue(QemuPreflight.needsPayload(tmp.root, sys, root, 6, 4))
    }

    @Test
    fun `cold start needs both`() {
        val (sys, root) = images()
        assertTrue(QemuPreflight.needsRamImage(tmp.root, sys, 3))
        assertTrue(QemuPreflight.needsPayload(tmp.root, sys, root, 6, 3))
    }

    @Test
    fun `ram stamp is independent of cores and boot args`() {
        val (sys, _) = images()
        val a = QemuPreflight.ramStampFor(sys, 3)
        // 核数与 boot-args 都不参与 ram stamp 的计算
        assertEquals(a, QemuPreflight.ramStampFor(sys, 3))
        assertTrue(a.startsWith("sys="))
        assertTrue(!a.contains("smp="))
        assertTrue(!a.contains("boot="))
    }

    @Test
    fun `payload stamp still carries the cmdline profile`() {
        val (sys, root) = images()
        val stamp = QemuPreflight.payloadStampFor(sys, root, 6, 3)
        assertTrue(stamp.contains("prof=${QemuPreflight.CMDLINE_PROFILE}"))
        assertTrue(QemuPreflight.CMDLINE_PROFILE >= 3)   // 0.24.0 让旧 payload 失效
    }

    @Test
    fun `legacy single stamp is migrated away`() {
        val (sys, root) = images()
        File(tmp.root, "payload.bin").writeBytes(ByteArray(16))
        File(tmp.root, "ram.img").writeBytes(ByteArray(16))
        // 0.23.0 只留下 .payload-stamp、没有 .ram-stamp
        File(tmp.root, ".payload-stamp").writeText("sys=64:0;root=32:0;smp=1;rev=3;prof=2")
        QemuPreflight.migrateLegacyStamp(tmp.root)
        assertFalse("旧单一 stamp 应被删除", File(tmp.root, ".payload-stamp").exists())
        assertTrue(QemuPreflight.needsPrepare(tmp.root, sys, root, 6, 3))
    }
}
