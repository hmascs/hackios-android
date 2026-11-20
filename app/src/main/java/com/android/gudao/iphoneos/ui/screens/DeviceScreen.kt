package com.android.gudao.iphoneos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.gudao.iphoneos.engine.QemuFailureKind
import com.android.gudao.iphoneos.state.AppUiState
import com.android.gudao.iphoneos.state.BuildUi
import com.android.gudao.iphoneos.state.DownloadUi
import com.android.gudao.iphoneos.state.QemuUi
import com.android.gudao.iphoneos.ui.Fmt
import com.android.gudao.iphoneos.ui.components.Dot
import com.android.gudao.iphoneos.ui.components.Editor
import com.android.gudao.iphoneos.ui.components.EditorSheet
import com.android.gudao.iphoneos.ui.components.GlassActionButton
import com.android.gudao.iphoneos.ui.components.GlassBanner
import com.android.gudao.iphoneos.ui.components.GlassKey
import com.android.gudao.iphoneos.ui.components.GlassPanel
import com.android.gudao.iphoneos.ui.components.Glyph
import com.android.gudao.iphoneos.ui.components.HeroCard
import com.android.gudao.iphoneos.ui.components.IosGlyph
import com.android.gudao.iphoneos.ui.components.IosProgressBar
import com.android.gudao.iphoneos.ui.components.MetricRow
import com.android.gudao.iphoneos.ui.components.SectionLabel
import com.android.gudao.iphoneos.ui.components.StatusChip
import com.android.gudao.iphoneos.ui.theme.LocalIosAccents
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioPopUpButton
import kotlinx.coroutines.delay
import io.github.neoruaa.glasmio.ui.GlasmioSwitch

data class DeviceActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val retry: () -> Unit,
    val compile: () -> Unit,
    val openLogs: () -> Unit,
    val openLibrary: () -> Unit,
    val openScreen: () -> Unit,
    val openSettings: () -> Unit,
    val forceCleanup: () -> Unit,
    /** true = Adreno（实验），false = OSMesa。 */
    val setGpuBackend: (Boolean) -> Unit,
    val setNet: (Boolean) -> Unit,
    val setDataDisk: (String, Int) -> Boolean,
    val copyStoragePath: () -> Unit,
)

/** 系统渲染后端的两个档位。顺序就是下拉栏里的顺序：默认档在前。 */
enum class GpuBackend(val label: String, val note: String) {
    OSMesa("OSMesa", "CPU 软件光栅化，任何设备都能跑"),
    Adreno("Adreno", "高通 GPU（Turnip/Zink），仅 Adreno 8xx"),
}

/** 选中 Adreno 之前要拦截的情形；一次只弹一个。 */
private sealed interface GpuDialog {
    /** APK 不带 Adreno 运行时（普通安装包）。 */
    data object NoRuntime : GpuDialog

    /** 本机 GPU 不是高通 Adreno 8xx。 */
    data class Not8xx(val detected: String) : GpuDialog

    /** 8xx，但不是我们实测过的 830。 */
    data class Untested(val model: Int) : GpuDialog
}

