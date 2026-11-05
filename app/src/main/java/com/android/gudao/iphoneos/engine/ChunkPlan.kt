package com.android.gudao.iphoneos.engine

/**
 * Splits a total byte length into HTTP `Range` chunks and, crucially, knows what is already on
 * disk. The old downloader deleted every `.part` file at the start of every run, so a 6.1 GB
 * download that died at 99 % restarted from zero and could not be paused at all. The planner is
 * pure (no I/O), so the resume arithmetic is covered by JVM unit tests.
 */
data class ChunkSpec(
    val index: Int,
    val start: Long,
    val endInclusive: Long,
) {
    val length: Long get() = endInclusive - start + 1

    /** First byte this chunk still has to fetch, given [have] bytes already on disk. */
    fun resumeFrom(have: Long): Long = start + have.coerceIn(0L, length)
}

data class ChunkPlan(
    val chunks: List<ChunkSpec>,
    val totalBytes: Long,
    val presentByIndex: Map<Int, Long>,
) {
    val presentBytes: Long get() = chunks.sumOf { present(it.index) }

    val remainingBytes: Long get() = (totalBytes - presentBytes).coerceAtLeast(0)

    val resumable: Boolean get() = presentBytes > 0

    val completedChunks: Int get() = chunks.count { present(it.index) >= it.length }

    fun present(index: Int): Long = (presentByIndex[index] ?: 0L).coerceIn(0L, Long.MAX_VALUE)

    companion object {
        fun of(
            totalBytes: Long,
            chunkBytes: Long,
            present: Map<Int, Long> = emptyMap(),
        ): ChunkPlan {
            require(totalBytes >= 0) { "totalBytes < 0" }
            require(chunkBytes > 0) { "chunkBytes <= 0" }
            val chunks = ArrayList<ChunkSpec>()
            var start = 0L
            var index = 0
            while (start < totalBytes) {
                val end = minOf(start + chunkBytes - 1, totalBytes - 1)
                chunks += ChunkSpec(index, start, end)
                index++
                start = end + 1
            }
            val clamped = HashMap<Int, Long>()
            for ((i, have) in present) {
                val chunk = chunks.getOrNull(i) ?: continue
                clamped[i] = have.coerceIn(0L, chunk.length)
            }
            return ChunkPlan(chunks, totalBytes, clamped)
        }
    }
}
