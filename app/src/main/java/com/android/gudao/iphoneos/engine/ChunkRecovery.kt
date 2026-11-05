package com.android.gudao.iphoneos.engine

/**
 * Rebuilds "what do we already have" from the two places bytes can live on disk.
 *
 * The merge deletes a chunk file the moment that chunk is inside the staging image, so after an
 * interrupted merge a chunk can have **no part file and still be complete**. Reading only the part
 * files would re-download gigabytes that are already on the phone; reading them wrong would
 * concatenate a chunk twice.
 *
 * The rule is purely positional: a chunk is satisfied by the staging file when the staging length
 * covers its whole range. A chunk that is only *partly* covered is not reused — the merge truncates
 * the staging file back to the chunk boundary and copies it again.
 */
object ChunkRecovery {

    data class Slices(
        /** chunk index -> bytes already available, including chunks living in the staging file. */
        val presentByIndex: Map<Int, Long>,
        /** chunk indices whose bytes are in the staging file rather than in a part file. */
        val mergedAway: Set<Int>,
    )

    fun scan(
        chunks: List<ChunkSpec>,
        stagingBytes: Long,
        partBytes: (Int) -> Long,
    ): Slices {
        val present = HashMap<Int, Long>()
        val mergedAway = HashSet<Int>()
        for (chunk in chunks) {
            val have = partBytes(chunk.index)
            if (have > 0L) {
                present[chunk.index] = have.coerceAtMost(chunk.length)
                continue
            }
            // No part file: either nothing yet, or the merge already consumed it.
            if (stagingBytes >= chunk.endInclusive + 1) {
                present[chunk.index] = chunk.length
                mergedAway += chunk.index
            }
        }
        return Slices(present, mergedAway)
    }
}
