package com.android.gudao.iphoneos

import android.graphics.Bitmap
import android.util.Log
import com.android.gudao.iphoneos.engine.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

/**
 * One decoded guest frame: pixels plus the numbers that say whether the picture is real.
 *
 * [mean] is the mean of r+g+b over the frame, 0..765. 0 means the guest never drew anything into
 * ramfb, which separates "the capture path is broken" from "there is genuinely nothing on the
 * guest screen" — two states that looked identical on screen before.
 */
data class FrameInfo(
    val bitmap: Bitmap,
    val w: Int,
    val h: Int,
    val srcW: Int,
    val srcH: Int,
    val mean: Int,
    /**
     * 稀疏采样得到的指纹，用来判断「画面是不是真的变了」。
     *
     * 只比 [mean] 不行：白底上一行小字从欢迎页换到锁屏，平均值可以一模一样。每 97 个像素取
     * 一个点做 FNV 混洗，几十微秒，够用来区分「新的一帧」和「同一帧又抓了一次」——
     * 后者正是「连接不稳定」时用户看到的静止画面。
     */
    val signature: Long = 0L,
)

/**
 * Talks to a running QEMU instance the way upstream `qemu/touch.py` and `qemu/screendump.sh` do:
 * QMP (4445) for input, HMP (4444) for `screendump`, because this app runs with `-display none`
 * and has no GL surface.
 *
 * The previous version opened a fresh TCP connection (and redid the QMP capabilities handshake)
 * for *every single pointer event*. A drag emitted ~60 events/s, i.e. 60 connect+handshake+close
 * cycles per second, each with a 5 s socket timeout, all serialised behind `@Synchronized`. That is
 * why dragging felt like it was fighting the finger. Both sessions are now persistent, and every
 * command is acknowledged by its JSON `id` instead of by scanning for the first `"return"` (which
 * could belong to an asynchronous QMP event).
 */
class QemuControl {

    private class Session(
        val socket: Socket,
        val writer: BufferedWriter,
        val reader: java.io.Reader,
    ) {
        var nextId = 1
    }

    /**
     * 两把锁，而不是一把。
     *
     * 抓帧会占住 monitor 最多 2 × 9 秒（guest 忙时 screendump 就是会慢），而触摸事件走 QMP。
     * 共用一把锁时，一次慢抓帧会让整段拖拽排在后面 —— 表现是"画面卡住时手指也卡住"。
     * QMP 与 HMP 是两个独立的 TCP 会话，没有理由互相等。
     */
    private val qmpLock = Any()

    /**
     * 0.25.0：复用 PPM 解码的两个大缓冲。旧实现每帧 `ppm.readBytes()` 分配 8 MB，
     * 再 `IntArray(cw*ch)` 分配约 11 MB；8 FPS 就是 ~150 MB/s 的临时对象，GC 线程
     * 与 QEMU 的 vCPU 线程抢核，正是「CPU 空转」在宿主侧的形态。两个数组只在
     * [captureFrame] 的同步块里使用（hmpLock 串行化），复用是安全的。
     */
    private var ppmBytes: ByteArray? = null
    private var pixelBuf: IntArray? = null
    private val hmpLock = Any()
    private var qmp: Session? = null
    private var hmp: Session? = null

    // ---- connection ---------------------------------------------------------------------------

    fun connect(timeoutMs: Int = 2000): Boolean = synchronized(qmpLock) {
        ensureQmp(timeoutMs) != null
    }

    fun close() {
        synchronized(qmpLock) {
            runCatching { qmp?.socket?.close() }
            qmp = null
        }
        synchronized(hmpLock) {
            runCatching { hmp?.socket?.close() }
            hmp = null
        }
    }

    private fun open(port: Int, timeoutMs: Int): Session? = try {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        socket.soTimeout = SOCKET_TIMEOUT_MS
        socket.tcpNoDelay = true
        Session(
            socket,
            BufferedWriter(OutputStreamWriter(socket.getOutputStream())),
            // BufferedReader 只为减少系统调用：读取仍然是逐字符的（提示符没有换行）。
            java.io.BufferedReader(InputStreamReader(socket.getInputStream())),
        )
    } catch (t: Throwable) {
        null
    }

