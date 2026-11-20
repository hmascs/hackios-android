package com.android.gudao.iphoneos

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GpuImageStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `shared material survives a fresh private directory without overwriting a backup`() {
        val publicRoot = temp.newFolder("Download-hackios")
        val privateA = temp.newFolder("old-private")
        val privateB = temp.newFolder("reset-private")
        val stage = File(privateA, "stage.tar").apply { writeText("data") }
        val shared = File(GpuImageStore.directory(publicRoot, "a".repeat(64)), "stage.tar")
        GpuImageStore.publish(stage, shared, 1024)
        stage.writeText("new-data")
        GpuImageStore.publish(stage, shared, 1024)
        val restored = File(privateB, "stage.tar")
        GpuImageStore.copyBounded(shared, restored, 1024)
        assertEquals("data", restored.readText())
    }

    @Test fun `oversized inputs and links are rejected without creating a snapshot`() {
        val shared = temp.newFile().apply { writeText("12345") }
        val destination = File(temp.root, "output")
        val link = File(temp.root, "linked")
        Files.createSymbolicLink(link.toPath(), shared.toPath())
        assertThrows(IOException::class.java) { GpuImageStore.copyBounded(shared, destination, 4) }
        assertThrows(IOException::class.java) { GpuImageStore.copyBounded(link, destination, 1024) }
        assertFalse(destination.exists())
    }

    @Test fun `materials manifest names every file and refuses malformed keys`() {
        val key = "c".repeat(64)
        val text = GpuImageStore.materialsManifest(key, "d".repeat(64), "e".repeat(64), "f".repeat(64))
        assertTrue(text.startsWith("hackios gpu materials v1"))
        assertTrue(text.contains("key=$key"))
        assertTrue(text.contains("stage.tar sha256=${"d".repeat(64)}"))
        assertTrue(text.contains("root-gpu.apfs sha256=${"e".repeat(64)}"))
        assertTrue(text.contains("gpu-libredwood-gl.dylib sha256=${"f".repeat(64)}"))
        assertThrows(IllegalArgumentException::class.java) {
            GpuImageStore.materialsManifest("not-a-hash", "d".repeat(64), "e".repeat(64), "f".repeat(64))
        }
    }

    @Test fun `manifest is written once and never overwrites an existing one`() {
        val dir = temp.newFolder("materials")
        val target = File(dir, "materials.txt")
        GpuImageStore.publishManifest(target, "first\n")
        GpuImageStore.publishManifest(target, "second\n")
        assertEquals("first\n", target.readText())
    }

    @Test fun `public subdirectory symlinks are not followed`() {
        val publicRoot = temp.newFolder("shared")
        val privateRoot = temp.newFolder("private")
        Files.createSymbolicLink(File(publicRoot, "gpu").toPath(), privateRoot.toPath())
        assertThrows(IOException::class.java) { GpuImageStore.directory(publicRoot, "b".repeat(64)) }
        assertFalse(File(privateRoot, "adreno-v2").exists())
    }
}
