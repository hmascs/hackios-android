package com.android.gudao.iphoneos

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes

/**
 * 可选的 virtio-blk 数据盘。
 *
 * 0.31.0 起多了一档 [Mode.PRIVATE_RW]：盘以**可写**方式挂给 guest，文件放在公共目录
 * `Download/hackios/disks/`（拿不到公共目录时退回应用私有目录）。它是持久化的块设备——
 * 文件不会随重启消失，清 App 数据也只影响私有回退路径。
 *
 * 边界：内核的 virtio-blk 通过 `virtio_blk=2` 允许写（资产内核已带这段实现），所以设备本身
 * 可写；但 APFS 驱动固定只读挂载，而且 iOS 不会自动格式化/挂载一个空的块设备，所以 guest
 * 自己的 `/private/var` 仍在内存根盘上（重启就丢）。要让 iOS 的状态真正落盘，还需要可写文件
 * 系统与 guest 侧挂载，见 docs/ios-data-disk.md 的两次开机验收。
 */
object IosDataStorage {
    enum class Mode { DISABLED, PRIVATE_RW, PRIVATE_QCOW2, PRIVATE_RAW, LEGACY_PUBLIC_READ_ONLY }

    private const val GIB = 1L shl 30
    const val MIN_SIZE_GIB = 1
    const val MAX_SIZE_GIB = 128
    private const val MAGIC = 0x514649fb // QFI\xfb
    private const val CLUSTER_BITS = 16
    private const val CLUSTER = 1 shl CLUSTER_BITS
    private const val HEADER_BYTES = 104

