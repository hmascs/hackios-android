package com.android.gudao.iphoneos.mcp

import android.util.Log
import com.android.gudao.iphoneos.engine.QemuFlags
import com.android.gudao.iphoneos.engine.TailText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 工具执行结果。`text` 会原样回到 MCP 客户端，所以它必须自带上下文（哪个文件、多少行、退出码）。 */
data class ToolResult(val ok: Boolean, val text: String) {
    companion object {
        fun ok(text: String) = ToolResult(true, text)
        fun fail(text: String) = ToolResult(false, text)
    }
}

/** 工具自身可预期的拒绝（越界路径、没有写权限、参数不合法），不需要打印堆栈。 */
class ToolError(message: String) : Exception(message)

data class ShellResult(val exit: Int, val output: String, val timedOut: Boolean)

fun interface ShellRunner {
    /**
     * [root] 表示请求以 root 跑（`su -c`）。实现方负责处理"设备没有 su"的情况——
     * 返回一个能说清楚的失败，而不是抛一个客户端读不懂的 IOException。
     */
    fun run(command: String, cwd: File, env: Map<String, String>, timeoutSec: Int, root: Boolean): ShellResult
}

/**
 * MCP 工具箱与 App 之间的窄接口。
 *
 * 客户端能做的事全部落在这几个方法上：读状态/配置、发 monitor 命令、截一帧、列出链路目录，
 * 以及一个统一的 [dispatch] 用来执行所有会改变链路的动作。把边界收在这里，[ToolBox] 就是
 * 纯文件与字符串逻辑，可以在 JVM 上直接测，不需要一台手机。
 *
 * [allowWrite] / [fullAccess] 刻意做成 getter：ViewModel 从当前配置实时取值，权限一改，
 * 下一次 `tools/list` 立刻给出新的工具表，端口不用重启。
 */
interface QemuBridge {
    val runDir: File
    val allowWrite: Boolean
    val fullAccess: Boolean get() = false

    fun buildLogDir(): File

    /**
     * 整条链路涉及的目录：`run`（QEMU、工具链、payload、日志）、`images`（system/root.apfs）、
     * `ipsw`（6.1 GB 固件）、`logs`（导出日志）、`exports`（分享缓存）。
     * [ToolBox] 用它们做 inspect_chain / delete_chain，不自己猜路径。
     */
    fun chainRoots(): Map<String, File>

    suspend fun status(): String
    suspend fun config(): String
    suspend fun mcpInfo(): String
    suspend fun hmp(command: String): String
    suspend fun screen(): String

    /** 所有会改变链路的动作（启停 QEMU、改配置、编译、下载、删链……）。失败时抛 [ToolError]。 */
    suspend fun dispatch(action: String, args: JSONObject): String
}

/**
 * MCP 客户端可以调用的动作集合。
 *
 * 权限分三档，工具表按档裁剪——不是"调用后再拒绝"，而是低权限档根本看不到那些工具：
 *
 *  - 只读（[QemuBridge.allowWrite]=false）：读日志、看状态/配置、列文件、截帧、HMP 只读命令、
 *    inspect_chain。
 *  - 读写：上面全部 + 启停/重启/清理 QEMU、改参数与配置、编译镜像、下载固件、导出日志、
 *    写文件、跑命令（路径限制在 `run/` 内，危险命令有黑名单）。
 *  - 完全访问（[QemuBridge.fullAccess]=true）：路径与命令放开到整台 Android，并额外开放
 *    `delete_chain`——分级删除整条链路的产物，需要确认串且默认 dry-run。
 *
 * 关于安全边界要说清楚：这是**护栏**，不是沙箱。完全访问档下客户端可以改动设备上任何
 * App 有权改动的东西，这正是默认关闭、并且要用户在设置里单独确认的原因。端口本身另有
 * Bearer token 鉴权（见 [McpHttpServer]）。
 */
