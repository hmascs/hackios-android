package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeResumeTest {

    private fun chunks(count: Int, size: Long, total: Long): List<ChunkSpec> =
        ChunkPlan.of(total, size).chunks.also { assertEquals(count, it.size) }

    @Test
    fun `nothing merged yet starts at the first chunk`() {
        val plan = chunks(4, 10, 40)
        val p = MergeResume.scan(plan) { true }
        assertEquals(0, p.firstIndex)
        assertEquals(0L, p.offset)
    }

    @Test
    fun `after two chunks are consumed the merge resumes at the third`() {
        val plan = chunks(4, 10, 40)
        // Chunks 0 and 1 were deleted because they are already inside the staging file.
        val p = MergeResume.scan(plan) { it >= 2 }
        assertEquals(2, p.firstIndex)
        assertEquals(20L, p.offset)
    }

    @Test
    fun `every chunk consumed means the staging file is complete`() {
        val plan = chunks(4, 10, 40)
        val p = MergeResume.scan(plan) { false }
        assertEquals(4, p.firstIndex)
        assertEquals(40L, p.offset)
    }

    @Test
    fun `ragged tail chunk counts its real length, not the chunk size`() {
        // 25 bytes in 10-byte chunks: 10 + 10 + 5.
        val plan = chunks(3, 10, 25)
        val p = MergeResume.scan(plan) { it >= 2 }
        assertEquals(2, p.firstIndex)
        assertEquals(20L, p.offset)
        val full = MergeResume.scan(plan) { false }
        assertEquals(25L, full.offset)
    }

    @Test
    fun `an interior gap still resumes at the first missing part`() {
        // Chunk 1 is missing but 0 is gone, which is the only shape a linear merge can produce.
        val plan = chunks(3, 10, 30)
        val p = MergeResume.scan(plan) { it != 0 }
        assertEquals(1, p.firstIndex)
        assertEquals(10L, p.offset)
    }
}
