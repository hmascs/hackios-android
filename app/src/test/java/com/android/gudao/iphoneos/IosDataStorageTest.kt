package com.android.gudao.iphoneos

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IosDataStorageTest {
    @get:Rule val tmp = TemporaryFolder()
    private val GiB = 1L shl 30
    private val privateMode = IosDataStorage.Mode.PRIVATE_QCOW2

    @Test fun `disabled requires no disk directory or legacy file`() {
        assertNull(IosDataStorage.prepare(File(tmp.root, "missing"), null, IosDataStorage.Mode.DISABLED, -1))
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }

    @Test fun `selecting QCOW2 size provisions distinct files and reuses a prior size`() {
        val eight = IosDataStorage.prepare(tmp.root, null, privateMode)!!
        assertEquals(8 * GiB, eight.sizeBytes)
        assertEquals("ios-data-8gib.qcow2", eight.file.name)
        assertEquals(256L * 1024, eight.file.length())
        val original = eight.file.readBytes()
        val larger = IosDataStorage.prepare(tmp.root, null, privateMode, 128)!!
        assertEquals(128 * GiB, larger.sizeBytes)
        assertNotEquals(eight.file, larger.file)
        assertEquals(eight.file, IosDataStorage.prepare(tmp.root, null, privateMode)!!.file)
        assertArrayEquals(original, eight.file.readBytes())
    }

    @Test fun `writable mode attaches read-write and persists next to the public disk`() {
        val public = tmp.newFolder("Download-hackios-disks")
        val location = File(public, "ios-data.qcow2")
        val first = IosDataStorage.prepare(tmp.root, location, IosDataStorage.Mode.PRIVATE_RW, 8)!!
        assertTrue(first.writable)
        assertEquals("ios-data-rw-8gib.qcow2", first.file.name)
        assertEquals(public.canonicalFile, first.file.parentFile.canonicalFile)
        assertEquals(8 * GiB, first.sizeBytes)
        assertEquals("virtio_blk=2", first.bootArg)
        assertEquals(0, first.qemuArgs.count { it.contains("\"read-only\":true") })
        assertEquals(2, first.qemuArgs.count { it.contains("\"read-only\":false") })
        assertEquals("virtio-blk-device,drive=hios_data,write-cache=off", first.qemuArgs.last())

        // 重启后复用同一块盘：不重建、字节与 mtime 都不变。
        val bytes = first.file.readBytes()
        val modified = first.file.lastModified()
        val again = IosDataStorage.prepare(tmp.root, location, IosDataStorage.Mode.PRIVATE_RW, 8)!!
        assertEquals(first.file, again.file)
        assertEquals(modified, again.file.lastModified())
        assertArrayEquals(bytes, again.file.readBytes())

        // 换容量就是另一块盘，不动原有的那块。
        val small = IosDataStorage.prepare(tmp.root, location, IosDataStorage.Mode.PRIVATE_RW, 2)!!
        assertEquals("ios-data-rw-2gib.qcow2", small.file.name)
        assertNotEquals(first.file, small.file)
        assertArrayEquals(bytes, first.file.readBytes())
    }

    @Test fun `writable mode falls back to app-private storage without a public location`() {
        val disk = IosDataStorage.prepare(tmp.root, null, IosDataStorage.Mode.PRIVATE_RW, 4)!!
        assertTrue(disk.writable)
        assertEquals("ios-data-rw-4gib.qcow2", disk.file.name)
        assertTrue(disk.file.canonicalPath.startsWith(tmp.root.canonicalPath))
        assertEquals(4 * GiB, disk.sizeBytes)
    }

    @Test fun `read-only modes keep both block nodes read-only`() {
        val legacyFixture = IosDataStorage.prepare(tmp.root, null, privateMode)!!.file
        val public = tmp.newFolder("public")
        public.resolve("ios-data.qcow2").also { legacyFixture.copyTo(it) }
        val publicDisk = IosDataStorage.prepare(tmp.root, File(public, "ios-data.qcow2"),
            IosDataStorage.Mode.LEGACY_PUBLIC_READ_ONLY)!!
        assertFalse(publicDisk.writable)
        assertEquals("virtio_blk=1", publicDisk.bootArg)
        assertEquals(2, publicDisk.qemuArgs.count { it.contains("\"read-only\":true") })
        val probe = IosDataStorage.prepare(tmp.root, null, privateMode)!!
        assertFalse(probe.writable)
        assertEquals(2, probe.qemuArgs.count { it.contains("\"read-only\":true") })
    }

    @Test fun `raw size and explicit format do not auto detect QCOW2`() {
        val image = IosDataStorage.prepare(tmp.root, null, IosDataStorage.Mode.PRIVATE_RAW, 2)!!
        assertEquals(2 * GiB, image.file.length())
        assertEquals(2 * GiB, image.sizeBytes)
        RandomAccessFile(image.file, "r").use { f ->
            assertEquals(0, f.readInt())
            f.seek(image.sizeBytes - 1)
            assertEquals(0, f.read())
        }
        assertTrue(image.qemuArgs[3].contains("\"driver\":\"raw\""))
        assertFalse(image.qemuArgs[3].contains("backing"))
    }

    @Test fun `invalid capacity and size mismatch never overwrite an image`() {
        for (bad in listOf(0, 129, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { IosDataStorage.prepare(tmp.root, null, privateMode, bad) }
        }
        assertTrue(tmp.root.listFiles()!!.isEmpty())
        val image = IosDataStorage.prepare(tmp.root, null, privateMode, 1)!!.file
        RandomAccessFile(image, "rw").use { f -> f.seek(24); f.writeLong(2 * GiB) }
        val before = image.readBytes()
        assertThrows(IOException::class.java) { IosDataStorage.prepare(tmp.root, null, privateMode, 1) }
        assertArrayEquals(before, image.readBytes())
    }

    @Test fun `legacy requires an existing standalone image and leaves bytes untouched`() {
        val legacyMode = IosDataStorage.Mode.LEGACY_PUBLIC_READ_ONLY
        val fixture = IosDataStorage.prepare(tmp.root, null, privateMode)!!.file
        val shared = tmp.newFolder("Download,hackios")
        val publicDisk = File(shared, "ios-data.qcow2")
        val neverCreated = File(tmp.root, "missing-private-root")
        assertThrows(IOException::class.java) { IosDataStorage.prepare(neverCreated, null, legacyMode) }
        assertThrows(IOException::class.java) { IosDataStorage.prepare(neverCreated, publicDisk, legacyMode) }
        assertFalse(publicDisk.exists())
        fixture.copyTo(publicDisk)
        val before = publicDisk.readBytes()
        val attachment = IosDataStorage.prepare(neverCreated, publicDisk, legacyMode, -1)!!
        assertEquals(publicDisk.canonicalFile, attachment.file)
        assertEquals("virtio_blk=1", attachment.bootArg)
        assertEquals(2, attachment.qemuArgs.count { it.contains("\"read-only\":true") })
        assertTrue(attachment.qemuArgs[1].contains("Download,hackios"))
        assertTrue(attachment.qemuArgs[3].contains("\"backing\":null"))
        assertEquals("virtio-blk-device,drive=hios_data,write-cache=off", attachment.qemuArgs.last())
        assertArrayEquals(before, publicDisk.readBytes())
        assertFalse(neverCreated.exists())

        RandomAccessFile(publicDisk, "rw").use { f -> f.seek(72); f.writeLong(4) }
        assertThrows(IOException::class.java) { IosDataStorage.prepare(neverCreated, publicDisk, legacyMode) }
        RandomAccessFile(publicDisk, "rw").use { f -> f.seek(72); f.writeLong(0); f.seek(8); f.writeLong(104) }
        assertThrows(IOException::class.java) { IosDataStorage.prepare(neverCreated, publicDisk, legacyMode) }
    }

    @Test fun `symlinks are not used as private directories or disk files`() {
        val priv = tmp.newFolder("private")
        val outside = tmp.newFolder("outside")
        Files.createSymbolicLink(File(priv, "ios-disks").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { IosDataStorage.prepare(priv, null, privateMode) }
        assertTrue(outside.listFiles()!!.isEmpty())
        val safe = tmp.newFolder("safe")
        val slots = File(safe, "ios-disks").apply { check(mkdir()) }
        val dangling = File(slots, "ios-data-8gib.qcow2").toPath()
        Files.createSymbolicLink(dangling, File(outside, "absent").toPath())
        assertThrows(IOException::class.java) { IosDataStorage.prepare(safe, null, privateMode) }
        assertTrue(Files.isSymbolicLink(dangling))
        assertFalse(File(outside, "absent").exists())
    }

    @Test fun `concurrent provisioning publishes one complete inode`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val calls = List(12) { Callable { IosDataStorage.prepare(tmp.root, null, privateMode)!!.file } }
            val files = executor.invokeAll(calls).map { it.get() }.toSet()
            assertEquals(1, files.size)
            assertEquals(setOf(files.single().name, ".provision.lock"),
                files.single().parentFile!!.listFiles()!!.map { it.name }.toSet())
            assertEquals(256L * 1024, files.single().length())
        } finally {
            executor.shutdownNow()
        }
    }
}
