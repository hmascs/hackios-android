package com.android.gudao.iphoneos.state

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.gudao.iphoneos.AssetDeployer
import com.android.gudao.iphoneos.BootPhase
import com.android.gudao.iphoneos.Constants
import com.android.gudao.iphoneos.DownloadPowerLock
import com.android.gudao.iphoneos.FirmwareDownloader
import com.android.gudao.iphoneos.ImageBuilder
import com.android.gudao.iphoneos.QemuControl
import com.android.gudao.iphoneos.QemuRunner
import com.android.gudao.iphoneos.StoragePaths
import com.android.gudao.iphoneos.TouchSender
import com.android.gudao.iphoneos.mcp.McpConfig
import com.android.gudao.iphoneos.mcp.McpHttpServer
import com.android.gudao.iphoneos.mcp.McpProtocol
import com.android.gudao.iphoneos.mcp.QemuBridge
import com.android.gudao.iphoneos.mcp.ToolBox
import com.android.gudao.iphoneos.mcp.ToolError
import com.android.gudao.iphoneos.engine.AppLog
import com.android.gudao.iphoneos.engine.BootDiagnosticLogger
import com.android.gudao.iphoneos.engine.BootDiagnostics
import com.android.gudao.iphoneos.engine.BootErrorCatalog
import com.android.gudao.iphoneos.engine.BootTimeline
import com.android.gudao.iphoneos.engine.CapturePipeline
import com.android.gudao.iphoneos.engine.FramebufferProbe
import com.android.gudao.iphoneos.engine.CrashGuard
import com.android.gudao.iphoneos.engine.LogExporter
import com.android.gudao.iphoneos.engine.LogTailer
import com.android.gudao.iphoneos.engine.QemuFailure
import com.android.gudao.iphoneos.engine.QemuFailureClassifier
import com.android.gudao.iphoneos.engine.QemuFailureKind
import com.android.gudao.iphoneos.engine.QemuFlags
import com.android.gudao.iphoneos.engine.QemuPreflight
import com.android.gudao.iphoneos.engine.QemuTopology
import com.android.gudao.iphoneos.engine.TailText
import com.android.gudao.iphoneos.ui.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Application logic layer.
 *
 * Owns no UI: every long-running operation (download, on-device image build, QEMU lifecycle,
 * framebuffer polling) writes into [state], and the Compose layer only renders that state and calls
 * the intent functions below. That is what makes "every operation has a visible loading / success /
 * error / retry" true by construction instead of by remembering to call `runOnUiThread`.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context = app
    private val settingsRepo = SettingsRepository(app)
    private val deployer = AssetDeployer(app)
    private val runner = QemuRunner(deployer.runDirectory())
    private val imageBuilder = ImageBuilder(app)
    private val downloader = FirmwareDownloader()
    private val powerLock = DownloadPowerLock(app)
    private val control = QemuControl()
    private val touch = TouchSender(control)
    private val capture = CapturePipeline(control, File(app.filesDir, "run/shot.ppm"))

    private val serialTail = LogTailer(File(deployer.runDirectory(), "serial.log"))
    private val qemuTail = LogTailer(File(deployer.runDirectory(), "qemu.log"))
    /**
     * 崩溃堆栈。它和 QEMU 无关：App 可能在没启动 QEMU 的时候就死在界面里，所以这个 tail 不看
     * isQemuRunning()，一直在轮询。
     */
    private val crashTail = LogTailer(CrashGuard.file(deployer.runDirectory()))

    private val _state = MutableStateFlow(AppUiState(settings = settingsRepo.load()))
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    /** 一次启动的分段计时。UI / MCP / 日志导出读的是同一个实例，不会出现三套数字。 */
    private val bootTimeline = BootTimeline()
    /** 自动降级只做一次：防止「卡住→重启→又卡住」无限循环。 */
    private var autoFallbackUsed = false

    /**
     * The log lines live outside [state] on purpose.
     *
     * A 400 ms tail flush used to copy three lists (up to 5000 strings each) into the single root
     * state, which recomposed the whole shell — tab bar, wallpaper host, every glass surface — just
     * because one line arrived. Only the log page subscribes to this flow now, and the root state
     * carries nothing but a line count.
     */
    private val _logLines = MutableStateFlow<Map<LogSource, List<String>>>(emptyMap())
    val logLines: StateFlow<Map<LogSource, List<String>>> = _logLines.asStateFlow()

    /** Latest decoded guest frame, for the display surface. */
    val frameTick: StateFlow<Long> = capture.frameTick

    private val serialBuf = ArrayDeque<String>()
    private val qemuBuf = ArrayDeque<String>()
    private val buildBuf = ArrayDeque<String>()
    private val crashBuf = ArrayDeque<String>()
    private val bootBuf = ArrayDeque<String>()
    private val phaseText = StringBuilder()
    private var logsDirty = false

    // ---- 0.26.0 启动诊断采样状态（只在 startWatcher / updatePhase 的 IO 线程里读写） ----------
    private var diagBootAt = 0L
    private var diagLastEmitAt = 0L
    private var diagSerialTotal = 0L
    private var diagSerialDelta = 0
    private var diagSerialLast: String? = null
    private var diagSerialLastAt = 0L
    private var diagStageSince = 0L
    private var diagPrev: BootDiagnostics.Snapshot? = null
    // 0.26.0 错误细化目录：一次启动一个 Session，逐行累积串口/崩溃/取帧/QEMU 退出。
    // 只在 watcher 的 IO 线程读写；错误档案实时落盘 + 推日志页，退出时用于具体归因。
    private var errorSession: BootErrorCatalog.Session? = null
    private var errorRenderedIds = mutableSetOf<String>()

    // 0.26.1 黑屏直读探针：串口里内核/MiniIOMFB 报告的显示参数 + 一次性的 pmemsave 判读结果。
    private var guestDisplay: FramebufferProbe.GuestDisplay? = null
    private var fbProbeDone = false
    private var fbProbeNote: String? = null
    private var fbProbeNoted = false

    private var downloadJob: Job? = null
    private var buildJob: Job? = null
    private var qemuJob: Job? = null
    private var watcher: Job? = null

    // ---- MCP 端口 ------------------------------------------------------------------------------
    /**
     * 整条链路的工具都挂在 [toolBox] 上，权限档由 [QemuBridge] 从当前配置实时读取——
     * 用户在设置里改权限后，下一次 tools/list 立刻生效，端口不需要重启。
     */
    private val toolBox = ToolBox(bridge())
    private val mcpProtocol = McpProtocol(toolBox) { name, ok -> onMcpTool(name, ok) }
    private val mcpServer = McpHttpServer(
        protocol = mcpProtocol,
        config = { _state.value.settings.mcp },
        onEvent = { onMcpEvent(it) },
    )

    private var toastId = 0L
    private var downloadGen = 0
    private var lastMoveAt = 0L

    private enum class PendingAction { Download, Compile }
    private var pending: PendingAction? = null

    init {
        AppLog.attach(deployer.runDirectory())
        refreshArtifacts()
        viewModelScope.launch {
            capture.snapshot.collect { snap ->
                // 8 frames/s must not become 8 recompositions/s of the whole app. Health changes and
                // error text go through immediately; the frame counters are batched to ~1 Hz.
                val prev = _state.value.capture
                val meaningful = prev.health != snap.health ||
                    prev.attempts != snap.attempts ||
                    prev.lastError != snap.lastError ||
                    snap.frames - prev.frames >= 8
                if (meaningful) _state.update { it.copy(capture = snap) }
            }
        }
        startWatcher()
        // 端口随进程一起起来：默认只听 127.0.0.1，adb forward 之后立刻可以 tools/list。
        restartMcpServer()
        // A previous session may have been killed mid-download; show that the bytes are reusable.
        val ipsw = StoragePaths.ipswFile(ctx)
        val resume = FirmwareDownloader.resumeBytes(ipsw)
        if (resume > 0 && !ipsw.isFile) {
            _state.update { it.copy(download = DownloadUi.Paused(resume)) }
        }
    }

    // ---- intents: navigation & settings ---------------------------------------------------------

    fun selectTab(tab: AppTab) {
        _state.update { it.copy(tab = tab) }
        // Entering 设备/资源 re-stats the image and firmware files: the user may have just changed
        // them from Termux or a file manager, and a stale "缺失" badge would send them in circles.
        if (tab == AppTab.Device || tab == AppTab.Library) {
            viewModelScope.launch(Dispatchers.IO) { refreshArtifacts() }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        settingsRepo.setTheme(mode)
        _state.update { it.copy(settings = it.settings.copy(themeMode = mode)) }
    }

    fun setSmp(value: Int) {
        if (isQemuRunning()) return
        settingsRepo.setSmp(value)
        _state.update { it.copy(settings = it.settings.copy(smp = value)) }
    }

    /**
     * 宿主算力池（0.26.0）。只影响下次 QEMU 命令行的 `tb-size` 与亲和性掩码，
     * 不进 payload / ram.img、不改 Guest vCPU 数，所以调节零重建成本。
     */
    fun setHostCores(value: Int) {
        if (isQemuRunning()) return
        val n = value.coerceIn(Constants.HOST_CORES_MIN, Constants.HOST_CORES_MAX)
        settingsRepo.setHostCores(n)
        _state.update { it.copy(settings = it.settings.copy(hostCores = n)) }
    }

    /** 多线程 TCG 开关：关掉回到 0.23.0 的单线程基线，供排障对比。运行中不可改。 */
    fun setMttcg(value: Boolean) {
        if (isQemuRunning()) return
        settingsRepo.setMttcg(value)
        _state.update { it.copy(settings = it.settings.copy(mttcg = value)) }
    }

    /** 卡住自动降核保护开关。 */
    fun setAutoFallback(value: Boolean) {
        settingsRepo.setAutoFallback(value)
        _state.update { it.copy(settings = it.settings.copy(autoFallback = value)) }
    }

    /**
     * 卡在界面层时的一键处置：停掉当前实例，把核数**降一档**（不是硬改回 1 核），再启动。
     *
     * 0.24.0 之前这里写死 `setSmp(1)`，理由是「TCG 下多核更慢」。那个结论只在没开 MTTCG
     * 时成立；现在默认六核 MTTCG，卡住时正确的降级动作是 6→3→2，退到 1 核反而丢掉了并行
     * 翻译的能力。降到底（2 核）后 [QemuTopology.fallbackCores] 返回 null，按钮不再做什么，
     * 避免无限重启。
     *
     * 黑屏的根因是 fbscale（见 BootPhase 的 display 结论），不是核数 —— 这个按钮只处理「卡住」。
     */
    fun restartWithOneCore() {
        if (qemuJob?.isActive == true) {
            toast("正在启动中，等这一轮结束再试", ToastMessage.Kind.Info)
            return
        }
        val current = _state.value.settings.smp
        val next = QemuTopology.fallbackCores(current)
        if (next == null) {
            toast("已是 ${QemuTopology.FALLBACK_MIN_CORES} 核稳定档，卡住原因多半不是核数：" +
                "看「屏幕」页的诊断与 serial.log", ToastMessage.Kind.Info)
            return
        }
        AppLog.w(TAG, "用户选择「降核重启」：$current → $next 核（MTTCG=${_state.value.settings.mttcg}）")
        viewModelScope.launch(Dispatchers.IO) {
            withContextIo {
                capture.stop()
                control.quit()
                delay(600)
                if (runner.isRunning()) runner.stop()
                control.close()
            }
            _state.update {
                it.copy(qemu = QemuUi.Stopped, capture = it.capture.copy(health = CapturePipeline.Health.Idle))
            }
            delay(300)
            setSmp(next)
            // 降核只重建 payload（cpus=N 变了），ram.img 由新 stamp 复用，不重拷 2.5 GB。
            toast("已降到 $next 核，复用镜像缓存重启（约 1–2 分钟）", ToastMessage.Kind.Info)
            startQemu()
        }
    }

    /**
     * 自动降级：画面超过 [Constants.STALL_FALLBACK_MS] 没有变化、且还没到桌面时，降一档重启
     * 一次。和手动按钮共用 [QemuTopology.fallbackCores]，但只触发一次，防止来回重启。
     */
    private fun maybeAutoFallback(stalledForMs: Long) {
        if (autoFallbackUsed) return
        if (!_state.value.settings.autoFallback) return
        if (stalledForMs < Constants.STALL_FALLBACK_MS) return
        if (bootTimeline.reached(BootTimeline.Stage.DESKTOP)) return
        if (qemuJob?.isActive == true || !isQemuRunning()) return
        val current = _state.value.settings.smp
        val next = QemuTopology.fallbackCores(current) ?: run {
            autoFallbackUsed = true
            AppLog.w(TAG, "自动降级：已到 ${QemuTopology.FALLBACK_MIN_CORES} 核下限，不再降核")
            return
        }
        autoFallbackUsed = true
        AppLog.w(TAG, "自动降级：画面 ${stalledForMs / 1000}s 无变化，$current → $next 核重启一次")
        toast("画面 ${stalledForMs / 1000}s 无变化，自动降到 $next 核重启一次", ToastMessage.Kind.Info)
        restartWithOneCore()
    }

    fun setNetEnabled(value: Boolean) {
        if (isQemuRunning()) return
        settingsRepo.setNet(value)
        _state.update { it.copy(settings = it.settings.copy(netEnabled = value)) }
    }

    /** 0.29.6：性能闸门「不再弹出」。只写偏好，不动 QEMU 参数。 */
    fun setPerfGateDismissed(value: Boolean) {
        settingsRepo.setPerfGateDismissed(value)
        _state.update { it.copy(settings = it.settings.copy(perfGateDismissed = value)) }
    }

    fun setFollowLog(value: Boolean) {
        settingsRepo.setFollow(value)
        _state.update { it.copy(followLog = value) }
    }

    fun setGlassStrength(value: GlassStrength) {
        settingsRepo.setGlassStrength(value)
        _state.update { it.copy(settings = it.settings.copy(glassStrength = value)) }
    }

    /** 进程级崩溃记录。MainActivity 在 setContent 之前调用，越早越好。 */
    fun installCrashGuard() = CrashGuard.install(deployer.runDirectory())

    fun dismissToast(id: Long) = _state.update { s -> s.copy(toasts = s.toasts.filterNot { it.id == id }) }

    // ---- intents: MCP 端口 ---------------------------------------------------------------------

    /** 设置页的总开关。关掉端口后 UI 仍可改配置，再打开即重新监听。 */
    fun setMcpEnabled(value: Boolean) {
        settingsRepo.setMcpEnabled(value)
        _state.update { it.copy(settings = it.settings.copy(mcp = it.settings.mcp.copy(enabled = value))) }
        viewModelScope.launch { delay(300); restartMcpServer() }
    }

    fun setMcpPort(value: Int) {
        val port = value.coerceIn(McpConfig.MIN_PORT, McpConfig.MAX_PORT)
        settingsRepo.setMcpPort(port)
        _state.update { it.copy(settings = it.settings.copy(mcp = it.settings.mcp.copy(port = port))) }
        viewModelScope.launch { delay(300); restartMcpServer() }
    }

    fun setMcpBindLan(value: Boolean) {
        settingsRepo.setMcpBindLan(value)
        _state.update { it.copy(settings = it.settings.copy(mcp = it.settings.mcp.copy(bindLan = value))) }
        viewModelScope.launch { delay(300); restartMcpServer() }
        if (value) toast("局域网模式：连接必须带 Bearer token（见下方令牌）", ToastMessage.Kind.Info)
    }

    fun regenerateMcpToken(): String {
        val fresh = settingsRepo.regenerateToken()
        _state.update { it.copy(settings = it.settings.copy(mcp = it.settings.mcp.copy(token = fresh))) }
        toast("已重新生成 MCP 令牌，旧令牌立即失效", ToastMessage.Kind.Success)
        return fresh
    }

    fun setMcpAllowWrite(value: Boolean) {
        settingsRepo.setMcpAllowWrite(value)
        val full = if (value) settingsRepo.load().mcp.fullAccess else false
        _state.update {
            it.copy(settings = it.settings.copy(mcp = it.settings.mcp.copy(allowWrite = value, fullAccess = full)))
        }
        toast(
            if (value) "已开放写类工具：MCP 客户端可启停 QEMU、改配置、编译镜像" else "已切回只读：只能看状态与日志",
            if (value) ToastMessage.Kind.Info else ToastMessage.Kind.Success,
        )
    }

    fun setMcpFullAccess(value: Boolean) {
        settingsRepo.setMcpFullAccess(value)
        _state.update { it.copy(settings = it.settings.copy(mcp = it.settings.mcp.copy(fullAccess = value))) }
        toast(
            if (value) "已开启完全访问：额外开放 delete_chain 与整台 Android 的路径/命令" else "已收回完全访问",
            if (value) ToastMessage.Kind.Info else ToastMessage.Kind.Success,
        )
    }

    /** 手动重启监听（端口占用、配置改完后用）。 */
    fun restartMcp() = restartMcpServer()

    private fun restartMcpServer() {
        val result = mcpServer.start()
        val cfg = _state.value.settings.mcp
        _state.update { s ->
            s.copy(
                mcp = McpUiState(
                    running = result.ok,
                    host = if (result.ok) result.host else cfg.bindHost,
                    port = if (result.ok) result.port else cfg.port,
                    url = cfg.url,
                    adbCommand = cfg.adbCommand,
                    lanUrl = if (cfg.bindLan) {
                        val ip = lanIp()
                        if (ip.isNotEmpty()) cfg.lanUrl(ip) else null
                    } else null,
                    requests = mcpServer.requests.get(),
                    toolCount = toolBox.specs.length(),
                    lastCall = s.mcp.lastCall,
                    lastError = if (result.ok) s.mcp.lastError else result.message,
                    message = result.message,
                )
            )
        }
        AppLog.i(TAG_MCP, result.message)
    }

    private fun onMcpTool(name: String, ok: Boolean) {
        AppLog.i(TAG_MCP, "tools/call $name → ${if (ok) "ok" else "failed"}")
        _state.update {
            it.copy(
                mcp = it.mcp.copy(
                    lastCall = "$name · ${if (ok) "成功" else "失败"}",
                    requests = mcpServer.requests.get(),
                )
            )
        }
    }

    private fun onMcpEvent(text: String) {
        AppLog.i(TAG_MCP, text)
        _state.update {
            it.copy(mcp = it.mcp.copy(message = text, requests = mcpServer.requests.get()))
        }
    }

    /** 复制地址 / adb 命令 / 令牌到剪贴板。 */
    fun copyToClipboard(text: String, label: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("hackios-mcp", text))
        toast("已复制$label", ToastMessage.Kind.Success)
    }

    /** 保存诊断参数；不合法就只提示、不落盘（下一次启动才不会死在参数解析上）。 */
    fun applyQemuFlags(raw: String): Boolean {
        val parsed = QemuFlags.parseDiagnostics(raw)
        if (!parsed.ok) {
            toast(parsed.error!!, ToastMessage.Kind.Error)
            return false
        }
        settingsRepo.setQemuFlags(parsed.text)
        _state.update { it.copy(settings = it.settings.copy(qemuFlags = parsed.text)) }
        toast(
            if (parsed.text.isBlank()) "已清空诊断参数" else "已保存诊断参数，重启后生效",
            ToastMessage.Kind.Success,
        )
        return true
    }

    fun applyBootArgs(raw: String): Boolean {
        val parsed = QemuFlags.parseBootArgs(raw)
        if (!parsed.ok) {
            toast(parsed.error!!, ToastMessage.Kind.Error)
            return false
        }
        settingsRepo.setBootArgs(parsed.text)
        _state.update { it.copy(settings = it.settings.copy(bootArgs = parsed.text)) }
        toast(
            if (parsed.text.isBlank()) "已清空启动参数" else "下次构建 payload 时写入，重启生效",
            ToastMessage.Kind.Success,
        )
        return true
    }

    // ---- intents: firmware ---------------------------------------------------------------------

    fun downloadFirmware() {
        if (downloadJob?.isActive == true) return
        if (!StoragePaths.hasAllFilesAccess(ctx)) {
            pending = PendingAction.Download
            _state.update { it.copy(permissionPending = true) }
            return
        }
        startDownload()
    }

    /** 资源页无储存权限时，「下载固件」按钮改为「授予储存权限」，点它就直接拉起授权。 */
    fun requestStoragePermission() {
        _state.update { it.copy(permissionPending = true) }
    }

    fun cancelDownload() {
        downloader.cancel()
        downloadJob?.cancel()
        val ipsw = StoragePaths.ipswFile(ctx)
        val kept = FirmwareDownloader.resumeBytes(ipsw)
        _state.update { it.copy(download = DownloadUi.Paused(kept)) }
        toast("已暂停下载，已完成的分块已保留，可继续", ToastMessage.Kind.Info)
    }

    fun verifyFirmware() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            val ipsw = StoragePaths.ipswFile(ctx)
            _state.update { it.copy(download = DownloadUi.Checking) }
            val gen = ++downloadGen
            val ok = runCatching { downloader.verifyExisting(ipsw) }.getOrDefault(false)
            if (gen != downloadGen) return@launch
            _state.update {
                it.copy(
                    download = if (ok) {
                        DownloadUi.Ready(ipsw.absolutePath, Constants.IPSW_SHA256, ipsw.length())
                    } else {
                        DownloadUi.Failed(
                            "校验未通过",
                            if (!ipsw.isFile) "文件不存在" else "大小或 SHA-256 与官方 iOS 16.5 (20F66) 不符",
                            FirmwareDownloader.resumeBytes(ipsw),
                        )
                    },
                    artifacts = it.artifacts.copy(
                        firmwarePresent = ipsw.isFile,
                        firmwareBytes = ipsw.length(),
                        firmwareVerified = ok,
                    ),
                )
            }
        }
    }

    private fun startDownload() {
        val ipsw = StoragePaths.ipswFile(ctx)
        ipsw.parentFile?.mkdirs()
        val gen = ++downloadGen
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(download = DownloadUi.Checking) }
            if (runCatching { downloader.verifyExisting(ipsw) }.getOrDefault(false)) {
                if (gen == downloadGen) {
                    _state.update {
                        it.copy(
                            download = DownloadUi.Ready(ipsw.absolutePath, Constants.IPSW_SHA256, ipsw.length()),
                            artifacts = it.artifacts.copy(
                                firmwarePresent = true,
                                firmwareBytes = ipsw.length(),
                                firmwareVerified = true,
                            ),
                        )
                    }
                    toast("固件已存在且校验通过", ToastMessage.Kind.Success)
                }
                return@launch
            }
            // More sockets on Wi-Fi; fewer on cellular so the carrier scheduler does not throttle us.
            downloader.connections = preferredConnections()
            powerLock.acquire()
            val outcome = try {
                downloader.download(ipsw, object : FirmwareDownloader.Listener {
                override fun onProgress(progress: FirmwareDownloader.Progress) {
                    if (gen != downloadGen) return
                    _state.update {
                        it.copy(
                            download = DownloadUi.Running(
                                fraction = progress.fraction,
                                downloaded = progress.downloaded,
                                total = progress.total,
                                bytesPerSec = progress.bytesPerSec,
                                etaSeconds = progress.etaSeconds,
                                chunksDone = progress.chunksDone,
                                chunksTotal = progress.chunksTotal,
                                resumedBytes = progress.resumedBytes,
                            )
                        )
                    }
                }

                override fun onMerging(done: Long, total: Long) {
                    if (gen != downloadGen) return
                    _state.update {
                        it.copy(
                            download = DownloadUi.Merging(
                                fraction = if (total <= 0) 0f else (done.toDouble() / total).toFloat(),
                                done = done,
                                total = total,
                            )
                        )
                    }
                }

                override fun onVerifying(done: Long, total: Long) {
                    if (gen != downloadGen) return
                    _state.update {
                        it.copy(download = DownloadUi.Verifying(if (total <= 0) 0f else done.toFloat() / total))
                    }
                }

                override fun onOutcome(outcome: FirmwareDownloader.Outcome) {
                    if (gen != downloadGen) return
                    when (outcome) {
                        is FirmwareDownloader.Outcome.Success -> {
                            _state.update {
                                it.copy(
                                    download = DownloadUi.Ready(
                                        outcome.file.absolutePath, outcome.sha256, outcome.file.length(),
                                    )
                                )
                            }
                            refreshArtifacts()
                            toast("固件下载并校验完成", ToastMessage.Kind.Success)
                        }

                        is FirmwareDownloader.Outcome.Failure -> {
                            _state.update {
                                it.copy(
                                    download = DownloadUi.Failed(
                                        outcome.message,
                                        outcome.detail,
                                        FirmwareDownloader.resumeBytes(ipsw),
                                    )
                                )
                            }
                        }

                        FirmwareDownloader.Outcome.Cancelled -> Unit   // cancelDownload already set state
                    }
                }
                })
            } finally {
                powerLock.release()
            }
            Log.i(TAG, "download outcome: $outcome")
        }
    }

    /**
     * Sockets to open for the firmware download.
     *
     * Apple's CDN throttles per connection, so throughput scales with socket count up to a point.
     * Wi-Fi/Ethernet get the full pool; cellular stays conservative because a burst of parallel
     * TCP flows on a mobile bearer is exactly what carrier schedulers deprioritise.
     */
    private fun preferredConnections(): Int {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return 6
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return 6
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> 8
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> 4
            else -> 6
        }
    }

    // ---- intents: images -----------------------------------------------------------------------

    fun compileImages() {
        if (buildJob?.isActive == true) return
        if (!StoragePaths.hasAllFilesAccess(ctx)) {
            pending = PendingAction.Compile
            _state.update { it.copy(permissionPending = true) }
            return
        }
        val ipsw = StoragePaths.ipswFile(ctx)
        if (!ipsw.isFile) {
            _state.update {
                it.copy(
                    build = BuildUi.Failed("还没有固件", "先下载 iOS 16.5 固件（约 6.2 GB），再编译镜像。"),
                )
            }
            return
        }
        buildJob = viewModelScope.launch(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            _state.update {
                it.copy(build = BuildUi.Running(
                    "准备", "部署脚本与工具链", BuildUi.Running.NO_PROGRESS, "prepare",
                    startedAtMs = startedAt, phaseStartedAtMs = startedAt,
                ))
            }
            val published = StoragePaths.imagesDir(ctx)
            val result = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val dep = deployer.deploy()
                    if (!dep.ok) throw IllegalStateException("资源未就绪：缺 ${dep.missing.joinToString()}")
                    imageBuilder.build(ipsw, published, object : ImageBuilder.Progress {
                        // A new phase inherits nothing from the previous one. Keeping the old
                        // fraction is what froze the card at "100 % · cryptex-app.dmg 10 / 10 MB"
                        // while fetch.sh was really listing a 4.7 GB image: the number belonged to
                        // the stage that had already finished.
                        override fun onPhase(phase: String) {
                            val now = System.currentTimeMillis()
                            _state.update { s ->
                                val b = s.build as? BuildUi.Running
                                val same = b?.phase == phase
                                s.copy(
                                    build = BuildUi.Running(
                                        phase = phase,
                                        detail = if (same) b?.detail.orEmpty() else "",
                                        fraction = if (same) b?.fraction ?: 0f else BuildUi.Running.NO_PROGRESS,
                                        stage = imageBuilder.stage,
                                        startedAtMs = b?.startedAtMs ?: now,
                                        phaseStartedAtMs = if (same) b?.phaseStartedAtMs ?: now else now,
                                    )
                                )
                            }
                        }

                        override fun onProgress(done: Long, total: Long, label: String) {
                            val f = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
                            val now = System.currentTimeMillis()
                            _state.update { s ->
                                val b = s.build as? BuildUi.Running
                                s.copy(
                                    build = BuildUi.Running(
                                        phase = b?.phase ?: "编译",
                                        // The percentage is already on the chip; repeating it here
                                        // ("… · 12%") said nothing the chip did not.
                                        detail = label,
                                        fraction = f,
                                        stage = imageBuilder.stage,
                                        startedAtMs = b?.startedAtMs ?: now,
                                        phaseStartedAtMs = b?.phaseStartedAtMs ?: now,
                                    )
                                )
                            }
                        }

                        override fun onLog(line: String) = appendLog(LogSource.Build, line)
                    })
                }
            }
            result.onSuccess { (sys, root) ->
                refreshArtifacts()
                _state.update {
                    it.copy(
                        build = BuildUi.Done(
                            sys.absolutePath,
                            root.absolutePath,
                            sys.length() / 1_000_000,
                            root.length() / 1_000_000,
                        ),
                        // 构建刚完成：立即询问是否清 5+GB 中间产物（保留最终 apfs 镜像）。
                        cleanupPrompt = true,
                    )
                }
                toast("镜像编译完成", ToastMessage.Kind.Success)
            }.onFailure { t ->
                if (t is CancellationException) return@onFailure
                val msg = t.message ?: t.javaClass.simpleName
                if (msg.contains("已取消")) {
                    _state.update { it.copy(build = BuildUi.Cancelled(imageBuilder.stage)) }
                } else {
                    // The reason sits at the END of this message: ImageBuilder puts the last 40
                    // lines of the failed step there, and the traceback or the tool's own refusal
                    // is the last thing in them. Keeping the first 1200 characters therefore kept
                    // forty lines of "job com.apple..." and cut off the one line that named the
                    // failure -- the card said "build.sh 失败（退出码 1）" and nothing else, twice.
                    val first = msg.lineSequence().firstOrNull()?.take(180) ?: msg
                    _state.update { it.copy(build = BuildUi.Failed(first, TailText.of(msg, FAIL_CHARS))) }
                }
            }
        }
    }

    fun cancelCompile() {
        imageBuilder.cancel()
        buildJob?.cancel()
        _state.update { it.copy(build = BuildUi.Cancelled(imageBuilder.stage)) }
        toast("已取消编译，已完成的阶段会在下次继续", ToastMessage.Kind.Info)
    }

    fun retryCompile() = compileImages()

    // ---- intents: QEMU -------------------------------------------------------------------------

    fun startQemu() {
        if (qemuJob?.isActive == true || isQemuRunning()) return
        val cores = _state.value.settings.smp
        val hostCores = _state.value.settings.hostCores
        val mttcg = _state.value.settings.mttcg
        autoFallbackUsed = false
        bootTimeline.start(cores, mttcg, hostCores)
        AppLog.i(TAG, "开始启动：${QemuTopology.describe(cores, hostCores, mttcg)}")
        // 0.26.0：每次启动开一份新的结构化诊断日志，上一份备份为 .prev.log 供对比。
        diagBootAt = System.currentTimeMillis()
        diagLastEmitAt = diagBootAt
        diagSerialTotal = 0L
        diagSerialDelta = 0
        diagSerialLast = null
        diagSerialLastAt = diagBootAt
        diagStageSince = diagBootAt
        diagPrev = null
        // 0.26.1 直读探针状态随每轮启动重置。
        guestDisplay = null
        fbProbeDone = false
        fbProbeNote = null
        fbProbeNoted = false
        // 0.26.0 错误细化目录：每次启动新建 Session，清掉上一轮档案与快照文件。
        errorSession = BootErrorCatalog.Session()
        errorRenderedIds.clear()
        runCatching {
            File(deployer.runDirectory(), "error-catalog.log")
                .writeText("# 启动错误细化档案（错误出现时实时刷新）· version=0.26.1\n")
        }
        BootDiagnosticLogger.begin(
            deployer.runDirectory(),
            "${QemuTopology.describe(cores, hostCores, mttcg)} · version=0.26.1",
        )
        if (cores > Constants.SMP_STABLE_MAX) {
            // 7/8 核只是软提示：MTTCG 让它们能并行，但 guest 自身的调度压力确实会上升。
            toast(
                "$cores 核超出推荐稳定档（${Constants.SMP_STABLE_MAX} 核），若界面层卡住会自动降核",
                ToastMessage.Kind.Info,
            )
        }
        qemuJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(qemu = QemuUi.Starting("检查运行资源")) }
            val dep = runCatching { deployer.deploy() }.getOrElse {
                failQemu("资源部署失败", it.message ?: "未知错误", "点「重新部署资源」后重试")
                return@launch
            }
            bootTimeline.mark(BootTimeline.Stage.DEPLOY)
            persistTimeline()
            if (!dep.ok) {
                _state.update {
                    it.copy(
                        qemu = QemuUi.Failed(
                            QemuFailure(
                                QemuFailureKind.AssetsMissing,
                                "运行资源未解压",
                                "缺少：${dep.missing.joinToString()}",
                                "点「重新部署资源」，App 会从 APK 里重新解压 QEMU 与固件。",
                            )
                        )
                    )
                }
                return@launch
            }

            val sys = StoragePaths.systemApfs(ctx)
            val root = StoragePaths.rootApfs(ctx)
            if (!sys.isFile || !root.isFile) {
                _state.update {
                    it.copy(
                        build = BuildUi.Failed("镜像缺失", "system.apfs / root.apfs 不在 ${StoragePaths.imagesDir(ctx)}"),
                        qemu = QemuUi.Failed(
                            QemuFailure(
                                QemuFailureKind.MissingPayload,
                                "还没有可启动的镜像",
                                "system.apfs / root.apfs 尚未生成。",
                                "到「资源」页编译镜像（约 20–40 分钟，可中断续跑）。",
                            )
                        ),
                    )
                }
                return@launch
            }

            val rev = imageBuilder.publishedRev()
            if (rev != Constants.IMAGE_REV) {
                _state.update { it.copy(qemu = QemuUi.StaleImages(rev, Constants.IMAGE_REV)) }
                return@launch
            }

            val busy = runCatching { QemuPreflight.busyPorts() }.getOrDefault(emptyList())
            if (busy.isNotEmpty()) {
                _state.update { it.copy(qemu = QemuUi.Starting("清理残留的 QEMU 进程")) }
                control.quit()
                delay(700)
                val stillBusy = runCatching { QemuPreflight.busyPorts() }.getOrDefault(emptyList())
                if (stillBusy.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            qemu = QemuUi.Failed(
                                QemuFailure(
                                    QemuFailureKind.PortInUse,
                                    "控制端口被占用",
                                    "端口 ${stillBusy.joinToString(" / ")} 仍被占用，通常是上一次 QEMU 崩溃后没有退出。",
                                    "点「强制清理」结束残留进程，再重新启动。",
                                )
                            )
                        )
                    }
                    return@launch
                }
            }

            val smp = _state.value.settings.smp
            val hostCores = _state.value.settings.hostCores
            val net = _state.value.settings.netEnabled
            val bootArgs = _state.value.settings.bootArgs
            val extraFlags = _state.value.settings.qemuFlags
            // 0.24.0：prepare 内部按 .ram-stamp / .payload-stamp 分别判断，改核数只重打包
            // payload，不重拷 2.5 GB 的 ram.img。外层这层判断只用来决定要不要显示「准备…」。
            if (runCatching {
                    QemuPreflight.needsPrepare(deployer.runDirectory(), sys, root, smp, rev, bootArgs)
                }.getOrDefault(true)
            ) {
                _state.update { it.copy(qemu = QemuUi.Starting("准备引导负载（payload / ram.img）")) }
                if (!runner.willRebuildRam(sys, rev)) bootTimeline.notePayloadReused()
                val prepared = runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        runner.prepare(sys, root, smp = smp, extraBootArgs = bootArgs, rev = rev)
                    }
                }
                if (prepared.isFailure) {
                    val t = prepared.exceptionOrNull()!!
                    val oom = t is OutOfMemoryError || t.message?.contains("OutOfMemory") == true
                    failQemu(
                        if (oom) "构建引导负载时内存不足" else "构建引导负载失败",
                        t.message ?: t.javaClass.simpleName,
                        if (oom) "关闭其它应用后重试；payload 已改为流式写入，通常 1–2 分钟完成。" else null,
                    )
                    return@launch
                }
            } else {
                bootTimeline.notePayloadReused()
            }
            bootTimeline.mark(BootTimeline.Stage.PREPARE)
            persistTimeline()

            // 上游 2026-11 修复：上次误进 EDK2 内置 Shell 后，UEFI NVRAM 会把 Boot0002(EFI Shell)
            // 排到启动顺序首位，之后每次「启动」都只剩一个 Shell 画面，看起来就是黑屏卡死。
            // 旧串口日志里出现 Shell 标记时，先用 APK 里的原始 QEMU_VARS.fd 覆盖 NVRAM，再清日志。
            val previousSerial = readFileTail(runner.serialLog(), 4000)
            if (previousSerial.contains("UEFI Interactive Shell") ||
                previousSerial.contains("Shell>") || previousSerial.contains("FS0:")
            ) {
                if (deployer.resetEfiVars()) {
                    runCatching { runner.serialLog().delete() }
                    AppLog.w(TAG, "上次停在 EFI Shell：已重置 UEFI NVRAM，本次重新从 BOOTAA64.EFI 启动")
                }
            }

            _state.update { it.copy(qemu = QemuUi.Starting("启动 QEMU（MTTCG 软件模拟）")) }
            val started = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runner.start(
                        smp = smp,
                        netEnabled = net,
                        dnsServers = if (net) hostDnsServers() else emptyList(),
                        extraFlags = extraFlags,
                        mttcg = mttcg,
                        hostCores = hostCores,
                    )
                }
            }
            if (started.isFailure) {
                val t = started.exceptionOrNull()!!
                _state.update {
                    it.copy(
                        qemu = QemuUi.Failed(
                            QemuFailure(
                                QemuFailureKind.BundleIncomplete,
                                "QEMU 未能启动",
                                t.message ?: t.javaClass.simpleName,
                                "点「重新部署资源」重试；若反复失败，展开日志查看最后几行。",
                            )
                        )
                    )
                }
                return@launch
            }

            delay(BOOT_PROBE_MS)
            if (!runner.isRunning()) {
                // 0.29.2 自愈重试：exit=1 且 serial.log 根本没被创建 = QEMU 在 qemu_create_late_backends()
                // 打开 `-serial file:` 之前就退出。实测该形态几乎都落在两个阶段：TCG 翻译缓存
                // （tb-size=1360 MiB 是匿名内存，失败最可能）或 guest 内存分配失败；参数解析错误
                // 也可能，但那样 qemu.log 有原文。先换「TB 512 MiB / 单线程 TCG」再试一次。
                //
                // guest 内存刻意保持 7G：system.apfs 从 0xFC000000 起驻留 2.32 GiB，结束于
                // 约 6.26 GiB；6G 会把它推出 RAM，反而必然失败。
                val firstExit = runner.exitCode()
                val firstLog = runCatching { runner.qemuLog().readText().trim() }.getOrDefault("")
                val firstSummary = runner.lastStartSummary
                var retried = false
                if ((firstExit == 1 || firstExit == 2) && !runner.serialLog().isFile) {
                    runCatching {
                        File(deployer.runDirectory(), "qemu-first-attempt.log").writeText(
                            "exit=$firstExit\n${firstLog.ifEmpty { "(qemu.log 为空)" }}\n" +
                                "$firstSummary\n"
                        )
                    }
                    AppLog.w(
                        TAG,
                        "QEMU 早退（exit=1、串口未建立）：用保守档重试一次" +
                            "（TB 512 MiB / 单线程 TCG）。首次输出：" +
                            firstLog.take(500).ifEmpty { "（空）" },
                    )
                    retried = runCatching {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            runner.start(
                                smp = smp,
                                netEnabled = net,
                                dnsServers = if (net) hostDnsServers() else emptyList(),
                                extraFlags = extraFlags,
                                mttcg = false,
                                hostCores = hostCores,
                                tbMiB = 512,
                            )
                        }
                    }.isSuccess
                    if (retried) delay(BOOT_PROBE_MS)
                }
                if (!runner.isRunning()) {
                    val failure = QemuFailureClassifier.classify(
                        exitCode = runner.exitCode(),
                        qemuLog = readFileTail(runner.qemuLog(), 4000),
                        serialLogExists = runner.serialLog().isFile,
                        hasPayload = File(deployer.runDirectory(), "payload.bin").isFile,
                        runtimeNote = if (retried) "$firstSummary\n${runner.lastStartSummary}" else firstSummary,
                        retriedWithSafeProfile = retried,
                    )
                    _state.update { it.copy(qemu = QemuUi.Failed(failure)) }
                    return@launch
                }
            }

            serialTail.reset()
            qemuTail.reset()
            phaseText.setLength(0)
            control.connect()
            capture.start()
            bootTimeline.mark(BootTimeline.Stage.QEMU_SPAWN)
            persistTimeline()
            _state.update {
                it.copy(
                    qemu = QemuUi.Running(
                        sinceEpochMs = System.currentTimeMillis(),
                        smp = smp,
                        netEnabled = net,
                        phase = BootPhaseUi(),
                    ),
                    tab = AppTab.Device,
                )
            }
            toast(
                "QEMU 已启动（${QemuTopology.describe(smp, hostCores, mttcg)}，" +
                    "亲和性${if (runner.lastAffinityApplied) "已启用" else "降级"}），正在引导 iOS",
                ToastMessage.Kind.Success,
            )
        }
    }

    fun stopQemu() {
        val wasRunning = isQemuRunning()
        capture.stop()
        qemuJob?.cancel()
        qemuJob = null
        viewModelScope.launch(Dispatchers.IO) {
            withContextIo {
                control.quit()
                delay(600)
                if (runner.isRunning()) runner.stop()
                control.close()
            }
            _state.update { it.copy(qemu = QemuUi.Stopped, capture = it.capture.copy(health = CapturePipeline.Health.Idle)) }
            if (wasRunning) toast("已停止 QEMU", ToastMessage.Kind.Info)
        }
    }

    fun forceCleanup() {
        viewModelScope.launch(Dispatchers.IO) {
            withContextIo {
                control.quit()
                runner.stop()
                control.close()
            }
            delay(400)
            _state.update { it.copy(qemu = QemuUi.Stopped) }
            toast("已清理残留进程与端口", ToastMessage.Kind.Success)
        }
    }

    /** 清空日志：内存环形缓冲 + 显示流 + 磁盘日志文件一并归零（QEMU 运行时由 UI 二次确认）。 */
    fun clearLogs() {
        synchronized(serialBuf) { serialBuf.clear() }
        synchronized(qemuBuf) { qemuBuf.clear() }
        synchronized(buildBuf) { buildBuf.clear() }
        synchronized(bootBuf) { bootBuf.clear() }
        synchronized(crashBuf) { crashBuf.clear() }
        _logLines.value = emptyMap()
        _state.update { it.copy(logCount = 0) }
        val dir = deployer.runDirectory()
        for (name in listOf(
            "serial.log", "qemu.log", "app.log", "crash.log",
            "error-catalog.log", "boot-diagnostics.log",
        )) {
            runCatching { File(dir, name).writeText("") }
        }
        runCatching {
            File(dir, "build-logs").listFiles()?.forEach { f -> runCatching { f.writeText("") } }
        }
        toast("日志已清空", ToastMessage.Kind.Info)
    }

    /** 构建完成后立即询问是否清 5+GB 构件残留：用户选择保留。 */
    fun dismissCleanupPrompt() {
        _state.update { it.copy(cleanupPrompt = false) }
    }

    /** 构建完成后立即询问是否清 5+GB 构件残留：用户选择清除（只删中间产物，保留最终 apfs 镜像）。 */
    fun clearBuildResidue() {
        _state.update { it.copy(cleanupPrompt = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val dir = deployer.runDirectory()
            // 中间产物：解包出的 rootfs / system、payload、ram.img、预编译目录、构建日志。
            // 不动最终的 system.apfs / root.apfs（那是编译交付物）。
            for (name in listOf(
                "rootfs", "rootfs.slt", "system", "prebuilt",
                "payload.bin", "ram.img", "payload",
            )) {
                runCatching { File(dir, name).deleteRecursively() }
            }
            runCatching { File(dir, "build-logs").deleteRecursively() }
            toast("已清除构件残留", ToastMessage.Kind.Success)
        }
    }

    /** User chose to boot anyway even though the images are older than this APK. */
    fun ignoreStaleImages() {
        _state.update { it.copy(qemu = QemuUi.Stopped) }
        toast("仍要启动旧镜像：可到「资源」页重编后再试", ToastMessage.Kind.Info)
    }

    fun retryQemu() {
        _state.update { it.copy(qemu = QemuUi.Stopped) }
        startQemu()
    }

    private fun failQemu(title: String, detail: String, hint: String?) {
        _state.update {
            it.copy(
                qemu = QemuUi.Failed(
                    QemuFailure(QemuFailureKind.Unknown, title, detail, hint)
                )
            )
        }
    }

    // ---- intents: guest input ------------------------------------------------------------------

    fun sendKey(name: String) {
        if (!isQemuRunning()) {
            toast("QEMU 未运行", ToastMessage.Kind.Error)
            return
        }
        viewModelScope.launch(Dispatchers.IO) { runCatching { touch.button(name) } }
    }

    fun swipeUpToUnlock() {
        if (!isQemuRunning()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { touch.swipe(0.5f, 0.98f, 0.5f, 0.42f, 260) }
        }
    }

    fun touchDown(x: Float, y: Float) {
        if (!isQemuRunning()) return
        viewModelScope.launch(Dispatchers.IO) { runCatching { control.pointer(x, y, down = true) } }
    }

    fun touchMove(x: Float, y: Float) {
        if (!isQemuRunning()) return
        val now = System.currentTimeMillis()
        // ~60 Hz ceiling: the old code opened a socket per event and still managed to flood QEMU.
        if (now - lastMoveAt < MOVE_INTERVAL_MS) return
        lastMoveAt = now
        viewModelScope.launch(Dispatchers.IO) { runCatching { control.pointer(x, y) } }
    }

    fun touchUp(x: Float, y: Float) {
        if (!isQemuRunning()) return
        viewModelScope.launch(Dispatchers.IO) { runCatching { control.pointer(x, y, down = false) } }
    }

    fun retryCapture() {
        capture.retryNow()
        toast("正在重试取帧", ToastMessage.Kind.Info)
    }

    fun frame() = capture.currentFrame()

    // ---- MCP bridge：整条链路的动作入口 ---------------------------------------------------------

    /**
     * [QemuBridge] 是 MCP 工具箱唯一能碰到 App 的窄接口。
     *
     * 权限档从当前配置实时读取；所有会改变链路的动作集中到 [QemuBridge.dispatch] 一个 when 里，
     * 这样"客户端能做什么"和"App 有哪些按钮"走的是同一套实现，不会出现两条路径行为不一致。
     */
    private fun bridge(): QemuBridge = object : QemuBridge {
        override val runDir: File get() = deployer.runDirectory()
        override val allowWrite: Boolean get() = _state.value.settings.mcp.allowWrite
        override val fullAccess: Boolean get() = _state.value.settings.mcp.effectiveFull
        override fun buildLogDir(): File = File(deployer.runDirectory(), "build-logs")

        /** 删链/盘点时的全部目录，交给 ToolBox 自己枚举，避免它去猜 Android 存储路径。 */
        override fun chainRoots(): Map<String, File> = linkedMapOf(
            "run" to deployer.runDirectory(),
            "images" to StoragePaths.imagesDir(ctx),
            "ipsw" to StoragePaths.ipswDir(ctx),
            "logs" to File(StoragePaths.root(ctx), "logs"),
            "exports" to File(ctx.cacheDir, "exports"),
        )

        override suspend fun status(): String = withContext(Dispatchers.IO) {
            val s = _state.value
            buildString {
                appendLine("QEMU: " + when (val q = s.qemu) {
                    is QemuUi.Running -> "运行中 " + Fmt.elapsed(q.sinceEpochMs) +
                        "，${q.smp} vCPU，网络" + (if (q.netEnabled) "开" else "关") +
                        "，阶段 ${q.phase.label}（${q.phase.stage}/7）"

                    is QemuUi.Starting -> "启动中：" + q.step
                    is QemuUi.Failed -> "失败：" + q.failure.title + " — " + q.failure.detail
                    is QemuUi.StaleImages -> "镜像过期（${q.foundRev} → ${q.expectedRev}）"
                    QemuUi.Stopped -> "未运行"
                })
                appendLine(
                    "镜像: system=${Fmt.bytes(s.artifacts.systemBytes)} 存在=${s.artifacts.systemImagePresent}；" +
                        "root=${Fmt.bytes(s.artifacts.rootBytes)} 存在=${s.artifacts.rootImagePresent}；" +
                        "rev=${s.artifacts.imageRev}（需要 ${s.artifacts.expectedRev}）"
                )
                appendLine(
                    "固件: 存在=${s.artifacts.firmwarePresent} ${Fmt.bytes(s.artifacts.firmwareBytes)}" +
                        "，校验=" + (s.artifacts.firmwareVerified?.let { if (it) "通过" else "失败" } ?: "未校验")
                )
                appendLine("构建: " + when (val b = s.build) {
                    is BuildUi.Running -> "${b.phase} ${b.detail}"
                    is BuildUi.Failed -> "失败：" + b.message
                    is BuildUi.Done -> "已完成"
                    is BuildUi.Cancelled -> "已取消"
                    BuildUi.Idle -> "空闲"
                })
                appendLine("下载: " + when (val d = s.download) {
                    is DownloadUi.Running -> "${(d.fraction * 100).toInt()}%"
                    is DownloadUi.Merging -> "合并中 ${(d.fraction * 100).toInt()}%"
                    is DownloadUi.Ready -> "已完成"
                    is DownloadUi.Failed -> "失败：" + d.message
                    is DownloadUi.Paused -> "已暂停，可续传 ${Fmt.bytes(d.resumeBytes)}"
                    is DownloadUi.Verifying -> "校验中 ${(d.fraction * 100).toInt()}%"
                    DownloadUi.Checking -> "检查中"
                    DownloadUi.Idle -> "空闲"
                })
                appendLine(
                    "配置: ${QemuTopology.describe(s.settings.smp, s.settings.hostCores, s.settings.mttcg)}，" +
                        "网络=${s.settings.netEnabled}，" +
                        "自动降核=" + (if (s.settings.autoFallback) "开" else "关")
                )
                appendLine(
                    "诊断参数: " + s.settings.qemuFlags.ifBlank { "无" } +
                        "；启动参数: " + s.settings.bootArgs.ifBlank { "无" }
                )
                if (s.bootTimeline.active) {
                    appendLine(
                        "启动计时: ${s.bootTimeline.smp} 核 MTTCG=${s.bootTimeline.mttcg}；" +
                            "Logo=" + (s.bootTimeline.logoMs?.let { "${it}ms ${if (s.bootTimeline.logoVerdict == true) "达标" else "超预算"}" } ?: "未到达") +
                            "；桌面=" + (s.bootTimeline.desktopMs?.let { "${it}ms ${if (s.bootTimeline.desktopVerdict == true) "达标" else "超预算"}" } ?: "未到达") +
                            "（逐段报告见 boot_timeline 工具）"
                    )
                }
                appendLine("日志导出: " + (s.export.summary?.let { "${s.export.lastPath}（$it）" } ?: "尚无"))
                append(
                    "MCP: " + if (s.mcp.running) {
                        "监听 ${s.mcp.host}:${s.mcp.port}，${s.mcp.toolCount} 个工具，${s.mcp.requests} 次请求，最近 ${s.mcp.lastCall ?: "无"}"
                    } else "未运行（${s.mcp.message ?: "设置 → MCP 服务"}）"
                )
            }
        }

        /** `get_config`：所有持久化配置。令牌只给掩码，完整值在设置页。 */
        override suspend fun config(): String = withContext(Dispatchers.IO) {
            val s = _state.value.settings
            val m = s.mcp
            buildString {
                appendLine("smp=${s.smp}（Guest vCPU / SMP 拓扑）")
                appendLine("host_cores=${s.hostCores}（宿主算力池；TB ${QemuTopology.tbSize(s.hostCores)} MiB，亲和性 ${QemuTopology.affinityMask(s.hostCores)}）")
                appendLine("mttcg=${s.mttcg}（thread=multi；false=单线程 TCG 排障基线）")
                appendLine("auto_fallback=${s.autoFallback}（卡住自动降核一次）")
                appendLine("net=${s.netEnabled}")
                appendLine("theme=${s.themeMode.name.lowercase()}")
                appendLine("glass=${s.glassStrength.name.lowercase()}")
                appendLine("follow_log=${s.followLog}")
                appendLine("qemu_flags=${s.qemuFlags.ifBlank { "(空)" }}")
                appendLine("boot_args=${s.bootArgs.ifBlank { "(空)" }}")
                appendLine("mcp_enabled=${m.enabled}")
                appendLine("mcp_port=${m.port}")
                appendLine("mcp_bind_lan=${m.bindLan}")
                appendLine("mcp_token=${maskToken(m.token)}（完整值在 设置 → MCP 服务；用 set_config key=mcp_token value=regenerate 可重生成）")
                appendLine("mcp_allow_write=${m.allowWrite}")
                appendLine("mcp_full_access=${m.fullAccess}（生效=${m.effectiveFull}）")
                append("mcp_url=${m.url} · ${m.adbCommand}")
            }
        }

        override suspend fun mcpInfo(): String = withContext(Dispatchers.IO) {
            val s = _state.value
            val m = s.settings.mcp
            buildString {
                appendLine("server=${McpConfig.SERVER_NAME} ${McpConfig.SERVER_VERSION} · protocol=${McpConfig.PROTOCOL_VERSION}")
                appendLine("listening=${s.mcp.running} ${s.mcp.host}:${s.mcp.port}")
                appendLine("url=${m.url}")
                appendLine("adb=${m.adbCommand}")
                appendLine("endpoints=POST /mcp · GET /mcp (SSE) · GET /sse + POST /message (legacy SSE) · GET /health")
                appendLine("token_required=${m.bindLan} · token=${maskToken(m.token)}")
                appendLine("permission=" + when {
                    m.effectiveFull -> "完全访问（含 delete_chain）"
                    m.allowWrite -> "读写"
                    else -> "只读"
                })
                appendLine("tools=${toolBox.specs.length()} · requests=${mcpServer.requests.get()} · lastCall=${s.mcp.lastCall ?: "无"}")
                appendLine("lastError=${s.mcp.lastError ?: "无"}")
                val ip = lanIp()
                if (m.bindLan && ip.isNotEmpty()) append("lan_url=${m.lanUrl(ip)}")
            }
        }

        override suspend fun hmp(command: String): String =
            withContext(Dispatchers.IO) { control.hmp(command) }

        override suspend fun screen(): String = withContext(Dispatchers.IO) {
            val snap = capture.snapshot.value
            if (capture.currentFrame() == null) {
                return@withContext "截帧失败：monitor 不可达（QEMU 可能没有在运行，或刚启动还没协商出分辨率）。" +
                    "上一次错误：" + (snap.lastError ?: "无")
            }
            "屏幕 ${snap.resolution}，平均亮度 ${snap.mean}/765，黑屏连续 ${snap.blackStreak} 帧，" +
                "取帧 ${snap.frames} 次（${"%.1f".format(snap.fps)} fps）。" +
                if (snap.mean <= 2) "全黑：访客没有往 framebuffer 画任何东西——问题在访客侧，不在取帧。" else "访客正在渲染。"
        }

        /**
         * 所有改变链路的动作。AppViewModel 的公开 intent 方法本身就是异步的（内部 launch），
         * 所以这里只负责调用它们并返回一句人话；客户端随后用 device_status / read_log 轮询。
         */
        override suspend fun dispatch(action: String, args: JSONObject): String = withContext(Dispatchers.IO) {
            when (action) {
                "start_qemu" -> {
                    if (isQemuRunning()) return@withContext "QEMU 已经在运行，无需重复启动。"
                    if (qemuJob?.isActive == true) return@withContext "正在启动中，请等这一轮结束。"
                    startQemu()
                    "已发出启动：部署资源 → 检查镜像 → 构建 payload → 启动 QEMU。" +
                        "TCG 下到 SpringBoard 需要 1–3 分钟，用 device_status / read_log(serial) 看进展。"
                }

                "stop_qemu" -> {
                    val wasRunning = isQemuRunning()
                    stopQemu()
                    delay(900)
                    if (wasRunning) "已停止 QEMU：monitor 已关闭，4444/4445 端口已释放；镜像与固件未动。"
                    else "QEMU 本来就没在跑，已顺带清理残留连接。"
                }

                "restart_qemu" -> {
                    if (qemuJob?.isActive == true) return@withContext "正在启动中，等这一轮结束再重启。"
                    stopQemu()
                    var waited = 0
                    while (runner.isRunning() && waited < 15_000) {
                        delay(200)
                        waited += 200
                    }
                    startQemu()
                    "已发出重启：旧进程已停止，正在按当前配置重建 payload 并启动。"
                }

                "force_cleanup" -> {
                    forceCleanup()
                    "已结束占用 4444/4445 的残留进程并释放端口。"
                }

                "send_key" -> {
                    if (!isQemuRunning()) throw ToolError("QEMU 未运行，无法注入按键。")
                    sendKey(args.optString("name"))
                    "已发送按键 ${args.optString("name")}。"
                }

                "deploy_assets" -> {
                    val dep = runCatching { deployer.deploy() }
                        .getOrElse { throw ToolError("资源部署失败：${it.message}") }
                    if (!dep.ok) throw ToolError("资源部署未完成，缺少：${dep.missing.joinToString()}")
                    "已从 APK 重新部署 QEMU 二进制、工具链与构建脚本到 ${deployer.runDirectory().absolutePath}。"
                }

                "compile_images" -> {
                    compileImages()
                    "已触发镜像编译（20–40 分钟，可中断续跑）。用 device_status 与 read_log(build) 跟踪。"
                }

                "cancel_compile" -> {
                    cancelCompile()
                    "已请求取消编译，进度会写入检查点，下次 compile_images 可续跑。"
                }

                "download_firmware" -> {
                    downloadFirmware()
                    "已触发 IPSW 下载（6.1 GB，Apple CDN，支持断点续传）。用 device_status 看进度。"
                }

                "cancel_download" -> {
                    cancelDownload()
                    "已暂停下载，已下载的分块保留，可再次 download_firmware 续传。"
                }

                "verify_firmware" -> {
                    verifyFirmware()
                    "已触发 SHA-256 校验，结果见 device_status 的「固件」一行。"
                }

                "export_logs" -> {
                    val share = args.optBoolean("share", false)
                    exportLogs(share)
                    "已触发完整日志导出（share=$share）。完成后路径写入 export.lastPath，" +
                        "可用 device_status 查看；share=true 会拉起系统分享面板。"
                }

                "run_diagnostics" -> {
                    runDiagnostics()
                    "已触发工具链自检（bash / python3 / 7zz / unzbm），结果见 device_status 与日志页。"
                }

                "refresh_artifacts" -> {
                    refreshArtifacts()
                    "已重新统计固件、镜像与镜像版本。"
                }

                "set_config" -> applyConfig(args.optString("key"), args.optString("value"))

                "reset_config" -> {
                    settingsRepo.resetQemuSettings()
                    _state.update { it.copy(settings = settingsRepo.load()) }
                    "已把 QEMU/外观设置恢复默认；MCP 端口配置保留，避免把当前连接关掉。"
                }

                else -> throw ToolError("未知的链路动作：$action")
            }
        }
    }

    /**
     * 通用配置写入。设置页的单项开关和 MCP 的 set_config 都落到这里，
     * 保证「通过端口改配置」和「在界面上拖滑块」写的是同一份 SharedPreferences。
     */
    private fun applyConfig(key: String, value: String): String {
        val cfg = _state.value.settings.mcp
        return when (key) {
            "smp" -> {
                if (isQemuRunning()) throw ToolError("QEMU 运行中不能改核数：先 stop_qemu。")
                val n = value.toIntOrNull() ?: throw ToolError("smp 需要整数，收到 $value")
                setSmp(n)
                "Guest vCPU(smp) 已设为 ${_state.value.settings.smp}（${QemuTopology.describe(_state.value.settings.smp, _state.value.settings.hostCores, _state.value.settings.mttcg)}），下一次启动生效。改 vCPU 数只重建 payload，不重拷 ram.img。"
            }

            "host_cores" -> {
                if (isQemuRunning()) throw ToolError("QEMU 运行中不能改宿主算力池：先 stop_qemu。")
                val n = value.toIntOrNull() ?: throw ToolError("host_cores 需要整数，收到 $value")
                if (n !in Constants.HOST_CORES_MIN..Constants.HOST_CORES_MAX) {
                    throw ToolError("host_cores 必须在 ${Constants.HOST_CORES_MIN}..${Constants.HOST_CORES_MAX}，收到 $n")
                }
                setHostCores(n)
                "宿主算力池(host_cores) 已设为 ${_state.value.settings.hostCores}，" +
                    "TB 缓存 ${QemuTopology.tbSize(_state.value.settings.hostCores)} MiB、" +
                    "亲和性 ${QemuTopology.affinityMask(_state.value.settings.hostCores)}（启动时按系统放行的核与大核优先重算，可能不同）；" +
                    "不改变 Guest vCPU 数，也不重建 payload / ram.img，下一次启动零成本生效。"
            }

            "mttcg" -> {
                if (isQemuRunning()) throw ToolError("QEMU 运行中不能改 TCG 模式：先 stop_qemu。")
                setMttcg(value.equals("true", ignoreCase = true))
                "mttcg 已设为 ${_state.value.settings.mttcg}；下一次启动使用 " +
                    QemuTopology.describe(_state.value.settings.smp, _state.value.settings.hostCores, _state.value.settings.mttcg) + "。"
            }

            "auto_fallback" -> {
                setAutoFallback(value.equals("true", ignoreCase = true))
                "auto_fallback 已设为 ${_state.value.settings.autoFallback}（卡住 ${Constants.STALL_FALLBACK_MS / 1000}s 自动降核一次）。"
            }

            "net" -> {
                if (isQemuRunning()) throw ToolError("QEMU 运行中不能改网络：先 stop_qemu。")
                setNetEnabled(value.equals("true", ignoreCase = true))
                "net 已设为 ${_state.value.settings.netEnabled}，下一次启动生效。"
            }

            "theme" -> {
                val mode = ThemeMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?: throw ToolError("theme 只能是 system / light / dark")
                setThemeMode(mode)
                "theme 已设为 ${mode.name.lowercase()}。"
            }

            "glass" -> {
                val g = GlassStrength.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?: throw ToolError("glass 只能是 subtle / standard / strong")
                setGlassStrength(g)
                "glass 已设为 ${g.name.lowercase()}。"
            }

            "follow_log" -> {
                setFollowLog(value.equals("true", ignoreCase = true))
                "follow_log 已设为 ${_state.value.followLog}。"
            }

            "qemu_flags" -> {
                settingsRepo.setQemuFlags(value)
                _state.update { it.copy(settings = it.settings.copy(qemuFlags = value)) }
                "qemu_flags 已保存「${value.ifBlank { "(空)" }}」，下一次启动生效。"
            }

            "boot_args" -> {
                settingsRepo.setBootArgs(value)
                _state.update { it.copy(settings = it.settings.copy(bootArgs = value)) }
                "boot_args 已保存「${value.ifBlank { "(空)" }}」，payload.bin 失效，下次启动自动重建。"
            }

            "mcp_enabled" -> {
                val on = value.equals("true", ignoreCase = true)
                settingsRepo.setMcpEnabled(on)
                _state.update { it.copy(settings = it.settings.copy(mcp = it.settings.mcp.copy(enabled = on))) }
                viewModelScope.launch { delay(400); restartMcpServer() }
                "mcp_enabled=$on。监听稍后更新；关闭会让当前连接断开，请把它作为最后一次调用。"
            }

            "mcp_port" -> {
                val p = value.toIntOrNull() ?: throw ToolError("mcp_port 需要整数，收到 $value")
                settingsRepo.setMcpPort(p)
                _state.update { it.copy(settings = it.settings.copy(mcp = it.settings.mcp.copy(port = p))) }
                viewModelScope.launch { delay(400); restartMcpServer() }
                "mcp_port 已设为 ${_state.value.settings.mcp.port}，监听稍后重启；记得更新 adb forward。"
            }

            "mcp_bind_lan" -> {
                val on = value.equals("true", ignoreCase = true)
                settingsRepo.setMcpBindLan(on)
                _state.update { it.copy(settings = it.settings.copy(mcp = it.settings.mcp.copy(bindLan = on))) }
                viewModelScope.launch { delay(400); restartMcpServer() }
                "mcp_bind_lan=$on。局域网模式下所有连接都必须带 Bearer token。"
            }

            "mcp_token" -> {
                val fresh = if (value.equals("regenerate", ignoreCase = true)) settingsRepo.regenerateToken() else value
                if (fresh.isBlank()) throw ToolError("令牌不能为空；传 regenerate 可自动生成。")
                settingsRepo.setMcpToken(fresh)
                _state.update { it.copy(settings = it.settings.copy(mcp = it.settings.mcp.copy(token = fresh))) }
                "令牌已更新（${maskToken(fresh)}），旧令牌立即失效。"
            }

            "mcp_allow_write" -> {
                val on = value.equals("true", ignoreCase = true)
                settingsRepo.setMcpAllowWrite(on)
                val full = if (on) settingsRepo.load().mcp.fullAccess else false
                _state.update {
                    it.copy(settings = it.settings.copy(mcp = it.settings.mcp.copy(allowWrite = on, fullAccess = full)))
                }
                "mcp_allow_write=$on，当前可用工具 ${toolBox.specs.length()} 个（无需重启端口）。"
            }

            "mcp_full_access" -> {
                val on = value.equals("true", ignoreCase = true)
                if (on && !cfg.allowWrite) throw ToolError("完全访问以写权限为前提：先设 mcp_allow_write=true。")
                settingsRepo.setMcpFullAccess(on)
                _state.update { it.copy(settings = it.settings.copy(mcp = it.settings.mcp.copy(fullAccess = on))) }
                "mcp_full_access=$on，当前可用工具 ${toolBox.specs.length()} 个。"
            }

            else -> throw ToolError("未知配置项：$key")
        }
    }

    private fun maskToken(token: String): String = when {
        token.isBlank() -> "(未设置)"
        token.length <= 8 -> "••••"
        else -> token.take(4) + "••••" + token.takeLast(4)
    }

    /** 局域网模式下给客户端看的本机 IPv4；取不到就留空，不影响 loopback 用法。 */
    private fun lanIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress.orEmpty()
    }.getOrDefault("")

    // ---- intents: logs -------------------------------------------------------------------------

    fun copyLog(source: LogSource) {
        val text = when (source) {
            LogSource.Serial -> serialBuf.joinToString("\n")
            LogSource.Qemu -> qemuBuf.joinToString("\n")
            LogSource.Build -> buildBuf.joinToString("\n")
            LogSource.Boot -> bootBuf.joinToString("\n")
            LogSource.Crash -> crashBuf.joinToString("\n")
        }
        if (text.isBlank()) {
            toast("${source.label}日志为空", ToastMessage.Kind.Error)
            return
        }
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("hackios-${source.name}", text))
        toast("已复制 ${source.label}日志（${text.length} 字）", ToastMessage.Kind.Success)
    }

    fun logText(source: LogSource): String = when (source) {
        LogSource.Serial -> serialBuf.joinToString("\n")
        LogSource.Qemu -> qemuBuf.joinToString("\n")
        LogSource.Build -> buildBuf.joinToString("\n")
        LogSource.Boot -> bootBuf.joinToString("\n")
        LogSource.Crash -> crashBuf.joinToString("\n")
    }

    /**
     * 导出**磁盘上**的完整日志，而不是界面上那一小段。
     *
     * 界面的列表为了不把手机拖死，只保留最近几千行；排查启动问题时需要的恰恰是这几千行
     * 之外的部分 —— 一次 `mkjobs.py` 的失败在几十万行里只占几行，`复制` 永远够不着。
     * 所以这里直接读文件（串口、QEMU 自己的 stderr、最近几次构建的输出），清洗掉 ANSI
     * 控制序列后写成一个 txt。
     *
     * @param share true 写到 cacheDir 并拉起系统分享面板；false 存到 Download/hackios/logs。
     */
    fun exportLogs(share: Boolean) {
        if (_state.value.export.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(export = it.export.copy(busy = true, summary = null)) }
            val runDir = deployer.runDirectory()
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val dest = if (share) {
                File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "hackios-logs-$stamp.txt")
            } else {
                File(File(StoragePaths.root(ctx), "logs").apply { mkdirs() }, "hackios-logs-$stamp.txt")
            }
            // 构建日志是「一条命令一个文件」，一次编译能产出几十个；按时间取最近几个，
            // 足够覆盖失败的那一步，也不会让导出的文件大到没法发出去。
            val builds = (File(runDir, "build-logs").listFiles() ?: emptyArray())
                .filter { it.isFile }
                .sortedByDescending { it.lastModified() }
                .take(5)
            val sections = buildList {
                // 0.26.0：错误细化档案排在导出最前 —— 它把散在几十万行串口里的错误逐类
                // 归好、带根因与处置，排障时比原始日志更快给出答案，也不用再猜「CPU 出错之类」。
                add(LogExporter.Section("启动错误细化档案 error-catalog.log", File(runDir, "error-catalog.log")))
                // 崩溃堆栈排在最前：一次排障里，它比几十万行串口输出更可能是那个答案。
                add(LogExporter.Section("异常堆栈 crash.log", CrashGuard.file(runDir)))
                // App 自己的运行日志：MCP 调用、取帧失败、QEMU 退出都在这里。没有它，一份日志
                // 只能说明"guest 说了什么"，说明不了"App 做了什么"。
                add(LogExporter.Section("应用日志 app.log", AppLog.file()))
                // 0.26.0：结构化启动诊断排在最前——排障时它比几十万行串口更快给出答案。
                add(LogExporter.Section("启动诊断 boot-diagnostics.log", BootDiagnosticLogger.file()))
                add(LogExporter.Section("启动诊断（上一次启动）", BootDiagnosticLogger.prevFile()))
                add(LogExporter.Section("串口 serial.log", File(runDir, "serial.log")))
                add(LogExporter.Section("QEMU 输出 qemu.log", File(runDir, "qemu.log")))
                // 0.24.0：分段计时随日志一起走，「3 分钟到桌面」这类结论要靠它复核。
                add(LogExporter.Section("启动分段计时", File(runDir, BootTimeline.FILE_NAME)))
                add(LogExporter.Section("启动命令行", File(runDir, "qemu-cmdline.txt")))
                builds.forEach { add(LogExporter.Section("构建输出 ${it.name}", it, maxLines = 400)) }
            }
            val result = runCatching { LogExporter.export(dest, exportHeader(), sections) }
            val r = result.getOrNull()
            if (r == null) {
                val t = result.exceptionOrNull()
                _state.update { it.copy(export = it.export.copy(busy = false)) }
                toast("导出失败：${t?.message ?: "无法写入 ${dest.absolutePath}"}", ToastMessage.Kind.Error)
                return@launch
            }
            val summary = "${r.lines} 行 · ${Fmt.bytes(r.bytes)}"
            _state.update {
                it.copy(export = ExportUi(busy = false, lastPath = r.file.absolutePath, summary = summary))
            }
            if (share) {
                shareFile(r.file)
                toast("已导出 $summary，选择应用发送即可", ToastMessage.Kind.Success)
            } else {
                toast("已保存 $summary 到 ${r.file.absolutePath}", ToastMessage.Kind.Success)
            }
        }
    }

    /** 再次分享上一次导出的文件（系统分享面板被误关时不用重新扫一遍日志）。 */
    fun shareExportedLog() {
        val path = _state.value.export.lastPath ?: return exportLogs(share = true)
        val file = File(path)
        if (!file.isFile) return exportLogs(share = true)
        shareFile(file)
    }

    private fun shareFile(file: File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(
                Intent.createChooser(send, "分享完整日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast("没有可用于分享的应用", ToastMessage.Kind.Error) }
    }

    /** 导出文件的抬头：出事时最先要看的上下文，都在这里。 */
    private fun exportHeader(): List<String> {
        val s = _state.value
        val qemu = when (val q = s.qemu) {
            is QemuUi.Running -> "运行中 · ${q.phase.label} · ${q.smp} 核 · 网络${if (q.netEnabled) "开" else "关"}"
            is QemuUi.Failed -> "启动失败 · ${q.failure.title}"
            is QemuUi.Starting -> "启动中 · ${q.step}"
            is QemuUi.StaleImages -> "镜像版本不一致（${q.foundRev} / ${q.expectedRev}）"
            QemuUi.Stopped -> "已停止"
        }
        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "?"
        return listOf(
            "HackiOS 完整日志",
            "导出时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}",
            "设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · " +
                android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            "APK：$version",
            "镜像：第 ${s.artifacts.imageRev} 版（本 APK 要求第 ${s.artifacts.expectedRev} 版）",
            "QEMU：$qemu",
            "说明：下面是从磁盘读取的完整日志。界面上的日志列表只保留最近一段，" +
                "所以这里的行数通常多得多；构建日志取最近 5 次命令的输出。",
        )
    }

    // ---- intents: diagnostics ------------------------------------------------------------------

    fun runDiagnostics() {
        if (_state.value.diagnostics.running) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(diagnostics = DiagnosticsUi(running = true, lines = listOf("正在自检…"))) }
            val lines = mutableListOf<String>()
            val checks = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    deployer.deploy()
                    imageBuilder.verify { line -> lines += line }
                }
            }
            checks.onSuccess { list ->
                val failed = list.count { !it.ok }
                _state.update {
                    it.copy(
                        diagnostics = DiagnosticsUi(
                            running = false,
                            lines = lines.takeLast(300),
                            summary = if (failed == 0) "全部 ${list.size} 项通过"
                            else "$failed / ${list.size} 项需要处理",
                        )
                    )
                }
            }.onFailure { t ->
                _state.update {
                    it.copy(
                        diagnostics = DiagnosticsUi(
                            running = false,
                            lines = lines + "自检异常：${t.message ?: t.javaClass.simpleName}",
                            summary = "自检未能完成",
                        )
                    )
                }
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        val action = pending
        pending = null
        _state.update { it.copy(permissionPending = false) }
        if (!granted) {
            toast("未授权：固件与镜像将保存到应用私有目录", ToastMessage.Kind.Info)
            return
        }
        when (action) {
            PendingAction.Download -> startDownload()
            PendingAction.Compile -> compileImages()
            null -> Unit
        }
    }

    // ---- internals -----------------------------------------------------------------------------

    fun refreshArtifacts() {
        val ipsw = StoragePaths.ipswFile(ctx)
        val sys = StoragePaths.systemApfs(ctx)
        val root = StoragePaths.rootApfs(ctx)
        _state.update {
            it.copy(
                artifacts = ArtifactsUi(
                    firmwarePresent = ipsw.isFile,
                    firmwareBytes = ipsw.length(),
                    firmwareVerified = if (_state.value.artifacts.firmwareVerified == true &&
                        it.artifacts.firmwareBytes == ipsw.length()
                    ) true else null,
                    systemImagePresent = sys.isFile,
                    rootImagePresent = root.isFile,
                    systemBytes = sys.length(),
                    rootBytes = root.length(),
                    imageRev = imageBuilder.publishedRev(),
                    expectedRev = Constants.IMAGE_REV,
                    storagePath = StoragePaths.root(ctx).absolutePath,
                    storageIsPublic = StoragePaths.isPublic(ctx),
                )
            )
        }
    }

    private fun isQemuRunning(): Boolean = _state.value.qemu is QemuUi.Running

    private fun startWatcher() {
        watcher = viewModelScope.launch(Dispatchers.IO) {
            var sincePhase = 0L
            while (isActive) {
                // 崩溃日志与 QEMU 状态无关：一个死在界面里的异常也要能读到。
                runCatching {
                    val crash = crashTail.poll()
                    if (crash.isNotEmpty()) {
                        appendAll(LogSource.Crash, crash)
                        // 0.26.0：App 侧崩溃也进错误目录（HOST_APP 层 FATAL）
                        ingestErrors(crash)
                    }
                }
                if (isQemuRunning()) {
                    runCatching {
                        val serial = serialTail.poll()
                        if (serial.isNotEmpty()) {
                            appendAll(LogSource.Serial, serial)
                            phaseText.append(serial.joinToString("\n")).append('\n')
                            if (phaseText.length > PHASE_WINDOW) phaseText.delete(0, phaseText.length - PHASE_WINDOW)
                            // 0.26.0：给结构化启动诊断累计串口增量、末行与活跃时间
                            diagSerialTotal += serial.size
                            diagSerialDelta += serial.size
                            diagSerialLast = serial.lastOrNull()?.take(160)
                            diagSerialLastAt = System.currentTimeMillis()
                            // 0.26.1：解析内核/MiniIOMFB 的显示参数行，供黑屏直读探针用
                            for (sl in serial) {
                                // 0.29.6：亮 Logo 改成以**苹果 Logo**为准。此前用的是
                                // `cap.mean > 2`（第一帧非黑），而那通常是 loader/ramfb 画的
                                // 开机图，比 iOS 自己的启动图早十几秒，把预算和耗时都算少了。
                                // guest 合成器画出苹果 Logo 时串口一定会先有 `redwood-gl: frame 1`。
                                if (sl.contains("redwood-gl: frame")) bootTimeline.noteLogo()
                                val d = FramebufferProbe.parseDisplayLine(sl)
                                if (d != null) {
                                    val merged = FramebufferProbe.merge(guestDisplay, d)
                                    if (merged != guestDisplay) {
                                        guestDisplay = merged
                                        AppLog.i(TAG, "显示参数：${merged!!.width}x${merged.height} " +
                                            "fb=0x${java.lang.Long.toHexString(merged.fbAddr)} stride=${merged.stride}")
                                    }
                                }
                            }
                            // 0.26.0：逐行细化 guest 错误（lsd / failed lookup / persona / 权限 …）
                            ingestErrors(serial)
                        }
                        val qemu = qemuTail.poll()
                        if (qemu.isNotEmpty()) {
                            appendAll(LogSource.Qemu, qemu)
                            // QEMU 自身的报错（动态库、端口、ROM）也要被细化
                            ingestErrors(qemu)
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (now - sincePhase > 1000) {
                        sincePhase = now
                        updatePhase()
                    }
                    if (!runner.isRunning()) {
                        // 0.26.0：把本轮错误目录的具体归因交给失败分类器，
                        // 兜底分支不再写「没有匹配到已知错误特征」。
                        val attribution = errorSession?.classifyExit(
                            exitCode = runner.exitCode(),
                            serialLogExists = runner.serialLog().isFile,
                        )
                        val failure = QemuFailureClassifier.classify(
                            exitCode = runner.exitCode(),
                            qemuLog = readFileTail(runner.qemuLog(), 4000),
                            serialLogExists = runner.serialLog().isFile,
                            hasPayload = File(deployer.runDirectory(), "payload.bin").isFile,
                            catalogAttribution = attribution,
                        )
                        // 退出时落一份完整错误档案 + 具体归因到诊断日志，供导出/MCP 回看。
                        errorSession?.let { sess ->
                            val tailLines = buildList {
                                add("[err] ===== 启动结束：错误细化汇总 =====")
                                addAll(sess.render())
                                add("[err] EXIT ${attribution ?: "（无错误档案）"}")
                                add("[err] ===== 汇总结束 =====")
                            }
                            BootDiagnosticLogger.append(tailLines)
                            appendAll(LogSource.Boot, tailLines)
                            runCatching {
                                File(deployer.runDirectory(), "error-catalog.log")
                                    .writeText(sess.render().joinToString("\n") + "\n")
                            }
                        }
                        capture.stop()
                        AppLog.e(
                            TAG,
                            "QEMU 进程退出：exit=${runner.exitCode()}，串口存在=${runner.serialLog().isFile}，" +
                                "判定=${failure.title} — ${failure.detail}",
                        )
                        _state.update { it.copy(qemu = QemuUi.Failed(failure)) }
                    }
                }
                flushLogs()
                delay(WATCH_INTERVAL_MS)
            }
        }
    }

    private fun updatePhase() {
        val r = BootPhase.analyze(phaseText.toString())
        val cap = _state.value.capture
        // 第 7 级只认画面：捕获到的不是黑帧，且至少换过 1 次内容。串口说 "backboardd startup
        // complete" 的那一刻屏幕依然可能是黑的 —— 那正是用户报「画面连不上」时的真实状态，
        // 所以文本证据（r.desktopSignal）只进 notes，不参与升级。
        // 0.25.0 把门槛从 3 次降到 1 次：自适应取帧在启动期只跑 2 FPS，要求换 3 次内容
        // 会让「已经看到桌面」却永远停在 6/7 —— 真机日志就是一直停在阶段 6。
        val painted = cap.frames > 0 && cap.mean > 2 && cap.changedFrames >= 1
        val stage = if (r.stage >= 6 && painted && !r.stuck) 7 else r.stage

        // 把文本阶段喂给取帧调度器：引导期 2 FPS，把 CPU 让给 XNU/SpringBoard。
        capture.setBootStage(r.stage)

        // ---- 启动分段计时：把 BootPhase 的文本阶段与真实画面一起喂给时间线 ------------------
        val beforeMarks = bootTimeline.markCount()
        val hadLogo = bootTimeline.reached(BootTimeline.Stage.LOGO)
        val hadDesktop = bootTimeline.reached(BootTimeline.Stage.DESKTOP)
        bootTimeline.advanceFromPhase(r.stage)
        // 0.29.6：亮 Logo 不再看 screendump 的第一帧非黑（那是 loader 的图），
        // 改为串口出现 `redwood-gl: frame` —— 见上面的串口解析分支。
        if (stage == 7) bootTimeline.noteDesktop()
        if (bootTimeline.markCount() != beforeMarks) {
            persistTimeline()
            // 里程碑只在新到达的那一轮写一行 AppLog，导出日志里能直接看到耗时。
            if (!hadLogo) bootTimeline.elapsed(BootTimeline.Stage.LOGO)?.let {
                AppLog.i(TAG, "亮起 Logo：${it}ms（预算 ${BootTimeline.LOGO_BUDGET_MS}ms）")
            }
            if (!hadDesktop) bootTimeline.elapsed(BootTimeline.Stage.DESKTOP)?.let {
                AppLog.i(TAG, "进入桌面：${it}ms（预算 ${BootTimeline.DESKTOP_BUDGET_MS}ms）")
            }
        }

        val notes = buildList {
            // 0.26.0：错误细化目录 —— 阻塞/致命错误显式置顶，每类都能点开看根因与处置。
            errorSession?.let { sess ->
                sess.findings().filter { it.rule.severity.rank >= BootErrorCatalog.Severity.BLOCKING.rank }
                    .take(3).forEach { f ->
                        add("⚠ 错误 [${f.rule.layer.label}] ${f.rule.component}：${f.rule.cause} → ${f.rule.action}")
                    }
                if (sess.distinctRules() > 0) {
                    add("启动错误细化：${BootErrorCatalog.summary(sess)}（详见日志页「启动诊断」/ error-catalog.log）")
                }
            }
            r.display?.let(::add)
            r.surface?.let(::add)
            r.rootDir?.let(::add)
            r.trampoline?.let(::add)
            r.persona?.let(::add)
            r.desktopSignal?.let(::add)
            r.net?.let(::add)
            r.noise?.let(::add)
            if (cap.frames > 0) {
                add("画面 ${cap.frames} 帧 · ${cap.resolution} · 亮度 ${cap.mean}/765 · 单帧 ${cap.captureMs}ms")
                add("取帧调度：${com.android.gudao.iphoneos.engine.FramePacer.describe(cap.intervalMs)}，" +
                    "间隔 ${cap.intervalMs}ms（引导期让 CPU 给 guest）")
            }
            bootTimeline.elapsed(BootTimeline.Stage.LOGO)?.let {
                add("亮 Logo 用时 ${it / 1000}.${(it % 1000) / 100}s · 预算 ${BootTimeline.LOGO_BUDGET_MS / 1000}s")
            }
            if (cap.frames > 0 && cap.unchangedForMs >= 90_000 && r.stage >= 6) {
                add(
                    "画面 ${cap.unchangedForMs / 1000} 秒没有变化：guest 大概率还在渲染，只是很慢" +
                        "（软件 GL softpipe，1125x2436 单帧 7–43 秒）；串口里 `redwood-gl: frame` 的帧号" +
                        "只要在增长就不是卡死。0.29.5 实测约 10 分钟会出主屏，之后 1–2 帧/秒。" +
                        (if (_state.value.settings.autoFallback && !autoFallbackUsed)
                            "超过 ${Constants.STALL_FALLBACK_MS / 1000}s 会自动降核重启一次。"
                        else "点「屏幕」页的「降核重启」即可。")
                )
            }
        }
        val label = if (stage == 7) "阶段 7/7 · 画面已出现" else r.label
        val next = BootPhaseUi(stage, label, r.stuck, notes)
        val before = (_state.value.qemu as? QemuUi.Running)?.phase?.stage
        _state.update { st ->
            val q = st.qemu
            if (q is QemuUi.Running && q.phase != next) st.copy(qemu = q.copy(phase = next)) else st
        }
        // Logged outside update{}: that lambda may be retried by the compare-and-set loop.
        if (before != null && before != next.stage) {
            AppLog.i(TAG, "启动阶段 $before → ${next.stage}：${next.label}")
        }

        // 0.25.0：把 CPU 空转观测落到 run/perf-stats.json，MCP perf_stats 与日志导出都能读。
        writePerfStats(stage, r.stage, cap)
        // 0.26.0：每轮输出 7 个维度行 + 事件行，落盘到 boot-diagnostics.log 并在日志页可看。
        emitBootDiagnostics(stage, r, cap)

        // 卡住自动降级：只在真的有帧、但长时间不变化时触发，且只做一次。
        if (cap.frames > 0 && stage < 7) maybeAutoFallback(cap.unchangedForMs)
    }

    /**
     * 0.26.0 启动诊断：把本轮的全部可观测值交给纯 Kotlin 的 [BootDiagnostics] 渲染成
     * 结构化维度行，落盘 + 推到日志页。之前这些值里的大部分（BootPhase 的 7 类结论、
     * 亲和性是否生效、串口吞吐、取帧耗时）只在 UI 上短暂出现，导出日志里完全找不到。
     */
    private fun emitBootDiagnostics(stage: Int, r: BootPhase.Result, cap: CapturePipeline.Snapshot) {
        val now = System.currentTimeMillis()
        val s = _state.value.settings
        val windowMs = (now - diagLastEmitAt).coerceAtLeast(1)
        // 0.26.1：进桌面阶段仍持续黑帧且显示参数已知 → 做一次帧缓冲直读，把结论钉进诊断。
        maybeProbeFramebuffer(stage, cap)
        val snap = BootDiagnostics.Snapshot(
            elapsedMs = now - diagBootAt,
            stage = stage,
            textStage = r.stage,
            label = r.label,
            stuck = r.stuck,
            dwellMs = now - diagStageSince,
            serialDeltaLines = diagSerialDelta,
            serialTotalLines = diagSerialTotal,
            serialLinesPerSec = diagSerialDelta * 1000.0 / windowMs,
            serialStallMs = now - diagSerialLastAt,
            serialLastLine = diagSerialLast,
            frames = cap.frames,
            changedFrames = cap.changedFrames,
            meanBrightness = cap.mean,
            resolution = cap.resolution,
            captureMs = cap.captureMs,
            intervalMs = cap.intervalMs,
            framePace = com.android.gudao.iphoneos.engine.FramePacer.describe(cap.intervalMs),
            unchangedForMs = cap.unchangedForMs,
            guestVcpu = s.smp,
            hostCores = s.hostCores,
            tbSizeMiB = QemuTopology.tbSize(s.hostCores),
            affinityApplied = runner.lastAffinityApplied,
            mttcg = s.mttcg,
            logoMs = bootTimeline.elapsed(BootTimeline.Stage.LOGO),
            desktopMs = bootTimeline.elapsed(BootTimeline.Stage.DESKTOP),
            logoBudgetMs = BootTimeline.LOGO_BUDGET_MS,
            desktopBudgetMs = BootTimeline.DESKTOP_BUDGET_MS,
            display = r.display,
            rootDir = r.rootDir,
            trampoline = r.trampoline,
            persona = r.persona,
            surface = r.surface,
            desktopSignal = r.desktopSignal,
            net = r.net,
            noise = r.noise,
            qemuAlive = runner.isRunning(),
            autoFallback = s.autoFallback,
            fallbackUsed = autoFallbackUsed,
        )
        val lines = BootDiagnostics.run {
            val extra = if (fbProbeNote != null && !fbProbeNoted) {
                // 真机教训（0.26.1）：这个 note 不去重就会在每轮采样里重复 465 条。
                fbProbeNoted = true
                listOf("framebuffer-probe $fbProbeNote")
            } else emptyList()
            render(snap.copy(events = detectEvents(diagPrev, snap) + extra))
        }
        BootDiagnosticLogger.append(lines)
        appendAll(LogSource.Boot, lines)
        if (diagPrev == null || diagPrev?.stage != stage) diagStageSince = now
        diagPrev = snap
        diagLastEmitAt = now
        diagSerialDelta = 0
    }

    /**
     * 0.26.1 黑屏直读探针（一次性）：桌面阶段连续黑帧、且串口报告过显示参数时，用 monitor
     * `pmemsave` 把 boot framebuffer 的 guest 物理内存导出来直接判读。这是区分两种黑屏的唯一
     * 宿主侧手段：
     *  - 直读非黑 → 画面就在内存里，QEMU ramfb surface 不是这块内存；App 可走直读显示（实验）。
     *  - 直读也黑 → 只能说明「这一帧在这个地址上是黑的」。若串口已出现 `swap N layer ...`，
 *    内核的拷贝路径是工作的；此时优先怀疑该帧内容本身（logo/黑帧）或可见 ramfb surface 与
 *    boot fb 地址不同，而不是断言 MiniIOMFB 缺 blit。
     * 只跑一次（runDirectory 下落 fb-dump.bin），结论写诊断事件 + AppLog，不重复做。
     */
    private fun maybeProbeFramebuffer(stage: Int, cap: CapturePipeline.Snapshot) {
        if (fbProbeDone) return
        if (stage < 5) return
        if (cap.mean > FramebufferProbe.BLACK_MEAN) return
        if (cap.blackStreak < 10) return
        // 0.26.2 教训：16.6s 就判过一次——那时 SpringBoard 可能还没画第一帧，黑是正常的。
        // 至少在当前阶段停留 30s 且连续 10 帧黑，才是「该画而不画」。
        if (System.currentTimeMillis() - diagStageSince < 30_000L) return
        val d = guestDisplay ?: return          // 还没从串口看到 display 行，等下一轮
        if (d.fbAddr == 0L) {
            fbProbeDone = true
            fbProbeNote = "skipped: 串口只有 MiniIOMFB 行、没有内核 display 地址行，无 pmemsave 目标"
            AppLog.w(TAG, "帧缓冲直读跳过：${fbProbeNote}")
            return
        }
        fbProbeDone = true
        runCatching {
            val dump = File(deployer.runDirectory(), "fb-dump.bin")
            val ok = control.dumpFramebuffer(d.fbAddr, d.byteSize, dump)
            if (!ok) {
                fbProbeNote = "failed: pmemsave 未产出 ${d.byteSize}B（monitor 无响应或被拒）"
                AppLog.w(TAG, "帧缓冲直读失败：${fbProbeNote}")
                return
            }
            val bytes = ByteArray(d.byteSize.toInt())
            java.io.FileInputStream(dump).use { input ->
                var off = 0
                while (off < bytes.size) {
                    val n = input.read(bytes, off, bytes.size - off)
                    if (n <= 0) break
                    off += n
                }
            }
            val mean = FramebufferProbe.computeMeanBgra(bytes, d)
            val v = FramebufferProbe.verdict(mean)
            fbProbeNote = "addr=0x${java.lang.Long.toHexString(d.fbAddr)} ${d.width}x${d.height} " +
                "mean=${if (mean < 0) "无法读取" else "$mean/765"} verdict=$v"
            when (v) {
                FramebufferProbe.Verdict.NON_BLACK -> {
                    AppLog.i(TAG, "帧缓冲直读有画面（mean=$mean/765）：内核确实在写 boot framebuffer，" +
                        "但 QEMU ramfb surface 不是这块内存。screendump 黑屏不等于 guest 没渲染。")
                    BootDiagnosticLogger.append(
                        listOf("[boot] EVENT kind=framebuffer-probe_non_black mean=$mean addr=0x" +
                            java.lang.Long.toHexString(d.fbAddr))
                    )
                }
                FramebufferProbe.Verdict.BLACK -> {
                    AppLog.w(TAG, "帧缓冲直读全黑（mean=$mean/765）：探测地址 0x" +
                        java.lang.Long.toHexString(d.fbAddr) + " 这一帧没有内容。注意这不等于内核没拷贝：" +
                        "0.29.4 复跑里同一地址能看到内核画的居中 Apple logo，而 QEMU 可见 surface 仍是 " +
                        "loader 的另一份 logo；若串口有 `swap N layer ...` 行，应先查这一帧本身是否为黑/" +
                        "或可见 surface 与 boot fb 地址错位，不要直接断言 MiniIOMFB 缺 memcpy。")
                    BootDiagnosticLogger.append(
                        listOf("[boot] EVENT kind=framebuffer-probe_black mean=$mean addr=0x" +
                            java.lang.Long.toHexString(d.fbAddr))
                    )
                }
                FramebufferProbe.Verdict.UNREADABLE -> {
                    AppLog.w(TAG, "帧缓冲直读无法判读：dump 与显示参数不匹配。")
                }
            }
        }.onFailure {
            fbProbeNote = "error: ${it.message ?: it.javaClass.simpleName}"
            AppLog.w(TAG, "帧缓冲直读异常：${fbProbeNote}")
        }
    }

    /**
     * 0.26.0 错误细化：**只在某类错误第一次出现时**把它的完整档案实时落盘 + 推到日志页，
     * 而不是每秒重复刷 7 个维度行。重复出现的同类错误由 Session 聚合计数，退出时统一汇总。
     * 这样「不管出错在哪里都仔细表示」与「不一味堆日志」是同一件事：每类错误一份讲透的档案。
     */
    private fun ingestErrors(lines: List<String>) {
        val session = errorSession ?: return
        if (lines.isEmpty()) return
        val fresh = session.ingest(lines)
        if (fresh.isEmpty()) return
        val out = mutableListOf<String>()
        for (f in fresh) {
            val r = f.rule
            out += "[err] NEW ${r.id}  sev=${r.severity.label} blocking=${if (r.blocking) "YES" else "no"} layer=${r.layer.name}"
            out += "[err]   WHERE   ${r.component} · ${r.where} · ${r.layer.label}"
            out += "[err]   CAUSE   ${r.cause}"
            out += "[err]   IMPACT  ${r.impact}"
            out += "[err]   ACTION  ${r.action}"
            out += "[err]   EVID    ${f.firstEvidence}"
            // 阻塞/致命错误额外写一行 AppLog，导出日志里一眼可见；噪音类不刷 app.log。
            if (r.severity.rank >= BootErrorCatalog.Severity.BLOCKING.rank) {
                AppLog.w(TAG, "启动错误 [${r.id}] ${r.component}：${r.cause}｜处置：${r.action}")
            }
        }
        BootDiagnosticLogger.append(out)
        appendAll(LogSource.Boot, out)
        // 刷新完整快照（供 MCP errors 工具与人工回看），重复的同类错误在这里带上聚合次数。
        runCatching {
            File(deployer.runDirectory(), "error-catalog.log")
                .writeText(session.render().joinToString("\n") + "\n")
        }
    }

    /** 每轮启动阶段推进都刷新一次；纯文本 JSON，不依赖 org.json（JVM 单测里也可复用格式）。 */
    private fun writePerfStats(stage: Int, textStage: Int, cap: CapturePipeline.Snapshot) {
        val s = _state.value.settings
        val pace = com.android.gudao.iphoneos.engine.FramePacer.describe(cap.intervalMs)
        val sess = errorSession
        // 0.26.0：错误细化摘要也进 perf-stats.json，MCP perf_stats 能直接看到有没有阻塞项。
        val errClasses = sess?.distinctRules() ?: 0
        val errHits = sess?.totalHits() ?: 0
        val errTop = sess?.topSeverity()?.label ?: "无"
        val errBlocking = sess?.hasBlocking() ?: false
        val json = buildString {
            appendLine("{")
            appendLine("  \"version\": \"0.26.0\",")
            appendLine("  \"stage\": $stage,")
            appendLine("  \"text_stage\": $textStage,")
            appendLine("  \"guest_vcpus\": ${s.smp},")
            appendLine("  \"host_cores\": ${s.hostCores},")
            appendLine("  \"tb_size_mib\": ${QemuTopology.tbSize(s.hostCores)},")
            // 0.29.5：写入**真正生效**的掩码（如 6-7）。它由启动时读到的 Cpus_allowed_list 与
            // cpufreq 现算，可能和滑块档位的 0-(N-1) 不同；降级时回落到计划掩码便于对照。
            val appliedMask = runner.lastAffinityMask.ifEmpty { QemuTopology.affinityMask(s.hostCores) }
            appendLine("  \"affinity_mask\": \"$appliedMask\",")
            appendLine("  \"affinity_applied\": ${runner.lastAffinityApplied},")
            appendLine("  \"mttcg\": ${s.mttcg},")
            appendLine("  \"frame_interval_ms\": ${cap.intervalMs},")
            appendLine("  \"frame_pace\": \"$pace\",")
            appendLine("  \"capture_ms\": ${cap.captureMs},")
            appendLine("  \"frames\": ${cap.frames},")
            appendLine("  \"changed_frames\": ${cap.changedFrames},")
            appendLine("  \"unchanged_for_ms\": ${cap.unchangedForMs},")
            appendLine("  \"boot_recipe\": \"FAST=1 (16 desktop jobs, QUIET=1, no cpu_test)\",")
            appendLine("  \"cpu_note\": \"decoupled vCPU/host pool; boot 2 FPS / active 8 FPS / idle 1 FPS / backoff on slow screendump\",")
            appendLine("  \"error_classes\": $errClasses,")
            appendLine("  \"error_hits\": $errHits,")
            appendLine("  \"error_top_severity\": \"$errTop\",")
            appendLine("  \"error_has_blocking\": $errBlocking,")
            appendLine("  \"error_catalog\": \"run/error-catalog.log (MCP: errors / read_log(source=errors))\"")
            appendLine("}")
        }
        runCatching { java.io.File(deployer.runDirectory(), "perf-stats.json").writeText(json) }
    }

    /** 把时间线报告写进 state 与 run/boot-timeline.json，供 UI / MCP / 日志导出共用。 */
    private fun persistTimeline() {
        if (!bootTimeline.isStarted) return
        val ui = BootTimelineUi(
            active = true,
            report = bootTimeline.report(),
            logoMs = bootTimeline.elapsed(BootTimeline.Stage.LOGO),
            desktopMs = bootTimeline.elapsed(BootTimeline.Stage.DESKTOP),
            logoVerdict = bootTimeline.logoVerdict,
            desktopVerdict = bootTimeline.desktopVerdict,
            smp = _state.value.settings.smp,
            mttcg = _state.value.settings.mttcg,
        )
        _state.update { it.copy(bootTimeline = ui) }
        runCatching {
            File(deployer.runDirectory(), BootTimeline.FILE_NAME).writeText(bootTimeline.serialize())
        }
    }

    private fun appendLog(source: LogSource, line: String) = appendAll(source, listOf(line))

    private fun appendAll(source: LogSource, lines: List<String>) {
        val buf = when (source) {
            LogSource.Serial -> serialBuf
            LogSource.Qemu -> qemuBuf
            LogSource.Build -> buildBuf
            LogSource.Boot -> bootBuf
            LogSource.Crash -> crashBuf
        }
        synchronized(buf) {
            for (l in lines) {
                buf.addLast(l)
                while (buf.size > MAX_LOG_LINES) buf.removeFirst()
            }
        }
        logsDirty = true
    }

    private fun flushLogs() {
        if (!logsDirty) return
        logsDirty = false
        val serial = synchronized(serialBuf) { serialBuf.toList() }
        val qemu = synchronized(qemuBuf) { qemuBuf.toList() }
        val build = synchronized(buildBuf) { buildBuf.toList() }
        val boot = synchronized(bootBuf) { bootBuf.toList() }
        val crash = synchronized(crashBuf) { crashBuf.toList() }
        _logLines.value = mapOf(
            LogSource.Serial to serial,
            LogSource.Qemu to qemu,
            LogSource.Build to build,
            LogSource.Boot to boot,
            LogSource.Crash to crash,
        )
        val total = serial.size + qemu.size
        _state.update { if (it.logCount == total) it else it.copy(logCount = total) }
    }

    private fun readFileTail(file: File, maxChars: Int): String = runCatching {
        if (!file.isFile) return@runCatching ""
        val len = file.length()
        val start = (len - maxChars).coerceAtLeast(0)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val bytes = ByteArray((len - start).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrDefault("")

    private fun toast(text: String, kind: ToastMessage.Kind) {
        val id = ++toastId
        // 追加而不是替换：两条消息同时出现时，新的排在旧的下面，不会互相覆盖。
        _state.update {
            it.copy(toasts = (it.toasts + ToastMessage(text, kind, id)).takeLast(MAX_TOASTS))
        }
        viewModelScope.launch {
            delay(TOAST_MS)
            _state.update { s -> s.copy(toasts = s.toasts.filterNot { it.id == id }) }
        }
    }

    private suspend fun withContextIo(block: suspend () -> Unit) =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    private fun hostDnsServers(): List<String> = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return@runCatching emptyList<String>()
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return@runCatching emptyList<String>()
        lp.dnsServers.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    override fun onCleared() {
        super.onCleared()
        mcpServer.stop()
        capture.stop()
        runCatching { control.close() }
        runCatching { if (runner.isRunning()) runner.stop() }
        watcher?.cancel()
    }

    private companion object {
        const val TAG = "AppViewModel"
        const val TAG_MCP = "MCP"
        const val WATCH_INTERVAL_MS = 400L
        const val BOOT_PROBE_MS = 1500L
        const val MOVE_INTERVAL_MS = 12L
        // 一次完整启动的串口输出有一两万行，3000 行会在最需要回头看的时候把开头挤掉。
        // 12000 行约占 8 MB（三个来源合计），largeHeap 下是安全的；LazyColumn 只渲染可见项。
        const val MAX_LOG_LINES = 12000
        const val PHASE_WINDOW = 128 * 1024
        const val TOAST_MS = 3200L

        /** 同时最多显示三条；再多的排队也没有人来得及读。 */
        const val MAX_TOASTS = 3

        /** Characters of a failure message kept for the card, taken from the END. */
        const val FAIL_CHARS = 1200
    }
}
