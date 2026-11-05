package com.android.gudao.iphoneos

import android.util.Log
import com.android.gudao.iphoneos.engine.ChunkPlan
import com.android.gudao.iphoneos.engine.ChunkRecovery
import com.android.gudao.iphoneos.engine.ChunkSpec
import com.android.gudao.iphoneos.engine.LinkHealth
import com.android.gudao.iphoneos.engine.IpswMerger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.URL
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.net.HttpURLConnection
import kotlin.random.Random

/**
 * Resumable, cancellable, throughput-aware download of the iPhone X iOS 16.5 (20F66) IPSW.
 *
 * ## Why a download can sit at "a few KB/s" while the network is fine
 *
 * 1. **A wedged TCP connection.** A single stalled segment makes the kernel retransmit for tens of
 *    seconds; the byte counter freezes but nobody reconnects. Three layers catch it now:
 *    [READ_TIMEOUT_MS] (12 s), [LinkHealth] inside [pipe] (a connection that *does* deliver, just
 *    very slowly), and — the important one — the **watchdog**, which looks at every live connection
 *    once per [WATCHDOG_TICK_MS] and tears down any that has been silent for [MAX_IDLE_MS]. The
 *    chunk then reconnects *from the byte it already has*, and a fresh socket usually comes back at
 *    full speed immediately. The old code only noticed after a read timeout, which is why a bad
 *    connection used to cost 20–40 s every time it happened.
 * 2. **Too few connections.** Apple's CDN throttles per connection; the pool scales with the
 *    transport (8 on Wi-Fi/Ethernet, 4 on cellular).
 * 3. **One slow chunk starving the rest.** Chunks are a work queue, so tail latency is bounded by
 *    the slowest *chunk*, not the slowest *slot*. Chunks are 64 MB rather than 256 MB so a stalled
 *    one costs a fraction of the traffic before the watchdog replaces its socket.
 * 4. **The screen going off.** Doze drops the radio into power-save; [DownloadPowerLock] holds a
 *    CPU + high-performance Wi-Fi lock for the duration.
 * 5. **A TLS handshake per range.** Successful responses go back to the keep-alive cache rather
 *    than being `disconnect()`ed.
 * 6. **Instantaneous rate jitter.** The reported rate is an EMA over a live byte delta.
 *
 * ## Finishing: merging and verifying are explicit phases
 *
 * The old implementation concatenated the chunks in silence. On a phone that takes minutes and the
 * UI kept rendering "100 %, 计算中…" because the ETA is null once every byte is on disk — which
 * looks exactly like a hang, and is what made people think the merge never happened.
 *
 * [Listener.onMerging] now streams real progress, and the merge deletes each chunk the moment it is
 * safe inside the staging file. That keeps peak disk usage at ~one extra chunk instead of a second
 * full copy of the 6.1 GB image, and it makes an interrupted merge resumable: the staging file's
 * length is exactly how far it got.
 *
 * A short chunk is repaired before the merge instead of being concatenated into a corrupt image.
 *
 * MUST be called from a background thread: it performs network I/O and shuffles ~6 GB.
 */
