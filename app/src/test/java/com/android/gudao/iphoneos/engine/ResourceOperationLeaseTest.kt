package com.android.gudao.iphoneos.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceOperationLeaseTest {
    @Test
    fun `only one concurrent caller wins`() {
        val lease = ResourceOperationLease()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val winners = java.util.Collections.synchronizedList(mutableListOf<ResourceOperationLease.Token>())
        repeat(8) {
            pool.execute {
                start.await()
                lease.tryAcquire(ResourceOperationLease.Operation.Diagnostics) { false }?.let(winners::add)
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, winners.size)
        lease.release(winners.single())
    }

    @Test
    fun `busy snapshot rejects and release permits a later operation`() {
        val lease = ResourceOperationLease()
        assertNull(lease.tryAcquire(ResourceOperationLease.Operation.DeployAssets) { true })
        val token = lease.tryAcquire(ResourceOperationLease.Operation.DeployAssets) { false }
        assertNotNull(token)
        assertEquals(ResourceOperationLease.Operation.DeployAssets, lease.currentOperation())
        assertTrue(lease.release(token!!))
        assertNull(lease.currentOperation())
        assertNotNull(lease.tryAcquire(ResourceOperationLease.Operation.Start) { false })
    }

    @Test
    fun `wrong token cannot release current owner`() {
        val lease = ResourceOperationLease()
        val first = lease.tryAcquire(ResourceOperationLease.Operation.Compile) { false }!!
        val forged = ResourceOperationLease.Token(first.id + 1, ResourceOperationLease.Operation.Compile)
        assertFalse(lease.release(forged))
        assertEquals(ResourceOperationLease.Operation.Compile, lease.currentOperation())
        assertTrue(lease.release(first))
    }

    @Test
    fun `active diagnostics lease blocks start compile and MCP deploy`() {
        val lease = ResourceOperationLease()
        val diagnostic = lease.tryAcquire(ResourceOperationLease.Operation.Diagnostics) { false }!!
        assertNull(lease.tryAcquire(ResourceOperationLease.Operation.Start) { false })
        assertNull(lease.tryAcquire(ResourceOperationLease.Operation.Compile) { false })
        assertNull(lease.tryAcquire(ResourceOperationLease.Operation.DeployAssets) { false })
        assertTrue(lease.release(diagnostic))
        assertNotNull(lease.tryAcquire(ResourceOperationLease.Operation.Start) { false })
    }

    @Test
    fun `cancel path releases owner in finally`() {
        val lease = ResourceOperationLease()
        val token = lease.tryAcquire(ResourceOperationLease.Operation.Diagnostics) { false }!!
        var cancelled = false
        try {
            throw java.util.concurrent.CancellationException("test")
        } catch (_: java.util.concurrent.CancellationException) {
            cancelled = true
        } finally {
            lease.release(token)
        }
        assertTrue(cancelled)
        assertNull(lease.currentOperation())
        assertNotNull(lease.tryAcquire(ResourceOperationLease.Operation.DeployAssets) { false })
    }

    @Test
    fun `stale completion cannot clean newer owner state`() {
        val lease = ResourceOperationLease()
        val old = lease.tryAcquire(ResourceOperationLease.Operation.Start) { false }!!
        assertTrue(lease.finish(old) { })
        val new = lease.tryAcquire(ResourceOperationLease.Operation.Compile) { false }!!
        var state = "new build running"
        assertFalse(lease.finish(old) { state = "stale cleanup" })
        assertEquals("new build running", state)
        assertEquals(ResourceOperationLease.Operation.Compile, lease.currentOperation())
        assertTrue(lease.finish(new) { state = "new build finished" })
        assertEquals("new build finished", state)
    }

    @Test
    fun `cleanup failure still releases current token`() {
        val lease = ResourceOperationLease()
        val token = lease.tryAcquire(ResourceOperationLease.Operation.Diagnostics) { false }!!
        try {
            lease.finish(token) { throw IllegalStateException("cleanup") }
        } catch (_: IllegalStateException) {
            // Cleanup failure must not strand the owner.
        }
        assertNull(lease.currentOperation())
        assertNotNull(lease.tryAcquire(ResourceOperationLease.Operation.Start) { false })
    }

    @Test
    fun `running state supplied by caller rejects diagnostics and deploy`() {
        val lease = ResourceOperationLease()
        var running = true
        assertNull(lease.tryAcquire(ResourceOperationLease.Operation.Diagnostics) { running })
        assertNull(lease.tryAcquire(ResourceOperationLease.Operation.DeployAssets) { running })
        running = false
        val token = lease.tryAcquire(ResourceOperationLease.Operation.Diagnostics) { running }
        assertNotNull(token)
        lease.release(token!!)
    }
}