class ToolBox(
    private val bridge: QemuBridge,
    private val shell: ShellRunner = RealShell,
) {

    private val allowWrite: Boolean get() = bridge.allowWrite
    private val fullAccess: Boolean get() = bridge.allowWrite && bridge.fullAccess

    /** 提供给客户端看的工具表，MCP `tools/list` 的原生格式。每次读取都按当前权限重算。 */
    val specs: JSONArray get() = buildSpecs(allowWrite, fullAccess)

    suspend fun execute(name: String, rawArgs: String): ToolResult {
        val args = runCatching { JSONObject(rawArgs.ifBlank { "{}" }) }
            .getOrElse { return ToolResult.fail("参数不是合法 JSON：${rawArgs.take(200)}") }
        return try {
            when (name) {
                // ---- 只读 ----
                "device_status" -> ToolResult.ok(bridge.status())
                "get_config" -> ToolResult.ok(bridge.config())
                "mcp_info" -> ToolResult.ok(bridge.mcpInfo())
                "boot_timeline" -> bootTimeline()
                "perf_stats" -> perfStats()
                "errors" -> errorCatalog()
                "inspect_chain" -> ToolResult.ok(inspectChain())
                "capture_screen" -> ToolResult.ok(bridge.screen())
                "read_log" -> readLog(args)
                "list_files" -> listFiles(args)
                "read_file" -> readFile(args)
                "hmp" -> hmp(args)

                // ---- 生命周期 ----
                "start_qemu" -> act("start_qemu", args)
                "stop_qemu" -> act("stop_qemu", args)
                "restart_qemu" -> act("restart_qemu", args)
                "force_cleanup" -> act("force_cleanup", args)
                "send_key" -> sendKey(args)

                // ---- 参数与配置 ----
                "set_smp" -> setSmp(args)
                "set_host_cores" -> setHostCores(args)
                "set_mttcg" -> setMttcg(args)
                "set_auto_fallback" -> setAutoFallback(args)
                "set_net" -> setNet(args)
                "set_qemu_flags" -> setQemuFlags(args)
                "set_boot_args" -> setBootArgs(args)
                "set_config" -> setConfig(args)

                // ---- 链路资源 ----
                "deploy_assets" -> act("deploy_assets", args)
                "compile_images" -> act("compile_images", args)
                "cancel_compile" -> act("cancel_compile", args)
                "download_firmware" -> act("download_firmware", args)
                "cancel_download" -> act("cancel_download", args)
                "verify_firmware" -> act("verify_firmware", args)
                "export_logs" -> act("export_logs", args)
                "run_diagnostics" -> act("run_diagnostics", args)
                "refresh_artifacts" -> act("refresh_artifacts", args)
                "reset_config" -> resetConfig()

                // ---- 文件与命令 ----
                "write_file" -> writeFile(args)
                "run_shell" -> runShell(args)
                "android_log" -> androidLog(args)

                // ---- 删链（完全访问） ----
                "delete_chain" -> deleteChain(args)

                else -> ToolResult.fail("没有这个工具：$name。先调 tools/list 看当前权限档可用的工具。")
            }
        } catch (e: ToolError) {
            ToolResult.fail(e.message.orEmpty())
        } catch (t: Throwable) {
            Log.w(TAG, "tool $name failed", t)
            ToolResult.fail("执行失败：${t.javaClass.simpleName} ${t.message.orEmpty()}")
        }
    }

    // ---- 只读工具 ------------------------------------------------------------------------------

    /**
     * 启动分段计时报告。优先读 `run/boot-timeline.json`（App 每到达一个阶段就写一次），
     * 文件不存在时明确告诉客户端「还没启动过」，而不是抛一个读不懂的 IO 错误。
     */
    private fun bootTimeline(): ToolResult {
        val f = File(bridge.runDir, "boot-timeline.json")
        if (!f.isFile) {
            return ToolResult.ok(
                "还没有启动计时数据（${f.absolutePath} 不存在）。\n" +
                    "先 start_qemu，然后每隔几秒调一次本工具；到达「亮 Logo / 进入桌面」时报告里会给出实际耗时与 90s/180s 预算的达成情况。"
            )
        }
        val text = runCatching { f.readText() }.getOrElse { return ToolResult.fail("读取计时文件失败：${it.message}") }
        return ToolResult.ok(
            "文件 boot-timeline.json（log=logo/desktop 里程碑，delta_ms=本段耗时）：\n" + text +
                "\n预算：Logo ≤ 90000ms，桌面 ≤ 180000ms。真机跑完以这里的数字验收；云端 x86 软件模拟不具备测速意义。"
        )
    }

    /** 0.25.0：CPU 空转观测。文件由 AppViewModel 每轮 updatePhase 落盘，纯文本读取。 */
    private fun perfStats(): ToolResult {
        val f = File(bridge.runDir, "perf-stats.json")
        if (!f.isFile) {
            return ToolResult.ok(
                "还没有性能统计（${f.absolutePath} 不存在）。先 start_qemu，" +
                    "等启动阶段推进后本工具会给出自适应取帧档位、单次取帧耗时和 CPU 让出情况。"
            )
        }
        val text = runCatching { f.readText() }.getOrElse { return ToolResult.fail("读取统计失败：${it.message}") }
        return ToolResult.ok("文件 perf-stats.json（0.25.0 CPU 空转观测）：\n" + text)
    }

    /**
     * 0.26.0 错误细化档案：返回 run/error-catalog.log 的完整结构化档案。每类错误带
     * 层级 / 严重度 / 是否阻塞 / 组件 / 根因 / 影响 / 处置 / 原文证据 / 聚合次数 / 抽取参数，
     * 而不是像 lsd 那样压成一句「CPU 出错之类」。排障时应先看这里再看 read_log(serial)。
     */
    private fun errorCatalog(): ToolResult {
        val f = File(bridge.runDir, "error-catalog.log")
        if (!f.isFile) {
            return ToolResult.ok(
                "还没有错误档案（${f.absolutePath} 不存在）。先 start_qemu，" +
                    "错误出现时 App 会把每类的完整根因与处置实时写进这个文件。"
            )
        }
        val text = runCatching { f.readText() }.getOrElse { return ToolResult.fail("读取错误档案失败：${it.message}") }
        return ToolResult.ok("启动错误细化档案 error-catalog.log（0.26.0）：\n" + text)
    }

    private fun readLog(a: JSONObject): ToolResult {
        val source = a.optString("source", "serial")
        val limit = a.optInt("lines", 120).coerceIn(10, 400)
        val grep = a.optString("grep").trim().takeIf { it.isNotEmpty() }

        val file = when (source) {
            "serial" -> File(bridge.runDir, "serial.log")
            "qemu" -> File(bridge.runDir, "qemu.log")
            // App 侧日志：MCP 调用、取帧失败、QEMU 退出码。查「画面连不上」先看这里。
            "app" -> File(bridge.runDir, "app.log")
            // 0.26.0 结构化启动诊断：每轮 7 个维度行 + 事件行，排障先看这一份。
            "boot" -> File(bridge.runDir, "boot-diagnostics.log")
            "boot_prev" -> File(bridge.runDir, "boot-diagnostics.prev.log")
            // 0.26.0 错误细化档案：每类错误带根因/影响/处置/证据，errors 工具读同一份。
            "errors" -> File(bridge.runDir, "error-catalog.log")
            "build" -> (bridge.buildLogDir().listFiles() ?: emptyArray())
                .filter { it.isFile }
                .maxByOrNull { it.lastModified() }

            else -> throw ToolError("source 只能是 serial / qemu / build / app / boot / boot_prev / errors")
        } ?: return ToolResult.ok("还没有构建日志：${bridge.buildLogDir().absolutePath} 是空的。")

        if (!file.isFile) {
            return ToolResult.fail(
                "${file.name} 不存在（${file.absolutePath}）。" +
                    if (source == "serial") "串口日志由 QEMU 的 -serial file: 创建；文件不存在通常意味着 QEMU 根本没起来。" else ""
            )
        }

        val matcher = when {
            grep == null -> null
            grep.startsWith("re:") -> runCatching { Regex(grep.removePrefix("re:")) }
                .getOrElse { throw ToolError("正则不合法：${it.message}") }

            else -> null
        }
        val needle = grep?.lowercase()

        val kept = ArrayDeque<String>()
        var scanned = 0
        file.bufferedReader(Charsets.UTF_8).useLines { seq ->
            for (raw in seq) {
                scanned++
                if (scanned > MAX_SCAN_LINES) break
                val line = stripAnsi(raw)
                val hit = when {
                    grep == null -> true
                    matcher != null -> matcher.containsMatchIn(line)
                    else -> line.lowercase().contains(needle!!)
                }
                if (!hit) continue
                kept.addLast(line)
                while (kept.size > limit) kept.removeFirst()
            }
        }

        val head = buildString {
            append(file.name).append(" · 共 ").append(scanned).append(" 行")
            if (scanned >= MAX_SCAN_LINES) append("（超过 $MAX_SCAN_LINES 行，只扫描到这里）")
            append('\n')
            append(
                if (grep == null) "最后 ${kept.size} 行："
                else "匹配「$grep」的最后 ${kept.size} 行："
            )
        }
        if (kept.isEmpty()) return ToolResult.ok(head + "\n（没有匹配的行）")
        return ToolResult.ok(head + "\n" + kept.joinToString("\n"))
    }

    private fun listFiles(a: JSONObject): ToolResult {
        val dir = resolve(a.optString("path", "."))
        if (!dir.exists()) return ToolResult.fail("不存在：${rel(dir)}")
        if (!dir.isDirectory) return ToolResult.fail("不是目录：${rel(dir)}")

        val entries = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: return ToolResult.fail("无法读取目录")
        if (entries.isEmpty()) return ToolResult.ok("${rel(dir)}/ 是空的")

        val shown = entries.take(MAX_LIST)
        val text = buildString {
            append(rel(dir)).append("/（").append(entries.size).append(" 项）\n")
            for (f in shown) {
                append(if (f.isDirectory) "d " else "  ")
                append(f.name)
                if (f.isDirectory) append('/') else append("  ").append(human(f.length()))
                append('\n')
            }
            if (entries.size > shown.size) append("…还有 ${entries.size - shown.size} 项未列出\n")
        }
        return ToolResult.ok(text.trimEnd())
    }

    private fun readFile(a: JSONObject): ToolResult {
        val file = resolve(a.optString("path"))
        if (!file.isFile) return ToolResult.fail("不是文件：${rel(file)}")
        val max = a.optInt("max_bytes", 16_384).coerceIn(256, 65_536)
        val size = file.length()
        val bytes = file.inputStream().use { it.readNBytes(max) }
        if (bytes.take(4096).any { it == 0.toByte() }) {
            return ToolResult.ok("${rel(file)} 是二进制文件（$size 字节），未读取内容。")
        }
        val text = String(bytes, Charsets.UTF_8)
        val note = if (size > bytes.size) "（只读了前 ${bytes.size} / $size 字节）" else ""
        return ToolResult.ok("${rel(file)} · $size 字节$note\n$text")
    }

    private suspend fun hmp(a: JSONObject): ToolResult {
        val command = a.optString("command").trim()
        if (command.isEmpty()) throw ToolError("command 不能为空")
        val verb = command.substringBefore(' ').lowercase()
        if (verb !in HMP_ALLOWED) {
            throw ToolError(
                "monitor 命令「$verb」不在白名单里。可用：${HMP_ALLOWED.joinToString("、")}。" +
                    "要停止或重启请用 stop_qemu / restart_qemu。"
            )
        }
        if (verb == "pmemsave") {
            // 写宿主文件的命令：读写档才可见地使用，且路径必须落在 run 目录里，
            // 防止只读客户端用它覆盖设备上的任意文件。
            requireWrite("hmp pmemsave")
            val runDir = bridge.runDir.canonicalPath + "/"
            val target = command.substringAfter(' ', "").substringAfter(' ', "").trim()
                .removeSurrounding("\"")
            if (!target.startsWith("/")) {
                throw ToolError("pmemsave 路径必须是绝对路径并用双引号包裹（HMP 会把裸 / 当除号）。")
            }
            if (File(target).canonicalPath + "/" != runDir &&
                !File(target).canonicalPath.startsWith(runDir)) {
                throw ToolError("pmemsave 只允许写到 run 目录内（$runDir），收到：$target")
            }
        }
        val out = bridge.hmp(command)
        return if (out.isBlank()) ToolResult.ok("$command：无输出") else ToolResult.ok(out)
    }

    // ---- 生命周期 / 链路动作 -------------------------------------------------------------------

    private suspend fun act(action: String, args: JSONObject): ToolResult {
        requireWrite(action)
        return ToolResult.ok(bridge.dispatch(action, args))
    }

    private suspend fun sendKey(a: JSONObject): ToolResult {
        requireWrite("send_key")
        val name = a.optString("name").trim()
        if (name.isEmpty()) throw ToolError("name 不能为空")
        if (!Regex("""[A-Za-z0-9_-]{1,24}""").matches(name)) throw ToolError("按键名不合法：$name")
        return ToolResult.ok(bridge.dispatch("send_key", JSONObject().put("name", name)))
    }

    private suspend fun setSmp(a: JSONObject): ToolResult {
        requireWrite("set_smp")
        val cores = a.optInt("cores", -1)
        if (cores !in 1..8) throw ToolError("cores 必须是 1..8 的整数（Guest vCPU，默认 2 = XNU 最稳档）")
        return ToolResult.ok(bridge.dispatch("set_config", JSONObject().put("key", "smp").put("value", cores.toString())))
    }

    /** 0.26.0：宿主算力池与 Guest vCPU 解耦后的独立配置项。 */
    private suspend fun setHostCores(a: JSONObject): ToolResult {
        requireWrite("set_host_cores")
        val cores = a.optInt("cores", -1)
        if (cores !in 2..8) {
            throw ToolError("cores 必须是 2..8 的整数（宿主算力池核数，默认 8）")
        }
        return ToolResult.ok(
            bridge.dispatch("set_config", JSONObject().put("key", "host_cores").put("value", cores.toString()))
        )
    }

    private suspend fun setMttcg(a: JSONObject): ToolResult {
        requireWrite("set_mttcg")
        if (!a.has("enabled")) throw ToolError("缺少 enabled（true/false）")
        return ToolResult.ok(
            bridge.dispatch("set_config", JSONObject().put("key", "mttcg").put("value", a.optBoolean("enabled").toString()))
        )
    }

    private suspend fun setAutoFallback(a: JSONObject): ToolResult {
        requireWrite("set_auto_fallback")
        if (!a.has("enabled")) throw ToolError("缺少 enabled（true/false）")
        return ToolResult.ok(
            bridge.dispatch("set_config", JSONObject().put("key", "auto_fallback").put("value", a.optBoolean("enabled").toString()))
        )
    }

    private suspend fun setNet(a: JSONObject): ToolResult {
        requireWrite("set_net")
        if (!a.has("enabled")) throw ToolError("缺少 enabled（true/false）")
        val on = a.optBoolean("enabled")
        return ToolResult.ok(bridge.dispatch("set_config", JSONObject().put("key", "net").put("value", on.toString())))
    }

    private suspend fun setQemuFlags(a: JSONObject): ToolResult {
        requireWrite("set_qemu_flags")
        val raw = a.optString("flags")
        val parsed = QemuFlags.parseDiagnostics(raw)
        if (!parsed.ok) throw ToolError(parsed.error!!)
        return ToolResult.ok(
            bridge.dispatch("set_config", JSONObject().put("key", "qemu_flags").put("value", parsed.text))
        )
    }

    private suspend fun setBootArgs(a: JSONObject): ToolResult {
        requireWrite("set_boot_args")
        val raw = a.optString("args")
        val parsed = QemuFlags.parseBootArgs(raw)
        if (!parsed.ok) throw ToolError(parsed.error!!)
        return ToolResult.ok(
            bridge.dispatch("set_config", JSONObject().put("key", "boot_args").put("value", parsed.text))
        )
    }

    /** 通用配置写入。key 白名单在 ToolBox 里校验，ViewModel 的 dispatch 只管落盘。 */
    private suspend fun setConfig(a: JSONObject): ToolResult {
        requireWrite("set_config")
        val key = a.optString("key").trim().lowercase()
        if (key.isEmpty()) throw ToolError("key 不能为空")
        val spec = CONFIG_KEYS[key] ?: throw ToolError(
            "未知配置项 $key。可用：${CONFIG_KEYS.keys.joinToString("、")}"
        )
        if (!a.has("value")) throw ToolError("缺少 value")
        var value = normalize(key, spec, a.get("value"))
        // set_config 是通用入口，不能绕过专用工具的校验：诊断参数与 boot-args 仍必须过
        // QemuFlags 的白名单，否则一条 set_config 就能把任意 -device/-machine 塞进启动命令。
        if (key == "qemu_flags") {
            val parsed = QemuFlags.parseDiagnostics(value)
            if (!parsed.ok) throw ToolError(parsed.error!!)
            value = parsed.text
        }
        if (key == "boot_args") {
            val parsed = QemuFlags.parseBootArgs(value)
            if (!parsed.ok) throw ToolError(parsed.error!!)
            value = parsed.text
        }
        return ToolResult.ok(
            bridge.dispatch("set_config", JSONObject().put("key", key).put("value", value))
        )
    }

    private suspend fun resetConfig(): ToolResult {
        requireWrite("reset_config")
        return ToolResult.ok(bridge.dispatch("reset_config", JSONObject()))
    }

    // ---- 文件与命令（写档） --------------------------------------------------------------------

    private fun writeFile(a: JSONObject): ToolResult {
        requireWrite("write_file")
        val file = resolve(a.optString("path"))
        val content = a.optString("content")
        if (file.isDirectory) throw ToolError("这是一个目录：${rel(file)}")
        if (content.length > MAX_WRITE_CHARS) {
            throw ToolError("内容太长（${content.length} 字符，上限 $MAX_WRITE_CHARS）")
        }
        val existed = file.isFile
        if (existed) {
            // 一份 .bak 就够了：改坏了能回到上一步，又不会在 run/ 里堆出一串历史版本。
            runCatching { file.copyTo(File(file.parentFile, file.name + ".bak"), overwrite = true) }
        }
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return ToolResult.ok(
            (if (existed) "已覆盖 ${rel(file)}（原内容备份为 ${file.name}.bak）" else "已创建 ${rel(file)}") +
                "，${content.length} 字符 / ${file.length()} 字节。"
        )
    }

    private fun runShell(a: JSONObject): ToolResult {
        requireWrite("run_shell")
        val command = a.optString("command").trim()
        if (command.isEmpty()) throw ToolError("command 不能为空")
        if (command.length > MAX_COMMAND) throw ToolError("命令过长（上限 $MAX_COMMAND 字符）")
        val wantRoot = a.optBoolean("root", false)
        if (wantRoot && !fullAccess) {
            throw ToolError("root=true 需要完全访问档：设置 → MCP 服务 → 完全访问。")
        }
        guardCommand(command)

        val timeout = a.optInt("timeout_sec", 20).coerceIn(3, 120)
        val result = shell.run(command, bridge.runDir, environment(), timeout, wantRoot)
        val output = TailText.of(result.output.trimEnd().ifBlank { "（无输出）" }, MAX_SHELL_OUTPUT)
        val status = when {
            result.timedOut -> "超时（${timeout}s 后被终止）"
            else -> "退出码 ${result.exit}"
        }
        return ToolResult.ok("$ $command\n$status\n$output")
    }

    /** Android 自己的日志：App 崩溃、SELinux avc denied、ActivityManager 决策都在这里。 */
    private fun androidLog(a: JSONObject): ToolResult {
        requireWrite("android_log")
        val buffer = when (a.optString("buffer", "main")) {
            "main" -> "main"
            "crash" -> "crash"
            "events" -> "events"
            else -> throw ToolError("buffer 只能是 main / crash / events")
        }
        val lines = a.optInt("lines", 120).coerceIn(10, 1000)
        val grep = a.optString("grep").trim()
        val cmd = buildString {
            append("logcat -d -b $buffer -t $lines -v time")
            if (grep.isNotEmpty()) append(" | grep -i ").append(JSONObject.quote(grep))
        }
        val result = shell.run(cmd, bridge.runDir, environment(), 15, root = false)
        val out = TailText.of(result.output.trimEnd().ifBlank { "（无输出）" }, MAX_SHELL_OUTPUT)
        val note = if (result.exit == 0) "" else "（非 0 退出：设备可能限制了 logcat，试试 run_shell 的 root=true。）"
        return ToolResult.ok("$ $cmd\n退出码 ${result.exit}$note\n$out")
    }

    // ---- 链路盘点与删除 ------------------------------------------------------------------------

    /**
     * 把整条链路摊开给客户端看：每一段存在哪些产物、多大。删除之前的默认动作就该是它，
     * 而不是直接 delete_chain。
     */
    private fun inspectChain(): String {
        val roots = bridge.chainRoots()
        val out = StringBuilder("HackiOS 链路盘点（${roots.size} 段）\n")
        var total = 0L
        for ((label, root) in roots) {
            out.append('\n').append("[").append(label).append("] ").append(root.absolutePath).append('\n')
            if (!root.exists()) {
                out.append("  （目录不存在）\n")
                continue
            }
            var files = 0
            var bytes = 0L
            val notable = mutableListOf<String>()
            root.walkTopDown().maxDepth(4).take(MAX_WALK).forEach { f ->
                if (f.isFile) {
                    files++
                    bytes += f.length()
                    if (f.name in NOTABLE_FILES && notable.size < 12) {
                        notable += "    ${f.name}  ${human(f.length())}"
                    }
                }
            }
            total += bytes
            out.append("  $files 个文件 · ").append(human(bytes)).append('\n')
            notable.forEach { out.append(it).append('\n') }
        }
        out.append('\n').append("合计 ").append(human(total)).append("。删除请用 delete_chain，默认只预览不执行。")
        return out.toString()
    }

    /**
     * 分级删除整条链路。
     *
     * 三道保险：只有完全访问档能看到这个工具；默认 `dry_run=true` 只返回计划；真正删除必须
     * `confirm="DELETE-CHAIN"` 一字不差。删除前自动停 QEMU，避免进程还在写 ram.img。
     */
    private suspend fun deleteChain(a: JSONObject): ToolResult {
        requireWrite("delete_chain")
        if (!fullAccess) {
            throw ToolError("delete_chain 需要完全访问档：设置 → MCP 服务 → 允许修改与执行 + 完全访问。")
        }
        val raw = a.optJSONArray("scope")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
            ?: a.optString("scope").split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val scopes = raw.map { it.lowercase() }.toSet()
        if (scopes.isEmpty()) throw ToolError("scope 不能为空，可用：${SCOPES.joinToString("、")}、all")
        val unknown = scopes - SCOPES - setOf("all")
        if (unknown.isNotEmpty()) throw ToolError("未知 scope：${unknown.joinToString()}。可用：${SCOPES.joinToString("、")}、all")
        val expanded = if (scopes.contains("all")) SCOPES.toSet() else scopes

        val roots = bridge.chainRoots()
        val plan = buildPlan(roots, expanded)
        val dryRun = a.optBoolean("dry_run", true)
        val preview = buildString {
            append("删除计划（scope=${expanded.sorted().joinToString("+")}）\n")
            if (plan.isEmpty()) append("  没有匹配到任何现存产物。\n")
            plan.forEach { append("  ").append(it.label).append("  ").append(human(it.size)).append('\n') }
            append("  合计 ").append(human(plan.sumOf { it.size })).append('\n')
            if (expanded.contains("config")) append("  [config] 重置 QEMU/外观设置（MCP 端口配置保留，避免自杀）\n")
            append("  会先停止 QEMU。\n")
        }
        if (dryRun) {
            return ToolResult.ok(
                preview + "\n这是预览（dry_run 默认 true）。确认无误后传 " +
                    "{\"dry_run\":false,\"confirm\":\"${McpConfig.CONFIRM_DELETE}\"} 才会真正删除。"
            )
        }
        if (a.optString("confirm") != McpConfig.CONFIRM_DELETE) {
            throw ToolError("确认串不对：必须传 confirm=\"${McpConfig.CONFIRM_DELETE}\" 才会执行删除。")
        }

        val stopped = runCatching { bridge.dispatch("stop_qemu", JSONObject()) }
            .getOrElse { "停止 QEMU 时出错：${it.message}" }
        var deletedFiles = 0
        var deletedBytes = 0L
        for (item in plan) {
            val (n, b) = deleteItem(item)
            deletedFiles += n
            deletedBytes += b
        }
        val configNote = if (expanded.contains("config")) {
            runCatching { bridge.dispatch("reset_config", JSONObject()) }.getOrElse { "重置配置失败：${it.message}" }
        } else "（配置未动）"
        runCatching { bridge.dispatch("refresh_artifacts", JSONObject()) }

        return ToolResult.ok(
            preview + "\n已执行删除：$deletedFiles 个文件 / ${human(deletedBytes)}。\n" +
                "停止 QEMU：$stopped\n重置配置：$configNote\n" +
                "链路产物已清空。下次 start_qemu 会自动重新部署资源；镜像与固件若被删除，需重新 download_firmware / compile_images。"
        )
    }

    private data class PlanItem(val label: String, val file: File, val contentsOnly: Boolean) {
        val size: Long get() = runCatching {
            if (!file.exists()) 0L
            else if (file.isFile) file.length()
            else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    private fun buildPlan(roots: Map<String, File>, scopes: Set<String>): List<PlanItem> {
        val plan = mutableListOf<PlanItem>()
        val run = roots["run"]
        if (run != null && scopes.contains("cache")) {
            CACHE_NAMES.map { File(run, it) }.filter { it.exists() }.forEach {
                plan += PlanItem("run/${it.name}", it, contentsOnly = false)
            }
        }
        if (run != null && scopes.contains("logs")) {
            run.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }?.forEach {
                plan += PlanItem("run/${it.name}", it, contentsOnly = false)
            }
        }
        if (scopes.contains("run") && run != null) {
            plan += PlanItem("run/ 全部（QEMU 二进制、工具链、脚本、payload）", run, contentsOnly = true)
        }
        for (key in listOf("images", "firmware", "logs", "exports")) {
            if (!scopes.contains(key)) continue
            val dir = if (key == "firmware") roots["ipsw"] else roots[key]
            if (dir != null && dir.exists()) {
                plan += PlanItem("$key/ 全部", dir, contentsOnly = true)
            }
        }
        // run/ 整段已经覆盖 cache 与 logs，去重避免重复计数。
        if (scopes.contains("run")) return plan.filter { !it.label.startsWith("run/") || it.contentsOnly }
        return plan.distinctBy { it.file.absolutePath }
    }

    private fun deleteItem(item: PlanItem): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        val targets = if (item.contentsOnly && item.file.isDirectory) {
            item.file.listFiles()?.toList() ?: emptyList()
        } else {
            listOf(item.file)
        }
        for (t in targets) {
            if (!t.exists()) continue
            if (t.isFile) {
                bytes += t.length()
                if (t.delete()) files++
            } else {
                val (n, b) = deleteTree(t)
                files += n
                bytes += b
            }
        }
        return files to bytes
    }

    private fun deleteTree(dir: File): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        // walkBottomUp 保证子项先于父项：最后连这个子目录本身也删掉，运行目录里不会留下一串
        // 空的 qemu/ toolchain/ 壳子。顶层 roots 不在这里删（deleteItem 只把它的子项传进来）。
        dir.walkBottomUp().forEach { f ->
            if (f.isFile) {
                bytes += f.length()
                if (f.delete()) files++
            } else {
                runCatching { f.delete() }
            }
        }
        return files to bytes
    }

    // ---- 护栏 ----------------------------------------------------------------------------------

    private fun requireWrite(tool: String) {
        if (!allowWrite) {
            throw ToolError(
                "「$tool」需要写权限。当前是只读模式：到 设置 → MCP 服务 打开「允许修改与执行」。"
            )
        }
    }

    private fun resolve(path: String): File {
        if (path.isBlank()) throw ToolError("path 不能为空")
        val base = bridge.runDir.canonicalFile
        val candidate = File(path)
        val file = (if (candidate.isAbsolute) candidate else File(base, path)).canonicalFile
        if (!fullAccess) {
            val prefix = base.absolutePath + File.separator
            if (file != base && !file.absolutePath.startsWith(prefix)) {
                throw ToolError(
                    "路径越界：只能访问 ${base.absolutePath} 以内，拒绝 $path。" +
                        "整台设备的完全访问需要用户在 设置 → MCP 服务 打开「完全访问」。"
                )
            }
        }
        // 完全访问档不再自建边界：App 进程读不到/写不到的位置由内核拒绝，那个错误比我们的
        // 白名单更有信息量（SELinux avc denied 指明的就是哪条规则）。
        return file
    }

    private fun rel(file: File): String =
        runCatching { file.relativeTo(bridge.runDir).path.ifEmpty { "." } }.getOrDefault(file.name)

    private fun environment(): Map<String, String> {
        val run = bridge.runDir
        val path = listOf(
            File(run, "toolchain/bin"),
            File(run, "hackios/xnu-deps/host/bin"),
            File("/system/bin"),
            File("/system/xbin"),
            File("/vendor/bin"),
        ).joinToString(":") { it.absolutePath }
        File(run, "tmp").mkdirs()
        return mapOf(
            "PATH" to path,
            "HOME" to run.absolutePath,
            "TMPDIR" to File(run, "tmp").absolutePath,
            "LD_LIBRARY_PATH" to File(run, "qemu/lib").absolutePath,
            // 工具链是 Termux 的 python 3.14，PYTHONHOME 指错会直接 ImportError。
            "PYTHONHOME" to File(run, "toolchain/usr").absolutePath,
            "PYTHONUNBUFFERED" to "1",
            "LC_ALL" to "C",
        )
    }

    /**
     * 危险命令黑名单。
     *
     * 这些模式和"调试 QEMU"没有任何关系，误伤成本很低；真正要防的是客户端把 `run/` 之外的
     * 东西删掉、装应用、改系统设置。命中时把原因原样回给客户端，让它换一种做法。
     */
    private fun guardCommand(command: String) {
        val lower = command.lowercase()
        if (fullAccess) {
            // 完全访问：只拦"物理损伤设备"这一类。其余交给内核——客户端需要看到真实的
            // Permission denied / avc denied 才能推断这台机器上什么能做什么不能做。
            for ((pattern, why) in DENY_CORE) {
                if (pattern.containsMatchIn(lower)) throw ToolError("拒绝执行：$why")
            }
            return
        }
        for ((pattern, why) in DENY) {
            if (pattern.containsMatchIn(lower)) throw ToolError("拒绝执行：$why")
        }
        // 任何绝对路径都必须落在 run/ 内（/dev/null 与 /system 例外）。
        val base = bridge.runDir.canonicalPath
        val paths = Regex("""(?:^|[\s"'])(/[A-Za-z0-9_./@+-]+)""").findAll(command).map { it.groupValues[1] }
        for (p in paths) {
            val allowed = p.startsWith(base) ||
                p == "/dev/null" || p.startsWith("/system/") || p.startsWith("/proc/self") ||
                p == "/data/local/tmp" || p.startsWith("/data/local/tmp/")
            if (!allowed) throw ToolError("拒绝执行：命令里的绝对路径 $p 不在 ${bridge.runDir.absolutePath} 之内")
        }
    }

    private companion object {
        const val TAG = "McpToolBox"
        const val MAX_SCAN_LINES = 400_000
        const val MAX_LIST = 200
        const val MAX_WRITE_CHARS = 262_144
        const val MAX_COMMAND = 2_000
        const val MAX_SHELL_OUTPUT = 6_000
        const val MAX_WALK = 20_000

        val SCOPES = setOf("cache", "run", "images", "firmware", "logs", "exports", "config")
        val NOTABLE_FILES = setOf(
            "payload.bin", "ram.img", ".payload-stamp", "qemu-cmdline.txt", "serial.log", "qemu.log",
            "app.log", "crash.log", "system.apfs", "root.apfs", ".image-rev", "qemu-system-aarch64",
        )
        val CACHE_NAMES = listOf(
            "payload.bin", "ram.img", ".payload-stamp", "shot.ppm", "qemu-cmdline.txt", "build-logs", "tmp",
        )

        val HMP_ALLOWED = setOf(
            "info", "help", "xp", "x", "screendump", "stop", "cont",
            "system_reset", "sendkey", "log", "sum", "print",
            // 0.26.1 黑屏直读探针：把 guest 物理内存导出成文件做 BGRA 判读（路径必须带引号）。
            "pmemsave",
        )

        /**
         * 完全访问档的黑名单：只剩"跑了就可能让设备起不来 / 丢数据"的命令。
         * su、mount、pm、am、settings、setenforce 都不在这里——它们是调试 Android 的正常手段，
         * 而且每一道都有内核兜底。
         */
        val DENY_CORE: List<Pair<Regex, String>> = listOf(
            Regex("""\bdd\b[^\n]*\bof=/dev/(block|mmcblk|sd[a-z])""") to "直接写块设备（会破坏整机存储）",
            Regex("""\bmkfs(\.[a-z0-9]+)?\b""") to "格式化文件系统",
            Regex("""\b(reboot|shutdown|poweroff|halt)\b""") to "重启或关机整台手机",
            Regex("""\brm\s+(-[a-zA-Z]+\s+)*/\s*(#.*)?$""") to "递归删除根目录",
        )

        val DENY: List<Pair<Regex, String>> = listOf(
            Regex("""\bmkfs(\.[a-z0-9]+)?\b""") to "格式化文件系统",
            Regex("""\bdd\b[^\n]*\bof=/dev/""") to "直接写块设备",
            Regex("""\b(reboot|shutdown|poweroff|halt)\b""") to "重启或关机整台手机",
            Regex("""\bpm\s+(install|uninstall|clear|disable)\b""") to "改动已安装应用",
            Regex("""\bam\s+(start|force-stop|broadcast|kill)\b""") to "操作 Activity 管理器",
            Regex("""\bsettings\s+(put|delete)\b""") to "改系统设置",
            Regex("""\bsetenforce\b""") to "改 SELinux 模式",
            Regex("""\bmount\b""") to "挂载文件系统",
            Regex("""\bsu(\s|$)""") to "提权",
            Regex("""rm\s+(-[a-z]*[rf][a-z]*\s+)+(/|/sdcard|/data|/system)""") to "递归删除系统或外部存储目录",
        )
    }
}

