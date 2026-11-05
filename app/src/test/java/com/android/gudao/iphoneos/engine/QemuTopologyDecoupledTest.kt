package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.26.0 的核心契约：**Guest vCPU 与宿主算力池正交**。
 *
 * 用户要的是「只告诉 QEMU 两个处理器保证稳定，但宿主给足算力」。在 TCG 里这落地为：
 *  - `-smp` / `cpus=N` 只由 Guest vCPU 决定；
 *  - `tb-size`（翻译缓存预算）与 CPU 亲和性掩码只由宿主算力池决定；
 *  - 因此 2 个 vCPU 也能拿到 8 核规格的 TB 缓存，减少重复翻译造成的 CPU 空转。
 *
 * 云端 QEMU 8.2.2 已用 `taskset -c 0-7` + `-smp 2` + `tb-size=1360` 实测启动成功
 * （见 cloud-verify-025/qemu-decoupled/）；这里保证代码永远生成同样的字符串。
 */
class QemuTopologyDecoupledTest {

    @Test
    fun `tb size is driven by host pool not by guest vCPU count`() {
        // 2 vCPU + 8 核算力池 → 8 核规格的翻译缓存（1360 MiB），不再是 2 核的 384 MiB
        val decoupled = QemuTopology.accelArg(cores = 2, hostCores = 8, mttcg = true)
        assertEquals("tcg,thread=multi,tb-size=1360", decoupled)

        // 同样的 2 vCPU，算力池降到 2 核 → TB 降到 384（旧基线）
        val small = QemuTopology.accelArg(cores = 2, hostCores = 2, mttcg = true)
        assertEquals("tcg,thread=multi,tb-size=384", small)

        // guest 从 2 涨到 6，只要算力池不变，TB 规格不变 —— 证明二者已解耦
        assertEquals(
            decoupled,
            QemuTopology.accelArg(cores = 6, hostCores = 8, mttcg = true),
        )
        assertTrue(QemuTopology.tbSize(8) > QemuTopology.tbSize(2))
    }

    @Test
    fun `legacy two arg accel signature keeps the 0 24 behavior`() {
        // 旧调用方（含 0.24.0 单测）语义必须零回归：TB 随 vCPU 数放大
        assertEquals("tcg,thread=multi,tb-size=1020", QemuTopology.accelArg(6, true))
        assertEquals("tcg,thread=multi,tb-size=384", QemuTopology.accelArg(1, true))
        assertEquals("tcg,tb-size=384", QemuTopology.accelArg(6, false))
        // 关掉 MTTCG 时算力池也不起作用，回到单线程基线
        assertEquals("tcg,tb-size=384", QemuTopology.accelArg(2, 8, false))
    }

    @Test
    fun `explicit tb size overrides the host pool for the safe retry`() {
        // 0.29.2：QEMU 在打开 -serial 前 exit=1 时，保守档用 512 MiB + 单线程重试，
        // 不再按 8 核算力池分配 1360 MiB。
        assertEquals("tcg,tb-size=512", QemuTopology.accelArgWith(mttcg = false, tbMiB = 512))
        assertEquals("tcg,thread=multi,tb-size=512", QemuTopology.accelArgWith(mttcg = true, tbMiB = 512))
        // 默认路径必须仍然等价于旧 accelArg（零回归）
        assertEquals(
            QemuTopology.accelArg(2, 8, true),
            QemuTopology.accelArgWith(mttcg = true, tbMiB = QemuTopology.tbSize(8)),
        )
    }

    @Test
    fun `smp arg and cpus boot arg never depend on the host pool`() {
        val base = QemuTopology.smpArg(2, true)
        assertEquals("2,sockets=1,cores=2,threads=1", base)
        assertEquals("2", QemuTopology.cpusBootArg(2))
        // 宿主算力池是另一个维度，不进 -smp，也不进 payload 的 cpus=N
        for (host in 2..8) {
            assertEquals(base, QemuTopology.smpArg(2, true))
            assertEquals("2", QemuTopology.cpusBootArg(2))
            assertTrue(QemuTopology.affinityMask(host).isNotEmpty())
        }
        // smpArg 只接受一个 cores 参数，本身没有 hostCores 入口（编译期即隔离）
        assertEquals("8,sockets=1,cores=8,threads=1", QemuTopology.smpArg(8, true))
    }

    @Test
    fun `host pool is clamped to 2 to 8 and mask is zero based`() {
        assertEquals(2, QemuTopology.normalizeHostCores(-5))
        assertEquals(2, QemuTopology.normalizeHostCores(1))
        assertEquals(8, QemuTopology.normalizeHostCores(99))
        assertEquals("0-1", QemuTopology.affinityMask(2))
        assertEquals("0-7", QemuTopology.affinityMask(8))
        assertEquals("0-7", QemuTopology.affinityMask(50))  // 越界也安全
    }

    @Test
    fun `affinity prefix is a pure prefix and degrades to empty`() {
        assertEquals(emptyList<String>(), QemuTopology.affinityPrefix(8, null))
        assertEquals(emptyList<String>(), QemuTopology.affinityPrefix(8, emptyList()))
        assertEquals(
            listOf("/system/bin/taskset", "-c", "0-7"),
            QemuTopology.affinityPrefix(8, listOf("/system/bin/taskset")),
        )
        // toybox 是「两词命令」，也要能正确拼接
        assertEquals(
            listOf("/system/bin/toybox", "taskset", "-c", "0-3"),
            QemuTopology.affinityPrefix(4, listOf("/system/bin/toybox", "taskset")),
        )
    }

    @Test
    fun `three arg describe reports both dimensions while legacy describe stays valid`() {
        val text = QemuTopology.describe(cores = 2, hostCores = 8, mttcg = true)
        assertTrue(text.contains("2 vCPU"))
        assertTrue(text.contains("宿主 8 核"))
        assertTrue(text.contains("TB 1360 MiB"))
        assertTrue(text.contains("0-7"))
        // 旧两参签名按 vCPU 数当算力池，语义与 0.24.0 一致
        assertTrue(QemuTopology.describe(6, true).contains("TB 1020 MiB"))
        assertFalse(QemuTopology.describe(6, false).contains("thread=multi"))
    }

    @Test
    fun `fallback only lowers guest vCPU and never touches the host pool`() {
        // 降核只改 guest；宿主算力池恒由设置独立提供，没有「降算力池」的路径
        assertEquals(4, QemuTopology.fallbackCores(8))
        assertEquals(2, QemuTopology.fallbackCores(4))
        assertNull(QemuTopology.fallbackCores(2))
        assertEquals(8, QemuTopology.HOST_CORES_DEFAULT)
    }
}
