package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VmDriversTest {
    private val context = VmDriverContext("user,id=net0,dns=1.1.1.1")

    @Test fun `default devices retain exact original QEMU flags`() {
        val p = VmDrivers.defaults(netEnabled = true)
        assertEquals(listOf("-netdev", context.userNetdev, "-device", "virtio-net-device,netdev=net0"),
            p.arguments(VmDriverSlot.NETWORK, context))
        assertEquals(listOf("-device", "virtio-tablet-device", "-device", "virtio-keyboard-device"),
            p.arguments(VmDriverSlot.INPUT, context))
        assertEquals(listOf("-device", "ramfb"), p.arguments(VmDriverSlot.DISPLAY, context))
        assertEquals("virtio-network-user, virtio-touch-keyboard, ramfb-2d", p.describe())
    }

    @Test fun `disable network changes only the network slot`() {
        val p = VmDrivers.defaults(netEnabled = false)
        assertEquals(listOf("-nic", "none"), p.arguments(VmDriverSlot.NETWORK, context))
        assertEquals(VmDrivers.defaults(true).arguments(VmDriverSlot.INPUT, context),
            p.arguments(VmDriverSlot.INPUT, context))
    }

    @Test fun `wrong slot and unknown drivers are explicit errors`() {
        assertThrows(IllegalArgumentException::class.java) {
            VmDrivers.builtin.select("network-none", "ramfb-2d", "virtio-touch-keyboard")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VmDrivers.builtin.select("network-none", "virtio-touch-keyboard", "no-such-display")
        }
    }

    @Test fun `duplicate IDs and incompatible plugin versions are rejected`() {
        val wrong = object : VmDriver {
            override val id = "wrong"
            override val slot = VmDriverSlot.INPUT
            override val apiVersion = 2
            override fun arguments(context: VmDriverContext) = emptyList<String>()
        }
        val first = object : VmDriver by wrong { override val apiVersion = VmDrivers.API_VERSION }
        assertThrows(IllegalArgumentException::class.java) { VmDrivers(listOf(wrong)) }
        assertThrows(IllegalArgumentException::class.java) { VmDrivers(listOf(first, first)) }
    }
}