/** 配置项的类型描述，同时是 set_config 的白名单。 */
private data class ConfigKey(val type: String, val note: String, val allowed: Set<String> = emptySet())

private val CONFIG_KEYS: Map<String, ConfigKey> = linkedMapOf(
    "smp" to ConfigKey("int", "Guest vCPU 核数 1..8（默认 2，XNU SMP bringup 最稳；调高加重 IPI/锁竞争）"),
    "host_cores" to ConfigKey("int", "宿主算力池 2..8（默认 8）：TB 缓存规格 + CPU 亲和性掩码；不改 vCPU、不重建镜像"),
    "mttcg" to ConfigKey("bool", "多线程 TCG（thread=multi）。默认 true；false 回退单线程 TCG 排障基线"),
    "auto_fallback" to ConfigKey("bool", "卡住超过 120s 自动降核重启一次（默认 true）"),
    "net" to ConfigKey("bool", "虚拟网络开关"),
    "theme" to ConfigKey("enum", "外观", setOf("system", "light", "dark")),
    "glass" to ConfigKey("enum", "液态玻璃强度", setOf("subtle", "standard", "strong")),
    "follow_log" to ConfigKey("bool", "日志自动跟随最新"),
    "qemu_flags" to ConfigKey("string", "QEMU 诊断参数，下次启动生效"),
    "boot_args" to ConfigKey("string", "额外 XNU 启动参数，下次构建 payload 时写入"),
    "mcp_enabled" to ConfigKey("bool", "MCP 服务总开关"),
    "mcp_port" to ConfigKey("int", "MCP 监听端口 1024..65535，改完自动重启监听"),
    "mcp_bind_lan" to ConfigKey("bool", "是否绑定 0.0.0.0 供局域网连接（强制 token）"),
    "mcp_token" to ConfigKey("string", "设为 regenerate 重新生成，或直接给一个新令牌"),
    "mcp_allow_write" to ConfigKey("bool", "是否开放写类工具"),
    "mcp_full_access" to ConfigKey("bool", "是否开放完全访问与 delete_chain"),
)

