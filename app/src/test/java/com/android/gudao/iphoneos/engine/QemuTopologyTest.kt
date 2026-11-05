package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.24.0 多核架构的参数链路。云端 QEMU 8.2.2 已用 QMP 验证：这些字符串真能拉起对应数量的
 * vCPU（见 docs/engineering-notes.md 第 16 节）；这里保证代码永远生成同样的字符串。
 */
class QemuTopologyTest {

    @Test
    fun `default is two guest vCPUs with an eight core host pool`() {
        // 0.24.0 的六核档保留为「稳定上限」，不再是默认值；0.26.0 起两者解耦。
        assertEquals(6, QemuTopology.STABLE_CORES)
        assertEquals(2, QemuTopology.GUEST_VCPU_DEFAULT)
        assertEquals(2, com.android.gudao.iphoneos.Constants.SMP_DEFAULT)
        assertEquals(6, com.android.gudao.iphoneos.Constants.SMP_STABLE_MAX)
        assertEquals(8, com.android.gudao.iphoneos.Constants.HOST_CORES_DEFAULT)
        assertEquals(2, com.android.gudao.iphoneos.Constants.HOST_CORES_MIN)
        assertEquals(8, com.android.gudao.iphoneos.Constants.HOST_CORES_MAX)
        assertTrue(com.android.gudao.iphoneos.Constants.MTTCG_DEFAULT)
    }

    @Test
    fun `mttcg smp arg uses explicit topology`() {
        assertEquals("6,sockets=1,cores=6,threads=1", QemuTopology.smpArg(6, true))
        assertEquals("1,sockets=1,cores=1,threads=1", QemuTopology.smpArg(1, true))
        assertEquals("8,sockets=1,cores=8,threads=1", QemuTopology.smpArg(8, true))
    }

    @Test
    fun `single thread TCG keeps flat smp as fallback baseline`() {
        assertEquals("1", QemuTopology.smpArg(1, false))
        assertEquals("4", QemuTopology.smpArg(4, false))
    }

    @Test
    fun `mttcg accel enables thread multi and scales TB cache`() {
        val six = QemuTopology.accelArg(6, true)
        assertTrue(six.startsWith("tcg,thread=multi,tb-size="))
        assertEquals(1020, QemuTopology.tbSize(6))
        assertEquals(384, QemuTopology.tbSize(1))       // 不低于旧基线
        assertEquals(1360, QemuTopology.tbSize(8))
        assertEquals("tcg,tb-size=384", QemuTopology.accelArg(6, false))
    }

    @Test
    fun `TB cache grows monotonically with cores`() {
        var prev = 0
        for (c in 1..8) {
            assertTrue(QemuTopology.tbSize(c) >= prev)
            prev = QemuTopology.tbSize(c)
        }
    }

    @Test
    fun `cpus boot arg always equals the smp vCPU total`() {
        for (c in 1..8) {
            assertEquals(c, QemuTopology.cpusBootArg(c).toInt())
            // 拓扑里 cores*threads*sockets 必须等于传给内核的 cpus=N
            val smp = QemuTopology.smpArg(c, true)
            val cores = Regex("cores=(\\d+)").find(smp)!!.groupValues[1].toInt()
            assertEquals(cores, QemuTopology.cpusBootArg(c).toInt())
        }
    }

    @Test
    fun `out of range cores are clamped`() {
        assertEquals(1, QemuTopology.normalize(-3))
        assertEquals(8, QemuTopology.normalize(99))
        assertEquals("1,sockets=1,cores=1,threads=1", QemuTopology.smpArg(0, true))
        assertEquals("8,sockets=1,cores=8,threads=1", QemuTopology.smpArg(50, true))
    }

    @Test
    fun `fallback steps down by half with a floor of two`() {
        assertEquals(3, QemuTopology.fallbackCores(6))
        assertEquals(4, QemuTopology.fallbackCores(8))
        assertEquals(2, QemuTopology.fallbackCores(3))
        assertNull(QemuTopology.fallbackCores(2))
        assertNull(QemuTopology.fallbackCores(1))
    }

    @Test
    fun `fallback never returns one core`() {
        // 0.23.0 的按钮把卡住一律改回 1 核，丢掉并行翻译能力；新策略最低 2 核。
        for (c in 3..8) {
            val next = QemuTopology.fallbackCores(c)
            assertTrue("fallback($c) 不该是 1 核", next == null || next >= 2)
        }
    }

    @Test
    fun `describe mentions MTTCG when enabled`() {
        assertTrue(QemuTopology.describe(6, true).contains("MTTCG"))
        assertTrue(QemuTopology.describe(6, true).contains("1020"))
        assertTrue(QemuTopology.describe(6, false).contains("单线程"))
        assertFalse(QemuTopology.describe(6, false).contains("thread=multi"))
    }
}
