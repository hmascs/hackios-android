package com.android.gudao.iphoneos.engine

import java.io.File
import java.io.RandomAccessFile

/**
 * Incremental reader for the QEMU serial / qemu.log files.
 *
 * The old console re-read the whole file every 2 s and pushed 8000 characters of text into a
 * TextView. Once the serial log passed a few hundred KB that meant reading and allocating the file
 * on every tick (on the UI thread for the `setText`), which is exactly the stutter users reported,
 * and resetting the scroll position so "auto scroll" fought the user's finger.
 *
 * This reader keeps a byte offset, returns only complete new lines, tolerates the file being
 * truncated or replaced (rotation) and leaves partial lines buffered until their newline arrives.
 */
class LogTailer(private val file: File) {

    private var offset = 0L
    private val partial = StringBuilder()
    private var lastLength = -1L

    /** Complete lines appended since the previous call. Never blocks longer than one read. */
    fun poll(maxLines: Int = 400): List<String> {
        val out = ArrayList<String>(minOf(maxLines, 64))
        // Lines left over from the previous poll (the caller asked for fewer than were available)
        // must come out first, even when the file has not grown since.
        drain(out, maxLines)
        if (out.size >= maxLines) return out

        if (!file.isFile) {
            if (offset != 0L) reset()
            return out
        }
        val length = file.length()
        if (length < offset || (lastLength >= 0 && length < lastLength)) {
            // Truncated (`-serial file:` on a restart) — start over so the new boot shows.
            reset()
        }
        lastLength = length
        if (length == offset) return out

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(64 * 1024)
            var budget = 512 * 1024
            while (budget > 0 && out.size < maxLines) {
                val n = raf.read(buf)
                if (n <= 0) break
                budget -= n
                partial.append(String(buf, 0, n, Charsets.UTF_8))
                drain(out, maxLines)
            }
            offset = raf.filePointer
        }
        // Do not let a binary/huge line grow without bound.
        if (partial.length > 64 * 1024) partial.delete(0, partial.length - 8 * 1024)
        return out
    }

    private fun drain(out: MutableList<String>, maxLines: Int) {
        var idx = partial.indexOf('\n')
        while (idx >= 0 && out.size < maxLines) {
            out += partial.substring(0, idx).trimEnd('\r')
            partial.delete(0, idx + 1)
            idx = partial.indexOf('\n')
        }
    }

    fun reset() {
        offset = 0
        partial.setLength(0)
        lastLength = -1
    }
}
