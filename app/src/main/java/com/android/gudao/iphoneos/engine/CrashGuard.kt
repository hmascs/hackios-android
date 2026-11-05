package com.android.gudao.iphoneos.engine

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把未捕获的异常写进 `run/crash.log`。
 *
 * 这个类存在的理由很直接：一次"发消息就闪退"没有任何现场可查 —— logcat 在手机上要 root 或
 * adb，用户能提供的只有一句"闪退了"。装上这个处理器之后，下一次崩溃会在应用私有目录里留下
 * 完整堆栈，日志页可以直接看到并导出。
 *
 * 三条纪律：
 *  1. **不吞异常**。记录完必须把控制权交回原来的处理器，否则系统不会杀进程，App 会停在一个
 *     半死的状态里（主线程没了 Looper），比崩溃更难查。
 *  2. **不在处理器里做重活**。只有一次 `FileOutputStream` 追加，不读磁盘、不申请大对象、
 *     不调 Android API（进程可能正在被拆掉，任何依赖 Looper/Binder 的调用都可能再抛一次）。
 *  3. **文件有上限**。`crash.log` 只保留最新的一段：一个死在循环里的 bug 能在几秒内写满存储。
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val FILE_NAME = "crash.log"

    /** 保留的字节数；超出时把旧内容截掉一半，留出继续追加的空间。 */
    private const val MAX_BYTES = 128L * 1024

    private val lock = Any()

    @Volatile private var installed = false

    /** 进程级安装，重复调用无副作用。 */
    fun install(runDir: File) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(runDir, thread, error) }
            // 交回系统：Android 的默认处理器会记录 tombstone、结束进程。
            previous?.uncaughtException(thread, error)
        }
    }

    fun file(runDir: File): File = File(runDir, FILE_NAME)

    /** 最近一次崩溃是否比 [sinceMs] 更新（用来在界面上提示"上次异常退出"）。 */
    fun crashedSince(runDir: File, sinceMs: Long): Boolean {
        val f = file(runDir)
        return f.isFile && f.length() > 0 && f.lastModified() > sinceMs
    }

    private fun write(runDir: File, thread: Thread, error: Throwable) {
        synchronized(lock) {
            runCatching { runDir.mkdirs() }
            val f = file(runDir)
            // 超限时砍掉前一半而不是清空：一次崩溃之后往往紧跟着另一次（比如崩溃处理器自己
            // 也依赖了坏状态），两次的堆栈放在一起才有因果可看。
            if (f.isFile && f.length() > MAX_BYTES) {
                val keep = runCatching { f.readBytes().copyOfRange((f.length() / 2).toInt(), f.length().toInt()) }
                    .getOrNull()
                runCatching {
                    if (keep != null) f.writeBytes(keep) else f.delete()
                }
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            // 崩溃前 App 自己记的最后几行，往往就是"点发送"这个动作本身；串口日志看不到这里。
            val before = AppLog.tailLines(25)
            val trace = StringWriter().also { sw ->
                PrintWriter(sw).use { pw ->
                    pw.println("=== $stamp ===")
                    pw.println("thread: ${thread.name} (id=${thread.id})")
                    pw.println("process: pid=${android.os.Process.myPid()}")
                    if (before.isNotEmpty()) {
                        pw.println("--- app.log 末尾 ---")
                        before.forEach(pw::println)
                        pw.println("--- 堆栈 ---")
                    }
                    error.printStackTrace(pw)
                }
            }.toString()
            f.appendText(trace + "\n")
            AppLog.crash(TAG, "未捕获异常 ${error.javaClass.simpleName}: ${error.message}", error)
            Log.e(TAG, "crash recorded: ${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
