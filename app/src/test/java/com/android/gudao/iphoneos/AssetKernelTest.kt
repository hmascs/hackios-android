package com.android.gudao.iphoneos

import com.android.gudao.iphoneos.engine.QemuPreflight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.30.0：App 内置内核必须就是 0.30.0 的合并内核（bootlog 叠层 + 合并后的 CPU monitor 告警）。
 *
 * 0.31.0 起托管 cmdline 不再传 bootlog=1，画面上不再有日志面板；内核里那段叠层代码仍然在
 * （带 boot-arg 才绘制），所以资产标记照旧成立。这条测试锁的是「资产没被换回旧内核」：
 * payload.bin 里的内核来自 assets/mach.development.bcm2837，换回旧资产编译和单测都不会报错。
 */
class AssetKernelTest {

    private val kernel = File("src/main/assets/mach.development.bcm2837")

    @Test
    fun `kernel asset is the merged 64-bit Mach-O`() {
        assertTrue("缺少内核资产：${kernel.absolutePath}", kernel.isFile)
        val head = kernel.inputStream().use { it.readNBytes(4) }
        assertEquals("不是 64 位小端 Mach-O", listOf(0xcf, 0xfa, 0xed, 0xfe), head.map { it.toInt() and 0xff })
        assertEquals("0.30.0 合并内核的大小", 15_791_240L, kernel.length())
    }

    @Test
    fun `kernel asset carries the boot log overlay and condensed CPU monitor`() {
        assertTrue("资产不是 0.30.0 合并内核（大小 ${kernel.length()}）", kernel.length() < 16_000_000L)
        val bytes = kernel.readBytes()
        for (marker in listOf(
            "bootlog drawn on Apple Logo frame",
            "bootlog retired at graphics handoff",
            "CPU monitor self-denials:",
        )) {
            assertTrue("内核缺少标记：$marker", contains(bytes, marker.toByteArray()))
        }
    }

    @Test
    fun `payload profile forces a rebuild after cmdline changes`() {
        assertTrue(
            "cmdline 去掉 bootlog=1 后必须让旧 payload 失效，否则老用户升级后仍会看到日志面板",
            QemuPreflight.CMDLINE_PROFILE >= 7,
        )
    }

    /** 朴素子串查找：内核 16 MB，测试里不值得为它引入额外依赖。 */
    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
