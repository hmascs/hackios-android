package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkRecoveryTest {

    private val plan = ChunkPlan.of(40, 10) // 4 chunks of 10

    @Test
    fun `nothing on disk means nothing recovered`() {
        val s = ChunkRecovery.scan(plan.chunks, stagingBytes = 0) { 0L }
        assertTrue(s.presentByIndex.isEmpty())
        assertTrue(s.mergedAway.isEmpty())
    }

    @Test
    fun `part files are counted at their length`() {
        val s = ChunkRecovery.scan(plan.chunks, stagingBytes = 0) { if (it == 2) 4L else 0L }
        assertEquals(mapOf(2 to 4L), s.presentByIndex)
        assertTrue(s.mergedAway.isEmpty())
    }

    @Test
    fun `chunks already inside the staging file count as complete`() {
        // Chunks 0 and 1 were copied and their part files deleted.
        val s = ChunkRecovery.scan(plan.chunks, stagingBytes = 20) { if (it >= 2) 3L else 0L }
        assertEquals(10L, s.presentByIndex[0])
        assertEquals(10L, s.presentByIndex[1])
        assertEquals(3L, s.presentByIndex[2])
        assertEquals(setOf(0, 1), s.mergedAway)
    }

    @Test
    fun `a half copied chunk is not reused`() {
        // Staging stops mid-chunk 2: the merge will truncate back to byte 20 and copy chunk 2 again.
        val s = ChunkRecovery.scan(plan.chunks, stagingBytes = 25) { 0L }
        assertEquals(10L, s.presentByIndex[0])
        assertEquals(10L, s.presentByIndex[1])
        assertFalse(2 in s.presentByIndex)
        assertEquals(setOf(0, 1), s.mergedAway)
    }

    @Test
    fun `a part longer than its chunk is clamped`() {
        val s = ChunkRecovery.scan(plan.chunks, stagingBytes = 0) { if (it == 0) 999L else 0L }
        assertEquals(10L, s.presentByIndex[0])
    }

    @Test
    fun `a complete staging file with every part deleted recovers the whole image`() {
        val s = ChunkRecovery.scan(plan.chunks, stagingBytes = 40) { 0L }
        assertEquals(4, s.presentByIndex.size)
        assertEquals(40L, s.presentByIndex.values.sum())
        assertEquals(setOf(0, 1, 2, 3), s.mergedAway)
    }
}
