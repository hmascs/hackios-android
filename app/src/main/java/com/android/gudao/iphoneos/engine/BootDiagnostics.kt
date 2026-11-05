package com.android.gudao.iphoneos.engine

/**
 * 0.26.0 启动诊断日志：把一次 iOS 启动变成可回放、可定位的结构化记录。
 *
 * 之前 App 侧关于「启动」只有几行：`开始启动`、`启动阶段 X → Y`（仅跃迁时写，一次最多 7 行）、
 * logo/desktop 里程碑、失败时的 `QEMU 进程退出`。荣耀 90 导出日志 4022 行里，App 侧阶段记录
 * 累计仅 9 行（单次启动 3~4 行）。而 BootPhase 已解析出的 7 类诊断结论（display / rootDir /
 * trampoline / persona / surface / desktopSignal / noise）只进 UI notes，从不落盘。
 *
 * 每轮采样（与 updatePhase 同频，1 秒）输出 7 个维度行 + 事件行，每行一组独立 key=value：
 * PHASE / SERIAL / FRAME / CPU / TIMELINE / GUEST / HEALTH / EVENT。
 * 「方便定位错误」靠 GUEST 与 EVENT：EACCES、cryptex dlopen 失败、lsd 风暴、慢取帧、
 * 串口停滞、画面停滞、自动降核都在发生那一刻带上下文写一行。
 *
 * 密度口径：一次启动期间 App 侧写入磁盘的启动相关**有效行数**。基线由 [legacyLines] 精确
 * 复刻 0.26.0 之前行为；[density] 给出倍数，要求 ≥ [DENSITY_TARGET]（3.0）。同时用
 * 「每行平均 key=value 字段数」证明不是灌水（要求 ≥ 4）。JVM 单测 + 云端真实日志复算双重验证。
 *
 * 纯 Kotlin（不引用 android.*），渲染与密度逻辑可在 JVM 单测逐行断言；落盘见 [BootDiagnosticLogger]。
 */
object BootDiagnostics {

    const val PREFIX = "[boot]"
    const val DENSITY_TARGET = 3.0
    const val MIN_FIELDS_PER_LINE = 4
    const val SLOW_CAPTURE_MS = 1_500L
    const val SERIAL_STALL_MS = 5_000L
    const val FRAME_STALL_MS = 90_000L

    /** 一轮采样的全部输入；刻意用原始类型，保持纯 JVM 可构造。 */
    data class Snapshot(
        val elapsedMs: Long,
        val stage: Int,
        val textStage: Int,
        val label: String,
        val stuck: Boolean = false,
        val dwellMs: Long = 0L,
        val serialDeltaLines: Int = 0,
        val serialTotalLines: Long = 0L,
        val serialLinesPerSec: Double = 0.0,
        val serialStallMs: Long = 0L,
        val serialLastLine: String? = null,
        val frames: Long = 0L,
        val changedFrames: Long = 0L,
        val meanBrightness: Int = -1,
        val resolution: String = "—",
        val captureMs: Long = 0L,
        val intervalMs: Long = 125L,
        val framePace: String = "—",
        val unchangedForMs: Long = 0L,
        val guestVcpu: Int = 2,
        val hostCores: Int = 8,
        val tbSizeMiB: Int = 1360,
        val affinityApplied: Boolean = false,
        val mttcg: Boolean = true,
        val logoMs: Long? = null,
        val desktopMs: Long? = null,
        val logoBudgetMs: Long = 90_000L,
        val desktopBudgetMs: Long = 180_000L,
        val display: String? = null,
        val rootDir: String? = null,
        val trampoline: String? = null,
        val persona: String? = null,
        val surface: String? = null,
        val desktopSignal: String? = null,
        val noise: String? = null,
        /** Guest 侧网络结论（合并上游 36a08e7），来自串口的 `net:` 行。 */
        val net: String? = null,
        val qemuAlive: Boolean = true,
        val autoFallback: Boolean = true,
        val fallbackUsed: Boolean = false,
        val events: List<String> = emptyList(),
    )

    data class Density(
        val baselineLines: Int,
        val enhancedLines: Int,
        val lineRatio: Double,
        val enhancedFields: Int,
        val avgFieldsPerLine: Double,
        val samples: Int,
        val dimensionsPerSample: Int,
    ) {
        val meetsTarget: Boolean
            get() = lineRatio >= DENSITY_TARGET && avgFieldsPerLine >= MIN_FIELDS_PER_LINE
    }

