package com.android.gudao.iphoneos

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Builds `ram.img`: a sparse 12 GiB file with the iOS system image (system.apfs) placed at the
 * loader's resident address, exactly like `qemu/run-ios.sh` does (offset = RD_ADDR - RAM_BASE,
 * sparse so it costs neither disk space nor host RAM until written).
 */
object RamImageBuilder {

    /**
     * @param systemApfs the iOS system image
     * @param ramImg    output sparse file
     * @return the number of bytes written (size of system.apfs)
     */
    fun build(systemApfs: File, ramImg: File): Long {
        require(systemApfs.exists()) { "system.apfs missing: ${systemApfs.absolutePath}" }
        val size = systemApfs.length()

        RandomAccessFile(ramImg, "rw").use { raf ->
            // Pre-allocate the full 12 GiB as a sparse file (most blocks stay unmapped).
            raf.setLength(Constants.FILE_SIZE)
            raf.channel.use { out ->
                systemApfs.inputStream().channel.use { `in` ->
                    // copy_file_range equivalent: transfer from the source into the sparse file
                    // at the resident offset. Only the data blocks are written.
                    var pos = Constants.RAM_IMG_OFFSET
                    var remaining = size
                    while (remaining > 0) {
                        val n = out.transferFrom(`in`, pos, remaining)
                        if (n == 0L) break
                        pos += n
                        remaining -= n
                    }
                }
            }
        }
        return size
    }
}
