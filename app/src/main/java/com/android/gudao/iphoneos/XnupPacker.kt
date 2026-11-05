package com.android.gudao.iphoneos

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the "XNUP" payload consumed by the HackiOS XNU loader, byte-for-byte compatible with
 * `xnuloader/mkpayload.py` from the upstream project.
 *
 * Format:
 *   header : u32 magic "XNUP" (0x50554E58), u32 body length
 *   body   : repeated entries, then a 24-byte terminator (empty name, size 0, reserved 0)
 *   entry  : char[16] name, u32 size, u32 reserved(0), data padded to a multiple of 16 bytes
 *
 * [packTo] is the path the app uses. The previous `pack(items: Map<String, ByteArray>)` had to hold
 * kernel + root.apfs + a full copy in a ByteArrayOutputStream + another copy in the final
 * ByteBuffer — roughly three times the ramdisk in RAM. On a phone with a 3 GB root.apfs that is an
 * OutOfMemoryError thrown from a worker thread, surfaced to the user as nothing at all.
 * Streaming keeps the peak at one 1 MB buffer regardless of payload size.
 */
object XnupPacker {

    private const val MAGIC = 0x50554E58
    private const val BUF = 1 shl 20

    /** In-memory variant, kept for the unit tests and for small payloads. */
    fun pack(
        items: Map<String, ByteArray>,
        cmdline: String? = null,
        rdres: String? = null,
    ): ByteArray {
        val parts = ByteArrayOutputStream()
        for ((name, data) in items) parts.writeEntry(name, data)
        if (!cmdline.isNullOrBlank()) parts.writeEntry("cmdline", (cmdline + "\u0000").toByteArray())
        if (!rdres.isNullOrBlank()) {
            parts.writeEntry("rdres", (rdres.lowercase().replace("0x", "") + "\u0000").toByteArray())
        }
        parts.write(ByteArray(24))

        val body = parts.toByteArray()
        val out = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC)
        out.putInt(body.size)
        out.put(body)
        return out.array()
    }

    /** Streams kernel + root.apfs into [dest] without ever materialising them in memory. */
    fun packTo(
        dest: File,
        kernel: File,
        ramdisk: File,
        cmdline: String? = null,
        rdres: String? = null,
    ) {
        val cmd = cmdline?.takeIf { it.isNotBlank() }?.plus("\u0000")?.toByteArray()
        val res = rdres?.takeIf { it.isNotBlank() }
            ?.lowercase()?.replace("0x", "")?.plus("\u0000")?.toByteArray()

        fun entrySize(len: Long): Long = 24L + ((len + 15) / 16) * 16
        val bodyLen = entrySize(kernel.length()) +
            entrySize(ramdisk.length()) +
            (cmd?.let { entrySize(it.size.toLong()) } ?: 0L) +
            (res?.let { entrySize(it.size.toLong()) } ?: 0L) +
            24L

        FileOutputStream(dest).use { fos ->
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(MAGIC)
            header.putInt(bodyLen.toInt())
            fos.write(header.array())

            fun writeName(name: String) {
                val nameBytes = ByteArray(16)
                val nb = name.toByteArray()
                System.arraycopy(nb, 0, nameBytes, 0, nb.size.coerceAtMost(16))
                fos.write(nameBytes)
            }

            fun writePadding(len: Long) {
                val padded = ((len + 15) / 16) * 16
                var rest = padded - len
                val zeros = ByteArray(16)
                while (rest > 0) {
                    val n = minOf(rest, 16L).toInt()
                    fos.write(zeros, 0, n)
                    rest -= n
                }
            }

            fun writeSmall(name: String, data: ByteArray) {
                writeName(name)
                fos.write(le32(data.size))
                fos.write(le32(0))
                fos.write(data)
                writePadding(data.size.toLong())
            }

            fun writeFile(name: String, file: File) {
                writeName(name)
                fos.write(le32(file.length().toInt()))
                fos.write(le32(0))
                RandomAccessFile(file, "r").use { raf ->
                    val buf = ByteArray(BUF)
                    var written = 0L
                    while (written < file.length()) {
                        val n = raf.read(buf, 0, minOf(BUF.toLong(), file.length() - written).toInt())
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        written += n
                    }
                }
                writePadding(file.length())
            }

            writeFile("kernel", kernel)
            writeFile("ramdisk", ramdisk)
            cmd?.let { writeSmall("cmdline", it) }
            res?.let { writeSmall("rdres", it) }
            fos.write(ByteArray(24))
            fos.flush()
            runCatching { fos.fd.sync() }
        }
    }

    private fun le32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun ByteArrayOutputStream.writeEntry(name: String, data: ByteArray) {
        val nameBytes = ByteArray(16)
        val nb = name.toByteArray()
        System.arraycopy(nb, 0, nameBytes, 0, nb.size.coerceAtMost(16))

        val padded = ((data.size + 15) / 16) * 16
        val entry = ByteBuffer.allocate(16 + 4 + 4 + padded).order(ByteOrder.LITTLE_ENDIAN)
        entry.put(nameBytes)
        entry.putInt(data.size)
        entry.putInt(0)
        entry.put(data)
        write(entry.array(), 0, entry.capacity())
    }
}