    /** 渲染一轮采样：固定 7 个维度行，外加若干 EVENT 行。每行一条、可被 grep/脚本解析。 */
    fun render(s: Snapshot): List<String> {
        val t = "+${secs(s.elapsedMs)}s"
        val out = mutableListOf<String>()
        out += row(t, "PHASE",
            "stage=${s.stage}/7", "textStage=${s.textStage}", "label=${f(s.label)}",
            "stuck=${s.stuck}", "dwell=${secs(s.dwellMs)}s")
        out += row(t, "SERIAL",
            "delta=${s.serialDeltaLines}", "total=${s.serialTotalLines}",
            "rate=${one(s.serialLinesPerSec)}/s", "stall=${secs(s.serialStallMs)}s",
            "last=${f(s.serialLastLine ?: "—")}")
        out += row(t, "FRAME",
            "frames=${s.frames}", "changed=${s.changedFrames}", "mean=${s.meanBrightness}",
            "res=${f(s.resolution)}", "capture=${s.captureMs}ms", "interval=${s.intervalMs}ms",
            "pace=${f(s.framePace)}", "unchanged=${secs(s.unchangedForMs)}s")
        out += row(t, "CPU",
            "vcpu=${s.guestVcpu}", "hostPool=${s.hostCores}", "tb=${s.tbSizeMiB}MiB",
            "affinity=${if (s.affinityApplied) "applied" else "degraded"}", "mttcg=${s.mttcg}")
        out += row(t, "TIMELINE",
            "logo=${s.logoMs?.let { secs(it) + "s" } ?: "—"}/${secs(s.logoBudgetMs)}s",
            "desktop=${s.desktopMs?.let { secs(it) + "s" } ?: "—"}/${secs(s.desktopBudgetMs)}s",
            "logoVerdict=${verdict(s.logoMs, s.logoBudgetMs)}",
            "desktopVerdict=${verdict(s.desktopMs, s.desktopBudgetMs)}")
        out += row(t, "GUEST", *guestFields(s))
        out += row(t, "HEALTH",
            "qemuAlive=${s.qemuAlive}",
            "serialGrowing=${s.serialDeltaLines > 0 && s.serialStallMs < SERIAL_STALL_MS}",
            "frameGrowing=${s.changedFrames > 0 && s.unchangedForMs < FRAME_STALL_MS}",
            "frameStall=${secs(s.unchangedForMs)}s",
            "fallback=${if (!s.autoFallback) "off" else if (s.fallbackUsed) "used" else "armed"}")
        for (e in s.events) out += row(t, "EVENT",
            "kind=${f(e)}", "stage=${s.stage}", "vcpu=${s.guestVcpu}", "elapsed=${secs(s.elapsedMs)}s")
        return out
    }

    /** GUEST 行：有什么写什么；全空时写 signals=none，保证该行仍有信息。 */
    private fun guestFields(s: Snapshot): Array<String> = buildList {
        s.display?.let { add("display=${f(it)}") }
        s.rootDir?.let { add("rootDir=${f(it)}") }
        s.trampoline?.let { add("trampoline=${f(it)}") }
        s.persona?.let { add("persona=${f(it)}") }
        s.surface?.let { add("surface=${f(it)}") }
        s.desktopSignal?.let { add("desktopSignal=${f(it)}") }
        s.net?.let { add("net=${f(it)}") }
        s.noise?.let { add("noise=${f(it)}") }
        if (isEmpty()) {
            add("signals=none")
            add("stage=${s.stage}")
            add("phase=${f(s.label)}")
            add("stuck=${s.stuck}")
        }
    }.toTypedArray()

