package com.android.gudao.iphoneos.engine

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动诊断日志的落盘器：`run/boot-diagnostics.log`。
 *
 * 为什么不写进 app.log：AppLog 有 512 KB 上限且超限截半。启动期每轮 7 行 × 180 轮约
 * 1500 行 ≈ 250 KB，加上崩溃堆栈很容易触发一次 rotate，把**最需要回看的启动早期**截掉。
 * 所以诊断日志用独立文件、2 MB 上限、每次启动重新开一份（上一份备份为 .prev.log）。
 *
 * 三条纪律：单行同步落盘、超限截半保留尾部、所有方法都不抛异常（日志系统自己崩掉比丢日志更糟）。
 */
object BootDiagnosticLogger {

    const val FILE_NAME = "boot-diagnostics.log"
    const val PREV_FILE_NAME = "boot-diagnostics.prev.log"

    private const val MAX_BYTES = 2L * 1024 * 1024

    private val lock = Any()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var sink: BufferedWriter? = null
    private var path: File? = null
    private var bytes = 0L

    fun file(): File? = path
    fun prevFile(): File? = path?.let { File(it.parentFile, PREV_FILE_NAME) }

    /**
     * 开始一次新启动：把上一份改名备份，新建文件并写一行带参数的头。
     * 重复用同一 runDir 调用不会误清（只有 begin 才轮转）。
     */
    fun begin(runDir: File, header: String) {
        synchronized(lock) {
            runCatching { sink?.close() }
            sink = null
            val target = File(runDir, FILE_NAME)
            val prev = File(runDir, PREV_FILE_NAME)
            runCatching {
                runDir.mkdirs()
                if (target.isFile) {
                    prev.delete()
                    target.renameTo(prev)
                }
                target.writeText("")
            }
            path = target
            bytes = 0L
            runCatching {
                sink = BufferedWriter(OutputStreamWriter(FileOutputStream(target, true), Charsets.UTF_8))
            }
        }
        val stamp = runCatching { time.format(Date()) }.getOrDefault("--:--:--")
        append(listOf("# $stamp boot-diagnostics begin · $header"))
    }

    /** 追加若干行；每行立即 flush，崩溃前的记录不会丢。 */
    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        synchronized(lock) {
            val out = sink ?: return
            runCatching {
                for (l in lines) {
                    out.append(l).append('\n')
                    bytes += l.length + 1
                }
                out.flush()
            }
            if (bytes > MAX_BYTES) rotate()
        }
    }

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
}
