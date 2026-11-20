package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QemuBackendCpuTest {
    @Test fun `Adreno always launches and displays its fixed profile`() {
        for (cores in 1..8) {
            for (savedMttcg in listOf(false, true)) {
                val profile = QemuTopology.backendCpu(adreno = true, cores, savedMttcg)
                assertEquals(2, profile.guestCores)
                assertTrue(profile.mttcg)
                assertFalse(profile.guestSettingsEditable)
            }
        }
    }

    @Test fun `switching back leaves the saved OSMesa profile usable`() {
        val presetCores = 6
        val presetMttcg = false
        QemuTopology.backendCpu(adreno = true, presetCores, presetMttcg)
        val software = QemuTopology.backendCpu(adreno = false, presetCores, presetMttcg)
        assertEquals(6, software.guestCores)
        assertFalse(software.mttcg)
        assertTrue(software.guestSettingsEditable)
        assertEquals(8, QemuTopology.backendCpu(false, 99, true).guestCores)
    }
}
