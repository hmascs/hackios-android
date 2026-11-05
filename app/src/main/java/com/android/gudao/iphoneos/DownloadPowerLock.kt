package com.android.gudao.iphoneos

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the CPU and the Wi-Fi radio awake while the 6.1 GB firmware is downloading.
 *
 * This is the single biggest reason a perfectly healthy connection drops to a few KB/s: once the
 * screen goes off, Doze parks the CPU and the Wi-Fi driver enters power-save, and a bulk download
 * silently turns into a trickle. Neither lock needs a runtime permission beyond the normal
 * `WAKE_LOCK` / `CHANGE_WIFI_STATE` manifest entries.
 *
 * Reference-counted by the caller through [acquire] / [release]; both are idempotent and never
 * throw (a device without Wi-Fi simply skips that lock).
 */
class DownloadPowerLock(context: Context) {

    private val app = context.applicationContext
    private val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var cpu: PowerManager.WakeLock? = null
    private var radio: WifiManager.WifiLock? = null

    fun acquire() {
        if (cpu == null) {
            cpu = runCatching {
                power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:cpu")?.apply {
                    setReferenceCounted(false)
                    acquire(MAX_HOLD_MS)
                }
            }.onFailure { Log.w(TAG, "cpu lock unavailable: ${it.message}") }.getOrNull()
        }
        if (radio == null) {
            radio = runCatching {
                @Suppress("DEPRECATION")
                wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:wifi")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure { Log.w(TAG, "wifi lock unavailable: ${it.message}") }.getOrNull()
        }
    }

    fun release() {
        runCatching { cpu?.takeIf { it.isHeld }?.release() }
        runCatching { radio?.takeIf { it.isHeld }?.release() }
        cpu = null
        radio = null
    }

    private companion object {
        const val TAG = "DownloadPowerLock"

        /** Safety net: a lock leaked by a crashed download can never outlive this window. */
        const val MAX_HOLD_MS = 4L * 60 * 60 * 1000
    }
}
