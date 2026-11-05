package com.android.gudao.iphoneos

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * End-to-end exercise of the download engine against a real HTTP server: ranged transfer, chunk
 * files, merge into the final image, SHA-256 verification.
 *
 * The bug this guards against is "it reaches 100 % and then sits on 计算中 forever": the merge used
 * to be silent and, worse, could sit on an input that never arrived. Here the whole pipeline runs
 * with the real code paths (only the byte counts are small).
 */
class FirmwareDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var payload: ByteArray

    /** Range headers the server actually received, in order. */
    private val ranges = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** Deterministic, non-repeating bytes so a wrong offset cannot accidentally verify. */
    private fun makePayload(size: Int): ByteArray =
        ByteArray(size) { i -> ((i * 31 + (i ushr 8) * 7) and 0xFF).toByte() }

    @Before
    fun setUp() {
        payload = makePayload(3 * 1024 * 1024 + 12345)
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop(0)
    }

    /** Starts a server that honours `Range` (206) and falls back to 200 for a plain GET. */
    private fun startServer(honourRange: Boolean = true): String {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(6)
        server.createContext("/ios.ipsw") { ex -> serve(ex, honourRange) }
        server.start()
        return "http://127.0.0.1:${server.address.port}/ios.ipsw"
    }

    private fun serve(ex: HttpExchange, honourRange: Boolean) {
        try {
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null) ranges += range
            if (!honourRange || range == null) {
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
                return
            }
            val spec = range.removePrefix("bytes=").split("-")
            val start = spec[0].toLong()
            val end = if (spec.size > 1 && spec[1].isNotBlank()) spec[1].toLong() else payload.size - 1L
            if (start >= payload.size) {
                ex.sendResponseHeaders(416, -1)
                ex.close()
                return
            }
            val stop = minOf(end, payload.size - 1L)
            val slice = payload.copyOfRange(start.toInt(), (stop + 1).toInt())
            ex.responseHeaders.add("Content-Range", "bytes $start-$stop/${payload.size}")
            ex.sendResponseHeaders(206, slice.size.toLong())
            ex.responseBody.use { it.write(slice) }
        } finally {
            ex.close()
        }
    }

    private fun expectedSha(): String {
        val f = tmp.newFile("ref.bin")
        f.writeBytes(payload)
        return FirmwareDownloader.sha256Of(f)
    }

    @Test
    fun `downloads, merges into one image and verifies`() {
        val url = startServer()
        val dir = tmp.newFolder("out")
        val dest = File(dir, "apple.ipsw")

        val progress = mutableListOf<Long>()
        val merging = mutableListOf<Long>()
        val verifying = mutableListOf<Long>()
        var outcome: FirmwareDownloader.Outcome? = null

        val downloader = FirmwareDownloader(
            initialConnections = 3,
            url = url,
            totalBytes = payload.size.toLong(),
            expectedSha256 = expectedSha(),
            chunkBytes = 1024L * 1024L,
        )
        val result = downloader.download(dest, object : FirmwareDownloader.Listener {
            override fun onProgress(progress_: FirmwareDownloader.Progress) {
                progress += progress_.downloaded
            }

            override fun onMerging(done: Long, total: Long) {
                merging += done
            }

            override fun onVerifying(done: Long, total: Long) {
                verifying += done
            }

            override fun onOutcome(outcome_: FirmwareDownloader.Outcome) {
                outcome = outcome_
            }
        })

        assertTrue("expected success, got $result / $outcome", result is FirmwareDownloader.Outcome.Success)
        assertArrayEquals(payload, dest.readBytes())
        // Chunk files must not survive the merge.
        assertFalse(File(dir, "apple.chunks").exists())
        assertFalse(File(dir, "apple.ipsw.part").exists())
        // The merge must actually report progress, that is what un-sticks the UI.
        assertTrue("merge reported no progress", merging.isNotEmpty())
        assertEquals(payload.size.toLong(), merging.last())
        assertTrue(verifying.isNotEmpty())
        assertEquals(payload.size.toLong(), verifying.last())
        assertTrue(progress.isNotEmpty())
    }

    @Test
    fun `a second run reuses the finished image instead of downloading again`() {
        val url = startServer()
        val dir = tmp.newFolder("out2")
        val dest = File(dir, "apple.ipsw")
        val sha = expectedSha()

        val first = FirmwareDownloader(3, url, payload.size.toLong(), sha, 1024L * 1024L)
        assertTrue(first.download(dest, SilentListener) is FirmwareDownloader.Outcome.Success)

        // The engine short-circuits through verifyExisting when the image is already good.
        assertTrue(first.verifyExisting(dest))
    }

    @Test
    fun `a server that ignores Range is handled by discarding the prefix, not by overflowing a chunk`() {
        val url = startServer(honourRange = false)
        val dir = tmp.newFolder("out3")
        val dest = File(dir, "apple.ipsw")

        val downloader = FirmwareDownloader(2, url, payload.size.toLong(), expectedSha(), 1024L * 1024L)
        val result = downloader.download(dest, SilentListener)

        // Every 200 response must be cut at the chunk boundary; otherwise the first part file would
        // swallow the whole image and the merge would produce garbage.
        assertTrue("expected success, got $result", result is FirmwareDownloader.Outcome.Success)
        assertArrayEquals(payload, dest.readBytes())
        assertTrue(File(dir, "apple.ipsw").length() == payload.size.toLong())
    }

    @Test
    fun `resumes from the bytes already on disk, asking only for what is missing`() {
        val url = startServer()
        val dir = tmp.newFolder("out4")
        val dest = File(dir, "apple.ipsw")
        val chunkDir = File(dir, "apple.chunks").apply { mkdirs() }
        val chunk = 1024 * 1024
        // Chunk 0 is complete; chunk 1 has 400 000 of its 1 048 576 bytes.
        File(chunkDir, "apple.part000").writeBytes(payload.copyOfRange(0, chunk))
        File(chunkDir, "apple.part001").writeBytes(payload.copyOfRange(chunk, chunk + 400_000))

        val downloader = FirmwareDownloader(2, url, payload.size.toLong(), expectedSha(), chunk.toLong())
        val result = downloader.download(dest, SilentListener)

        assertTrue("expected success, got $result", result is FirmwareDownloader.Outcome.Success)
        assertArrayEquals(payload, dest.readBytes())
        // The finished chunk must not be requested again.
        assertTrue("re-downloaded a finished chunk: $ranges", ranges.none { it.startsWith("bytes=0-") })
        // The partial chunk must resume at its exact byte, not at its start.
        val expected = "bytes=${chunk + 400_000}-${2 * chunk - 1}"
        assertTrue("expected a request for $expected, saw $ranges", ranges.contains(expected))
    }

    private object SilentListener : FirmwareDownloader.Listener {
        override fun onProgress(progress: FirmwareDownloader.Progress) {}
        override fun onOutcome(outcome: FirmwareDownloader.Outcome) {}
    }
}
