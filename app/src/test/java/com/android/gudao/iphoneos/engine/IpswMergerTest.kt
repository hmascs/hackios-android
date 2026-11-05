package com.android.gudao.iphoneos.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the phase that used to look like a permanent "计算中…": concatenating the chunk files and
 * surviving an interrupted merge.
 */
class IpswMergerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun parts(payloads: List<String>): Pair<List<ChunkSpec>, (Int) -> File> {
        val dir = tmp.newFolder("chunks")
        payloads.forEachIndexed { i, body ->
            File(dir, "x.part%03d".format(i)).writeText(body)
        }
        val total = payloads.sumOf { it.length }.toLong()
        val chunkBytes = payloads[0].length.toLong()
        val plan = ChunkPlan.of(total, chunkBytes)
        return plan.chunks to { i: Int -> File(dir, "x.part%03d".format(i)) }
    }

    @Test
    fun `merges chunks in order and deletes each part`() {
        val (chunks, partFile) = parts(listOf("aaaa", "bbbb", "cccc"))
        val plan = ChunkPlan.of(12, 4, emptyMap())
        val dest = File(tmp.root, "iphone.ipsw")
        val staging = File(tmp.root, "iphone.ipsw.part")

        var lastReported = -1L
        val merged = IpswMerger.merge(plan, partFile, dest, staging, onProgress = { lastReported = it })

        assertEquals(12L, merged)
        assertEquals("aaaabbbbcccc", dest.readText())
        assertEquals(12L, lastReported)
        assertFalse(staging.exists())
        chunks.forEach { assertFalse("part ${it.index} should be freed", partFile(it.index).exists()) }
    }

    @Test
    fun `resumes where an interrupted merge stopped and ignores a half written tail`() {
        val (_, partFile) = parts(listOf("aaaa", "bbbb", "cccc"))
        val plan = ChunkPlan.of(12, 4, emptyMap())
        val dest = File(tmp.root, "iphone.ipsw")
        val staging = File(tmp.root, "iphone.ipsw.part")

        // Chunk 0 was copied and deleted; chunk 1 was half copied when the process died.
        partFile(0).delete()
        staging.writeText("aaaaBBBB")

        val merged = IpswMerger.merge(plan, partFile, dest, staging)

        assertEquals(12L, merged)
        assertArrayEquals("aaaabbbbcccc".toByteArray(), dest.readBytes())
    }

    @Test
    fun `reports monotonically increasing progress`() {
        val (_, partFile) = parts(listOf("aa", "bb"))
        val plan = ChunkPlan.of(4, 2, emptyMap())
        val dest = File(tmp.root, "i.ipsw")
        val seen = mutableListOf<Long>()

        IpswMerger.merge(plan, partFile, dest, File(tmp.root, "i.ipsw.part"), onProgress = { seen += it })

        assertEquals(listOf(0L, 4L), seen)
    }

    @Test
    fun `fails loudly instead of writing a truncated image when a part vanishes`() {
        val (_, partFile) = parts(listOf("aaaa", "bbbb"))
        val plan = ChunkPlan.of(8, 4, emptyMap())
        partFile(1).delete()

        val err = runCatching {
            IpswMerger.merge(plan, partFile, File(tmp.root, "i.ipsw"), File(tmp.root, "i.ipsw.part"))
        }.exceptionOrNull()

        assertTrue(err is java.io.IOException)
        assertFalse(File(tmp.root, "i.ipsw").exists())
    }
}
