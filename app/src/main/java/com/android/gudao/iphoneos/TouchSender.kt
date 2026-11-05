package com.android.gudao.iphoneos

/**
 * High-level guest input: taps, holds, swipes and the hardware buttons.
 *
 * Coordinates are fractions of the guest screen (0,0 top-left .. 1,1 bottom-right), matching the
 * upstream `qemu/touch.py` convention. [QemuControl] keeps one persistent QMP session, so a drag
 * costs one write per move instead of a TCP handshake per move.
 *
 * Call from a background thread: every method blocks on socket I/O and, for [hold]/[swipe], on
 * `Thread.sleep`.
 */
class TouchSender(private val control: QemuControl) {

    /** A short touch. One down and one up: never "a tap plus a stray move". */
    fun tap(xFrac: Float, yFrac: Float, pressMs: Long = 45) {
        control.pointer(xFrac, yFrac, down = true)
        Thread.sleep(pressMs)
        control.pointer(xFrac, yFrac, down = false)
    }

    /** Touch and hold for [ms]; iOS sees a continuous contact, which is what long-press needs. */
    fun hold(xFrac: Float, yFrac: Float, ms: Long = 500) {
        control.pointer(xFrac, yFrac, down = true)
        Thread.sleep(ms)
        control.pointer(xFrac, yFrac, down = false)
    }

    /** Drag from one point to another at ~60 Hz, ideal for swipe-to-unlock and Control Centre. */
    fun swipe(x0: Float, y0: Float, x1: Float, y1: Float, ms: Long = 300) {
        val steps = maxOf(2, (ms / 16).toInt())
        control.pointer(x0, y0, down = true)
        for (i in 1..steps) {
            Thread.sleep((ms / steps).coerceAtLeast(1))
            val t = i.toFloat() / steps
            control.pointer(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
        }
        Thread.sleep(16)
        control.pointer(x1, y1, down = false)
    }

    /** Hardware button: "home", "lock", "volup", "voldown", "fivefingers". */
    fun button(name: String, ms: Long = 80) = control.key(name, ms)
}
