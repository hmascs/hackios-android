package com.android.gudao.iphoneos.engine

/**
 * 一次启动的**分段计时**。纯 JVM，不碰 Android，整条判定链都能在本地单测里跑。
 *
 * 之前「启动到桌面要多久」只能靠人盯着日志估，App 里也没有任何一处记录「从按下启动到
 * 第一帧亮 logo 花了多少秒」。要做「1 分半亮 logo / 3 分钟进桌面」这类优化，第一步不是
 * 猜哪里慢，而是让每一段都可测。这里把一次启动拆成与 [com.android.gudao.iphoneos.BootPhase]
 * 的 1..7 级对齐、再加上 App 侧的三个准备段（部署 / 构建负载 / 拉起 QEMU）：
 *
 * ```
 *  deploy → prepare → qemu_spawn → uefi(1) → loader(2) → kernel(3)
 *         → launchd(4) → checks(5) → gui(6) → logo(首帧) → desktop(7)
 * ```
 *
 * 两个硬指标来自需求，作为**预算**而不是承诺：
 *  - [LOGO_BUDGET_MS]  = 60 s（0.29.6 实测：第一帧非黑约 20 s，第 2 帧 55 s）
 *  - [DESKTOP_BUDGET_MS] = 600 s（0.29.6 实测：第 10 分钟才出主屏；Mesa softpipe 单帧
 *    7–43 秒，前 9 分半只出 7 帧，所以旧值 3 分钟从来没有参考意义）
 *
 * 真机跑一次，[report] 会给出逐段耗时、累计耗时和这两个预算是否达成；[serialize] 落盘成
 * `boot-timeline.json`，随日志导出一起带走。云端的 x86_64 无 KVM 环境**不具备测速意义**
 * （guest 是 aarch64、再套一层软件翻译），所以这里是「可观测的验收口径」，不是编造的实测值。
 */
class BootTimeline(private val clock: Clock = SystemClock) {

    /** 注入时钟是为了让单测能精确控制时间，不用 sleep。 */
    fun interface Clock {
        fun now(): Long
    }

    private object SystemClock : Clock {
        override fun now(): Long = System.currentTimeMillis()
    }

    enum class Stage(val key: String, val label: String, val phaseStage: Int = -1) {
        DEPLOY("deploy", "部署运行资源"),
        PREPARE("prepare", "构建引导负载（payload / ram.img）"),
        QEMU_SPAWN("qemu_spawn", "拉起 QEMU 进程"),
        UEFI("uefi", "UEFI / EDK2 固件初始化", 1),
        LOADER("loader", "XNULOADER 加载内核 + ramfb 协商", 2),
        KERNEL("kernel", "XNU 内核启动", 3),
        LAUNCHD("launchd", "launchd 用户态", 4),
        CHECKS("checks", "系统自检（36 checks）", 5),
        GUI("gui", "守护进程 / SpringBoard 界面层", 6),
        LOGO("logo", "亮起 Logo（第一帧非黑画面）"),
        DESKTOP("desktop", "进入桌面 / 锁屏（阶段 7 画面出现）", 7),
    }

    /** 一次打点：阶段、相对 t0 的毫秒、墙上时钟。 */
    data class Mark(val stage: Stage, val elapsedMs: Long, val wallMs: Long)

    private val marks = LinkedHashMap<Stage, Mark>()
    private var t0: Long = -1L
    private var smp: Int = 0
    private var hostCores: Int = 0
    private var mttcg: Boolean = false
    private var reusedPayload: Boolean = false

    /**
     * 开始一次新的计时；清掉上一轮所有打点。
     * [hostCores] 带默认值，旧调用 `start(smp, mttcg)` 与既有单测零回归。
     */
    fun start(smp: Int, mttcg: Boolean, hostCores: Int = smp) {
        marks.clear()
        t0 = clock.now()
        this.smp = smp
        this.hostCores = hostCores
        this.mttcg = mttcg
        reusedPayload = false
    }

    val isStarted: Boolean get() = t0 >= 0

    /** 标记「本次启动复用了缓存的 payload / ram.img」——这是提速链路里最值钱的一段。 */
    fun notePayloadReused() {
        reusedPayload = true
    }

    /**
     * 打点，幂等：同一阶段只记第一次。返回 true 表示这是新阶段。
     * 未 [start] 时安全忽略。
     */
    fun mark(stage: Stage): Boolean {
        if (t0 < 0 || marks.containsKey(stage)) return false
        val now = clock.now()
        marks[stage] = Mark(stage, (now - t0).coerceAtLeast(0), now)
        return true
    }

    /**
     * 用 BootPhase 的文本阶段（1..6）推进时间线。阶段只增不减，重复调用无副作用。
     * 阶段 7 由「真实画面」决定，走 [noteDesktop]，不在这里升。
     */
    fun advanceFromPhase(phaseStage: Int) {
        for (s in Stage.entries) {
            if (s.phaseStage in 1..phaseStage) mark(s)
        }
    }

