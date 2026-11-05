package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkHealthTest {

    @Test
    fun `no rate until the window closes`() {
        val health = LinkHealth(windowMs = 1_000, minBytesPerSecond = 1_000)
        assertNull(health.onBytes(10_000, nowMs = 0))
        assertNull(health.onBytes(10_000, nowMs = 999))
    }

    @Test
    fun `reports the average rate of the window`() {
        val health = LinkHealth(windowMs = 1_000, minBytesPerSecond = 1_000)
        health.onBytes(500_000, nowMs = 0)
        val bps = health.onBytes(500_000, nowMs = 1_000)
        assertEquals(1_000_000L, bps)
    }

    @Test
    fun `flags a stalled connection`() {
        val health = LinkHealth(windowMs = 15_000, minBytesPerSecond = 20 * 1024)
        health.onBytes(1_024, nowMs = 0)
        val bps = health.onBytes(2_048, nowMs = 15_000)!!
        assertTrue(health.isTooSlow(bps))
    }

    @Test
    fun `does not flag a healthy connection`() {
        val health = LinkHealth(windowMs = 15_000, minBytesPerSecond = 20 * 1024)
        health.onBytes(1_000_000, nowMs = 0)
        val bps = health.onBytes(1_000_000, nowMs = 15_000)!!
        assertFalse(health.isTooSlow(bps))
    }

    @Test
    fun `window restarts after it is reported`() {
        val health = LinkHealth(windowMs = 1_000, minBytesPerSecond = 1_000)
        health.onBytes(100_000, nowMs = 0)
        assertEquals(100_000L, health.onBytes(0, nowMs = 1_000))
        // Bytes measured before the reset must not leak into the next window.
        assertEquals(50_000L, health.onBytes(50_000, nowMs = 2_000))
    }

    @Test
    fun `reset discards partial window`() {
        val health = LinkHealth(windowMs = 1_000, minBytesPerSecond = 1_000)
        health.onBytes(900_000, nowMs = 0)
        health.reset(nowMs = 500)
        assertEquals(10_000L, health.onBytes(10_000, nowMs = 1_500))
    }
}
