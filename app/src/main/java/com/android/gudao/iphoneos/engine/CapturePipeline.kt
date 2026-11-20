package com.android.gudao.iphoneos.engine

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.android.gudao.iphoneos.Constants
import com.android.gudao.iphoneos.FrameInfo
import com.android.gudao.iphoneos.QemuControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Coalesces clicks without interrupting socket I/O. Stop can still interrupt the timed wait. */
internal class CaptureRetrySignal {
    private val pending = ArrayBlockingQueue<Unit>(1)

    fun request() { pending.offer(Unit) }
    fun consume(): Boolean = pending.poll() != null
    fun await(ms: Long): Boolean = try {
        pending.poll(ms, TimeUnit.MILLISECONDS) != null
    } catch (_: InterruptedException) {
        false
    }
}

/**
 * Polls the guest framebuffer and publishes the latest frame.
 *
 * The old surface simply returned `null` when `screendump` failed and retried on the next tick with
 * no state of its own: a broken capture looked identical to a guest that had not drawn yet, there
 * was no retry policy, and the only way out was to leave and re-enter the screen. This pipeline
 * keeps an explicit health state (live / retrying with attempt count and next retry / stopped), an
 * exponential backoff, and a [retryNow] that a button can call.
 *
 * It also reuses one Bitmap instead of allocating 11 MB per frame (1125 × 2436 × 4), which the old
 * per-frame `Bitmap.createBitmap` + `recycle()` churn turned into constant GC pressure.
 */