class FirmwareDownloader(
    initialConnections: Int = DEFAULT_CONNECTIONS,
    /** Injectable so the whole download → merge → verify pipeline can run against a local server. */
    private val url: String = Constants.IPSW_URL,
    private val totalBytes: Long = Constants.IPSW_SIZE_BYTES,
    private val expectedSha256: String = Constants.IPSW_SHA256,
    private val chunkBytes: Long = CHUNK_BYTES,
) {

    /**
     * How many range requests run at once. Wi-Fi can take more; cellular prefers fewer sockets so
     * the carrier scheduler does not punish us. Clamped to [1, MAX_CONNECTIONS].
     */
    @Volatile
    var connections: Int = initialConnections.coerceIn(1, MAX_CONNECTIONS)
        set(value) {
            field = value.coerceIn(1, MAX_CONNECTIONS)
        }

    data class Progress(
        val downloaded: Long,
        val total: Long,
        val bytesPerSec: Long,
        val chunksDone: Int,
        val chunksTotal: Int,
        val resumedBytes: Long,
    ) {
        val fraction: Float
            get() = if (total <= 0) 0f else (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f)
        val etaSeconds: Long?
            get() = if (bytesPerSec <= 0 || downloaded >= total) null else (total - downloaded) / bytesPerSec
    }

    sealed interface Outcome {
        data class Success(val file: File, val sha256: String, val resumedBytes: Long) : Outcome
        data class Failure(
            val message: String,
            val detail: String? = null,
            val cause: Throwable? = null,
        ) : Outcome

        data object Cancelled : Outcome
    }

    interface Listener {
        fun onProgress(progress: Progress)

        /** Concatenating the parts into the final image. [total] is the image size. */
        fun onMerging(done: Long, total: Long) {}

        fun onVerifying(done: Long, total: Long) {}

        fun onOutcome(outcome: Outcome)
    }

    /** Signals "this socket is not worth keeping" — reconnect from the current offset. */
    private class SlowLink(val bps: Long) : IOException("链路降到 ${bps / 1024} KB/s")

    /** Server asked us to come back later (429 / 503). */
    private class RetryAfter(val delayMs: Long) : IOException("服务器要求 ${delayMs}ms 后重试")

    /** The CDN answered 200 to a Range request, i.e. it would stream the whole file into one chunk. */
    private class RangeIgnored(val detail: String) : IOException(detail)

    /** Live view of one socket, for the watchdog. Written by the reader, read by the watchdog. */
    private class ConnStat(val chunkIndex: Int, val startedAt: Long) {
        @Volatile var bytes = 0L
        @Volatile var lastAt = startedAt

        /** Set once the response headers are in; before that the watchdog leaves it alone. */
        @Volatile var streaming = false

        /**
         * False once the chunk has decided to tolerate a genuinely slow link. The watchdog must not
         * keep killing it, or a slow-but-working connection would never finish a chunk.
         */
        @Volatile var watched = true

        /** Set by the watchdog when it tears this socket down. */
        @Volatile var killed = false

        fun bps(now: Long): Long {
            val dt = (now - startedAt).coerceAtLeast(1)
            return bytes * 1000 / dt
        }
    }

    @Volatile private var cancelled = false

    /** Live sockets, so [cancel] and the watchdog can unblock a thread sitting in `read()`. */
    private val active = ConcurrentHashMap<HttpURLConnection, ConnStat>()

    private var watchdog: ScheduledExecutorService? = null

    /** Ask the running download to stop. Safe from the main thread; returns immediately. */
    fun cancel() {
        cancelled = true
        for (c in active.keys) runCatching { c.disconnect() }
        active.clear()
    }

    /** @return true if [file] exists and matches the expected SHA-256. */
    fun verifyExisting(file: File): Boolean {
        if (!file.exists() || file.length() != totalBytes) return false
        return sha256Of(file) == expectedSha256
    }

    /** Blocking. [dest] only appears once every chunk is on disk and verified. */
    fun download(dest: File, listener: Listener): Outcome {
        cancelled = false
        // The keep-alive pool defaults to 5 sockets per host; with 8 workers the extra three would
        // pay a fresh TLS handshake on every range request.
        runCatching { System.setProperty("http.maxConnections", MAX_CONNECTIONS.toString()) }
        val total = totalBytes
        val base = dest.name.substringBeforeLast('.')
        val partDir = File(dest.parentFile, "$base.chunks").apply { mkdirs() }
        val staging = File(dest.parentFile, "${dest.name}.part")
        fun partFile(index: Int) = File(partDir, partName(base, index))
        val chunkCount = ((total + chunkBytes - 1) / chunkBytes).toInt()

        // A merge that copied every chunk but died before the rename leaves a complete staging
        // file. Adopting it avoids re-downloading 6 GB for nothing.
        if (staging.isFile && staging.length() == total &&
            partDir.listFiles()?.none { it.name.endsWith(".part") } != false
        ) {
            dest.delete()
            if (staging.renameTo(dest)) {
                Log.i(TAG, "adopted completed staging file")
                if (verifyExisting(dest)) {
                    Log.i(TAG, "adopted file verified, nothing to download")
                    listener.onOutcome(Outcome.Success(dest, expectedSha256, total))
                    return Outcome.Success(dest, expectedSha256, total)
                }
                // Garbage: we are about to rebuild the image anyway, so do not keep two copies.
                dest.delete()
            }
        }

        // A staging file left behind by a killed merge is worth keeping only if the chunks it was
        // built from still exist; otherwise it is up to 6 GB of garbage.
        runCatching {
            if (staging.isFile && partDir.listFiles()?.any { it.name.endsWith(".part") } != true) {
                staging.delete()
            }
        }

        // Trim leftovers an older build could have written past the end of a chunk (it appended a
        // whole 6 GB response into one part file), and drop empty ones.
        for (i in 0 until chunkCount) {
            val f = partFile(i)
            if (!f.isFile) continue
            val expected = chunkLength(i, total)
            if (f.length() > expected) runCatching { truncateTo(f, expected) }
            if (f.length() <= 0L) f.delete()
        }

        // What a previous run left behind. A chunk with no part file may still be complete if the
        // merge already copied it into the staging image — see ChunkRecovery.
        val shape = ChunkPlan.of(total, chunkBytes, emptyMap())
        val stagingBytes = if (staging.isFile) staging.length() else 0L
        val recovered = ChunkRecovery.scan(shape.chunks, stagingBytes) { partFile(it).length() }
        val present = recovered.presentByIndex
        val mergedAway = recovered.mergedAway
        val plan = ChunkPlan.of(total, chunkBytes, present)
        val resumedBytes = plan.presentBytes

        // The merge deletes each part as soon as it is copied, so the extra space needed is about
        // one chunk — not a second copy of the whole image.
        val needBytes = plan.remainingBytes + MERGE_HEADROOM
        val free = runCatching { partDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (free < needBytes) {
            val msg = "存储空间不足：续传还需要 ${gb(needBytes)}，可用 ${gb(free)}"
            return fail(listener, msg, "清理 ${partDir.absolutePath} 或换一个存储位置")
        }

        val pending = plan.chunks.filter { plan.present(it.index) < it.length }
        val workers = connections.coerceAtMost(pending.size.coerceAtLeast(1))
        Log.i(
            TAG,
            "download: total=$total resumed=$resumedBytes pending=${pending.size} " +
                "chunks=${plan.chunks.size} workers=$workers staging=${staging.length()}",
        )

        val failure = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(pending.size)
        val doneChunks = AtomicInteger(plan.completedChunks)
        val executor = Executors.newFixedThreadPool(workers) { r ->
            Thread(r, "ipsw-dl").apply { isDaemon = true }
        }
        val queue = java.util.concurrent.LinkedBlockingQueue(pending.map { it.index })

        startWatchdog()

        try {
            repeat(workers) {
                executor.submit {
                    while (!cancelled) {
                        val index = queue.poll() ?: return@submit
                        val chunk = plan.chunks[index]
                        val have = plan.present(index)
                        try {
                            downloadChunk(chunk, have, partFile(index))
                            doneChunks.incrementAndGet()
                        } catch (t: Throwable) {
                            if (!cancelled) failure.compareAndSet(null, t)
                        } finally {
                            latch.countDown()
                        }
                    }
                }
            }

            var reported = resumedBytes
            var lastReported = resumedBytes
            var lastAt = System.currentTimeMillis()
            var lastEmit = 0L
            var lastGrowthAt = System.currentTimeMillis()
            var ema = 0.0
            while (!latch.await(RATE_SAMPLE_MS, TimeUnit.MILLISECONDS)) {
                if (cancelled) break
                val live = liveDownloaded(plan, ::partFile, mergedAway)
                if (live > reported) {
                    reported = live
                    lastGrowthAt = System.currentTimeMillis()
                }
                val now = System.currentTimeMillis()

                // Nothing has moved for long enough that this is not jitter: the sockets are alive
                // but useless. Drop them all and let the workers reconnect from disk.
                if (now - lastGrowthAt >= TOTAL_STALL_MS) {
                    lastGrowthAt = now
                    val n = active.size
                    if (n > 0) {
                        Log.w(TAG, "no bytes for ${TOTAL_STALL_MS / 1000}s, dropping $n socket(s)")
                        for ((conn, st) in active) {
                            st.killed = true
                            runCatching { conn.disconnect() }
                        }
                    }
                }

                val dt = (now - lastAt).coerceAtLeast(1)
                val instant = (reported - lastReported) * 1000.0 / dt
                // EMA hides single-sample gaps without lagging a real speed change for long.
                ema = if (ema <= 0.0) instant else ema * (1 - RATE_EMA) + instant * RATE_EMA
                if (now - lastEmit >= EMIT_INTERVAL_MS) {
                    lastEmit = now
                    listener.onProgress(
                        Progress(
                            downloaded = reported,
                            total = total,
                            bytesPerSec = ema.toLong().coerceAtLeast(0),
                            chunksDone = doneChunks.get(),
                            chunksTotal = plan.chunks.size,
                            resumedBytes = resumedBytes,
                        )
                    )
                }
                lastAt = now
                lastReported = reported
            }

            if (cancelled) {
                val kept = liveDownloaded(plan, ::partFile, mergedAway)
                Log.i(TAG, "cancelled at $kept bytes (kept for resume)")
                listener.onOutcome(Outcome.Cancelled)
                return Outcome.Cancelled
            }

            failure.get()?.let { t ->
                val kept = plan.chunks.count { it.index !in mergedAway && partFile(it.index).length() > 0 }
                return fail(
                    listener,
                    "下载中断：${t.message ?: t.javaClass.simpleName}",
                    "已完成 $kept/${plan.chunks.size} 个分块并保留，可继续下载续传",
                    t,
                )
            }

            // Never concatenate a short chunk: that produces an image which only fails at the very
            // end, after the whole 6 GB has been written.
            val short = plan.chunks.filter {
                it.index !in mergedAway && partFile(it.index).length() < it.length
            }
            if (short.isNotEmpty()) {
                Log.w(TAG, "${short.size} chunk(s) short after transfer, repairing")
                for (c in short) {
                    if (cancelled) break
                    runCatching { downloadChunk(c, partFile(c.index).length(), partFile(c.index)) }
                }
                val still = plan.chunks.count { it.index !in mergedAway && partFile(it.index).length() < it.length }
                if (still > 0) {
                    return fail(
                        listener,
                        "分块不完整",
                        "$still 个分块补齐失败，已下载内容保留，可继续下载",
                    )
                }
            }

            // Final transfer-phase tick at exactly 100 %, so the bar never stops at 97 % and jumps
            // straight to the merge phase.
            listener.onProgress(
                Progress(
                    downloaded = liveDownloaded(plan, ::partFile, mergedAway).coerceAtMost(plan.totalBytes),
                    total = total,
                    bytesPerSec = 0,
                    chunksDone = plan.chunks.size,
                    chunksTotal = plan.chunks.size,
                    resumedBytes = resumedBytes,
                )
            )

            Log.i(TAG, "all chunks complete, merging into ${dest.name}")
            merge(plan, partDir, dest, staging, base) { done -> listener.onMerging(done, plan.totalBytes) }

            Log.i(TAG, "merged, verifying sha-256")
            val hex = sha256Of(dest) { done -> listener.onVerifying(done, dest.length()) }
            if (hex != expectedSha256) {
                dest.delete()
                cleanup(partDir)
                return fail(
                    listener,
                    "SHA-256 校验失败",
                    "期望 ${expectedSha256.take(16)}…，实际 ${hex.take(16)}…。分块已清理，请重新下载。",
                )
            }
            cleanup(partDir)
            val ok = Outcome.Success(dest, hex, resumedBytes)
            listener.onOutcome(ok)
            return ok
        } catch (t: Throwable) {
            return if (cancelled) {
                listener.onOutcome(Outcome.Cancelled)
                Outcome.Cancelled
            } else {
                fail(listener, "下载异常：${t.message ?: t.javaClass.simpleName}", null, t)
            }
        } finally {
            executor.shutdownNow()
            stopWatchdog()
            active.clear()
        }
    }

    private fun fail(listener: Listener, message: String, detail: String?, cause: Throwable? = null): Outcome {
        val o = Outcome.Failure(message, detail, cause)
        listener.onOutcome(o)
        return o
    }

    /** Sum of each chunk file, capped at its expected length. */
    private fun liveDownloaded(plan: ChunkPlan, partFile: (Int) -> File, mergedAway: Set<Int>): Long =
        plan.chunks.sumOf { c ->
            val f = partFile(c.index)
            if (f.isFile) f.length().coerceAtMost(c.length) else if (c.index in mergedAway) c.length else 0L
        }

    // ---- link watchdog -------------------------------------------------------------------------

    /**
     * Tears down sockets that are open but delivering nothing.
     *
     * This is the difference between "a stall costs 30 s" and "a stall costs 8 s". A congested
     * connection does not fail, it just stops: `read()` sits there, the pushback is invisible, and
     * the only thing that eventually notices is the read timeout. Polling for silence catches it an
     * order of magnitude sooner, and reconnecting costs one TLS handshake.
     */
    private fun startWatchdog() {
        val ex = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ipsw-watchdog").apply { isDaemon = true }
        }
        ex.scheduleWithFixedDelay({
            if (cancelled) return@scheduleWithFixedDelay
            val now = System.currentTimeMillis()
            for ((conn, st) in active) {
                if (st.killed || !st.streaming || !st.watched) continue
                val idle = now - st.lastAt
                val slow = now - st.startedAt >= SLOW_PROBE_MS && st.bps(now) < MIN_CONN_BPS
                if (idle >= MAX_IDLE_MS || slow) {
                    st.killed = true
                    Log.w(
                        TAG,
                        "watchdog: chunk ${st.chunkIndex} idle ${idle}ms " +
                            "${st.bps(now) / 1024} KB/s → reconnect",
                    )
                    runCatching { conn.disconnect() }
                }
            }
        }, WATCHDOG_TICK_MS, WATCHDOG_TICK_MS, TimeUnit.MILLISECONDS)
        watchdog = ex
    }

    private fun stopWatchdog() {
        watchdog?.shutdownNow()
        watchdog = null
    }

    /**
     * Fetch one chunk, reconnecting as needed.
     *
     * Two independent budgets:
     *  - [MAX_ATTEMPTS] for genuine failures (I/O errors, HTTP errors), with exponential backoff;
     *  - [MAX_SLOW_RESTARTS] for "connection alive but useless" events, which reconnect after a
     *    short jittered pause and, once exhausted, let the slow link finish instead of looping
     *    forever on a bad cell.
     */
    private fun downloadChunk(chunk: ChunkSpec, have: Long, out: File) {
        // Everything in here is *chunk-relative*; [fetchRange] adds chunk.start when it builds the
        // Range header. Mixing the two is what made every chunk after the first request bytes 0-…
        var done = have.coerceIn(0L, chunk.length)
        var failures = 0
        var slowRestarts = 0
        var tolerateSlow = false
        while (true) {
            if (cancelled) throw InterruptedException("cancelled")
            if (done >= chunk.length) return
            try {
                fetchRange(chunk, done, out, detectTrickle = !tolerateSlow)
                val len = out.length()
                if (len >= chunk.length) return
                done = len.coerceAtLeast(done)
                failures++
                if (failures >= MAX_ATTEMPTS) break
                Log.w(TAG, "chunk ${chunk.index} ended early at $done/${chunk.length}")
                Thread.sleep(backoffMs(failures))
            } catch (e: SlowLink) {
                done = out.length().coerceAtLeast(done)
                slowRestarts++
                Log.w(TAG, "chunk ${chunk.index} slow (${e.bps / 1024} KB/s), reconnect #$slowRestarts at $done")
                if (slowRestarts >= MAX_SLOW_RESTARTS) {
                    tolerateSlow = true
                    Log.w(TAG, "chunk ${chunk.index}: giving up on speed checks, letting it finish")
                } else {
                    // Jitter so eight workers do not stampede the same CDN edge at once.
                    Thread.sleep(150L + Random.nextLong(350L))
                }
            } catch (e: RetryAfter) {
                done = out.length().coerceAtLeast(done)
                failures++
                if (failures >= MAX_ATTEMPTS) break
                Thread.sleep(e.delayMs)
            } catch (e: RangeIgnored) {
                throw IOException("服务器未遵循 Range 请求（${e.message}），无法分块续传")
            } catch (t: Throwable) {
                if (cancelled) throw InterruptedException("cancelled")
                done = if (out.exists()) out.length().coerceAtLeast(0) else 0L
                failures++
                if (failures >= MAX_ATTEMPTS) break
                Log.w(TAG, "chunk ${chunk.index} attempt $failures failed: ${t.message} (have=$done)")
                Thread.sleep(backoffMs(failures))
            }
        }
        throw IOException("分块 ${chunk.index} 重试 $failures 次仍失败（已有 $done/${chunk.length} 字节）")
    }

    /** [from] is chunk-relative; the absolute range is derived here. */
    private fun fetchRange(chunk: ChunkSpec, from: Long, out: File, detectTrickle: Boolean) {
        if (from >= chunk.length) return
        val absolute = chunk.start + from
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Range", "bytes=$absolute-${chunk.endInclusive}")
            setRequestProperty("User-Agent", USER_AGENT)
            // Never let a transparent gzip wrapper make the byte ranges meaningless.
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Connection", "keep-alive")
        }
        val stat = ConnStat(chunk.index, System.currentTimeMillis())
        stat.watched = detectTrickle
        active[conn] = stat
        var reusable = false
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val append = from > 0L
                    if (!append) out.delete()
                    FileOutputStream(out, append).use { fos ->
                        pipe(conn.inputStream, fos, detectTrickle, stat)
                    }
                    reusable = true
                }

                HttpURLConnection.HTTP_OK -> {
                    // A proxy stripped the Range header. Only safe if we can discard the prefix;
                    // otherwise we would append the whole 6.1 GB into a 64 MB part file.
                    if (absolute > MAX_RANGE_IGNORE_SKIP) {
                        throw RangeIgnored("需要跳过 ${gb(absolute)}")
                    }
                    Log.w(TAG, "chunk ${chunk.index}: HTTP 200 instead of 206, discarding $absolute bytes")
                    out.delete()
                    FileOutputStream(out, false).use { fos ->
                        val input = conn.inputStream
                        var remaining = absolute
                        val sink = ByteArray(DISCARD_BUF)
                        while (remaining > 0) {
                            if (cancelled) throw InterruptedException("cancelled")
                            val n = input.read(sink, 0, minOf(sink.size.toLong(), remaining).toInt())
                            if (n < 0) break
                            remaining -= n
                        }
                        // Stop at the chunk boundary: the 200 body is the whole image, and letting
                        // it run would pour 6 GB into one part file.
                        pipe(input, fos, detectTrickle, stat, chunk.length - from)
                    }
                    // The response was cut short on purpose, so this socket is not reusable.
                    reusable = false
                }

                // 416: the range starts past EOF for this chunk, i.e. it is already complete.
                416 -> reusable = true

                429, 503 -> throw RetryAfter(retryAfterMs(conn))
                else -> throw IOException("HTTP $code")
            }
        } catch (t: Throwable) {
            // A socket the watchdog (or a pool-wide reset) killed is not a failure: it is a hint to
            // reconnect immediately from the bytes already on disk.
            if (stat.killed && t !is InterruptedException) {
                throw SlowLink(stat.bps(System.currentTimeMillis()))
            }
            throw t
        } finally {
            active -= conn
            // On success the socket goes back to the keep-alive cache for the next range request;
            // on failure we tear it down so a wedged connection cannot be handed out again.
            if (!reusable) runCatching { conn.disconnect() }
        }
    }

    /**
     * Copy the socket to disk, aborting when the link is alive but effectively dead.
     *
     * [detectTrickle] is disabled for the last phase of a chunk so a genuinely slow mobile link can
     * still finish; fresh connections are cheap, a 6 GB download at 10 KB/s is not.
     */
    private fun pipe(
        input: InputStream,
        out: FileOutputStream,
        detectTrickle: Boolean,
        stat: ConnStat,
        limit: Long = Long.MAX_VALUE,
    ) {
        var written = 0L
        input.use { stream ->
            val buf = ByteArray(BUF)
            val health = LinkHealth(TRICKLE_WINDOW_MS, TRICKLE_MIN_BPS)
            stat.streaming = true
            stat.lastAt = System.currentTimeMillis()
            health.reset(stat.lastAt)
            try {
                while (written < limit) {
                    if (cancelled) throw InterruptedException("cancelled")
                    val want = minOf(buf.size.toLong(), limit - written).toInt()
                    val n = stream.read(buf, 0, want)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    val now = System.currentTimeMillis()
                    stat.bytes += n
                    stat.lastAt = now
                    if (detectTrickle) {
                        val bps = health.onBytes(n.toLong(), now)
                        if (bps != null && health.isTooSlow(bps)) throw SlowLink(bps)
                    }
                }
            } finally {
                // Keep whatever made it to disk so a reconnect resumes from here.
                runCatching { out.flush() }
            }
            runCatching { out.fd.sync() }
            Log.d(TAG, "chunk ${stat.chunkIndex} connection finished at ${out.channel.size()} bytes")
        }
    }

    private fun retryAfterMs(conn: HttpURLConnection): Long {
        val raw = runCatching { conn.getHeaderField("Retry-After") }.getOrNull() ?: return 3_000
        val seconds = raw.trim().toLongOrNull() ?: return 3_000
        return (seconds * 1000).coerceIn(1_000, 30_000)
    }

    /**
     * Concatenate the parts into the image. The mechanics (progress, delete-as-you-go, resume) live
     * in [IpswMerger] so they can be unit-tested without a 6 GB download.
     */
    private fun merge(
        plan: ChunkPlan,
        partDir: File,
        dest: File,
        staging: File,
        base: String,
        onProgress: (Long) -> Unit,
    ) {
        val merged = IpswMerger.merge(
            plan = plan,
            partFile = { File(partDir, partName(base, it)) },
            dest = dest,
            staging = staging,
            onProgress = onProgress,
            isCancelled = { cancelled },
        )
        Log.i(TAG, "merge done: $merged bytes → ${dest.name}")
    }

    private fun cleanup(partDir: File) {
        partDir.listFiles()?.forEach { it.delete() }
        partDir.delete()
    }

    private fun truncateTo(file: File, size: Long) {
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.truncate(size) }
    }

    private fun backoffMs(attempt: Int): Long =
        (700L shl (attempt - 1).coerceIn(0, 3)).coerceAtMost(8_000L)

    private fun chunkLength(index: Int, total: Long): Long =
        minOf(chunkBytes, total - index.toLong() * chunkBytes)

    private fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1_073_741_824.0)

    companion object {
        private const val TAG = "FirmwareDownloader"
        private const val USER_AGENT = "HackiOS-Android/0.20"

        /**
         * 64 MB rather than 256 MB: a stalled chunk is retried sooner, the merge has finer
         * granularity, and the tail of the download is not dominated by one 256 MB range.
         */
        private const val CHUNK_BYTES = 64L * 1024 * 1024
        const val MAX_CONNECTIONS = 8
        private const val DEFAULT_CONNECTIONS = 6
        private const val MAX_ATTEMPTS = 5
        private const val MAX_SLOW_RESTARTS = 10

        private const val CONNECT_TIMEOUT_MS = 15_000

        /** Short on purpose: a healthy connection delivers 512 KB far more often than this. */
        private const val READ_TIMEOUT_MS = 12_000

        /** Rolling window used to decide "this socket is useless". */
        private const val TRICKLE_WINDOW_MS = 10_000L
        private const val TRICKLE_MIN_BPS = 16L * 1024

        private const val WATCHDOG_TICK_MS = 1_500L

        /** Silence longer than this on a single socket means the connection is gone. */
        private const val MAX_IDLE_MS = 8_000L

        /** Below this per-socket average after [SLOW_PROBE_MS], the socket is replaced. */
        private const val SLOW_PROBE_MS = 12_000L
        private const val MIN_CONN_BPS = 3L * 1024

        /** Nothing at all moved for this long across every socket: reset the whole pool. */
        private const val TOTAL_STALL_MS = 25_000L

        private const val RATE_SAMPLE_MS = 250L
        private const val EMIT_INTERVAL_MS = 500L
        private const val RATE_EMA = 0.25

        private const val BUF = 512 * 1024
        private const val DISCARD_BUF = 64 * 1024

        /** Above this, honouring an ignored Range by discarding the prefix costs too much traffic. */
        private const val MAX_RANGE_IGNORE_SKIP = 512L * 1024 * 1024

        /** Merge is copy-then-delete, so only about one chunk of headroom is needed. */
        private const val MERGE_HEADROOM = 256L * 1024 * 1024

        private fun partName(base: String, index: Int) = "%s.part%03d".format(base, index)


        fun sha256Of(file: File, onProgress: ((Long) -> Unit)? = null): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8 shl 20)
            var done = 0L
            var lastEmit = 0L
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    done += read
                    if (onProgress != null && done - lastEmit >= (64L shl 20)) {
                        lastEmit = done
                        onProgress(done)
                    }
                }
            }
            onProgress?.invoke(done)
            return BigInteger(1, digest.digest()).toString(16).padStart(64, '0')
        }

        /** True when leftover chunk data exists that a new attempt could continue from. */
        fun hasResumeData(dest: File): Boolean = resumeBytes(dest) > 0

        fun resumeBytes(dest: File): Long {
            val base = dest.name.substringBeforeLast('.')
            val dir = File(dest.parentFile, "$base.chunks")
            val parts = dir.listFiles()?.filter { it.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L
            // A merge that was interrupted leaves completed chunks inside the staging file; count
            // them so a paused download does not report "0 B kept" after copying 5 GB.
            val staging = File(dest.parentFile, "${dest.name}.part")
            val merged = if (staging.isFile) staging.length() else 0L
            return parts + merged
        }
    }
}