    /** 从相邻两轮采样自动检测事件；AppViewModel 也可自行追加。 */
    fun detectEvents(prev: Snapshot?, cur: Snapshot): List<String> = buildList {
        if (prev == null) {
            add("boot-start stage=${cur.stage} vcpu=${cur.guestVcpu} hostPool=${cur.hostCores}")
            return@buildList
        }
        if (prev.stage != cur.stage) add("phase-jump ${prev.stage}->${cur.stage} ${f(cur.label)}")
        if (cur.captureMs >= SLOW_CAPTURE_MS && prev.captureMs < SLOW_CAPTURE_MS)
            add("slow-capture ${cur.captureMs}ms threshold=${SLOW_CAPTURE_MS}ms")
        if (cur.serialStallMs >= SERIAL_STALL_MS && prev.serialStallMs < SERIAL_STALL_MS)
            add("serial-stall ${secs(cur.serialStallMs)}s lastSerial=${f(cur.serialLastLine ?: "—")}")
        if (cur.unchangedForMs >= FRAME_STALL_MS && prev.unchangedForMs < FRAME_STALL_MS)
            add("frame-stall ${secs(cur.unchangedForMs)}s frames=${cur.frames}")
        if (cur.fallbackUsed && !prev.fallbackUsed) add("auto-fallback triggered vcpu=${cur.guestVcpu}")
        if (!cur.qemuAlive && prev.qemuAlive) add("qemu-exit stage=${cur.stage}")
        mapOf(
            "root-eacces" to cur.rootDir, "cryptex-dlopen" to cur.trampoline,
            "display-mismatch" to cur.display, "desktop-signal" to cur.desktopSignal,
        ).forEach { (kind, value) ->
            val before = when (kind) {
                "root-eacces" -> prev.rootDir
                "cryptex-dlopen" -> prev.trampoline
                "display-mismatch" -> prev.display
                else -> prev.desktopSignal
            }
            if (value != null && before == null) add("$kind ${f(value)}")
        }
        if (cur.noise != null && prev.noise == null) add("guest-noise ${f(cur.noise)}")
    }

    /**
     * 精确复刻 0.26.0 之前 App 在启动期间写进 app.log 的启动相关行：
     * 开始 1 行 + 每次阶段跃迁 1 行 + logo/desktop 里程碑各 1 行 + 退出 1 行。
     * 这是密度对照的**基线**，不允许为了让倍率好看而改它。
     */
    fun legacyLines(samples: List<Snapshot>): List<String> = buildList {
        if (samples.isEmpty()) return@buildList
        add("开始启动：Guest vCPU=${samples.first().guestVcpu}")
        var last = -1
        var logo = false
        var desktop = false
        for (s in samples) {
            if (s.stage != last) {
                add("启动阶段 $last → ${s.stage}：${s.label}")
                last = s.stage
            }
            if (!logo && s.logoMs != null) { add("亮起 Logo：${s.logoMs}ms"); logo = true }
            if (!desktop && s.desktopMs != null) { add("进入桌面：${s.desktopMs}ms"); desktop = true }
        }
        add("QEMU 进程退出：exit=0")
    }

    /** 一次启动的密度对照。enhanced 用真实 [render] 结果，不做任何估算。 */
    fun density(samples: List<Snapshot>): Density {
        val baseline = legacyLines(samples).size
        val enhanced = samples.flatMap { render(it) }
        val fields = enhanced.sumOf { countFields(it) }
        return Density(
            baselineLines = baseline,
            enhancedLines = enhanced.size,
            lineRatio = if (baseline == 0) 0.0 else enhanced.size.toDouble() / baseline,
            enhancedFields = fields,
            avgFieldsPerLine = if (enhanced.isEmpty()) 0.0 else fields.toDouble() / enhanced.size,
            samples = samples.size,
            dimensionsPerSample = if (samples.isEmpty()) 0 else render(samples.first()).size,
        )
    }

    /** 一行里的 `key=value` 字段数（跳过 [boot] / t= / 维度名三个头）。 */
    fun countFields(line: String): Int =
        line.split(' ').drop(3).count { '=' in it }

    // ---- 格式化工具 -------------------------------------------------------------------------

    private fun row(t: String, dim: String, vararg fields: String): String =
        "$PREFIX t=$t $dim " + fields.joinToString(" ")

    /** 去换行、空格转下划线、限长：保证一行一条、不破坏 key=value 分隔。 */
    private fun f(v: String): String = v
        .replace('\n', ' ').replace('\r', ' ').replace(' ', '_')
        .replace("\t", "_").take(160)

    private fun secs(ms: Long): String = "%.1f".format(ms / 1000.0)
    private fun one(v: Double): String = "%.1f".format(v)
    private fun verdict(v: Long?, budget: Long): String = when {
        v == null -> "pending"
        v <= budget -> "ok"
        else -> "over-budget"
    }
}
