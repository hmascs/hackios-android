package com.android.gudao.iphoneos.state

import com.android.gudao.iphoneos.Constants
import com.android.gudao.iphoneos.engine.BootTimeline
import com.android.gudao.iphoneos.engine.CapturePipeline
import com.android.gudao.iphoneos.engine.QemuFailure
import com.android.gudao.iphoneos.mcp.McpConfig

enum class AppTab { Device, Screen, Library, Settings }

enum class ThemeMode { System, Light, Dark }

/**
 * How much of the Liquid Glass model is actually executed at runtime.
 *
 * The three steps are not just a tint: each one switches a different amount of GPU work.
 *  - [Subtle]  skips the backdrop sample entirely (`reduceTransparency`), so no render node is
 *              captured and no blur runs. This is the fallback for low-end GPUs and the honest
 *              answer to "make it stop lagging".
 *  - [Standard] samples the backdrop once per surface and blurs it — the default iOS look.
 *  - [Strong]   additionally lets every surface resolve its own colour from that sample
 *              (`adaptiveColorScheme`), which is the most expensive path but keeps text legible
 *              on any wallpaper.
 */
enum class GlassStrength(val label: String, val note: String) {
    Subtle("弱", "不采样背景，关掉模糊与折射，最省电"),
    Standard("标准", "采样一次背景并模糊，iOS 默认观感"),
    Strong("强", "逐控件自适应取色，折射最明显，最耗性能"),
}

enum class LogSource(val label: String) {
    Serial("串口"),
    Qemu("QEMU"),
    Build("构建"),

    /** 0.26.0 结构化启动诊断：每轮 7 个维度行 + 事件行，落盘到 boot-diagnostics.log。 */
    Boot("启动诊断"),

    /** 未捕获异常的堆栈（CrashGuard 写入）。默认不显示在分段里也可以，但它是最该被导出的一节。 */
    Crash("异常"),
}

data class SettingsUi(
    /**
     * Guest vCPU（SMP 拓扑）：`-smp N` 与 payload `cpus=N` 呈现给 XNU 的处理器数，
     * 决定 SMP bringup 稳定性。0.26.0 默认 2。
     */
    val smp: Int = Constants.SMP_DEFAULT,
    /**
     * 宿主算力池（Host compute pool / CPU 亲和性 + TB 缓存规格）：QEMU 进程族允许调度到的
     * 物理核数，以及 `tb-size` 按几核规格分配。0.26.0 默认 8，与 [smp] 正交、互不影响。
     */
    val hostCores: Int = Constants.HOST_CORES_DEFAULT,
    /** 多线程 TCG。关掉即回到单线程基线，排障用。 */
    val mttcg: Boolean = Constants.MTTCG_DEFAULT,
    /** 画面卡住超过阈值时自动降核重启一次。 */
    val autoFallback: Boolean = Constants.AUTO_FALLBACK_DEFAULT,
    val netEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.System,
    val followLog: Boolean = true,
    val glassStrength: GlassStrength = GlassStrength.Standard,
    /** 对外 MCP 端口的配置：开关、端口、绑定方式、令牌与权限档。 */
    val mcp: McpConfig = McpConfig(),
    /** 诊断用的 QEMU 开关，下一次启动生效（见 QemuFlags）。 */
    val qemuFlags: String = "",
    /** 额外的 XNU 启动参数，下一次构建 payload 时写入。 */
    val bootArgs: String = "",
    /**
     * 0.29.6：性能闸门。首启弹一次，勾「不再弹出」后永久记住。
     * 默认 false —— 每次打开都弹，直到用户明确选择不再弹出。
     */
    val perfGateDismissed: Boolean = false,
)

sealed interface DownloadUi {
    data object Idle : DownloadUi
    data object Checking : DownloadUi
    data class Running(
        val fraction: Float,
        val downloaded: Long,
        val total: Long,
        val bytesPerSec: Long,
        val etaSeconds: Long?,
        val chunksDone: Int,
        val chunksTotal: Int,
        val resumedBytes: Long,
    ) : DownloadUi

    /**
     * Concatenating the 96 parts into the 6.1 GB image.
     *
     * This used to be silent: the transfer reached 100 % and then nothing happened for minutes
     * while the ETA label kept rendering "计算中…", so the merge looked like a hang.
     */
    data class Merging(val fraction: Float, val done: Long, val total: Long) : DownloadUi

    data class Verifying(val fraction: Float) : DownloadUi
    data class Paused(val resumeBytes: Long) : DownloadUi
    data class Ready(val path: String, val sha256: String, val sizeBytes: Long) : DownloadUi
    data class Failed(val message: String, val detail: String?, val resumableBytes: Long) : DownloadUi
}

sealed interface BuildUi {
    data object Idle : BuildUi
    data class Running(
        val phase: String,
        val detail: String,
        /** 0..1, or [NO_PROGRESS] when this step cannot report one (7z listing, mkapfs). */
        val fraction: Float,
        val stage: String,
        /** Wall clock, so the card can show that the build is still alive. */
        val startedAtMs: Long = 0L,
        val phaseStartedAtMs: Long = 0L,
    ) : BuildUi {
        companion object {
            /** A step without a percentage: the UI shows a sweeping bar instead of a frozen 0 %. */
            const val NO_PROGRESS = -1f
        }
    }

