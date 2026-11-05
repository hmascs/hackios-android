package com.android.gudao.iphoneos.engine

/**
 * AI 可以改的那部分 QEMU 命令行 —— 以及它不能碰的部分。
 *
 * 让模型直接编辑启动脚本是最省事的做法，也是最危险的做法：一次笔误就是「镜像还在、QEMU 起不来」，
 * 而用户看到的只有一句退出码。这里改成「只开放诊断类开关」：
 *
 *  - `-d <items>` / `-D <file>`  把 TCG 的执行轨迹写进文件，排查"卡在某条指令"必须靠它；
 *  - `-trace <pattern>`          跟踪单个设备/事件，比 `-d` 克制；
 *  - `-no-shutdown`              访客关机后保留进程，便于在崩溃现场继续 `info registers`；
 *  - `-singlestep`               单步，配合 `-d` 用；
 *  - `-gdb tcp::<port>`          挂 gdb，这是唯一的外部调试入口。
 *
 * 明确拒绝 `-S`：它会在一开始就冻结 CPU，访客永远不启动，症状与"卡死"完全一样，对排障只有误导。
 * 也拒绝一切能改变设备拓扑的开关（`-device`、`-drive`、`-object`、`-M`…），那些属于构建配置，
 * 归脚本和镜像管，不该由一次对话改掉。
 *
 * 内核启动参数同理：只允许 `key=value` 与 `-v` 这类调试开关，`cpus=` / `rd=` / `loader_return_after=`
 * 由 `QemuRunner.prepare()` 根据界面设置写入，模型改了只会让两边不一致。
 */
object QemuFlags {

    data class Parsed(val values: List<String>, val error: String? = null) {
        val ok: Boolean get() = error == null
        val text: String get() = values.joinToString(" ")
    }

    /** 允许的诊断开关，用于错误提示（也让模型知道下一步该试什么）。 */
    const val DIAGNOSTIC_HELP =
        "只允许：-d <项目>（如 int,guest_errors,unimp）、-D <文件名>、-trace <模式>、" +
            "-no-shutdown、-singlestep、-gdb tcp::<端口>"

    val BOOT_ARG_HELP = "只允许 key=value 形式（如 debug=0x144 serial=1）或 -v；" +
        "cpus= / rd= / epoch= / loader_return_after= 由 App 管理，不能改"

    private val SAFE_ITEM = Regex("^[A-Za-z0-9_,+.-]+$")
    private val SAFE_FILE = Regex("^[A-Za-z0-9._-]+$")
    private val SAFE_TRACE = Regex("^[A-Za-z0-9_*?.,:/\\[\\]-]+$")
    private val BOOT_TOKEN = Regex("^[A-Za-z0-9_.-]+(=([A-Za-z0-9_.,:/@+\\[\\]-]*))?$")

    /** 这些 boot-arg 由 App 按界面设置写入，模型再写一遍会造成两个来源打架。 */
    private val MANAGED_BOOT_KEYS = setOf("cpus", "rd", "epoch", "loader_return_after")

    private const val MAX_RAW = 240
    private const val MAX_ARGS = 10

    fun parseDiagnostics(raw: String): Parsed {
        val text = raw.trim()
        if (text.isEmpty()) return Parsed(emptyList())
        if (text.length > MAX_RAW) return Parsed(emptyList(), "参数过长（上限 $MAX_RAW 字符）")

        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "-S" -> return Parsed(
                    emptyList(),
                    "-S 会在启动瞬间冻结 CPU，访客永远不启动，看起来和卡死一样；已拒绝"
                )

                t == "-no-shutdown" || t == "-singlestep" -> {
                    out += t
                    i++
                }

                t == "-d" || t == "-D" || t == "-trace" || t == "-gdb" -> {
                    val v = tokens.getOrNull(i + 1)
                        ?: return Parsed(emptyList(), "$t 需要一个值")
                    val error = when (t) {
                        "-d" -> if (!SAFE_ITEM.matches(v)) "$t 的值只能包含字母、数字和 _ , + . -" else null
                        "-D" -> if (!SAFE_FILE.matches(v)) "$t 只接受一个纯文件名（不带路径）" else null
                        "-trace" -> if (!SAFE_TRACE.matches(v)) "$t 的值含有不支持的字符" else null
                        else -> {
                            val port = v.removePrefix("tcp::").toIntOrNull()
                            if (!v.startsWith("tcp::") || port == null || port !in 1024..65535) {
                                "-gdb 只接受 tcp::<1024-65535> 形式，例如 tcp::1234"
                            } else null
                        }
                    }
                    if (error != null) return Parsed(emptyList(), error)
                    out += t
                    out += v
                    i += 2
                }

                else -> return Parsed(emptyList(), "不允许的开关 $t。$DIAGNOSTIC_HELP")
            }
            if (out.size > MAX_ARGS * 2) return Parsed(emptyList(), "参数太多（最多 $MAX_ARGS 个开关）")
        }
        return Parsed(out)
    }

    fun parseBootArgs(raw: String): Parsed {
        val text = raw.trim()
        if (text.isEmpty()) return Parsed(emptyList())
        if (text.length > MAX_RAW) return Parsed(emptyList(), "参数过长（上限 $MAX_RAW 字符）")

        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size > MAX_ARGS) return Parsed(emptyList(), "最多 $MAX_ARGS 个启动参数")

        val out = mutableListOf<String>()
        for (t in tokens) {
            if (t == "-v") {
                out += t
                continue
            }
            if (!BOOT_TOKEN.matches(t)) {
                return Parsed(emptyList(), "无法识别的启动参数「$t」。$BOOT_ARG_HELP")
            }
            val key = t.substringBefore('=')
            if (key in MANAGED_BOOT_KEYS) {
                return Parsed(emptyList(), "「$key」由 App 管理，请改用界面上的处理器核心设置")
            }
            out += t
        }
        return Parsed(out)
    }
}
