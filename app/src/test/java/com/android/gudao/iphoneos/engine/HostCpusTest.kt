package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.29.5：宿主算力池不再写死 `0-(N-1)`，而是先读系统放行的核、再按频率挑大核。
 *
 * 两个真机教训驱动了这些断言：
 *  - big.LITTLE 上编号最小的核通常是小核，`0-3` 会把 QEMU 钉死在小核上；
 *  - 系统 cpuset 可能只放行一部分核，掩码越界会让 setaffinity 整条失败、算力池直接降级。
 */
class HostCpusTest {

    @Test
    fun `parseAllowed reads Cpus_allowed_list`() {
        val status = """
            Name:	com.android.gudao.iphoneos
            State:	S (sleeping)
            Cpus_allowed_list:	0-6,8
            Mems_allowed_list:	0
        """.trimIndent()
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 8), HostCpus.parseAllowed(status))
    }

    @Test
    fun `parseAllowed returns empty when the field is missing`() {
        assertTrue(HostCpus.parseAllowed("Name:\tfoo\n").isEmpty())
    }

    @Test
    fun `parseCpuList tolerates single cores reversed ranges and junk`() {
        assertEquals(listOf(0, 1, 2, 3), HostCpus.parseCpuList("0-3"))
        assertEquals(listOf(7), HostCpus.parseCpuList("7"))
        assertEquals(listOf(0, 1, 2), HostCpus.parseCpuList("2-0"))
        assertEquals(listOf(0, 3, 4, 5), HostCpus.parseCpuList("0, 3-5,,"))
        assertTrue(HostCpus.parseCpuList("x-y").isEmpty())
    }

    @Test
    fun `formatCpuList groups runs`() {
        assertEquals("0-3", HostCpus.formatCpuList(listOf(0, 1, 2, 3)))
        assertEquals("0,2,4", HostCpus.formatCpuList(listOf(0, 2, 4)))
        assertEquals("4-7", HostCpus.formatCpuList(listOf(7, 6, 5, 4)))
        assertEquals("", HostCpus.formatCpuList(emptyList()))
    }

    @Test
    fun `rankByFreq puts the fastest cores first and breaks ties by index`() {
        val ranked = HostCpus.rankByFreq(mapOf(0 to 1_800_000L, 4 to 2_800_000L, 5 to 2_800_000L, 1 to 1_800_000L))
        assertEquals(listOf(4, 5, 0, 1), ranked)
    }

    @Test
    fun `chooseCores prefers big cores inside the allowed set`() {
        val allowed = (0..7).toList()
        val ranked = listOf(4, 5, 6, 7, 0, 1, 2, 3)
        assertEquals(listOf(4, 5, 6, 7), HostCpus.chooseCores(allowed, ranked, 4))
    }

    @Test
    fun `chooseCores never leaves the allowed set`() {
        // 系统只放行 0-3（典型的 top-app cpuset），就算大核在 4-7 也不能选。
        val allowed = listOf(0, 1, 2, 3)
        val ranked = listOf(4, 5, 6, 7, 0, 1, 2, 3)
        assertEquals(listOf(0, 1, 2, 3), HostCpus.chooseCores(allowed, ranked, 4))
        assertEquals(listOf(0, 1, 2, 3), HostCpus.chooseCores(allowed, ranked, 8))
    }

    @Test
    fun `chooseCores returns everything when the pool covers the device`() {
        val allowed = listOf(0, 1, 2, 3)
        assertEquals(allowed, HostCpus.chooseCores(allowed, allowed, 8))
        assertTrue(HostCpus.chooseCores(emptyList(), emptyList(), 4).isEmpty())
    }

    @Test
    fun `affinityMask accepts an explicit core list`() {
        assertEquals("4-7", QemuTopology.affinityMask(listOf(4, 5, 6, 7)))
        assertEquals("0-3", QemuTopology.affinityMask(4))
    }

    @Test
    fun `affinityPrefix skips the wrapper when there is no mask`() {
        val command = listOf("/system/bin/taskset")
        assertTrue(QemuTopology.affinityPrefix(command, "").isEmpty())
        assertTrue(QemuTopology.affinityPrefix(null, "4-7").isEmpty())
        assertEquals(
            listOf("/system/bin/taskset", "-c", "4-7"),
            QemuTopology.affinityPrefix(command, "4-7"),
        )
    }

    @Test
    fun `resolve pins the big cores and keeps the syntax that worked`() {
        val topo = HostCpus.Topology(
            allowed = (0..7).toList(),
            ranked = listOf(4, 5, 6, 7, 0, 1, 2, 3),
            big = listOf(4, 5, 6, 7),
            source = "sysfs",
        )
        val probed = mutableListOf<List<String>>()
        val r = QemuAffinity.resolve(4, shell = "/bin/sh", topology = topo) { probed += it; "Cpus_allowed_list:\t4-7\n" }
        assertEquals("/system/bin/taskset", r.command?.first())
        assertEquals("4-7", r.mask)
        assertEquals(listOf("-c", "4-7"), r.prefix.subList(1, 3))
        assertTrue(r.note.contains("大核"))
    }

    @Test
    fun `resolve does not pin when the system already allows every core`() {
        val topo = HostCpus.Topology((0..7).toList(), (0..7).toList(), listOf(4, 5, 6, 7), "sysfs")
        var called = false
        val r = QemuAffinity.resolve(8, shell = "/bin/sh", topology = topo) { called = true; "Cpus_allowed_list:\t0-7\n" }
        assertNull(r.command)
        assertFalse(called)
        assertEquals("", r.mask)
    }

    @Test
    fun `resolve never pins without topology`() {
        val r = QemuAffinity.resolve(4, shell = "/bin/sh", topology = HostCpus.UNKNOWN) {
            "Cpus_allowed_list:\t0-3\n"
        }
        assertNull(r.command)
        assertTrue(r.prefix.isEmpty())
    }

    @Test
    fun `resolve degrades when every candidate fails`() {
        val topo = HostCpus.Topology((0..7).toList(), (0..7).toList(), listOf(4, 5, 6, 7), "sysfs")
        val r = QemuAffinity.resolve(4, shell = "/bin/sh", topology = topo) { null }
        assertNull(r.command)
        assertTrue(r.note.contains("降级"))
    }
}
