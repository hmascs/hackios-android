package com.android.gudao.iphoneos.engine

/** Host-side QEMU device plug-in API. Modules are app-bundled and selected before boot. */
internal interface VmDriver {
    val id: String
    val slot: VmDriverSlot
    val apiVersion: Int get() = VmDrivers.API_VERSION
    fun arguments(context: VmDriverContext): List<String>
}

/** Order is part of the current virt machine contract (in particular virtio-mmio slots). */
internal enum class VmDriverSlot { NETWORK, INPUT, DISPLAY }

internal data class VmDriverContext(val userNetdev: String)

internal class VmDrivers(private val catalog: List<VmDriver>) {
    init {
        require(catalog.map { it.id }.distinct().size == catalog.size) { "Duplicate VM driver ID" }
        require(catalog.all { it.apiVersion == API_VERSION }) { "Incompatible VM driver API" }
    }

    fun select(network: String, input: String, display: String): Plan {
        val ids = listOf(network, input, display)
        val drivers = VmDriverSlot.entries.zip(ids).associate { (slot, id) ->
            val driver = catalog.singleOrNull { it.id == id && it.slot == slot }
                ?: throw IllegalArgumentException("VM driver '$id' unavailable for $slot")
            slot to driver
        }
        return Plan(drivers)
    }

    class Plan internal constructor(private val drivers: Map<VmDriverSlot, VmDriver>) {
        fun arguments(slot: VmDriverSlot, context: VmDriverContext): List<String> =
            drivers.getValue(slot).arguments(context)

        fun describe(): String = VmDriverSlot.entries.joinToString(", ") { drivers.getValue(it).id }
    }

    companion object {
        const val API_VERSION = 1
        val builtin = VmDrivers(listOf(UserVirtioNetwork, NoNetwork, VirtioTouchAndKeys, RamFramebuffer))

        fun defaults(netEnabled: Boolean): Plan = builtin.select(
            network = if (netEnabled) UserVirtioNetwork.id else NoNetwork.id,
            input = VirtioTouchAndKeys.id,
            display = RamFramebuffer.id,
        )
    }
}

private object UserVirtioNetwork : VmDriver {
    override val id = "virtio-network-user"
    override val slot = VmDriverSlot.NETWORK
    override fun arguments(context: VmDriverContext) =
        listOf("-netdev", context.userNetdev, "-device", "virtio-net-device,netdev=net0")
}

private object NoNetwork : VmDriver {
    override val id = "network-none"
    override val slot = VmDriverSlot.NETWORK
    override fun arguments(context: VmDriverContext) = listOf("-nic", "none")
}

private object VirtioTouchAndKeys : VmDriver {
    override val id = "virtio-touch-keyboard"
    override val slot = VmDriverSlot.INPUT
    override fun arguments(context: VmDriverContext) =
        listOf("-device", "virtio-tablet-device", "-device", "virtio-keyboard-device")
}

private object RamFramebuffer : VmDriver {
    override val id = "ramfb-2d"
    override val slot = VmDriverSlot.DISPLAY
    override fun arguments(context: VmDriverContext) = listOf("-device", "ramfb")
}
