package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.29.9：亲和性探测的兼容性契约。
 *
 * 真机教训：Android 的 `/system/bin/taskset` 是 **toybox** 版，只认十六进制掩码
 * （`taskset c0`），遇到 util-linux 的 `-c 6-7` 会「Unknown option」直接失败。
 * 0.26.0 只按 util-linux 探测，所以绝大多数手机第一次就降级。
 *
 * 另外不能只看退出码：判据是子进程打印出来的 `Cpus_allowed_list`。
 */
class QemuAffinityTest {

    private val shell = "/bin/sh"
    private val eightCores = (0..7).toList()
    private val topo = HostCpus.Topology(
        allowed = eightCores,
        ranked = listOf(6, 7, 0, 1, 2, 3, 4, 5),
        big = listOf(6, 7),
        source = "sysfs",
    )

    private fun allowed(text: String) = "Cpus_allowed_list:\t$text\n"

    @Test
    fun `candidates include system taskset toybox busybox and PATH form`() {
        val joined = QemuAffinity.CANDIDATES.map { it.joinToString(" ") }
        assertTrue(joined.any { it.contains("/system/bin/taskset") })
        assertTrue(joined.any { it.contains("toybox") })
        assertTrue(joined.any { it.contains("busybox") })
        assertTrue(joined.contains("taskset"))
    }

    @Test
    fun `util-linux style is used when it really pins`() {
        val probes = mutableListOf<List<String>>()
        // 池=4 时按「大核优先」选中的是 6,7,0,1（ranked 顺序），排序后是 0,1,6,7。
        val r = QemuAffinity.resolve(4, shell, topo) { argv ->
            probes += argv
            allowed("0-1,6-7")
        }
        assertEquals(QemuAffinity.CANDIDATES.first(), r.command)
        assertEquals("0-1,6-7", r.mask)
        assertEquals(listOf("-c", "0-1,6-7"), r.prefix.subList(r.prefix.size - 2, r.prefix.size))
        assertTrue(r.note.contains("已生效"))
        assertEquals(1, probes.size)
    }

    @Test
    fun `toybox hex mask is used when -c is rejected`() {
        val seen = mutableListOf<List<String>>()
        val r = QemuAffinity.resolve(4, shell, topo) { argv ->
            seen += argv
            // 模拟 toybox：`-c` 报错（退出码非 0 → null），十六进制掩码才成功
            if (argv.size >= 3 && argv[1] == "-c") null else allowed("0-1,6-7")
        }
        assertEquals("0-1,6-7", r.mask)
        // 0,1,6,7 → 0b11000011 = c3
        assertEquals("c3", r.prefix[r.prefix.size - 1])
        assertTrue(r.note.contains("十六进制"))
        assertTrue(seen.size >= 2)
    }

    @Test
    fun `a wrapper that exits 0 without pinning is not accepted`() {
        // 退出码 0 但掩码没变：必须判失败（这正是靠退出码会误判的情形）
        val r = QemuAffinity.resolve(4, shell, topo) { allowed("0-7") }
        assertNull(r.command)
        assertTrue(r.note.contains("降级"))
    }

    @Test
    fun `every candidate failing degrades instead of breaking startup`() {
        val r = QemuAffinity.resolve(4, shell, topo) { null }
        assertNull("降级时必须返回 null", r.command)
        assertTrue(r.emptyPrefix())
        assertTrue(r.note.contains("降级"))
    }

    private fun QemuAffinity.Result.emptyPrefix() = prefix.isEmpty()

    @Test
    fun `no pinning when the system already allows every core`() {
        var called = false
        val r = QemuAffinity.resolve(8, shell, topo) { called = true; allowed("0-7") }
        assertNull(r.command)
        assertTrue("全放行时不应浪费一次 fork", !called)
    }

    @Test
    fun `unknown topology never pins`() {
        val r = QemuAffinity.resolve(4, shell, HostCpus.UNKNOWN) { allowed("0-3") }
        assertNull(r.command)
        assertTrue(r.note.contains("拓扑"))
    }

    @Test
    fun `no shell degrades immediately`() {
        var called = false
        val r = QemuAffinity.resolve(8, shell = null, topology = topo) { called = true; allowed("0-3") }
        assertNull(r.command)
        assertTrue(!called)
    }

    @Test
    fun `hex mask covers sixty four cores`() {
        assertEquals("c0", QemuAffinity.hexMask(listOf(6, 7)))
        assertEquals("1", QemuAffinity.hexMask(listOf(0)))
        assertEquals("3", QemuAffinity.hexMask(listOf(0, 1)))
    }

    @Test
    fun `note always mentions the tb budget`() {
        for (host in 2..8) {
            val r = QemuAffinity.resolve(host, shell, topo) { allowed("0-7") }
            assertTrue(r.note.contains(QemuTopology.tbSize(host).toString()))
        }
    }
}