    private fun ensureQmp(timeoutMs: Int): Session? {
        qmp?.let { if (!it.socket.isClosed) return it }
        val s = open(Constants.QMP_PORT, timeoutMs) ?: return null
        // The greeting arrives before capabilities are negotiated; it *is* newline terminated.
        runCatching { readLine(s, System.currentTimeMillis() + GREETING_TIMEOUT_MS) }
        qmp = s
        if (!qmpCommandLocked("qmp_capabilities", null)) {
            qmp = null
            runCatching { s.socket.close() }
            return null
        }
        return s
    }

    private fun ensureHmp(): Session? {
        hmp?.let { if (!it.socket.isClosed) return it }
        val s = open(Constants.MON_PORT, 1500) ?: return null
        // QEMU prints "QEMU x.y monitor ..." (with \r\n) and then the prompt "(qemu) " **without a
        // trailing newline**. The old code called readLine() here: it blocked for the whole socket
        // timeout, threw SocketTimeoutException, and the next HMP command then parsed the *stale*
        // greeting as its own completion. Every capture also paid 6 s and eventually dropped the
        // session, which is what "有时连不上画面 / 连接不稳定" actually was.
        // The socket timeout must not outlive the deadline we are about to honour, or a silent
        // monitor costs the full SO_TIMEOUT on every reconnect.
        runCatching { s.socket.soTimeout = GREETING_TIMEOUT_MS }
        val greeting = readUntilPrompt(s, System.currentTimeMillis() + GREETING_TIMEOUT_MS)
        if (greeting == null) {
            // 没有问候语不代表连接不可用（有些构建只在收到第一条命令后才打印提示符）。
            // 旧代码在这里直接判失败，于是一个"安静"的 monitor 会让画面永远起不来。
            AppLog.w(TAG, "monitor 4444 未在 ${GREETING_TIMEOUT_MS}ms 内给出提示符，先按可用处理")
            runCatching { s.socket.soTimeout = SOCKET_TIMEOUT_MS }
        }
        hmp = s
        return s
    }

    private fun dropHmp(s: Session?) {
        runCatching { s?.socket?.close() }
        if (hmp === s) hmp = null
    }

    // ---- input ---------------------------------------------------------------------------------

    /** Absolute pointer position only: no button change. Used for every move of a drag. */
    fun pointer(x: Float, y: Float) {
        val events = JSONArray()
        events.put(absAxis("x", axis(x)))
        events.put(absAxis("y", axis(y)))
        qmpCommand("input-send-event", JSONObject().put("events", events))
    }

    fun button(down: Boolean) {
        val events = JSONArray().put(btnEvent(down))
        qmpCommand("input-send-event", JSONObject().put("events", events))
    }

    /** Move + press/release in one QMP command, so the position and the button agree. */
    fun pointer(x: Float, y: Float, down: Boolean) {
        val events = JSONArray()
        events.put(absAxis("x", axis(x)))
        events.put(absAxis("y", axis(y)))
        events.put(btnEvent(down))
        qmpCommand("input-send-event", JSONObject().put("events", events))
    }

    /** Press a hardware key for [ms]. Names map to the qcodes HackiOS's kernel expects. */
    fun key(name: String, ms: Long = 80) {
        val qcode = when (name) {
            "lock" -> "l"
            "home" -> "h"
            "volup" -> "up"
            "voldown" -> "down"
            "fivefingers" -> "f11"
            else -> name
        }
        qmpKey(qcode, true)
        Thread.sleep(ms)
        qmpKey(qcode, false)
    }

    /** Ask QEMU to exit cleanly, so the monitor ports are released. */
    fun quit() {
        if (!qmpCommand("quit", null)) {
            // QMP may close the socket before replying to `quit`; that is still success.
            synchronized(qmpLock) {
                runCatching { qmp?.writer?.write("{\"execute\":\"quit\"}\n"); qmp?.writer?.flush() }
            }
        }
        close()
    }

    private fun qmpKey(qcode: String, down: Boolean) {
        val key = JSONObject().put("type", "qcode").put("data", qcode)
        val event = JSONObject().put(
            "type", "key"
        ).put("data", JSONObject().put("key", key).put("down", down))
        qmpCommand("input-send-event", JSONObject().put("events", JSONArray().put(event)))
    }

    // ---- QMP plumbing --------------------------------------------------------------------------