private fun normalize(key: String, spec: ConfigKey, raw: Any): String {
    val text = when (raw) {
        is Boolean -> raw.toString()
        is Number -> raw.toString()
        else -> raw.toString().trim()
    }
    return when (spec.type) {
        "bool" -> {
            if (text.lowercase() !in setOf("true", "false")) throw ToolError("$key 需要 true / false，收到 $text")
            text.lowercase()
        }

        "int" -> {
            val n = text.toIntOrNull() ?: throw ToolError("$key 需要整数，收到 $text")
            val (lo, hi) = when (key) {
                "smp" -> 1 to 8
                "host_cores" -> 2 to 8
                "mcp_port" -> McpConfig.MIN_PORT to McpConfig.MAX_PORT
                else -> Int.MIN_VALUE to Int.MAX_VALUE
            }
            if (n !in lo..hi) throw ToolError("$key 必须在 $lo..$hi，收到 $n")
            n.toString()
        }

        "enum" -> {
            val v = text.lowercase()
            if (v !in spec.allowed) throw ToolError("$key 只能是 ${spec.allowed.joinToString(" / ")}，收到 $text")
            v
        }

        else -> text
    }
}

/**
 * 真正执行 `sh -c` 的实现。
 *
 * 输出必须边跑边读：`build.sh` 一次能打几万行，等进程结束再读会先把管道塞满（64 KB），子进程
 * 反而卡在 write() 上，表现为"脚本没反应"。所以单独一个线程持续 drain，主线程只等超时。
 */