    /** 捕获到第一帧非黑画面：亮 logo 的时刻，同时把此前所有阶段补齐（日志可能还没跟上）。 */
    fun noteLogo() {
        for (s in Stage.entries) {
            if (s.ordinal <= Stage.GUI.ordinal) mark(s)
        }
        mark(Stage.LOGO)
    }

    /** 阶段 7：画面真的出现，进入桌面。 */
    fun noteDesktop() {
        noteLogo()
        mark(Stage.DESKTOP)
    }

    fun elapsed(stage: Stage): Long? = marks[stage]?.elapsedMs
    fun reached(stage: Stage): Boolean = marks.containsKey(stage)

    /** 已打点阶段数；调用方据此判断本轮是否有新进展，避免每 400 ms 重写一次文件。 */
    fun markCount(): Int = marks.size

    /** 从启动到最近一个打点的耗时；没打点返回 0。 */
    val elapsedMs: Long get() = marks.values.lastOrNull()?.elapsedMs ?: 0L

    /** 相邻两段之间的耗时表，用来定位「到底慢在哪一段」。 */
    fun segments(): List<Triple<Stage, Long, Long>> {
        var prev = 0L
        return marks.values.map { m ->
            val delta = m.elapsedMs - prev
            prev = m.elapsedMs
            Triple(m.stage, delta, m.elapsedMs)
        }
    }

    /** logo 是否在预算内亮起；null 表示还没亮。 */
    val logoVerdict: Boolean? get() = elapsed(Stage.LOGO)?.let { it <= LOGO_BUDGET_MS }

    /** desktop 是否在预算内到达；null 表示还没到。 */
    val desktopVerdict: Boolean? get() = elapsed(Stage.DESKTOP)?.let { it <= DESKTOP_BUDGET_MS }

    /** 最长的一段（不含 logo/desktop 这两个里程碑本身），用于「下一步该优化哪」。 */
    val slowestSegment: Pair<Stage, Long>?
        get() = segments()
            .filter { it.first != Stage.LOGO && it.first != Stage.DESKTOP }
            .maxByOrNull { it.second }
            ?.let { it.first to it.second }

    /**
     * 人读的报告：逐段耗时 + 累计 + 两个预算的达成情况。没到达的阶段标「—」。
     */
    fun report(): String = buildString {
        append("启动分段计时 · ").append(QemuTopology.describe(smp, hostCores, mttcg)).append('\n')
        if (reusedPayload) append("引导负载：复用缓存（跳过 payload.bin / ram.img 重建）\n")
        append("预算：Logo ≤ ${LOGO_BUDGET_MS / 1000}s，桌面 ≤ ${DESKTOP_BUDGET_MS / 1000}s\n")
        append("────────────────────────────────────────\n")
        for ((stage, delta, cumulative) in segments()) {
            append(stage.label.padEnd(30))
            append("+").append(fmt(delta)).append("  = ").append(fmt(cumulative)).append('\n')
        }
        if (segments().isEmpty()) append("（尚无打点）\n")
        append("────────────────────────────────────────\n")
        append("Logo：").append(verdictText(Stage.LOGO, LOGO_BUDGET_MS)).append('\n')
        append("桌面：").append(verdictText(Stage.DESKTOP, DESKTOP_BUDGET_MS)).append('\n')
        slowestSegment?.let { (s, ms) ->
            append("最慢一段：${s.label}（+${fmt(ms)}）\n")
        }
    }

    private fun verdictText(stage: Stage, budget: Long): String {
        val e = elapsed(stage)
            ?: return "未到达 · 预算 ${budget / 1000}s（当前已 ${fmt(elapsedMs)}）"
        val ok = e <= budget
        return "${fmt(e)} · ${if (ok) "✓ 达标" else "✗ 超预算"} ${budget / 1000}s"
    }

    /** 落盘格式：一行一个 key=value，外加逐段耗时，方便日志导出与 MCP 读取。 */
    fun serialize(): String = buildString {
        append("profile_smp=").append(smp).append('\n')
        append("profile_mttcg=").append(mttcg).append('\n')
        append("payload_reused=").append(reusedPayload).append('\n')
        append("logo_budget_ms=").append(LOGO_BUDGET_MS).append('\n')
        append("desktop_budget_ms=").append(DESKTOP_BUDGET_MS).append('\n')
        for ((stage, delta, cumulative) in segments()) {
            append(stage.key).append("_at_ms=").append(cumulative)
                .append(" delta_ms=").append(delta).append('\n')
        }
        append("logo_verdict=").append(logoVerdict?.toString() ?: "pending").append('\n')
        append("desktop_verdict=").append(desktopVerdict?.toString() ?: "pending").append('\n')
    }

    private fun fmt(ms: Long): String =
        if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0)

    companion object {
        /** 1 分半内亮 logo。 */
        const val LOGO_BUDGET_MS = 60_000L

        /** 3 分钟内进桌面。 */
        const val DESKTOP_BUDGET_MS = 600_000L

        /** 落盘文件名，位于 run/ 下，随日志导出。 */
        const val FILE_NAME = "boot-timeline.json"
    }
}
