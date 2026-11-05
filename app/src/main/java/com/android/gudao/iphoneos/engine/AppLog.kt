package com.android.gudao.iphoneos.engine

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App 自己的运行日志：`run/app.log`。
 *
 * 为什么需要它：0.20.0 导出的那份日志里，串口、QEMU、构建输出都在，唯独没有**App 自己**
 * 说了什么 —— 而用户遇到的恰恰是「画面偶尔连不上」「MCP 工具调用异常」这类 App 侧故障。
 * Android 上的 logcat 第三方进程读不到别人的日志，崩溃又发生在 UI 线程之外，所以只能自己记。
 *
 * 三条纪律与 [CrashGuard] 一致：写文件用追加、单行同步落盘（量很小，一次几行）、超过上限
 * 就截半，避免一个循环把手机存储写满。所有方法都不会抛异常：日志系统自己崩掉比丢日志更糟。
 */
object AppLog {

    private const val MAX_BYTES = 512L * 1024
    private const val TWO_LINES = 400

    private val lock = Any()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var sink: BufferedWriter? = null
    private var path: File? = null
    private var bytes = 0L
    private val recent = ArrayDeque<String>()

    /** 绑定运行目录；重复调用换目录时会先关掉旧文件。 */
    fun attach(runDir: File) {
        synchronized(lock) {
            val target = File(runDir, "app.log")
            if (path?.absolutePath == target.absolutePath && sink != null) return
            runCatching { sink?.close() }
            sink = null
            path = target
            recent.clear()
            bytes = runCatching { if (target.isFile) target.length() else 0L }.getOrDefault(0L)
            runCatching {
                target.parentFile?.mkdirs()
                sink = BufferedWriter(OutputStreamWriter(FileOutputStream(target, true), Charsets.UTF_8))
            }
        }
        i("AppLog", "---- 日志开始 · pid=${android.os.Process.myPid()} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} ----")
    }

    fun file(): File? = path

    fun d(tag: String, message: String) = write("D", tag, message, null)

    fun i(tag: String, message: String) = write("I", tag, message, null)

    fun w(tag: String, message: String) = write("W", tag, message, null)

    fun e(tag: String, message: String, error: Throwable? = null) = write("E", tag, message, error)

    /** 供崩溃处理器调用：不依赖 Looper / Binder，只做字符串拼接与一次写入。 */
    fun crash(tag: String, message: String, error: Throwable) = write("E", tag, message, error)

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val stamp = runCatching { time.format(Date()) }.getOrDefault("--:--:--")
        val body = message.replace('\n', ' ').replace('\r', ' ').let {
            if (it.length > TWO_LINES) it.take(TWO_LINES) + "…" else it
        }
        val head = "$stamp $level/$tag: $body"
        synchronized(lock) {
            recent.addLast(head)
            while (recent.size > 400) recent.removeFirst()
            val out = sink ?: return
            runCatching {
                out.append(head).append('\n')
                if (error != null) {
                    out.append(error.javaClass.name).append(": ")
                        .append(error.message ?: "(no message)").append('\n')
                    error.stackTrace.take(24).forEach { out.append("    at ").append(it.toString()).append('\n') }
                }
                out.flush()
            }
            bytes += head.length + 1
            if (bytes > MAX_BYTES) rotate()
        }
    }

    /** 截半而不是清空：崩溃前的那几百行往往正是原因。 */
    private fun rotate() {
        val f = path ?: return
        runCatching { sink?.close() }
        sink = null
        val keep = runCatching {
            val all = f.readBytes()
            all.copyOfRange(all.size / 2, all.size)
        }.getOrNull()
        runCatching {
            if (keep != null) f.writeBytes(keep) else f.delete()
            sink = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
            bytes = keep?.size?.toLong() ?: 0L
        }
    }

    /** 崩溃现场的尾巴，交给 CrashGuard 一起写进 crash.log。 */
    fun tailLines(max: Int): List<String> = synchronized(lock) {
        recent.toList().takeLast(max)
    }
}