    private fun qmpCommand(execute: String, arguments: JSONObject?): Boolean = synchronized(qmpLock) {
        val session = ensureQmp(1500) ?: return false
        qmpCommandLocked(execute, arguments)
    }

    private fun qmpCommandLocked(execute: String, arguments: JSONObject?): Boolean {
        val s = qmp ?: return false
        return try {
            val id = s.nextId++
            val cmd = JSONObject().put("execute", execute).put("id", id)
            if (arguments != null) cmd.put("arguments", arguments)
            s.writer.write(cmd.toString())
            s.writer.write("\n")
            s.writer.flush()
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                val line = readLine(s, deadline) ?: return false
                if (!line.startsWith("{")) continue
                val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (obj.optInt("id", -1) != id) continue       // async event (SHUTDOWN, RESET…)
                if (obj.has("error")) {
                    Log.w(TAG, "qmp $execute error: ${obj.optJSONObject("error")}")
                    return false
                }
                return true
            }
            false
        } catch (t: Throwable) {
            Log.w(TAG, "qmp $execute failed: ${t.message}")
            runCatching { s.socket.close() }
            qmp = null
            false
        }
    }

    /** QMP is newline framed; read exactly one line or give up at the deadline. */
    private fun readLine(s: Session, deadlineMs: Long): String? {
        val sb = StringBuilder()
        while (System.currentTimeMillis() < deadlineMs) {
            val c = try {
                s.reader.read()
            } catch (t: java.net.SocketTimeoutException) {
                return null
            } catch (t: Throwable) {
                return null
            }
            if (c < 0) return null
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 1 shl 20) return null
        }
        return null
    }

    /**
     * Read monitor output until the "(qemu) " prompt.
     *
     * The prompt is the *only* completion signal HMP gives and it has no trailing newline, so a
     * line-oriented reader can never see it: it waits for a '\n' that never comes. Reading one
     * character at a time and matching the tail costs nothing at these volumes and makes each
     * command cost exactly as long as the command itself.
     */
    private fun readUntilPrompt(s: Session, deadlineMs: Long): String? {
        val sb = StringBuilder()
        while (System.currentTimeMillis() < deadlineMs) {
            val c = try {
                s.reader.read()
            } catch (t: java.net.SocketTimeoutException) {
                return null
            } catch (t: Throwable) {
                return null
            }
            if (c < 0) return null
            sb.append(c.toChar())
            if (endsWith(sb, PROMPT)) {
                sb.setLength(sb.length - PROMPT.length)
                return sb.toString()
            }
            // A dump of `info mtree` can be megabytes; keep only what a human/model needs.
            if (sb.length > MAX_MONITOR_CHARS) sb.delete(0, sb.length - PROMPT.length - 1)
        }
        return null
    }

    private fun endsWith(sb: StringBuilder, suffix: String): Boolean {
        if (sb.length < suffix.length) return false
        val offset = sb.length - suffix.length
        for (i in suffix.indices) if (sb[offset + i] != suffix[i]) return false
        return true
    }

    private fun absAxis(name: String, value: Int): JSONObject =
        JSONObject().put("type", "abs").put("data", JSONObject().put("axis", name).put("value", value))

    private fun btnEvent(down: Boolean): JSONObject =
        JSONObject().put("type", "btn")
            .put("data", JSONObject().put("button", "left").put("down", down))

    private fun axis(value: Float): Int = (value.coerceIn(0f, 1f) * 0x7fff).roundToInt()

    // ---- framebuffer ---------------------------------------------------------------------------

    /**
     * Grab the guest display through the HMP monitor and decode it into [reuse] when the geometry
     * matches. Returns null when QEMU is not reachable; the caller decides how to back off.
     */
    fun captureFrame(ppmPath: File, reuse: Bitmap? = null): FrameInfo? = synchronized(hmpLock) {
        val started = System.currentTimeMillis()
        if (!hmpScreendump(ppmPath)) return null
        val frame = decodePpm(ppmPath, reuse)
        val cost = System.currentTimeMillis() - started
        if (cost > SLOW_CAPTURE_MS) {
            AppLog.w(TAG, "取帧耗时 ${cost}ms（guest 忙或 PPM ${runCatching { ppmPath.length() }.getOrDefault(0L) / 1024}KB）")
        }
        frame
    }

    /**
     * Run one HMP command and return its output.
     *
     * Used by the AI toolbox (`info registers`, `info block`, `xp …`) and by nothing else: the
     * capture path has its own screendump routine, because that one must also wait for an 8 MB
     * PPM to be flushed to disk. Here the prompt alone is the completion signal, exactly as it is
     * for a human at the other end of `telnet localhost 4444`.
     */
    fun hmp(command: String, timeoutMs: Int = 5000): String = synchronized(hmpLock) {
        val out = hmpLocked(command, timeoutMs) ?: return@synchronized "（monitor 无响应：QEMU 可能没有在运行，或上一条命令还没结束）"
        out.trimEnd()
    }

    /** Write one monitor command and read until the prompt. Caller holds [lock]. */
    private fun hmpLocked(command: String, timeoutMs: Int): String? {
        val s = ensureHmp() ?: return null
        return try {
            // A screendump can legitimately take longer than the default SO_TIMEOUT; let the
            // deadline below be the thing that decides, not an earlier socket timeout.
            s.socket.soTimeout = timeoutMs + 500
            s.writer.write(command)
            s.writer.write("\n")
            s.writer.flush()
            readUntilPrompt(s, System.currentTimeMillis() + timeoutMs)
        } catch (t: Throwable) {
            AppLog.w(TAG, "monitor 命令「$command」失败：${t.message}")
            dropHmp(s)
            null
        }
    }

    /**
     * 黑屏直读探针（0.26.1）：用 HMP `pmemsave` 把 guest 物理内存 [addr] 开始的 [size] 字节
     * 导出到 [outFile]，供 [com.android.gudao.iphoneos.engine.FramebufferProbe] 判读。
     *
     * 背景：SpringBoard 的渲染只进 IOSurface，内核 MiniIOMobileFramebuffer 收到 2 次 swap 后
     * 停摆、内容不进 ramfb，screendump 只能读到 loader 时代的黑底。pmemsave 是同步命令，
     * 提示符返回即写盘完成；11 MB 级别在真机 UFS 上一次几百毫秒，只做诊断/兜底，不做常驻取帧。
     */
    fun dumpFramebuffer(addr: Long, size: Long, outFile: File, timeoutMs: Int = 20000): Boolean =
        synchronized(hmpLock) {
            if (size <= 0 || size > 64L * 1024 * 1024) return false
            outFile.parentFile?.mkdirs()
            runCatching { if (outFile.isFile) outFile.delete() }
            val hex = java.lang.Long.toHexString(addr)
            // 路径必须带双引号：HMP 对裸参数做表达式求值，"/data/..." 里的 `/` 会被当除号，
            // 报 "invalid char 't' in expression"（云端 QEMU 8.2.2 实测）。
            val out = hmpLocked("pmemsave 0x$hex $size \"${outFile.absolutePath}\"", timeoutMs)
            val ok = out != null && !out.contains("unknown command", true) &&
                outFile.isFile && outFile.length() == size
            if (!ok) {
                AppLog.w(TAG, "帧缓冲直读失败：${out?.trim()?.take(160) ?: "无提示符"}，" +
                    "文件=${outFile.length()} / 期望=$size")
            }
            ok
        }

    private fun hmpScreendump(ppmPath: File): Boolean {
        ppmPath.parentFile?.mkdirs()
        repeat(2) { attempt ->
            // Delete first. If a dump fails halfway, the previous PPM is still on disk and the
            // decoder would happily hand back a stale picture — which looks like "the screen is
            // frozen" instead of "capture failed".
            runCatching { if (ppmPath.isFile) ppmPath.delete() }
            val out = hmpLocked("screendump ${ppmPath.absolutePath}", SCREENDUMP_TIMEOUT_MS)
            if (out != null && !out.contains("unknown command", true) && ppmPath.isFile && ppmPath.length() > 0L) {
                return true
            }
            AppLog.w(TAG, "screendump 第 ${attempt + 1} 次失败：${out?.trim()?.take(200) ?: "无提示符（超时）"}")
            dropHmp(hmp)
        }
        return false
    }

    private fun decodePpm(ppm: File, reuse: Bitmap?): FrameInfo? {
        // 读到复用缓冲里：文件长度变化时按需要扩容，之后一直复用同一块内存。
        val fileLen = runCatching { ppm.length().toInt() }.getOrDefault(0)
        if (fileLen < 15 || fileLen > PPM_MAX_BYTES) return null
        var buf = ppmBytes
        if (buf == null || buf.size < fileLen) {
            buf = ByteArray(fileLen)
            ppmBytes = buf
        }
        val bytes = buf
        val len = try {
            java.io.FileInputStream(ppm).use { fis ->
                var off = 0
                while (off < fileLen) {
                    val n = fis.read(bytes, off, fileLen - off)
                    if (n <= 0) break
                    off += n
                }
                off
            }
        } catch (t: Throwable) {
            return null
        }
        if (len < 15) return null
        var i = 0
        if (bytes[0] != 'P'.code.toByte() || bytes[1] != '6'.code.toByte()) return null
        i = 2
        fun token(): String {
            while (i < len && (bytes[i] == ' '.code.toByte() || bytes[i] == '\n'.code.toByte() ||
                        bytes[i] == '\r'.code.toByte() || bytes[i] == '\t'.code.toByte())
            ) i++
            val start = i
            while (i < len && bytes[i] != ' '.code.toByte() && bytes[i] != '\n'.code.toByte() &&
                bytes[i] != '\r'.code.toByte() && bytes[i] != '\t'.code.toByte()
            ) i++
            return String(bytes, start, i - start)
        }
        val w = token().toIntOrNull() ?: return null
        val h = token().toIntOrNull() ?: return null
        token()                                            // maxval
        while (i < len && (bytes[i] == ' '.code.toByte() || bytes[i] == '\n'.code.toByte() ||
                    bytes[i] == '\r'.code.toByte() || bytes[i] == '\t'.code.toByte())
        ) i++
        if (i >= len || w <= 0 || h <= 0) return null

        val cw = minOf(w, Constants.SCREEN_W)
        val ch = minOf(h, Constants.SCREEN_H)
        val stride = (len - i) / h
        if (stride < cw * 3) return null
        if (len - i < (ch - 1) * stride + cw * 3) return null

        val target = if (reuse != null && reuse.width == cw && reuse.height == ch && !reuse.isRecycled) {
            reuse
        } else {
            Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        }
        val px = pixelBuf?.takeIf { it.size == cw * ch }
            ?: IntArray(cw * ch).also { pixelBuf = it }
        var sum = 0L
        // ~28k samples per frame is enough to tell two screens apart and costs microseconds.
        val step = ((cw.toLong() * ch) / 28_000L).coerceAtLeast(1L).toInt()
        var countdown = 0
        var hash = -0x340d631b7bdddcdbL
        for (y in 0 until ch) {
            val base = i + y * stride
            val out = y * cw
            for (x in 0 until cw) {
                val p = base + x * 3
                val r = bytes[p].toInt() and 0xff
                val g = bytes[p + 1].toInt() and 0xff
                val b = bytes[p + 2].toInt() and 0xff
                val argb = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                px[out + x] = argb
                sum += (r + g + b).toLong()
                if (countdown-- <= 0) {
                    hash = (hash xor (argb.toLong() and 0xffffffffL)) * 0x100000001b3L
                    countdown = step
                }
            }
        }
        target.setPixels(px, 0, cw, 0, 0, cw, ch)
        return FrameInfo(target, cw, ch, w, h, (sum / (cw.toLong() * ch * 3)).toInt(), hash)
    }

    companion object {
        private const val TAG = "QemuControl"
        private const val SOCKET_TIMEOUT_MS = 6000
        private const val PROMPT = "(qemu) "
        /** Connecting to the monitor and getting the first prompt is local and instantaneous. */
        private const val GREETING_TIMEOUT_MS = 2500
        /**
         * A 1125×2436 PPM is ~8 MB and QEMU writes it from the main loop while the guest keeps
         * running under TCG. 4 s was enough to time out on a busy guest and throw away a frame
         * that was about to arrive; 9 s never loses a good dump and still fails fast enough for
         * the retry banner to be useful.
         */
        private const val SCREENDUMP_TIMEOUT_MS = 9000
        private const val SLOW_CAPTURE_MS = 1500L
        private const val MAX_MONITOR_CHARS = 64 * 1024
        /** 1125×2436×3 + PPM 头，留一点余量；异常大的文件直接拒绝而不是 OOM。 */
        private const val PPM_MAX_BYTES = 16 * 1024 * 1024
    }
}