object RealShell : ShellRunner {

    override fun run(command: String, cwd: File, env: Map<String, String>, timeoutSec: Int, root: Boolean): ShellResult {
        val argv: List<String> = if (root) {
            val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
                .firstOrNull { File(it).isFile }
                ?: return ShellResult(-1, "设备上没有找到 su（未 root）。root=true 需要一台已解锁的设备。", false)
            listOf(su, "-c", command)
        } else {
            val sh = File("/system/bin/sh").takeIf { it.isFile }?.absolutePath ?: "sh"
            listOf(sh, "-c", command)
        }
        val pb = ProcessBuilder(argv)
            .directory(cwd)
            .redirectErrorStream(true)
        pb.environment().putAll(env)

        val process = pb.start()
        val out = StringBuilder()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    for (line in seq) {
                        if (out.length < 400_000) out.append(line).append('\n')
                    }
                }
            }
        }, "mcp-shell").apply {
            isDaemon = true
            start()
        }

        val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        runCatching { reader.join(2000) }
        val exit = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        return ShellResult(exit, out.toString(), timedOut = !finished)
    }
}

/** 去掉 `-serial file:` 里 UEFI 写下的清屏序列；不然客户端读到的是一堆 ESC。 */
private fun stripAnsi(line: String): String {
    if (line.indexOf('\u001B') < 0) return line
    return line.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "").replace("\u001B", "")
}