@Composable
fun DeviceScreen(
    state: AppUiState,
    actions: DeviceActions,
    modifier: Modifier = Modifier,
) {
    val accents = LocalIosAccents.current
    val qemu = state.qemu
    val running = qemu is QemuUi.Running
    val phase = (qemu as? QemuUi.Running)?.phase
    val hardwareLocked = running || qemu is QemuUi.Starting
    val gpu = state.gpu
    val backend = if (state.settings.experimentalGpu) GpuBackend.Adreno else GpuBackend.OSMesa

    var editor by remember { mutableStateOf<Editor?>(null) }
    var gpuDialog by remember { mutableStateOf<GpuDialog?>(null) }

    fun pickGpu(picked: GpuBackend) {
        if (picked == backend) return
        when {
            picked == GpuBackend.OSMesa -> actions.setGpuBackend(false)
            !state.experimentalGpuAvailable -> gpuDialog = GpuDialog.NoRuntime
            !gpu.isAdreno8xx -> gpuDialog = GpuDialog.Not8xx(gpu.summary)
            !gpu.isTestedModel -> gpuDialog = GpuDialog.Untested(gpu.model ?: 0)
            else -> actions.setGpuBackend(true)
        }
    }

    GlasmioPinnedListPage(
        title = "设备",
        bottomPadding = 144.dp,
        subtitle = "iPhone X · iOS 16.5 (20F66)",
        modifier = modifier.testTag("screen_device"),
        actions = {
            // 这里曾有一个 MCP 端口按钮，但它只能切到设置页顶部、落不到 MCP 区块，
            // 点了像没反应；MCP 入口统一走底部「设置」，顶栏只保留日志。
            GlasmioButton(
                onClick = actions.openLogs,
                size = null,
                emphasis = GlassEmphasis.Regular,
                modifier = Modifier.semantics { contentDescription = "运行日志" },
            ) {
                IosGlyph(Glyph.Terminal, size = 16.dp)
            }
        },
    ) {
        when (qemu) {
            is QemuUi.Failed -> item {
                GlassBanner(
                    title = qemu.failure.title,
                    message = buildString {
                        append(qemu.failure.detail)
                        qemu.failure.hint?.let { append("\n\n").append(it) }
                    },
                    tint = accents.red,
                    icon = Glyph.Alert,
                    // A missing image is not a retry problem: the next useful action is on the
                    // 资源 page, so the banner sends the user there instead of failing again.
                    primaryLabel = when {
                        qemu.failure.kind == QemuFailureKind.MissingPayload -> "去资源页"
                        // VM 的三条提示都写「重新部署资源」，按钮沿用同一个词，
                        // 用户才能在文案里找到要点的东西。
                        qemu.failure.kind == QemuFailureKind.AssetsMissing -> "重新部署资源"
                        qemu.failure.retryable -> "重试"
                        else -> null
                    },
                    onPrimary = when {
                        qemu.failure.kind == QemuFailureKind.MissingPayload -> actions.openLibrary
                        qemu.failure.kind == QemuFailureKind.AssetsMissing -> actions.retry
                        qemu.failure.retryable -> actions.retry
                        else -> null
                    },
                    secondaryLabel = if (qemu.failure.kind == QemuFailureKind.PortInUse) "强制清理" else "查看日志",
                    onSecondary = if (qemu.failure.kind == QemuFailureKind.PortInUse) actions.forceCleanup else actions.openLogs,
                )
            }

            is QemuUi.StaleImages -> item {
                GlassBanner(
                    title = "镜像版本不一致",
                    message = "公共目录里的 root.apfs 是第 ${qemu.foundRev} 版，本机脚本需要第 ${qemu.expectedRev} 版。" +
                        "启动会被拦下：先编译镜像，再回来启动。",
                    tint = accents.orange,
                    icon = Glyph.Alert,
                    primaryLabel = "编译镜像",
                    onPrimary = actions.compile,
                    // 旧版的「仍然启动」只把状态改回已停止、并没有真的启动旧镜像，点了等于没反应。
                    // 这里改成一个真正可用的下一步；是否允许强启旧镜像留给产品决定。
                    secondaryLabel = "查看日志",
                    onSecondary = actions.openLogs,
                )
            }

            else -> Unit
        }

        val imagesReady = state.artifacts.imagesReady
        val starting = qemu is QemuUi.Starting
        item {
            HeroCard(Modifier.testTag("device_hero")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(
                        text = when (qemu) {
                            is QemuUi.Running -> "运行中"
                            is QemuUi.Starting -> "启动中"
                            is QemuUi.Failed -> "启动失败"
                            is QemuUi.StaleImages -> "待重编"
                            QemuUi.Stopped -> "已停止"
                        },
                        tint = when (qemu) {
                            is QemuUi.Running -> accents.green
                            is QemuUi.Starting -> accents.orange
                            is QemuUi.Failed -> accents.red
                            is QemuUi.StaleImages -> accents.orange
                            QemuUi.Stopped -> LocalContentColor.current.copy(alpha = 0.6f)
                        },
                        icon = Glyph.Circle,
                    )
                    Spacer(Modifier.weight(1f))
                    SessionElapsed(qemu, state.settings.experimentalGpu)
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    when {
                        running -> phase?.label ?: "正在引导 iOS"
                        starting -> qemu.step
                        // First run: images are missing, so "启动" would only ever fail. Point at
                        // the one action that actually moves the flow forward.
                        !imagesReady -> "先准备 iOS 镜像"
                        else -> "在这个设备上启动 iOS"
                    },
                    style = IosTypography.title2,
                    color = LocalContentColor.current,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        running -> if ((phase?.stage ?: 0) < 7) {
                            "正在等待 iOS 界面；首次进入桌面可能需要 10–15 分钟或更久。"
                        } else {
                            "已观测到界面就绪信号，可打开实时画面查看。"
                        }
                        starting -> "正在准备运行资源；首次复制镜像可能需要几分钟，请保持 App 在前台。"
                        !imagesReady -> "下载固件 → 编译镜像，完成后这里就能启动。全部在本机完成。"
                        else -> "下载固件 → 编译镜像 → 启动 QEMU。全部在本机完成。"
                    },
                    style = IosTypography.footnote,
                    color = LocalContentColor.current.copy(alpha = 0.6f),
                )

                Spacer(Modifier.height(16.dp))
                IosProgressBar(
                    fraction = phase?.fraction ?: 0f,
                    indeterminate = starting || (running && (phase?.stage ?: 0) < 7),
                    tint = if (running) accents.blue else LocalContentColor.current.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(10.dp))
                StageDots(stage = phase?.stage ?: 0, active = running, tint = accents.blue)

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlasmioButton(
                        onClick = {
                            when {
                                running -> actions.stop()
                                starting -> Unit
                                !imagesReady -> actions.openLibrary()
                                else -> actions.start()
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("btn_power"),
                        emphasis = GlassEmphasis.Prominent,
                        // No "tap but nothing happens": while the startup job is already running
                        // the button is visibly disabled instead of silently ignoring taps.
                        enabled = !starting,
                    ) {
                        val dim = LocalContentColor.current.copy(alpha = 0.45f)
                        IosGlyph(
                            Glyph.Power,
                            size = 18.dp,
                            tint = if (starting) dim else LocalContentColor.current,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                running -> "停止"
                                starting -> "启动中…"
                                !imagesReady -> "准备镜像"
                                else -> "启动 iOS"
                            },
                            style = IosTypography.headline,
                            color = when {
                                running -> accents.red
                                starting -> dim
                                else -> LocalContentColor.current
                            },
                        )
                    }
                    GlasmioButton(
                        onClick = actions.openScreen,
                        modifier = Modifier.weight(1f).testTag("btn_open_screen"),
                    ) {
                        IosGlyph(Glyph.Screen, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("实时画面", style = IosTypography.headline)
                    }
                }
            }
        }

        // ---- 驱动 -----------------------------------------------------------------------------
        // 0.31.0 起驱动只有两项（OSMesa / Adreno），下拉栏直接选；导入驱动的功能已删除。
        item { SectionLabel("驱动") }
        item {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = "系统渲染后端",
                    subtitle = gpuHint(state),
                    leading = { IosGlyph(Glyph.Cpu, size = 20.dp, tint = accents.blue) },
                    showTopSeparator = false,
                    trailing = {
                        GlasmioPopUpButton(
                            backend,
                            GpuBackend.entries,
                            { pickGpu(it) },
                            { it.label },
                            Modifier.testTag("popup_gpu"),
                            !hardwareLocked,
                            LocalGlassBackdrop.current,
                        )
                    },
                )
                GlasmioListRow(
                    title = "虚拟网络",
                    subtitle = when {
                        state.settings.experimentalGpu -> "slirp NAT；Adreno 固定开启"
                        else -> "slirp NAT，无需 root"
                    },
                    leading = { IosGlyph(Glyph.Network, size = 20.dp, tint = accents.green) },
                    trailing = {
                        GlasmioSwitch(
                            checked = state.settings.netEnabled,
                            onCheckedChange = actions.setNet,
                            // Was `!running`: during 启动中 the switch still looked tappable even
                            // though setNetEnabled would ignore it.
                            enabled = !hardwareLocked && !state.settings.experimentalGpu,
                            backdrop = LocalGlassBackdrop.current,
                            onTintColor = accents.green,
                        )
                    },
                )
            }
        }
        item {
            Text(
                "Adreno 后端需要高通 Adreno 8xx：830 已实测，其它 8xx 理论上通用但未验证。",
                style = IosTypography.caption2,
                color = LocalContentColor.current.copy(alpha = 0.45f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }

        // ---- 存储 -----------------------------------------------------------------------------
        item { SectionLabel("存储") }
        item {
            GlasmioGroupedList {
                val mode = state.settings.dataDiskMode
                val gib = state.settings.dataDiskGiB
                GlasmioListRow(
                    title = "实验数据盘容量",
                    detail = "$gib GiB",
                    subtitle = "1–128 GiB，每个容量独立成盘",
                    showTopSeparator = false,
                    // 修改容量只改容量，不再顺手把模式切回只读私有盘（旧版这里写死了
                    // PRIVATE_QCOW2，导致可写盘用户一改容量就静默变回只读）。
                    onClick = {
                        editor = Editor(
                            "实验数据盘容量",
                            "输入 1–128 GiB 的整数。修改只影响下次启动使用的盘文件。",
                            gib.toString(),
                            false,
                            "8",
                        ) { text ->
                            val value = text.trim().toIntOrNull() ?: 0
                            actions.setDataDisk(mode, value)
                        }
                    },
                    enabled = !hardwareLocked,
                )
                // 模式行的说明统一放到下方脚注：四行各带一条 subtitle 会把每行撑到 68dp、
                // 还会在窄屏被截断，整组高度随所选模式跳动。
                for ((id, label) in listOf(
                    "PRIVATE_RW" to "宿主可写实验盘（guest 挂载待验证）",
                    "PRIVATE_QCOW2" to "只读私有盘",
                    "LEGACY_PUBLIC_READ_ONLY" to "原有公共数据盘（只读）",
                    "DISABLED" to "不挂载数据盘",
                )) {
                    if (mode == id) {
                        // Selected rows show a checkmark instead of the old 已选择/选择 pair, and
                        // are not clickable — tapping the current value was a no-op.
                        GlasmioListRow(
                            title = label,
                            trailing = { IosGlyph(Glyph.Check, size = 18.dp, tint = accents.blue) },
                            showDisclosure = false,
                        )
                    } else {
                        GlasmioListRow(
                            title = label,
                            showDisclosure = false,
                            onClick = { actions.setDataDisk(id, gib) },
                            // Dims the row (GlasmioListRow alpha 0.4) instead of removing the
                            // click handler silently, so "locked" is visible, not just inert.
                            enabled = !hardwareLocked,
                        )
                    }
                }
                GlasmioListRow(
                    title = "固件与镜像位置",
                    subtitle = state.artifacts.storagePath,
                    leading = { IosGlyph(Glyph.Folder, size = 20.dp, tint = accents.orange) },
                    onClick = actions.copyStoragePath,
                )
            }
        }
        if (hardwareLocked) {
            item {
                Text(
                    "启动中或运行中，驱动与存储不可修改；停止后修改会在下次启动生效。",
                    style = IosTypography.caption2,
                    color = accents.orange.copy(alpha = 0.75f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
        item {
            Text(
                dataDiskNote(state),
                style = IosTypography.caption2,
                color = LocalContentColor.current.copy(alpha = 0.45f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }

        if (running) {
            item { SectionLabel("引导阶段") }
            item {
                GlassPanel {
                    phase?.notes?.takeIf { it.isNotEmpty() }?.forEachIndexed { i, note ->
                        if (i > 0) Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Dot(if (phase.stuck) accents.red else accents.green, 5.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                note,
                                style = IosTypography.caption1,
                                color = LocalContentColor.current.copy(alpha = 0.7f),
                            )
                        }
                    }
                    if (phase == null || phase.notes.isEmpty()) {
                        Text(
                            "正在等待串口输出…",
                            style = IosTypography.caption1,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }

        item { SectionLabel("环境") }
        item {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = "处理器核心",
                    detail = when {
                        !state.settings.mttcg -> "${state.settings.smp} 核 · 单线程 TCG（排障基线）"
                        state.settings.smp > com.android.gudao.iphoneos.Constants.SMP_STABLE_MAX ->
                            "${state.settings.smp} 核 ⚠ 超出稳定档，会自动降核"
                        else -> "${state.settings.smp} 核 · MTTCG 并行"
                    },
                    systemName = null,
                    leading = { IosGlyph(Glyph.Cpu, size = 20.dp, tint = accents.blue) },
                    showTopSeparator = false,
                    // The value is read-only here; tapping goes to the sliders that own it.
                    onClick = actions.openSettings,
                )
                GlasmioListRow(
                    title = "镜像版本",
                    detail = if (state.artifacts.imageRevMatches) "第 ${state.artifacts.imageRev} 版" else "需重编",
                    leading = { IosGlyph(Glyph.Doc, size = 20.dp, tint = accents.purple) },
                )
                GlasmioListRow(
                    title = "固件",
                    detail = if (state.artifacts.firmwarePresent) Fmt.bytes(state.artifacts.firmwareBytes) else "未下载",
                    leading = { IosGlyph(Glyph.Folder, size = 20.dp, tint = accents.orange) },
                )
                GlasmioListRow(
                    title = "运行日志",
                    detail = "${state.logCount} 行",
                    leading = { IosGlyph(Glyph.Terminal, size = 20.dp, tint = accents.teal) },
                    onClick = actions.openLogs,
                )
                GlasmioListRow(
                    title = "资源下载",
                    detail = downloadSubtitle(state.download),
                    leading = { IosGlyph(Glyph.Download, size = 20.dp, tint = accents.blue) },
                    onClick = actions.openLibrary,
                )
            }
        }

        item {
            Text(
                "HackiOS 在 Android 上以 TCG 软件模拟运行 iOS 16.5。首次进入桌面可能需要 10–15 分钟，" +
                    "处理器较弱或发热时可能半小时以上；这不是倒计时，期间可随时查看实时画面和日志。",
                style = IosTypography.caption1,
                color = LocalContentColor.current.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp),
            )
        }
    }

    EditorSheet(editor = editor, onDismiss = { editor = null }, accent = accents.blue)

    when (val dialog = gpuDialog) {
        null -> Unit

        GpuDialog.NoRuntime -> GlasmioAlertDialog(
            visible = true,
            onDismissRequest = { gpuDialog = null },
            title = "当前安装包不含 Adreno",
            message = "Adreno 后端需要 GPU 测试包里的 Turnip/Zink 运行时，这个安装包没有打包进去。" +
                "现在只能用 OSMesa（CPU 软件光栅化）。",
            buttons = listOf(
                GlasmioAlertButtonSpec("知道了", { gpuDialog = null }, GlasmioAlertButtonRole.Cancel),
            ),
        )

        is GpuDialog.Not8xx -> GlasmioAlertDialog(
            visible = true,
            onDismissRequest = { gpuDialog = null },
            title = "这台设备的 GPU 不支持",
            message = "识别结果：${dialog.detected}。\n\n" +
                "Adreno 后端只在高通 Adreno 8xx 上启用；继续用 OSMesa 启动即可。",
            buttons = listOf(
                GlasmioAlertButtonSpec("知道了", { gpuDialog = null }, GlasmioAlertButtonRole.Cancel),
            ),
        )

        is GpuDialog.Untested -> GlasmioAlertDialog(
            visible = true,
            onDismissRequest = { gpuDialog = null },
            title = "Adreno ${dialog.model} 未实测",
            message = "理论上 8XX 系列 GPU 通用，但仅在 830 上使用过。\n\n" +
                "在这台设备上启用 Adreno 可能启动失败或画面异常；失败不会自动回退，" +
                "可以切回 OSMesa 重试。",
            buttons = listOf(
                GlasmioAlertButtonSpec("取消", { gpuDialog = null }, GlasmioAlertButtonRole.Cancel),
                GlasmioAlertButtonSpec(
                    "仍要启用",
                    {
                        gpuDialog = null
                        actions.setGpuBackend(true)
                    },
                    GlasmioAlertButtonRole.Destructive,
                ),
            ),
        )
    }
}

/** 驱动行下面那行说明：先讲本机识别结果，再讲能不能选。 */
private fun gpuHint(state: AppUiState): String {
    val gpu = state.gpu
    return when {
        !state.experimentalGpuAvailable -> "本机 ${gpu.summary} · 当前安装包不含 Adreno 运行时"
        gpu.isAdreno8xx && gpu.isTestedModel -> "本机 ${gpu.summary} · 已实测"
        gpu.isAdreno8xx -> "本机 ${gpu.summary} · 未实测，切过去会先提示"
        else -> "本机 ${gpu.summary} · 非 8xx，只能用 OSMesa"
    }
}

private fun dataDiskNote(state: AppUiState): String = when {
    state.settings.dataDiskMode == "DISABLED" ->
        "当前未挂载数据盘；数据盘设置本身不限制图形后端，Adreno 仍受设备和运行时条件限制。"
    state.settings.dataDiskMode == "PRIVATE_RW" ->
        "已选择 ${state.settings.dataDiskGiB} GiB 宿主可写 qcow2；此数据盘模式不额外限制图形后端，" +
            "Adreno 仍受设备和运行时条件限制。不同容量使用独立文件，已有盘不会被覆盖或调整大小。" +
            "块设备可写不等于客体 APFS 已挂载到 /private/var，因此不能据此保证 iOS 用户数据持久。"
    state.settings.dataDiskMode == "LEGACY_PUBLIC_READ_ONLY" ->
        "已选择现有公共只读数据盘；此数据盘模式不额外限制图形后端，Adreno 仍受设备和运行时条件限制。" +
            "不会创建、修复、移动或覆盖原盘；iOS 是否实际挂载以 guest 日志为准。"
    state.settings.dataDiskMode == "PRIVATE_QCOW2" || state.settings.dataDiskMode == "PRIVATE_RAW" ->
        "已选择 ${state.settings.dataDiskGiB} GiB 私有只读探测盘；此数据盘模式不额外限制图形后端，" +
            "Adreno 仍受设备和运行时条件限制。不同容量使用独立文件，不覆盖已有盘；" +
            "这不是已验证的 iOS 用户数据持久化。"
    else ->
        "存储模式值无法识别，本次不会假定数据盘已挂载。"
}

/** Keep the one-second ticker local: it need not recompose the whole device page. */
@Composable
private fun SessionElapsed(qemu: QemuUi, adreno: Boolean) {
    val since = when (qemu) {
        is QemuUi.Starting -> qemu.sinceEpochMs
        is QemuUi.Running -> qemu.sinceEpochMs
        else -> 0L
    }
    var now by remember(since) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        if (since <= 0L) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Text(
        if (since > 0L) {
            val label = if (qemu is QemuUi.Starting) "准备已用" else "运行已用"
            "$label ${Fmt.duration(((now - since) / 1000).coerceAtLeast(0L))}"
        }
        else if (adreno) "TCG · Adreno" else "TCG · OSMesa",
        style = IosTypography.footnote,
        color = LocalContentColor.current.copy(alpha = 0.55f),
    )
}

@Composable
private fun StageDots(stage: Int, active: Boolean, tint: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..7) {
            val done = stage >= i && active
            Box(
                Modifier
                    .size(if (done) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(
                        if (done) tint else LocalContentColor.current.copy(alpha = if (active) 0.18f else 0.1f)
                    )
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (active) "阶段 ${stage.coerceAtLeast(1)}/7" else "未开始",
            style = IosTypography.caption2,
            color = LocalContentColor.current.copy(alpha = 0.45f),
        )
    }
}

@Composable
fun BuildSummary(build: BuildUi, modifier: Modifier = Modifier) {
    when (build) {
        is BuildUi.Running -> Column(modifier) {
            Text(build.phase, style = IosTypography.subheadline)
            Text(build.detail.ifBlank { "进行中" }, style = IosTypography.caption1)
        }

        else -> Unit
    }
}

/** One-line download state for the "资源下载" row, so the home page never grows a second card. */
private fun downloadSubtitle(d: DownloadUi): String = when (d) {
    DownloadUi.Idle -> "未开始"
    DownloadUi.Checking -> "检查中"
    is DownloadUi.Running -> "下载中 ${(d.fraction * 100).toInt()}%"
    is DownloadUi.Merging -> "合并中 ${(d.fraction * 100).toInt()}%"
    is DownloadUi.Verifying -> "校验中 ${(d.fraction * 100).toInt()}%"
    is DownloadUi.Paused -> "已暂停 · ${Fmt.bytes(d.resumeBytes)}"
    is DownloadUi.Ready -> "已就绪"
    is DownloadUi.Failed -> "下载失败"
}
