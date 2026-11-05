package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resume arithmetic for the 6.1 GB firmware download. */
class ChunkPlanTest {

    @Test
    fun `plan splits the total into inclusive chunks`() {
        val plan = ChunkPlan.of(totalBytes = 1000, chunkBytes = 300)
        assertEquals(4, plan.chunks.size)
        assertEquals(0L, plan.chunks.first().start)
        assertEquals(299L, plan.chunks.first().endInclusive)
        assertEquals(999L, plan.chunks.last().endInclusive)
        assertEquals(1000L, plan.chunks.sumOf { it.length })
    }

    @Test
    fun `partial chunk data is counted and resumed`() {
        val plan = ChunkPlan.of(1000, 300, present = mapOf(0 to 300L, 1 to 42L))
        assertEquals(342L, plan.presentBytes)
        assertEquals(658L, plan.remainingBytes)
        assertEquals(1, plan.completedChunks)
        assertTrue(plan.resumable)
        // chunk 1 already has 42 bytes: the next Range request starts at 300 + 42.
        assertEquals(342L, plan.chunks[1].resumeFrom(42))
    }

    @Test
    fun `present bytes are clamped to the chunk length`() {
        // A .part file can be longer than expected after a bad merge; never let that inflate progress.
        val plan = ChunkPlan.of(1000, 300, present = mapOf(0 to 9999L))
        assertEquals(300L, plan.presentByIndex[0])
        assertEquals(700L, plan.remainingBytes)
        assertFalse(plan.resumable.not())
    }

    @Test
    fun `unknown chunk indexes are ignored`() {
        val plan = ChunkPlan.of(500, 200, present = mapOf(7 to 100L))
        assertEquals(0L, plan.presentBytes)
    }

    @Test
    fun `zero total produces no chunks`() {
        val plan = ChunkPlan.of(0, 256)
        assertTrue(plan.chunks.isEmpty())
        assertEquals(0L, plan.remainingBytes)
    }
}
