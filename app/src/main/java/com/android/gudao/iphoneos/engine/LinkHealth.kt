package com.android.gudao.iphoneos.engine

/**
 * Rolling-window throughput sampler used to spot a socket that is still "open" but useless.
 *
 * A congestion-stalled TCP connection does not fail: the peer's window collapses, `read()` blocks
 * for a long time and then returns a few KB, and the read timeout never fires because *something*
 * always arrives eventually. That is how a 6 GB download ends up sitting at single-digit KB/s on a
 * perfectly healthy network.
 *
 * This class turns the observation into arithmetic the caller can act on: feed it every received
 * byte count with a monotonic timestamp, and it reports the average rate once per window. Pure and
 * clock-free (the caller supplies time), so the policy is covered by JVM unit tests instead of
 * being buried in a socket loop.
 */
class LinkHealth(
    private val windowMs: Long,
    private val minBytesPerSecond: Long,
) {
    private var windowStartMs = 0L
    private var bytesInWindow = 0L
    private var started = false

    /** Begin a fresh window, e.g. right after a reconnect. */
    fun reset(nowMs: Long) {
        windowStartMs = nowMs
        bytesInWindow = 0
        started = true
    }

    /**
     * Account for [count] freshly received bytes at [nowMs].
     *
     * @return the measured bytes/second if this call closed a window (the window then restarts at
     *   [nowMs]), or `null` while the window is still filling.
     */
    fun onBytes(count: Long, nowMs: Long): Long? {
        if (!started) {
            reset(nowMs)
            bytesInWindow += count
            return null
        }
        bytesInWindow += count
        val elapsed = nowMs - windowStartMs
        if (elapsed < windowMs) return null
        val bps = if (elapsed <= 0) 0L else bytesInWindow * 1000 / elapsed
        windowStartMs = nowMs
        bytesInWindow = 0
        return bps
    }

    /** True when [measured] is beneath the floor this instance was built with. */
    fun isTooSlow(measured: Long): Boolean = measured < minBytesPerSecond
}
