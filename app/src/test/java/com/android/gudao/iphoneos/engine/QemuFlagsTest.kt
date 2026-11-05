package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 改 QEMU 参数的守门测试。
 *
 * 这些用例对应的是"模型提了一个看起来很合理的参数"时会发生什么：`-S` 看起来像 "start"，
 * 实际是把 CPU 冻住；`-D /sdcard/x` 会把日志写到应用沙箱外；`cpus=4` 会和界面上的核心数打架。
 * 每一种都必须被拒绝，并且拒绝理由要能直接回给模型。
 */
class QemuFlagsTest {

    @Test
    fun `accepts the diagnostic flags that matter`() {
        val p = QemuFlags.parseDiagnostics("-d int,guest_errors -D qemu-debug.log -no-shutdown")
        assertTrue(p.ok)
        assertEquals(listOf("-d", "int,guest_errors", "-D", "qemu-debug.log", "-no-shutdown"), p.values)
    }

    @Test
    fun `empty input means no extra flags`() {
        assertEquals(emptyList<String>(), QemuFlags.parseDiagnostics("").values)
        assertNull(QemuFlags.parseDiagnostics("   ").error)
    }

    @Test
    fun `rejects -S because it freezes the guest before boot`() {
        val p = QemuFlags.parseDiagnostics("-S")
        assertFalse(p.ok)
        assertTrue(p.error!!.contains("冻结"))
    }

    @Test
    fun `rejects device topology changes`() {
        for (bad in listOf("-device virtio-net-device", "-drive file=x,if=virtio", "-M virt", "-smp 4")) {
            val p = QemuFlags.parseDiagnostics(bad)
            assertFalse("应该拒绝 $bad", p.ok)
        }
    }

    @Test
    fun `rejects a log path`() {
        val p = QemuFlags.parseDiagnostics("-D /sdcard/qemu.log")
        assertFalse(p.ok)
        assertTrue(p.error!!.contains("纯文件名"))
    }

    @Test
    fun `gdb only accepts a tcp port in range`() {
        assertTrue(QemuFlags.parseDiagnostics("-gdb tcp::1234").ok)
        assertFalse(QemuFlags.parseDiagnostics("-gdb tcp::80").ok)
        assertFalse(QemuFlags.parseDiagnostics("-gdb /tmp/sock").ok)
    }

    @Test
    fun `accepts ordinary boot args`() {
        val p = QemuFlags.parseBootArgs("debug=0x144 -v serial=1")
        assertTrue(p.ok)
        assertEquals(listOf("debug=0x144", "-v", "serial=1"), p.values)
    }

    @Test
    fun `rejects boot args managed by the app`() {
        for (bad in listOf("cpus=4", "rd=0x1", "epoch=1", "loader_return_after=0")) {
            val p = QemuFlags.parseBootArgs(bad)
            assertFalse("应该拒绝 $bad", p.ok)
        }
    }

    @Test
    fun `rejects shell metacharacters in boot args`() {
        assertFalse(QemuFlags.parseBootArgs("debug=1; rm -rf /").ok)
        assertFalse(QemuFlags.parseBootArgs("a=\$(id)").ok)
    }

    @Test
    fun `rejects too many tokens`() {
        val many = (1..12).joinToString(" ") { "k$it=1" }
        assertFalse(QemuFlags.parseBootArgs(many).ok)
    }
}
