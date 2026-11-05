package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** B3: cancelling a 20–40 minute build must not throw away completed stages. */
class BuildCheckpointTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun checkpoint() = BuildCheckpoint(File(tmp.root, "run/.build-stage"))

    @Test
    fun `nothing is done before the first mark`() {
        val c = checkpoint()
        assertFalse(c.done("extract"))
        assertTrue(c.stages().isEmpty())
    }

    @Test
    fun `marks survive a new instance`() {
        checkpoint().mark("extract")
        assertTrue(checkpoint().done("extract"))
        assertFalse(checkpoint().done("fetch"))
    }

    @Test
    fun `a resumed build starts at the interrupted stage`() {
        val c = checkpoint()
        c.mark("extract")
        c.mark("fetch")
        // cancelled during `build`: no marker for it, so it is the stage that runs again
        assertEquals(setOf("extract", "fetch"), c.stages())
        assertFalse(c.done("build"))
        assertTrue(c.done("extract") && c.done("fetch"))
    }

    @Test
    fun `marking twice does not duplicate`() {
        val c = checkpoint()
        c.mark("extract")
        c.mark("extract")
        assertEquals(1, c.stages().size)
    }

    @Test
    fun `reset clears everything after a successful publish`() {
        val c = checkpoint()
        c.mark("extract")
        c.reset()
        assertFalse(c.done("extract"))
    }

    @Test
    fun `forget drops one stage and keeps the rest`() {
        val c = checkpoint()
        c.mark("extract")
        c.mark("fetch")
        c.forget("fetch")
        // extract still stands: its DMGs are the input of the stage that has to run again
        assertTrue(c.done("extract"))
        assertFalse(c.done("fetch"))
    }

    @Test
    fun `forget on a checkpoint with no file does nothing`() {
        val c = checkpoint()
        c.forget("fetch")
        assertTrue(c.stages().isEmpty())
    }

    @Test
    fun `a forgotten stage runs again after a new instance reads the file`() {
        checkpoint().apply { mark("extract"); mark("fetch"); forget("fetch") }
        val reloaded = checkpoint()
        assertEquals(setOf("extract"), reloaded.stages())
    }
}
