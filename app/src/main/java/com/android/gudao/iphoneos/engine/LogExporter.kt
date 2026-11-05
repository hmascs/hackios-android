package com.android.gudao.iphoneos.engine

import java.io.File

/**
 * 把磁盘上的日志**整份**导出成一个文件。
 *
 * 日志页只能持有内存里的一小段（列表有上限，`复制` 也只复制手上这些），而排查一次启动
 * 需要的是全部：串口几十万行里的某一段、QEMU 自己的 stderr、构建脚本每一步的输出。之前
 * 只能靠「复制」往外带几百行，结果是最有用的那段永远在框外。
 *
 * 这里直接读文件，不经过内存里的环形缓冲，所以导出的行数与界面上看到的无关；同时把它
 * 清洗成人能读的样子：QEMU 的 `-serial file:` 会把 UEFI 的清屏序列（ESC[...）原样写进去，
 * 不过滤的话文件里夹着一堆控制字符，粘到哪里都乱。
 */
object LogExporter {

    data class Section(
        val title: String,
        val file: File?,
        /**
         * 只保留最后 N 行；0 表示整份。构建日志里最典型的是 `fetch.sh: listing the image`
         * 后面跟着四万行文件名 —— 它们对任何一次排障都没有用，却能把一份 500 KB 的导出
         * 撑到 3 MB，让分享面板和读取它的人都变慢。
         */
        val maxLines: Int = 0,
    )

    data class Result(
        val file: File,
        val lines: Long,
        val bytes: Long,
    )

    /** 每次导出最多读这么多行，防止一个失控的日志把手机卡死（50 万行 ≈ 60 MB）。 */
    private const val MAX_LINES = 500_000L

    fun export(dest: File, header: List<String>, sections: List<Section>): Result {
        dest.parentFile?.mkdirs()
        var lines = 0L
        dest.bufferedWriter(Charsets.UTF_8).use { out ->
            header.forEach { out.append(it).append('\n') }
            out.append('\n')
            for (section in sections) {
                val file = section.file?.takeIf { it.isFile }
                out.append("=".repeat(24)).append(' ').append(section.title)
                out.append("  ·  ").append(file?.name ?: "无")
                out.append(' ').append("=".repeat(24)).append('\n')
                if (file == null) {
                    out.append('\n')
                    continue
                }
                file.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    if (section.maxLines <= 0) {
                        for (raw in seq) {
                            if (lines >= MAX_LINES) {
                                out.append("…（已达 $MAX_LINES 行上限，其余内容未写入）\n")
                                return@useLines
                            }
                            out.append(clean(raw)).append('\n')
                            lines++
                        }
                    } else {
                        val ring = ArrayDeque<String>()
                        var total = 0L
                        for (raw in seq) {
                            total++
                            ring.addLast(raw)
                            if (ring.size > section.maxLines) ring.removeFirst()
                        }
                        if (total > section.maxLines) {
                            out.append("…（共 $total 行，仅保留最后 ${section.maxLines} 行）\n")
                        }
                        for (raw in ring) {
                            if (lines >= MAX_LINES) break
                            out.append(clean(raw)).append('\n')
                            lines++
                        }
                    }
                }
                out.append('\n')
            }
        }
        return Result(dest, lines, dest.length())
    }

    /**
     * 去掉 ANSI 控制序列与 NUL。绝大多数行没有 ESC，先判一次再走正则，几十万行也只有
     * 开头那几十行走慢路径。
     */
    fun clean(line: String): String {
        if (line.indexOf('\u001B') < 0 && line.indexOf('\u0000') < 0) return line
        val noEsc = ANSI.replace(line, "")
        return noEsc.replace("\u0000", "")
    }

    /**
     * CSI 序列（ESC [ 参数 中间字节 终止字节）以及两字节的 ESC 组合。
     * 参数字节是 0x30–0x3F，`=` 与 `>` 也在其中 —— UEFI 的 `ESC[=3h` 就是靠这个才被去掉的。
     */
    private val ANSI = Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|[@-Z\\\\-_])")
}