    class Attachment internal constructor(
        val file: File,
        val sizeBytes: Long,
        private val qcow2: Boolean,
        /** true = 以读写方式挂给 guest；false = 只读探针。 */
        val writable: Boolean,
    ) {
        /**
         * 0.31.0：可写盘传 `virtio_blk=2`（内核驱动里这个值才是「允许写」，1 只读）。
         * 这个可写实现已经在打包的内核里（串口格式串 `", writable"` 在资产中），不需要重编内核。
         */
        val bootArg: String get() = if (writable) "virtio_blk=2" else "virtio_blk=1"
        val qemuArgs: List<String> get() {
            val ro = if (writable) "false" else "true"
            return listOf(
                "-blockdev", "{\"driver\":\"file\",\"node-name\":\"hios_data_file\",\"read-only\":$ro," +
                    "\"auto-read-only\":false,\"filename\":${jsonString(file.absolutePath)}}",
                "-blockdev", "{\"driver\":\"${if (qcow2) "qcow2" else "raw"}\"," +
                    "\"node-name\":\"hios_data\",\"file\":\"hios_data_file\",\"read-only\":$ro," +
                    "\"auto-read-only\":false${if (qcow2) ",\"backing\":null" else ""}}",
                // write-cache=off：guest 的 flush 落到宿主文件，切回运行/重启后数据还在。
                "-device", "virtio-blk-device,drive=hios_data,write-cache=off"
            )
        }
    }

    /** [internalFilesDir] must be Context.filesDir, not the external fallback StoragePaths.root().
     * [publicDiskLocation] is where the user's existing `ios-data.qcow2` would live; for
     * [Mode.PRIVATE_RW] only its directory is used (the writable disk lives next to it). A
     * configured but missing/invalid disk fails explicitly; no mode falls back to another image.
     * Call off the main thread before constructing the payload or starting QEMU. */
    @Throws(IOException::class)
    fun prepare(internalFilesDir: File, publicDiskLocation: File?, mode: Mode, sizeGiB: Int = 8): Attachment? {
        if (mode == Mode.DISABLED) return null
        if (mode == Mode.PRIVATE_RW) {
            require(sizeGiB in MIN_SIZE_GIB..MAX_SIZE_GIB) { "Disk size must be $MIN_SIZE_GIB–$MAX_SIZE_GIB GiB" }
            val bytes = sizeGiB * GIB
            val target = writableTarget(internalFilesDir, publicDiskLocation, sizeGiB)
            if (Files.notExists(target, NOFOLLOW_LINKS)) createDisk(target.parent, target, bytes, qcow2 = true)
            if (sizeOf(target, qcow2 = true) != bytes) {
                throw IOException("Existing writable disk has a different size; it will not be resized or replaced")
            }
            return Attachment(target.toFile(), bytes, qcow2 = true, writable = true)
        }
        if (mode == Mode.LEGACY_PUBLIC_READ_ONLY) {
            val supplied = publicDiskLocation ?: throw IOException("Public iOS disk is unavailable")
            require(supplied.name == "ios-data.qcow2") { "Expected the existing public ios-data.qcow2" }
            val path = supplied.parentFile?.canonicalFile?.resolve(supplied.name)?.toPath()
                ?: throw IOException("Public iOS disk must have a parent directory")
            return Attachment(path.toFile(), sizeOf(path, qcow2 = true), qcow2 = true, writable = false)
        }
        require(sizeGiB in MIN_SIZE_GIB..MAX_SIZE_GIB) { "Disk size must be $MIN_SIZE_GIB–$MAX_SIZE_GIB GiB" }
        val root = internalFilesDir.canonicalFile.toPath()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw IOException("App-private files directory is unavailable")
        val directory = root.resolve("ios-disks")
        try {
            Files.createDirectory(directory)
        } catch (_: FileAlreadyExistsException) {
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw IOException("Invalid app-private disk directory")
        }
        val qcow2 = mode == Mode.PRIVATE_QCOW2
        val format = if (qcow2) "qcow2" else "raw"
        // A different size is a different blank probe volume, never a resize of an existing disk.
        val target = directory.resolve("ios-data-${sizeGiB}gib.$format")
        val bytes = sizeGiB * GIB
        if (Files.notExists(target, NOFOLLOW_LINKS)) createDisk(directory, target, bytes, qcow2)
        if (sizeOf(target, qcow2) != bytes) {
            throw IOException("Existing private disk has a different size; it will not be resized or replaced")
        }
        return Attachment(target.toFile(), bytes, qcow2, writable = false)
    }

    /**
     * 可写盘的落点：优先公共目录（`Download/hackios/disks/ios-data-rw-<N>gib.qcow2`，重启与
     * 清 App 数据后都在），拿不到公共目录时退回应用私有 `files/ios-disks/`。
     */
    private fun writableTarget(internalFilesDir: File, publicDiskLocation: File?, sizeGiB: Int): Path {
        val name = "ios-data-rw-${sizeGiB}gib.qcow2"
        val publicDir = publicDiskLocation?.parentFile
        if (publicDir != null) {
            val candidate = publicDir.canonicalFile.toPath()
            try {
                if (Files.notExists(candidate, NOFOLLOW_LINKS)) Files.createDirectory(candidate)
                if (Files.isDirectory(candidate, NOFOLLOW_LINKS) &&
                    Files.isWritable(candidate) &&
                    Files.exists(candidate, NOFOLLOW_LINKS)
                ) {
                    return candidate.resolve(name)
                }
            } catch (_: IOException) {
                // 落到下面的私有回退；不把「公共目录不可用」当成配置错误。
            } catch (_: SecurityException) {
                // 同上。
            }
        }
        val root = internalFilesDir.canonicalFile.toPath()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw IOException("App-private files directory is unavailable")
        val directory = root.resolve("ios-disks")
        try {
            Files.createDirectory(directory)
        } catch (_: FileAlreadyExistsException) {
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw IOException("Invalid app-private disk directory")
        }
        return directory.resolve(name)
    }

    @Synchronized
    private fun createDisk(directory: Path, target: Path, size: Long, qcow2: Boolean) {
        // Android SELinux can deny hard links even inside Context.filesDir. All writers
        // serialize via this private lock before publishing with a same-directory rename.
        FileChannel.open(directory.resolve(".provision.lock"), java.nio.file.StandardOpenOption.CREATE,
            WRITE, NOFOLLOW_LINKS).use { lockChannel ->
            lockChannel.lock().use {
                if (Files.notExists(target, NOFOLLOW_LINKS)) publishDisk(directory, target, size, qcow2)
            }
        }
    }

    private fun publishDisk(directory: Path, target: Path, size: Long, qcow2: Boolean) {
        val tmp = Files.createTempFile(directory, ".provision-", ".tmp")
        try {
            FileChannel.open(tmp, WRITE, NOFOLLOW_LINKS).use { channel ->
                if (qcow2) writeQcow2(channel, size) else {
                    // A single write at EOF extends the sparse file without writing gigabytes of zeros.
                    channel.position(size - 1)
                    writeAll(channel, ByteBuffer.wrap(byteArrayOf(0)))
                }
                channel.force(true)
            }
            // All app writers hold the same lock; this directory is not shared storage.
            check(Files.notExists(target, NOFOLLOW_LINKS)) { "Private disk already exists" }
            Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Minimal QCOW2 v3: header, refcount table, refcount block, and an empty L1 table. */
    private fun writeQcow2(channel: FileChannel, size: Long) {
        val cluster = CLUSTER.toLong()
        val coverage = cluster * (cluster / 8)
        val l1Entries = ((size + coverage - 1) / coverage).toInt()
        check(l1Entries in 1..CLUSTER / 8)
        val metadata = ByteBuffer.allocate(CLUSTER * 4)
        metadata.putInt(MAGIC).putInt(3).putLong(0).putInt(0).putInt(CLUSTER_BITS)
        metadata.putLong(size).putInt(0).putInt(l1Entries).putLong(cluster * 3)
        metadata.putLong(cluster).putInt(1).putInt(0).putLong(0)
        metadata.putLong(0).putLong(0).putLong(0).putInt(4).putInt(HEADER_BYTES)
        metadata.putLong(CLUSTER, cluster * 2)
        for (i in 0 until 4) metadata.putShort(CLUSTER * 2 + i * 2, 1)
        metadata.position(0)
        writeAll(channel, metadata)
    }

    private fun writeAll(channel: FileChannel, data: ByteBuffer) {
        while (data.hasRemaining()) channel.write(data)
    }

    private fun sizeOf(path: Path, qcow2: Boolean): Long {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attrs.isRegularFile) throw IOException("iOS disk is not a regular file: ${path.fileName}")
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val physicalSize = channel.size()
            val header = ByteBuffer.allocate(HEADER_BYTES)
            while (header.hasRemaining() && channel.read(header) > 0) Unit
            val hasMagic = header.position() >= 4 && header.getInt(0) == MAGIC
            if (!qcow2) {
                if (hasMagic || physicalSize <= 0 || physicalSize % 512L != 0L) throw IOException("Invalid raw disk")
                return physicalSize
            }
            if (!hasMagic || header.position() < 72) throw IOException("Missing QCOW2 header")
            val version = header.getInt(4)
            val clusterBits = header.getInt(20)
            val size = header.getLong(24)
            val l1Entries = header.getInt(36)
            val l1Offset = header.getLong(40)
            val refcountOffset = header.getLong(48)
            val refcountClusters = header.getInt(56)
            if (version !in 2..3 || clusterBits !in 9..21 || size <= 0 || size % 512L != 0L ||
                header.getLong(8) != 0L || header.getInt(16) != 0 || header.getInt(32) != 0 ||
                header.getInt(60) != 0 || header.getLong(64) != 0L || l1Entries <= 0 || refcountClusters <= 0) {
                throw IOException("Expected standalone, unencrypted QCOW2 without snapshots")
            }
            if (version == 3 && (header.position() < HEADER_BYTES || header.getLong(72) != 0L ||
                    header.getLong(88) != 0L)) {
                throw IOException("Unsupported QCOW2 features (including external data or dirty state)")
            }
            val cluster = 1L shl clusterBits
            val headerSize = if (version == 3) header.getInt(100) else 72
            val coverage = cluster * (cluster / 8)
            fun validRegion(start: Long, length: Long): Boolean =
                start >= cluster && start % cluster == 0L && start <= physicalSize - length
            if (headerSize < (if (version == 3) HEADER_BYTES else 72) || headerSize.toLong() > cluster ||
                size / coverage + (if (size % coverage != 0L) 1 else 0) > l1Entries ||
                !validRegion(l1Offset, l1Entries * 8L) || !validRegion(refcountOffset, refcountClusters * cluster)) {
                throw IOException("Invalid QCOW2 metadata bounds")
            }
            return size
        }
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"', '\\' -> { append('\\'); append(c) }
            in '\u0000'..'\u001f' -> { append("\\u"); append(c.toInt().toString(16).padStart(4, '0')) }
            else -> append(c)
        }
        append('"')
    }
}