    data class Cancelled(val stage: String) : BuildUi
    data class Done(val systemPath: String, val rootPath: String, val systemMb: Long, val rootMb: Long) : BuildUi
    data class Failed(val message: String, val detail: String?) : BuildUi
}

sealed interface QemuUi {
    data object Stopped : QemuUi
    data class Starting(val step: String) : QemuUi
    data class Running(
        val sinceEpochMs: Long,
        val smp: Int,
        val netEnabled: Boolean,
        val phase: BootPhaseUi,
    ) : QemuUi

    data class Failed(val failure: QemuFailure) : QemuUi

    /** Images in public storage predate this APK's scripts; booting them is a silent no-op. */
    data class StaleImages(val foundRev: Int, val expectedRev: Int) : QemuUi
}

data class BootPhaseUi(
    val stage: Int = 0,
    val label: String = "等待启动",
    val stuck: Boolean = false,
    val notes: List<String> = emptyList(),
) {
    val fraction: Float get() = (stage.coerceIn(0, 7) / 7f)
}

data class ArtifactsUi(
    val firmwarePresent: Boolean = false,
    val firmwareBytes: Long = 0,
    val firmwareVerified: Boolean? = null,
    val systemImagePresent: Boolean = false,
    val rootImagePresent: Boolean = false,
    val systemBytes: Long = 0,
    val rootBytes: Long = 0,
    val imageRev: Int = 0,
    val expectedRev: Int = 0,
    val storagePath: String = "",
    val storageIsPublic: Boolean = false,
) {
    val imagesReady: Boolean get() = systemImagePresent && rootImagePresent
    val imageRevMatches: Boolean get() = imageRev == expectedRev
}

data class ToastMessage(val text: String, val kind: Kind = Kind.Info, val id: Long = 0) {
    enum class Kind { Info, Success, Error }
}

/**
 * 一次「导出完整日志」的结果。
 *
 * 界面上的日志列表有行数上限（内存里只留最近一小段），导出读的是磁盘上的原文件，所以这里
 * 报的行数常常比列表里看到的多一个数量级 —— 那正是这个功能存在的理由。
 */
data class ExportUi(
    val busy: Boolean = false,
    val lastPath: String? = null,
    val summary: String? = null,
)

/**
 * MCP 端口的实时运行状态，设置页据此显示"连上了没有、地址是什么、谁刚刚调了什么"。
 *
 * 和 [McpConfig] 分开：config 是持久化的用户选择，这里是从 [com.android.gudao.iphoneos.mcp.McpHttpServer]
 * 读回来的实际监听结果——端口被占用时两者会不一致，那正是要显示出来的信息。
 */
data class McpUiState(
    val running: Boolean = false,
    val host: String = "",
    val port: Int = McpConfig.DEFAULT_PORT,
    val url: String = "",
    val adbCommand: String = "",
    /** 绑定 0.0.0.0 时的手机局域网地址，null 表示当前是 loopback 模式。 */
    val lanUrl: String? = null,
    val requests: Long = 0L,
    val toolCount: Int = 0,
    val lastCall: String? = null,
    val lastError: String? = null,
    /** 最近一次启停/配置变更的一行结果。 */
    val message: String? = null,
)

data class DiagnosticsUi(
    val running: Boolean = false,
    val lines: List<String> = emptyList(),
    val summary: String? = null,
)

/**
 * 启动分段计时在界面上的投影。由 [BootTimeline] 生成，屏幕页 / MCP / 日志导出共用这一份报告，
 * 避免「界面说 3 分钟、MCP 说 4 分钟」这类两套口径。
 */
data class BootTimelineUi(
    val active: Boolean = false,
    val report: String = "",
    val logoMs: Long? = null,
    val desktopMs: Long? = null,
    val logoVerdict: Boolean? = null,
    val desktopVerdict: Boolean? = null,
    val smp: Int = 0,
    val mttcg: Boolean = false,
)

data class AppUiState(
    val tab: AppTab = AppTab.Device,
    val settings: SettingsUi = SettingsUi(),
    val artifacts: ArtifactsUi = ArtifactsUi(),
    val download: DownloadUi = DownloadUi.Idle,
    val build: BuildUi = BuildUi.Idle,
    val qemu: QemuUi = QemuUi.Stopped,
    val capture: CapturePipeline.Snapshot = CapturePipeline.Snapshot(),
    /** Only the total line count stays in the root state; the lines themselves live in a
     *  dedicated flow so a 400 ms tail flush cannot recompose the whole app shell. */
    val logCount: Int = 0,
    val followLog: Boolean = true,
    /** 新的排在后面：提示条从上往下堆叠，两条永远不会画在同一处。 */
    val toasts: List<ToastMessage> = emptyList(),
    val diagnostics: DiagnosticsUi = DiagnosticsUi(),
    val export: ExportUi = ExportUi(),
    val mcp: McpUiState = McpUiState(),
    val bootTimeline: BootTimelineUi = BootTimelineUi(),
    val permissionPending: Boolean = false,
    /** 构建完成后立即弹窗询问是否清除 5+GB 构件残留。 */
    val cleanupPrompt: Boolean = false,
)
