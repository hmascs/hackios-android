package com.android.gudao.iphoneos.engine

import android.graphics.Bitmap
import android.util.Log
import com.android.gudao.iphoneos.Constants
import com.android.gudao.iphoneos.FrameInfo
import com.android.gudao.iphoneos.QemuControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.roundToInt

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
        /** 本帧之后调度器决定的等待间隔（毫秒）。看得到「现在为什么慢」。 */
        val intervalMs: Long = Constants.FB_INTERVAL_MS,
    ) {
        val resolution: String get() = if (width > 0) "$width × $height" else "—"
    }

    private val ppm = ppmFile
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _frameTick = MutableStateFlow(0L)
    val frameTick: StateFlow<Long> = _frameTick.asStateFlow()

    @Volatile private var bitmap: Bitmap? = null
    @Volatile private var running = false
    @Volatile private var retryRequested = false
    private var thread: Thread? = null
    private var lastFrameAt = 0L
    private var fpsEma = 0f

    /**
     * 0.25.0：BootPhase 的文本阶段。由 AppViewModel 在每轮 updatePhase 里喂进来，
     * [FramePacer] 据此在「引导期 2 FPS / 活跃 8 FPS / 静止 1 FPS / 忙时退避」之间切换。
     * 用 @Volatile 是因为写线程是 ViewModel 的 watcher，读线程是下面的 ios-capture。
     */
    @Volatile private var bootStage = 0

    /** 由启动阶段观察器更新；纯赋值，不触发重组。 */
    fun setBootStage(stage: Int) {
        bootStage = stage
    }

    /** 当前调度档位，供 MCP perf_stats / 诊断页展示。 */
    fun currentPace(): Long = lastPacedMs

    @Volatile private var lastPacedMs = Constants.FB_INTERVAL_MS

    /** Latest frame. Read it in the draw phase together with [frameTick]. */
    fun currentFrame(): Bitmap? = bitmap

    fun start() {
        if (running) return
        running = true
        retryRequested = true
        thread = Thread({ loop() }, "ios-capture").apply {
            isDaemon = true
            start()
        }
        publish(_snapshot.value.copy(health = Health.Retrying, lastError = null))
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        publish(_snapshot.value.copy(health = Health.Stopped))
    }

    fun isRunning(): Boolean = running

    /** Reset the backoff and try immediately (the "重试" button on the capture banner). */
    fun retryNow() {
        retryRequested = true
        publish(_snapshot.value.copy(health = Health.Retrying, attempts = 0, lastError = null))
        if (!running) start()
    }

    private fun loop() {
        var attempts = 0
        var lastSignature = Long.MIN_VALUE
        var lastChangeAt = System.currentTimeMillis()
        var prevFrameAt = 0L
        var changed = 0L
        while (running) {
            val started = System.currentTimeMillis()
            val frame = try {
                control.captureFrame(ppm, bitmap)
            } catch (t: Throwable) {
                Log.w(TAG, "capture threw: ${t.message}")
                AppLog.w(TAG, "取帧抛异常：${t.message}")
                null
            }
            if (!running) break
            val cost = System.currentTimeMillis() - started

            if (frame != null) {
                attempts = 0
                bitmap = frame.bitmap
                lastFrameAt = System.currentTimeMillis()
                if (frame.signature != lastSignature || lastSignature == Long.MIN_VALUE) {
                    lastSignature = frame.signature
                    lastChangeAt = lastFrameAt
                    changed++
                }
                // No sleep when capture itself is slower than the target interval: sleeping would
                // just add latency on top of an already slow path.
                // fps is measured, not assumed: reporting the target 8 fps while a screendump
                // takes 900 ms was one more way the UI said "fine" when it was not.
                val dt = if (prevFrameAt == 0L) intervalMs else (lastFrameAt - prevFrameAt).coerceAtLeast(1L)
                prevFrameAt = lastFrameAt
                val pace = 1000f / dt
                fpsEma = if (fpsEma == 0f) pace else fpsEma * 0.8f + pace * 0.2f
                val prev = _snapshot.value
                val black = if (frame.mean <= BLACK_MEAN) prev.blackStreak + 1 else 0
                val wasLive = prev.health == Health.Live
                // 0.25.0：把调度决策放在取帧之后，用「本次真实耗时 + 画面是否静止 +
                // 当前启动阶段」决定下一帧多久来，而不是固定 125ms 硬压 QEMU。
                val paced = FramePacer.nextIntervalMs(
                    stage = bootStage,
                    unchangedForMs = lastFrameAt - lastChangeAt,
                    lastCaptureMs = cost,
                    changedFrames = changed,
                    frames = prev.frames + 1,
                    normalIntervalMs = intervalMs,
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
                    intervalMs = paced,
                )
                publish(snap)
                if (!wasLive) {
                    AppLog.i(TAG, "取帧恢复：${snap.resolution}，亮度 ${frame.mean}/765，首帧耗时 ${cost}ms，" +
                        "调度=${FramePacer.describe(paced)}")
                }
                _frameTick.value = prev.frames + 1
                // 本次取帧已经花掉 cost 毫秒，剩余等待才是真正的间隔。
                if (cost < paced) sleep(paced - cost)
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
                if (retryRequested) {
                    retryRequested = false
                    attempts = 0
                }
                if (attempts >= FAILED_AFTER) {
                    // Keep the loop alive but idle: a manual retry or a QEMU restart should not
                    // require re-entering the screen.
                    sleep(SLOW_RETRY_MS)
                    attempts = 0
                } else {
                    sleep(delay)
                }
            }
        }
        publish(_snapshot.value.copy(health = Health.Stopped))
    }

    private fun publish(s: Snapshot) {
        _snapshot.value = s
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            // stop() interrupts us; the loop condition handles the exit.
        }
    }

    internal fun backoffMs(attempt: Int): Long =
        (250L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(4_000L)

    companion object {
        private const val TAG = "CapturePipeline"
        private const val BLACK_MEAN = 2
        private const val FAILED_AFTER = 6
        private const val SLOW_RETRY_MS = 4_000L

        fun meanBrightness(snapshot: Snapshot): Int = snapshot.mean
        fun megapixels(snapshot: Snapshot): String =
            if (snapshot.width <= 0) "—"
            else "%.1f MP".format(snapshot.width.toLong() * snapshot.height / 1_000_000.0)
    }
}
