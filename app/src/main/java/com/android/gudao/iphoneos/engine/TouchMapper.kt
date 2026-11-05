package com.android.gudao.iphoneos.engine

/**
 * Maps a touch on the on-screen iOS display to guest coordinates (0..1) and decides what gesture
 * the user performed.
 *
 * Two defects in the previous build lived here:
 *  1. The listener divided by the *view* size, not by the rectangle the guest frame is actually
 *     drawn into. Whenever the view's aspect ratio differed from 1125:2436 (the letterbox case the
 *     old code deliberately supported) every touch was offset, and taps near the edges landed
 *     outside the guest screen.
 *  2. Every `ACTION_MOVE` was turned into a fresh "tap" (absolute move + button down + 80 ms +
 *     button up). A drag therefore arrived at iOS as a burst of taps, so scrolling, swipe-to-unlock
 *     and any gesture recogniser that needs a continuous contact never worked.
 *
 * The mapping is a pure function so it is covered by JVM unit tests.
 */
data class GuestPoint(val x: Float, val y: Float)

data class Viewport(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

enum class GestureKind { Tap, LongPress, Drag, Cancel }

object TouchMapper {

    /** The centred, aspect-correct rectangle the guest frame occupies inside the view. */
    fun viewport(viewWidth: Float, viewHeight: Float, guestWidth: Int, guestHeight: Int): Viewport {
        if (viewWidth <= 0f || viewHeight <= 0f || guestWidth <= 0 || guestHeight <= 0) {
            return Viewport(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f))
        }
        val guestAspect = guestWidth.toFloat() / guestHeight
        val viewAspect = viewWidth / viewHeight
        return if (viewAspect > guestAspect) {
            val w = viewHeight * guestAspect
            Viewport((viewWidth - w) / 2f, 0f, w, viewHeight)
        } else {
            val h = viewWidth / guestAspect
            Viewport(0f, (viewHeight - h) / 2f, viewWidth, h)
        }
    }

    /** Guest fractions for a touch, or null when the touch is in the letterbox / outside. */
    fun toGuest(
        viewWidth: Float,
        viewHeight: Float,
        guestWidth: Int,
        guestHeight: Int,
        x: Float,
        y: Float,
    ): GuestPoint? {
        val vp = viewport(viewWidth, viewHeight, guestWidth, guestHeight)
        if (vp.width <= 0f || vp.height <= 0f) return null
        if (x < vp.left || x > vp.right || y < vp.top || y > vp.bottom) return null
        return GuestPoint(
            x = ((x - vp.left) / vp.width).coerceIn(0f, 1f),
            y = ((y - vp.top) / vp.height).coerceIn(0f, 1f),
        )
    }

    /**
     * iOS uses a 10 pt tap slop and a 0.5 s long-press threshold. [slopPx] is the same distance in
     * device pixels, so the decision is density independent.
     */
    fun classify(durationMs: Long, distancePx: Float, slopPx: Float, longPressMs: Long = 500): GestureKind = when {
        distancePx > slopPx -> GestureKind.Drag
        durationMs >= longPressMs -> GestureKind.LongPress
        else -> GestureKind.Tap
    }
}
