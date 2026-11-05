package com.android.gudao.iphoneos.engine

/**
 * Works out how far an interrupted merge got.
 *
 * The merge copies chunk files into the `.part` staging image and deletes each chunk the moment it
 * is safely inside it. That makes the staging file's length exactly the number of bytes consumed
 * from the front of the chunk list — so the resume point is "the first chunk file that still
 * exists", and its offset is the sum of the lengths of the ones that are gone.
 *
 * Pure (the caller supplies "does this part exist"), so the arithmetic that decides whether a
 * resumed merge silently corrupts a 6 GB image is covered by JVM unit tests.
 */
object MergeResume {

    /** @param firstIndex index into the chunk list that still has to be copied. */
    data class Point(val firstIndex: Int, val offset: Long)

    fun scan(chunks: List<ChunkSpec>, partExists: (Int) -> Boolean): Point {
        var offset = 0L
        for ((i, chunk) in chunks.withIndex()) {
            if (partExists(chunk.index)) return Point(i, offset)
            offset += chunk.length
        }
        // Every part is gone: the staging file already holds the whole image.
        return Point(chunks.size, offset)
    }
}
