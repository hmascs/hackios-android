package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adreno 后端的门禁完全建立在 [GpuIdentity] 的解析结果上，所以这里把真机可能报出来的
 * GL_RENDERER 都钉住：Adreno 8xx 放行、8xx 之外一律不选 Adreno。
 */
class GpuIdentityTest {

    private fun gpu(renderer: String, vendor: String = "Qualcomm") =
        GpuIdentity.of(renderer, vendor, GpuIdentity.Source.Egl)

    @Test
    fun `snapdragon 8 elite reports adreno 830 and is the tested model`() {
        val g = gpu("Adreno (TM) 830")
        assertEquals(830, g.model)
        assertTrue(g.isAdreno8xx)
        assertTrue(g.isTestedModel)
        assertEquals(GpuIdentity.Family.ADRENO_8XX, g.family)
        assertEquals("Adreno 830 · 高通 8xx 系列", g.summary)
    }

    @Test
    fun `other 8xx models pass the family gate but are not the tested model`() {
        for (model in listOf(810, 820, 829, 840, 899)) {
            val g = gpu("Adreno (TM) $model")
            assertTrue("$model 应该算 8xx", g.isAdreno8xx)
            assertFalse("$model 不应该被当成 830", g.isTestedModel)
        }
    }

    @Test
    fun `older adreno models are recognised but rejected`() {
        for (model in listOf(505, 618, 640, 730, 740)) {
            val g = gpu("Adreno (TM) $model")
            assertEquals(model, g.model)
            assertFalse("$model 不是 8xx", g.isAdreno8xx)
            assertEquals(GpuIdentity.Family.ADRENO_OTHER, g.family)
        }
    }

    @Test
    fun `non adreno gpu is rejected`() {
        val g = gpu("Mali-G715", "ARM")
        assertNull(g.model)
        assertFalse(g.isAdreno8xx)
        assertEquals(GpuIdentity.Family.OTHER, g.family)
        assertTrue(g.summary.contains("非高通"))
    }

    @Test
    fun `failed probe is unknown rather than silently supported`() {
        val g = GpuIdentity.of("", "", GpuIdentity.Source.Unavailable, "SM8750")
        assertNull(g.model)
        assertFalse(g.isAdreno8xx)
        assertEquals(GpuIdentity.Family.UNKNOWN, g.family)
        assertTrue(g.summary.contains("SM8750"))
    }

    @Test
    fun `four digit numbers are not mistaken for a model`() {
        assertNull(GpuIdentity.parseAdrenoModel("Adreno (TM) 8300", ""))
        assertNull(GpuIdentity.parseAdrenoModel("Adreno 7400", ""))
    }

    @Test
    fun `parse falls back to the vendor string when renderer is blank`() {
        assertEquals(830, GpuIdentity.parseAdrenoModel("", "Adreno (TM) 830"))
    }
}
