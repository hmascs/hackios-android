package com.android.gudao.iphoneos.engine

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Concatenates the downloaded chunk files into the final IPSW image.
 *
 * Two properties matter, and both are why this is not a three-line `copyTo` loop:
 *
 *  - **It reports progress.** Concatenating 6 GB takes minutes on a phone. The old code did it in
 *    silence while the UI kept showing "100 %, 计算中…", which is indistinguishable from a hang.
 *  - **It deletes each chunk as soon as it is inside the staging file.** A phone with just over
 *    6 GB free could otherwise never finish: the chunks and the merged image would have to coexist.
 *    That also makes the merge resumable, because the staging file's length is exactly the number
 *    of bytes consumed from the front of the chunk list ([MergeResume]).
 *
 * Pure file I/O, no Android types, so the resume path is covered by JVM unit tests instead of only
 * being exercised by a 6.1 GB download on a phone.
 */
object IpswMerger {

    private const val MERGE_REPORT_BYTES = 32L * 1024 * 1024
    private const val TRANSFER_BLOCK = 8L * 1024 * 1024

    /**
     * @param partFile chunk index -> chunk file.
     * @param dest final image path; replaced atomically by a rename from [staging].
     * @param onProgress cumulative bytes now in the staging file.
     * @return the merged size.
     */
    fun merge(
        plan: ChunkPlan,
        partFile: (Int) -> File,
        dest: File,
        staging: File,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Long {
        val point = MergeResume.scan(plan.chunks) { partFile(it).isFile }
        val offset = point.offset
        if (offset > 0L && (!staging.isFile || staging.length() < offset)) {
            // The staging file is gone or short: the parts it consumed are unrecoverable.
            throw IOException("合并暂存文件缺失（需要 $offset 字节），请重新下载")
        }
        if (offset == 0L) staging.delete()

        var merged = offset
        var lastEmit = offset
        onProgress(merged)

        FileChannel.open(
            staging.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ).use { out ->
            // Drop a half-written tail left by a merge that died mid-chunk.
            out.truncate(offset)
            out.position(offset)
            for (i in point.firstIndex until plan.chunks.size) {
                if (isCancelled()) throw InterruptedException("cancelled")
                val chunk = plan.chunks[i]
                val src = partFile(chunk.index)
                if (!src.isFile) throw IOException("分块 ${chunk.index} 在合并第 $i 步丢失")
                FileChannel.open(src.toPath(), StandardOpenOption.READ).use { input ->
                    val size = input.size()
                    var pos = 0L
                    while (pos < size) {
                        val moved = input.transferTo(pos, minOf(size - pos, TRANSFER_BLOCK), out)
                        if (moved <= 0L) break
                        pos += moved
                        merged += moved
                        if (merged - lastEmit >= MERGE_REPORT_BYTES) {
                            lastEmit = merged
                            onProgress(merged)
                        }
                    }
                }
                // Free it now, not after the whole image is written.
                src.delete()
            }
            out.force(false)
        }
        onProgress(merged)

        dest.delete()
        if (!staging.renameTo(dest)) throw IOException("无法写入 ${dest.absolutePath}")
        return merged
    }
}
