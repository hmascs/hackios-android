package com.android.gudao.iphoneos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boot-phase detection drives the whole "where is it now" banner, so the strings it keys on are
 * pinned here rather than rediscovered on a device.
 */
class BootPhaseTest {

    @Test
    fun `empty log is stage zero`() {
        val r = BootPhase.analyze(null)
        assertEquals(0, r.stage)
        assertFalse(r.stuck)
    }

    @Test
    fun `loader output reaches stage two`() {
        val r = BootPhase.analyze("XNULOADER: display 1125 x 2436 at 0x...\nboot-args: ...")
        assertEquals(2, r.stage)
    }

    @Test
    fun `launchd reaches stage four`() {
        val log = """
            XNULOADER: display 1125 x 2436
            Darwin Kernel Version 22.5.0: xnu-8796.101.5
            launchd: Bootstrapping
        """.trimIndent()
        assertEquals(4, BootPhase.analyze(log).stage)
    }

    @Test
    fun `springboard plus backboardd reaches the lock screen stage`() {
        val log = """
            XNULOADER: display 1125 x 2436
            Darwin Kernel Version 22.5.0
            launchd: Bootstrapping
            system bootstrapper finished: 36 checks, 0 failed
            SPAWNED SpringBoard
            backboardd: BackBoard initialized
        """.trimIndent()
        val r = BootPhase.analyze(log)
        assertTrue(r.stage >= 6)
        assertFalse(r.stuck)
    }

    @Test
    fun `root directory EACCES is flagged as stuck`() {
        val log = """
            launchd: Bootstrapping
            SpringBoard: failed to open root directory: /: 13
            backboardd: running
        """.trimIndent()
        val r = BootPhase.analyze(log)
        assertTrue(r.stuck)
        assertTrue(r.rootDir!!.contains("EACCES"))
    }

    @Test
    fun `persona noise is reported as non blocking`() {
        val log = "launchd: Unable to find persona with type 6\nnotifyd started"
        val r = BootPhase.analyze(log)
        assertFalse(r.stuck)
        assertTrue(r.persona!!.contains("非阻塞"))
    }

    /**
     * 2026-11-04 的真机日志：串口一切正常、2310 帧全黑。原因是 App 自己把 `fbscale=1` 写进了
     * boot-args，WindowServer 只建 375×812 的帧缓冲，ramfb 却是 1125×2436。
     */
    private val blackByScale = """
        XNULOADER: display 1125 x 2436 at 0x1fb6bb000
        Darwin Kernel Version 22.5.0
        launchd: Bootstrapping
        mg_test: main-screen-scale = <CFNumber 0x1 [0x2]>{value = +1.0000000000, type = kCFNumberFloat32Type}
        StartWindowServer: initial main screen size:{375, 812} scale:1
        SpringBoard
    """.trimIndent()

    @Test
    fun `scale 1 explains a black screen instead of claiming the lock screen`() {
        val r = BootPhase.analyze(blackByScale)
        assertNotNull(r.display)
        assertTrue(r.display!!.contains("渲染倍率 1x"))
        assertTrue(r.display!!.contains("1125×2436"))
        assertTrue(r.display!!.contains("全黑"))
        // backboardd 起来只到阶段 6；第 7 级现在由真实帧决定，不再由文本升上去。
        assertEquals(6, r.stage)
    }

    @Test
    fun `scale 3 is reported as consistent`() {
        val ok = blackByScale
            .replace("value = +1.0000000000", "value = +3.0000000000")
            .replace("{375, 812} scale:1", "{1125, 2436} scale:3")
        val r = BootPhase.analyze(ok)
        assertNotNull(r.display)
        assertTrue(r.display!!.contains("一致"))
        assertFalse(r.display!!.contains("⚠"))
    }

    @Test
    fun `no display lines means no claim`() {
        assertNull(BootPhase.analyze("Darwin Kernel Version 22.5.0\nlaunchd started").display)
    }

    // ---- 网络状态（合并上游 36a08e7）：判据全部来自内核的 "net:" 行 ------------------------

    @Test
    fun `no virtio device is reported as qemu side`() {
        val r = BootPhase.analyze("Darwin Kernel Version\nnet: no virtio network device")
        assertNotNull(r.net)
        assertTrue(r.net!!.contains("未提供 virtio 网卡"))
    }

    @Test
    fun `recognized device without en0 is reported as not attached`() {
        val r = BootPhase.analyze("net: virtio network device in slot 0")
        assertNotNull(r.net)
        assertTrue(r.net!!.contains("没挂上"))
    }

    @Test
    fun `en0 attached carries the frame counters`() {
        val r = BootPhase.analyze(
            "net: virtio network device in slot 0\n" +
                "net: en0 attached on 10.0.2.15, frames sent 42 received 7"
        )
        assertNotNull(r.net)
        assertTrue(r.net!!.contains("en0 已挂上"))
        assertTrue(r.net!!.contains("frames sent 42"))
    }

    @Test
    fun `no net lines means nothing claimed`() {
        assertNull(BootPhase.analyze("launchd: Bootstrapping").net)
    }
}