class CapturePipeline(
    private val control: QemuControl,
    ppmFile: File,
    private val intervalMs: Long = Constants.FB_INTERVAL_MS,
    frameRing: FrameRing? = null,
) {

    enum class Health { Idle, Live, Retrying, Stopped }

    data class Snapshot(
        val health: Health = Health.Idle,
        val frames: Long = 0,
        val fps: Float = 0f,
        val width: Int = 0,
        val height: Int = 0,
        val mean: Int = -1,
        val blackStreak: Int = 0,
        val attempts: Int = 0,
        val lastError: String? = null,
        /** 画面内容已经多久没有变化（毫秒）。取帧成功但每次都是同一张图时才会增长。 */
        val unchangedForMs: Long = 0,
        /** 内容真正变化过的帧数，用来把「在动」与「同一帧抓了 300 次」分开。 */
        val changedFrames: Long = 0,
        /** 单次取帧耗时（毫秒），诊断「画面慢」时比 fps 更直接。 */
        val captureMs: Long = 0,
        /** 取帧耗时拆成 QEMU 截屏与 App 解码两段。 */
        val dumpMs: Long = 0,
        val decodeMs: Long = 0,
        /**
         * 内容真正变化的帧率（每秒变几次画面），与 [fps]（每秒抓几次）分开。
         * 20 FPS 的目标看的是这个数，而不是采样率。
         */
        val contentFps: Float = 0f,
        /** 本帧之后调度器决定的等待间隔（毫秒）。看得到「现在为什么慢」。 */
        val intervalMs: Long = Constants.FB_INTERVAL_MS,
        val sceneHasContent: Boolean = false,
    ) {
        val resolution: String get() = if (width > 0) "$width × $height" else "—"
    }

    private val ppm = ppmFile
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _frameTick = MutableStateFlow(0L)
    val frameTick: StateFlow<Long> = _frameTick.asStateFlow()

    @Volatile private var bitmap: Bitmap? = null
    /**
     * Optional ring reader. Production leaves it disconnected: the current worker
     * publishes GL-surface readbacks before final panel composition. HFR2 guards
     * protect copy consistency, not final-display identity. Diagnostics may still
     * exercise the reader; the App defaults to the full-resolution framebuffer.
     */
    @Volatile private var ring: FrameRing? = frameRing
    /**
     * 最近一次从帧环拿到新帧的时刻；0 表示这个环还没出过帧。
     * 用它区分「刚启动、worker 还没出帧」与「已经接管过、正在等下一帧」。
     */
    @Volatile private var ringFrameAt = 0L
    /** 帧环比 PPM 解码少一次中间 ByteArray，但仍要一块复用的像素缓冲（约 11 MB，懒分配）。 */
    private var ringPixels: IntArray? = null
    @Volatile private var running = false
    @Volatile private var thread: Thread? = null
    private var pacing = CaptureRetrySignal() // accessed under this object's monitor
    private var lastFrameAt = 0L
    private var fpsEma = 0f
    private var lastSignatureForContent = Long.MIN_VALUE
    /** 最近 CONTENT_WINDOW_MS 内画面变化的时间戳，用来算内容帧率。 */
    private val changeTimes = ArrayDeque<Long>()
    private var firstFrameAt = 0L
    @Volatile private var contentFps = 0f

    /**
     * 0.25.0：BootPhase 的文本阶段，只用于诊断文案（`取帧调度` 一行里附带）。
     * 0.32.0 起不再用它降帧：引导期和桌面都按同一个 60 FPS 目标发请求。
     * 用 @Volatile 是因为写线程是 ViewModel 的 watcher，读线程是下面的 ios-capture。
     */
    @Volatile private var bootStage = 0

    /** 由启动阶段观察器更新；纯赋值，不触发重组。 */
    fun setBootStage(stage: Int) {
        bootStage = stage
    }

    /** 当前文本阶段（仅诊断用）。 */
    fun stage(): Int = bootStage

    /** 当前调度档位，供 MCP perf_stats / 诊断页展示。 */
    fun currentPace(): Long = lastPacedMs

    @Volatile private var lastPacedMs = Constants.FB_INTERVAL_MS

    /** Latest frame. Read it in the draw phase together with [frameTick]. */
    fun currentFrame(): Bitmap? = bitmap

    /**
     * A6：接上/摘掉帧环。
     *
     * 传 null 就回到纯 screendump 路径——这正是默认开关关闭、或 worker 还没写头的状态。
     * 允许在运行中替换，是因为 worker 是 QEMU 启动之后才就绪的。
     */
    fun useFrameRing(value: FrameRing?) {
        // 换环就等于换了一条数据源，必须重新从「还没出过帧」开始判断。
        if (ring !== value) ringFrameAt = 0L
        ring = value
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        val signal = CaptureRetrySignal()
        pacing = signal
        synchronized(changeTimes) {
            changeTimes.clear()
            lastSignatureForContent = Long.MIN_VALUE
            firstFrameAt = 0L
            contentFps = 0f
        }
        ringFrameAt = 0L
        thread = Thread({ loop(signal) }, "ios-capture").apply { isDaemon = true }
        publish(_snapshot.value.copy(health = Health.Retrying, lastError = null, sceneHasContent = false))
        thread?.start()
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        publish(_snapshot.value.copy(health = Health.Stopped))
    }

    fun isRunning(): Boolean = running

    /** Wake a paced/backing-off worker; never start a concurrent grab over an in-flight grab. */
    @Synchronized
    fun retryNow() {
        publish(_snapshot.value.copy(health = Health.Retrying, attempts = 0, lastError = null))
        if (running) pacing.request() else start()
    }

    /**
     * 记录一次画面变化并按窗口折算内容帧率。
     * 返回本秒真实变化次数；窗口未满 1 秒时按已过去的时长折算，避免刚启动就报 0。
     */
    private fun noteContentChange(signature: Long, now: Long): Float {
        if (firstFrameAt == 0L) firstFrameAt = now
        if (signature != lastSignatureForContent) {
            lastSignatureForContent = signature
            changeTimes.addLast(now)
        }
        while (changeTimes.isNotEmpty() && now - changeTimes.first() > CONTENT_WINDOW_MS) {
            changeTimes.removeFirst()
        }
        return windowFps(changeTimes.size, now - firstFrameAt)
    }

    /**
     * A6：从帧环取一帧。返回 null 表示环里没有新帧或环不可用，调用方必须继续 screendump。
     *
     * 只有接上环才会走到这里；默认关闭时连这块 11 MB 的复用缓冲都不会分配。
     */
    private fun frameFromRing(ring: FrameRing): FrameInfo? {
        val w = Constants.SCREEN_W
        val h = Constants.SCREEN_H
        val px = ringPixels?.takeIf { it.size == w * h } ?: IntArray(w * h).also { ringPixels = it }
        val copyStarted = SystemClock.elapsedRealtime()
        if (!ring.latestInto(px, w, h)) return null
        val decodeMs = SystemClock.elapsedRealtime() - copyStarted
        // 指纹与 PPM 解码共用 QemuPpm.stats：换取帧路径时 changedFrames / contentFps 的
        // 含义必须不变，否则同一张画面会被两条路径算成「变了」。
        val stats = QemuPpm.stats(px, w, h)
        val target = bitmap?.takeIf { it.width == w && it.height == h && !it.isRecycled }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        target.setPixels(px, 0, w, 0, 0, w, h)
        return FrameInfo(
            bitmap = target,
            w = w,
            h = h,
            srcW = w,
            srcH = h,
            mean = stats.mean,
            signature = stats.signature,
            sceneHasContent = stats.sceneHasContent,
            // 这一帧没有经过 QEMU 截屏，dump 段恒为 0；decodeMs 只算环内拷贝。
            dumpMs = 0L,
            decodeMs = decodeMs,
        )
    }

    private fun loop(signal: CaptureRetrySignal) {
        val worker = Thread.currentThread()
        var attempts = 0
        var lastSignature = Long.MIN_VALUE
        var lastChangeAt = SystemClock.elapsedRealtime()
        var prevFrameAt = 0L
        var changed = 0L
        var retry = false
        while (thread === worker) {
            val pendingRetry = signal.consume()
            if (retry || pendingRetry) attempts = 0
            retry = false
            var started = 0L
            var fromRing: FrameInfo? = null
            val liveRing = ring
            if (liveRing != null) {
                if (ringFrameAt == 0L) {
                    // 环还没出过帧（含早期引导 seq=0）：只试一次，失败立刻 screendump，
                    // 让用户马上看到画面；worker 开始出帧后下一拍会自动接管。
                    started = SystemClock.elapsedRealtime()
                    fromRing = try {
                        frameFromRing(liveRing)
                    } catch (t: Throwable) {
                        AppLog.w(TAG, "帧环取帧失败，回退 screendump：${t.message}")
                        null
                    }
                    if (fromRing != null) ringFrameAt = started
                } else {
                    // 环已经接管过画面：等它的下一帧，最多等 3 秒（协议读者规则 4）。
                    // 这段等待里不能 screendump —— worker 只有 2–5 FPS 时，16 ms 一拍都去
                    // 截屏会重新占满 QEMU 主循环，帧环省下的时间就全还回去了。
                    val deadline = ringFrameAt + RING_STALL_MS
                    while (thread === worker) {
                        started = SystemClock.elapsedRealtime()
                        fromRing = try {
                            frameFromRing(liveRing)
                        } catch (t: Throwable) {
                            AppLog.w(TAG, "帧环取帧失败，回退 screendump：${t.message}")
                            null
                        }
                        if (fromRing != null) {
                            ringFrameAt = started
                            break
                        }
                        if (started >= deadline) break
                        signal.await(RING_POLL_MS)
                    }
                    if (thread !== worker) break
                }
            }
            // 没接环 / 环没帧 / 环停了 3 秒：这一拍回到原来的 screendump 路径。
            if (fromRing == null) started = SystemClock.elapsedRealtime()
            val frame = try {
                fromRing ?: control.captureFrame(ppm, bitmap)
            } catch (t: Throwable) {
                Log.w(TAG, "capture threw: ${t.message}")
                AppLog.w(TAG, "取帧抛异常：${t.message}")
                null
            }
            if (thread !== worker) break
            val cost = SystemClock.elapsedRealtime() - started

            if (frame != null) {
                attempts = 0
                bitmap = frame.bitmap
                lastFrameAt = SystemClock.elapsedRealtime()
                if (frame.signature != lastSignature || lastSignature == Long.MIN_VALUE) {
                    lastSignature = frame.signature
                    lastChangeAt = lastFrameAt
                    changed++
                }
                // No sleep when capture itself is slower than the target interval: sleeping would
                // just add latency on top of an already slow path.
                // fps is measured, not assumed: reporting the 60 fps target while a screendump
                // takes 900 ms was one more way the UI said "fine" when it was not.
                val dt = if (prevFrameAt == 0L) intervalMs else (lastFrameAt - prevFrameAt).coerceAtLeast(1L)
                prevFrameAt = lastFrameAt
                val pace = 1000f / dt
                fpsEma = if (fpsEma == 0f) pace else fpsEma * 0.8f + pace * 0.2f
                // 内容帧率：数「指纹变了」的帧，按最近 CONTENT_WINDOW_MS 的滑动窗口折算成每秒。
                // 用窗口计数而不是指数滑动，是因为画面真的 0.2 Hz 时滑动值会在 0 和采样率之间
                // 来回跳（看一眼 9、再看一眼 0），而窗口计数稳定报 0.2——这是能直接和
                // 「20 FPS 目标」比较的数。采样率仍单独显示，否则又会把「抓得快」当成「画得动」。
                contentFps = noteContentChange(frame.signature, lastFrameAt)
                val prev = _snapshot.value
                val black = if (frame.mean <= BLACK_MEAN) prev.blackStreak + 1 else 0
                val wasLive = prev.health == Health.Live
                // 0.25.0：把调度决策放在取帧之后，用「本次真实耗时 + 画面是否静止 +
                // 当前启动阶段」决定下一帧多久来，而不是固定 125ms 硬压 QEMU。
                // 画面静止超过 2 秒就把采样降到 4 次/秒：每次 screendump 都占 QEMU 主循环，
                // 来宾 0.1–0.3 FPS 时 99% 的采样拍的是同一张图，纯属和 TCG 抢 BQL。
                val staticForMs = if (lastChangeAt == 0L) 0L else lastFrameAt - lastChangeAt
                val paced = FramePacer.nextIntervalMs(
                    lastCaptureMs = cost,
                    normalIntervalMs = intervalMs,
                    staticForMs = staticForMs,
                )
                lastPacedMs = paced
                val snap = prev.copy(
                    health = Health.Live,
                    frames = prev.frames + 1,
                    fps = fpsEma,
                    width = frame.w,
                    height = frame.h,
                    mean = frame.mean,
                    blackStreak = black,
                    attempts = 0,
                    lastError = null,
                    unchangedForMs = lastFrameAt - lastChangeAt,
                    changedFrames = changed,
                    captureMs = cost,
                    dumpMs = frame.dumpMs,
                    decodeMs = frame.decodeMs,
                    contentFps = contentFps,
                    intervalMs = paced,
                    sceneHasContent = frame.sceneHasContent,
                )
                publish(snap)
                if (!wasLive) {
                    AppLog.i(TAG, "取帧恢复：${snap.resolution}，亮度 ${frame.mean}/255，首帧耗时 ${cost}ms，" +
                        "调度=${FramePacer.describe(paced)}")
                }
                _frameTick.value = prev.frames + 1
                // 本次取帧已经花掉 cost 毫秒，剩余等待才是真正的间隔。
                if (cost < paced) retry = signal.await(paced - cost)
            } else {
                attempts++
                val delay = backoffMs(attempts)
                val prev = _snapshot.value
                val stopped = attempts >= FAILED_AFTER
                publish(
                    prev.copy(
                        health = if (stopped) Health.Stopped else Health.Retrying,
                        attempts = attempts,
                        lastError = if (stopped)
                            "连续 $attempts 次取帧失败（监视器 4444 不可达或 QEMU 未运行）"
                        else "取帧失败，${delay}ms 后重试（第 $attempts 次）",
                    )
                )
                if (stopped && prev.health != Health.Stopped) {
                    AppLog.e(TAG, "连续 $attempts 次取帧失败，暂停自动重试（monitor 4444）")
                }
                if (stopped) {
                    // Keep the loop alive but idle: manual retry wakes the same worker.
                    retry = signal.await(SLOW_RETRY_MS)
                    attempts = 0
                } else {
                    retry = signal.await(delay)
                }
            }
        }
    }

    private fun publish(s: Snapshot) {
        _snapshot.value = s
    }

    internal fun backoffMs(attempt: Int): Long =
        (250L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(4_000L)

    companion object {
        private const val TAG = "CapturePipeline"
        private const val BLACK_MEAN = 2
        private const val FAILED_AFTER = 6
        private const val SLOW_RETRY_MS = 4_000L
        /**
         * 内容帧率的统计窗口：窗口内的变化帧数 ÷ 实际窗口时长。
         * 5 秒是「最低可诚实报出 0.2 fps」与「变化后立刻有反馈」的折中：更短的窗口会把
         * 5 秒一帧的画面报成 0.33 然后掉回 0，看起来像卡住；更长则反应太慢。
         * 比 0.2 fps 更慢的画面看 unchangedForMs（已静止多久），不要看这个数。
         */
        private const val CONTENT_WINDOW_MS = 5_000L

        /** 帧环等待下一帧的轮询间隔（协议建议 8 ms，够细但不会自己占满一个核）。 */
        private const val RING_POLL_MS = 8L

        /**
         * seq 连续这么久不前进，就判定 worker 没在出帧（早期引导、worker 崩了、旧 bundle），
         * 回退 screendump；一旦环重新出现新 seq，下一拍又自动切回来。
         */
        private const val RING_STALL_MS = 3_000L

        /**
         * 内容帧率 = 窗口内变化次数 ÷ 实际窗口时长。窗口未满时按已过去时长折算，
         * 所以刚启动时不会因为「还没攒够 3 秒」而永远显示 0。
         */
        internal fun windowFps(changes: Int, elapsedMs: Long): Float {
            if (changes <= 0 || elapsedMs <= 0L) return 0f
            return changes * 1000f / minOf(elapsedMs, CONTENT_WINDOW_MS)
        }

        fun meanBrightness(snapshot: Snapshot): Int = snapshot.mean
        fun megapixels(snapshot: Snapshot): String =
            if (snapshot.width <= 0) "—"
            else "%.1f MP".format(snapshot.width.toLong() * snapshot.height / 1_000_000.0)
    }
}