private fun human(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

// ---- 工具表（MCP tools/list 原生格式） ----------------------------------------------------------

private fun obj(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().also { o ->
    pairs.forEach { (k, v) -> o.put(k, v) }
}

private fun schema(props: JSONObject, required: List<String>): JSONObject = JSONObject().apply {
    put("type", "object")
    put("properties", props)
    put("required", JSONArray(required))
}

private fun str(desc: String, enum: List<String>? = null): JSONObject = JSONObject().apply {
    put("type", "string")
    put("description", desc)
    if (enum != null) put("enum", JSONArray(enum))
}

private fun bool(desc: String): JSONObject = JSONObject().apply {
    put("type", "boolean")
    put("description", desc)
}

private fun num(desc: String, min: Int, max: Int, default: Int): JSONObject = JSONObject().apply {
    put("type", "integer")
    put("description", desc)
    put("minimum", min)
    put("maximum", max)
    put("default", default)
}

private fun tool(name: String, description: String, params: JSONObject): JSONObject = JSONObject().apply {
    put("name", name)
    put("description", description)
    put("inputSchema", params)
}

private fun empty() = schema(obj(), emptyList())

/** 按权限档生成工具表。低权限档根本看不到高权限工具，避免客户端反复尝试被拒。 */
private fun buildSpecs(allowWrite: Boolean, fullAccess: Boolean): JSONArray = JSONArray().apply {
    // ---- 只读档 ----
    put(tool(
        "device_status",
        "Read the whole device state as text: QEMU process and boot phase, image/firmware presence " +
            "and revision, current config (smp, net, flags, boot-args), build and download state.",
        empty(),
    ))
    put(tool(
        "get_config",
        "Read every persisted setting, including the MCP server config (port, bind, permissions). " +
            "Use this before set_config to see current values.",
        empty(),
    ))
    put(tool(
        "mcp_info",
        "Describe this MCP endpoint: protocol version, listening host/port, session id, tool count, " +
            "request count and how to reach it (adb forward / LAN URL / token requirement).",
        empty(),
    ))
    put(tool(
        "inspect_chain",
        "List every artifact on the whole pipeline: run dir (QEMU binary, toolchain, payload.bin, " +
            "ram.img, logs), built images, downloaded IPSW firmware, exported logs. Shows file count " +
            "and sizes. Always call this BEFORE delete_chain.",
        empty(),
    ))
    put(tool(
        "boot_timeline",
        "Read the per-stage boot timing (deploy/prepare/qemu_spawn/UEFI/loader/kernel/launchd/" +
            "checks/gui/logo/desktop) with cumulative ms and budget verdicts: Logo <= 90s, " +
            "desktop <= 180s. Call repeatedly after start_qemu to watch progress and locate the " +
            "slowest stage. Verification of the 3-minute boot target is done on the real arm64 device.",
        empty(),
    ))
    put(tool(
        "capture_screen",
        "Ask QEMU for a screendump and report geometry plus the mean brightness of the frame. " +
            "mean=0 means the guest never drew anything (capture path is fine, the guest is not); " +
            "nonzero means iOS is rendering.",
        empty(),
    ))
    put(tool(
        "perf_stats",
        "Read the 0.25.0 CPU-spin report written each time the boot phase advances: " +
            "adaptive frame pacing (2 FPS while booting, 8 FPS when the screen is active, " +
            "1 FPS when static, backoff when a screendump is slow), the current boot stage, " +
            "capture cost, and the FAST-boot recipe in use. Use it to verify that the App is " +
            "not competing with QEMU's TCG threads for CPU.",
        empty(),
    ))
    put(tool(
        "errors",
        "Read the 0.26.0 structured boot-error catalog (run/error-catalog.log). Every distinct " +
            "error class observed in the serial/crash/qemu logs is explained in detail: which layer " +
            "(firmware/kernel/launchd/daemon/SpringBoard/host-App/frame-capture/QEMU), severity, " +
            "whether it blocks reaching the desktop, the concrete component, root cause, impact, " +
            "the exact remediation, verbatim evidence line, hit count and extracted parameters " +
            "(e.g. missing XPC service names, requesting processes, capture cost). Unlike the old " +
            "single vague 'noise' label, lsd CPU-monitor denials, failed lookups, persona warnings, " +
            "plist-import lints and hardware-service gaps are each explained separately. Read this " +
            "FIRST when diagnosing why a boot is slow or fails, before read_log(serial).",
        empty(),
    ))
    put(tool(
        "read_log",
        "Read the tail of a log file. source=serial is the iOS/XNU console (boot messages, launchd, " +
            "SpringBoard crashes); qemu is QEMU's own stdout/stderr; build is the newest image-build " +
            "script output; app is the App's own log (MCP calls, frame-capture failures, QEMU exit); " +
            "boot / boot_prev are the 0.26.0 structured per-second boot diagnostics (this / previous run); " +
            "errors is the 0.26.0 detailed boot-error catalog (also available via the errors tool). " +
            "Always read the log before proposing a fix; for diagnosing errors, prefer errors or read_log(boot).",
        schema(
            obj(
                "source" to str("Which log", listOf("serial", "qemu", "build", "app", "boot", "boot_prev", "errors")),
                "lines" to num("How many trailing (or matching) lines, default 120", 10, 400, 120),
                "grep" to str("Optional case-insensitive substring filter. Prefix with 're:' for a regex."),
            ),
            listOf("source"),
        ),
    ))
    put(tool(
        "list_files",
        "List a directory. Relative paths resolve inside the app's run directory." +
            (if (fullAccess) " Full-access mode: absolute Android paths are allowed too." else ""),
        schema(obj("path" to str("Relative path, default '.'")), emptyList()),
    ))
    put(tool(
        "read_file",
        "Read a UTF-8 text file. Binary files are reported, not dumped." +
            (if (fullAccess) " Full-access mode: absolute Android paths are allowed." else ""),
        schema(
            obj(
                "path" to str("Relative path, e.g. hackios/userland/rc.jobs"),
                "max_bytes" to num("Byte cap, default 16384", 256, 65536, 16384),
            ),
            listOf("path"),
        ),
    ))
    put(tool(
        "hmp",
        "Send a read-mostly command to QEMU's HMP monitor, e.g. 'info registers', 'info block', " +
            "'info network', 'xp /16xb 0x...', 'stop', 'cont', 'system_reset'. Whitelisted verbs only; " +
            "stopping the emulator itself is done with stop_qemu. " +
            "Black-screen debugging: 'pmemsave 0x1fb6bb000 10935200 \"<runDir>/fb-dump.bin\"' dumps the " +
            "guest boot framebuffer directly (quote the path — bare / breaks HMP expression parsing); " +
            "the kernel prints the exact address in 'display W x H at ADDR' on the serial log.",
        schema(obj("command" to str("Full monitor command line")), listOf("command")),
    ))

    if (!allowWrite) return@apply

    // ---- 读写档：QEMU 生命周期 ----
    put(tool(
        "start_qemu",
        "Start the QEMU emulator with the current config. Deploys bundled assets, rebuilds payload.bin " +
            "if the cache is stale, then boots iOS under TCG. Returns immediately; poll device_status " +
            "and read_log(serial) for progress (SpringBoard takes 1-3 minutes).",
        empty(),
    ))
    put(tool(
        "stop_qemu",
        "Stop QEMU gracefully: close the monitor, terminate the process, release ports 4444/4445. " +
            "Images and firmware are untouched.",
        empty(),
    ))
    put(tool(
        "restart_qemu",
        "Stop and start QEMU with the current settings. Takes 1-2 minutes (payload rebuild + TCG boot). " +
            "Returns immediately.",
        empty(),
    ))
    put(tool(
        "force_cleanup",
        "Kill leftover QEMU processes occupying the HMP/QMP ports after a crash. Use before start_qemu " +
            "when the port-in-use error appears.",
        empty(),
    ))
    put(tool(
        "send_key",
        "Inject a key into the guest through the QMP monitor (e.g. 'h' for home, 'p' for power).",
        schema(obj("name" to str("Key name, alphanumeric/_/- only")), listOf("name")),
    ))

    // ---- 读写档：参数与配置 ----
    put(tool(
        "set_smp",
        "Set the Guest vCPU / SMP topology (1..8, default 2). This is what XNU sees and what decides " +
            "SMP bringup, IPI and lock contention stability — lower is more stable. Under mttcg=true " +
            "each vCPU gets one host TCG thread. Changing it only rebuilds payload.bin (cpus=N), NOT " +
            "ram.img. Independent of host_cores. Verified on QEMU 8.2.2 via QMP. Takes effect next start.",
        schema(obj("cores" to num("Guest vCPU count", 1, 8, 2)), listOf("cores")),
    ))
    put(tool(
        "set_host_cores",
        "Set the Host compute pool (2..8, default 8): how many physical cores the whole QEMU process " +
            "family may be scheduled on (CPU affinity mask 0..N-1) and the TCG translation-block cache " +
            "budget (170 MiB/core, min 384). It does NOT add vCPUs and does NOT rebuild any image, so " +
            "it is a zero-cost change. On devices without a usable taskset / where cpuset denies " +
            "sched_setaffinity, the affinity wrapper is skipped (logged) and only the TB cache layer " +
            "applies. Independent of smp. Takes effect next start.",
        schema(obj("cores" to num("Host compute pool cores", 2, 8, 8)), listOf("cores")),
    ))
    put(tool(
        "set_mttcg",
        "Toggle multi-threaded TCG. true (default) = thread=multi + explicit CPU topology + a TB cache " +
            "sized by host_cores. false = legacy single-thread TCG baseline for troubleshooting. " +
            "Only takes effect when QEMU is stopped.",
        schema(obj("enabled" to bool("Enable MTTCG")), listOf("enabled")),
    ))
    put(tool(
        "set_auto_fallback",
        "Toggle the stall guard: when the frame stops changing for >120s before desktop, QEMU is " +
            "restarted once with the Guest vCPU count halved (8->4->2, then stops). Prevents an endless " +
            "reboot loop. The default 2 vCPU is already the floor, so this only matters for raised vCPU. Default on.",
        schema(obj("enabled" to bool("Enable automatic core fallback")), listOf("enabled")),
    ))
    put(tool(
        "set_net",
        "Enable or disable the guest's slirp NAT network. Only takes effect when QEMU is stopped.",
        schema(obj("enabled" to bool("Whether the virtual NIC is enabled")), listOf("enabled")),
    ))
    put(tool(
        "set_qemu_flags",
        "Replace diagnostic QEMU flags used at the next start. Allowed: -d <items>, -D <file>, " +
            "-trace <pattern>, -no-shutdown, -singlestep, -gdb tcp::<port>. Empty string clears them.",
        schema(obj("flags" to str("-d int,guest_errors -D qemu-debug.log")), listOf("flags")),
    ))
    put(tool(
        "set_boot_args",
        "Replace extra XNU boot-args appended after the managed ones (cpus=, rd=, epoch= are " +
            "reserved). Example: 'debug=0x144 -v'. Invalidates payload.bin, so the next start rebuilds it.",
        schema(obj("args" to str("Space separated key=value tokens")), listOf("args")),
    ))
    put(tool(
        "set_config",
        "Write ANY persisted setting by key. Keys: smp, host_cores, mttcg, auto_fallback, net, " +
            "theme(system/light/dark), glass(subtle/standard/strong), follow_log, qemu_flags, boot_args, " +
            "mcp_enabled, mcp_port, mcp_bind_lan, mcp_token(regenerate or a new value), " +
            "mcp_allow_write, mcp_full_access. " +
            "smp = Guest vCPU (XNU stability); host_cores = Host compute pool (TB cache + affinity, no vCPU " +
            "change, no image rebuild); the two are independent in 0.26.0. " +
            "Changing smp only rebuilds payload.bin (cpus=N), NOT the 2.5 GB ram.img. " +
            "Changing mcp_enabled/port/bind_lan restarts the MCP listener; mcp_enabled=false will " +
            "close this connection, so call it last.",
        schema(
            obj(
                "key" to str("Setting key"),
                "value" to str("New value; bool as true/false, int as a number or string"),
            ),
            listOf("key", "value"),
        ),
    ))
    put(tool(
        "reset_config",
        "Reset QEMU/appearance settings to defaults (MCP server config is kept so this endpoint stays " +
            "reachable). Requires full access. For a full wipe use delete_chain with scope=all.",
        empty(),
    ))

    // ---- 读写档：链路资源 ----
    put(tool(
        "deploy_assets",
        "Re-extract QEMU binary, toolchain and build scripts from the APK into the run directory. " +
            "Use after delete_chain(scope=run) or a broken bundle.",
        empty(),
    ))
    put(tool(
        "compile_images",
        "Build system.apfs / root.apfs from the downloaded IPSW using the bundled toolchain " +
            "(20-40 minutes, resumable). Poll device_status and read_log(build).",
        empty(),
    ))
    put(tool(
        "cancel_compile",
        "Cancel a running image build; progress is checkpointed and can be resumed.",
        empty(),
    ))
    put(tool(
        "download_firmware",
        "Start/resume the 6.1 GB IPSW download from Apple's CDN into Download/hackios/ipsw.",
        empty(),
    ))
    put(tool(
        "cancel_download",
        "Pause the firmware download; downloaded chunks are kept for resume.",
        empty(),
    ))
    put(tool(
        "verify_firmware",
        "Verify the SHA-256 of the already downloaded IPSW.",
        empty(),
    ))
    put(tool(
        "export_logs",
        "Export the complete on-disk logs (crash, app, serial, qemu, qemu cmdline, last 5 build logs) " +
            "to Download/hackios/logs, or to the share cache when share=true. Returns the path via " +
            "device_status / get_config afterwards.",
        schema(obj("share" to bool("Write to share cache and open the share sheet, default false")), emptyList()),
    ))
    put(tool(
        "run_diagnostics",
        "Self-check the bundled toolchain (bash / python3 / 7zz / unzbm) and report failures.",
        empty(),
    ))
    put(tool(
        "refresh_artifacts",
        "Re-stat firmware and image files (call after changing files outside this app).",
        empty(),
    ))

    // ---- 读写档：文件与命令 ----
    put(tool(
        "write_file",
        "Create or overwrite a text file inside the run directory (scripts, rc.jobs, plists). " +
            "The previous version is kept as <name>.bak.",
        schema(
            obj("path" to str("Relative path inside run/"), "content" to str("Full new file content")),
            listOf("path", "content"),
        ),
    ))
    put(tool(
        "android_log",
        "Read Android's own logcat (main/crash/events buffers). App crashes, SELinux avc denied and " +
            "ActivityManager decisions land here — useful when the problem is in the App, not the guest.",
        schema(
            obj(
                "buffer" to str("Which buffer", listOf("main", "crash", "events")),
                "lines" to num("How many trailing lines, default 120", 10, 1000, 120),
                "grep" to str("Optional case-insensitive filter"),
            ),
            emptyList(),
        ),
    ))
    put(tool(
        "run_shell",
        "Run a shell command with the bundled toolchain on PATH (bash, python3.14, 7zz). " +
            "Output is captured and truncated. Default timeout 20 s, max 120 s." +
            (if (fullAccess) {
                " FULL ACCESS: paths and commands outside the run directory are allowed — Android " +
                    "userland is a full Linux (toybox, /proc, logcat, pm, am); set root=true for su " +
                    "(only works on a rooted device). Only physically destructive commands " +
                    "(writing block devices, mkfs, reboot, rm -rf /) are refused."
            } else {
                " Paths must stay inside the run directory; dangerous commands are refused."
            }),
        schema(
            JSONObject().apply {
                put("command", str("Command line, run with sh -c"))
                put("timeout_sec", num("Timeout in seconds", 3, 120, 20))
                if (fullAccess) put("root", bool("Run via su -c (requires a rooted device)"))
            },
            listOf("command"),
        ),
    ))

    if (!fullAccess) return@apply

    // ---- 完全访问档：删除整条链路 ----
    put(tool(
        "delete_chain",
        "DESTRUCTIVE: delete the artifacts of the whole pipeline. Stops QEMU first. scope is an array " +
            "(or comma-separated string) of: cache (payload.bin/ram.img/stamp/logs), run (the whole " +
            "run dir incl. QEMU binary + toolchain), images (system.apfs/root.apfs), firmware " +
            "(the 6.1 GB IPSW), logs, exports, config (reset QEMU settings), or 'all' for everything. " +
            "DEFAULT dry_run=true only returns a preview; actually deleting requires " +
            "dry_run=false AND confirm=\"DELETE-CHAIN\". Call inspect_chain first.",
        schema(
            JSONObject().apply {
                put(
                    "scope", JSONObject().apply {
                        put(
                            "description",
                            "Array (preferred) or comma-separated string: " +
                                "cache,run,images,firmware,logs,exports,config,all",
                        )
                        put(
                            "oneOf", JSONArray()
                                .put(
                                    JSONObject().put("type", "array")
                                        .put("items", str("scope", listOf("cache", "run", "images", "firmware", "logs", "exports", "config", "all")))
                                        .put("minItems", 1)
                                )
                                .put(JSONObject().put("type", "string"))
                        )
                    }
                )
                put("dry_run", bool("Preview only, default true"))
                put("confirm", str("Must be exactly DELETE-CHAIN when dry_run=false"))
            },
            listOf("scope"),
        ),
    ))
}
