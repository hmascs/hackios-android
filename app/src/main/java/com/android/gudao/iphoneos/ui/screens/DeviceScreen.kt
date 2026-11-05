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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.gudao.iphoneos.engine.QemuFailureKind
import com.android.gudao.iphoneos.state.AppUiState
import com.android.gudao.iphoneos.state.BuildUi
import com.android.gudao.iphoneos.state.DownloadUi
import com.android.gudao.iphoneos.state.QemuUi
import com.android.gudao.iphoneos.ui.Fmt
import com.android.gudao.iphoneos.ui.components.Dot
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
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSectionTitle

data class DeviceActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val retry: () -> Unit,
    val compile: () -> Unit,
    val openLogs: () -> Unit,
    val openLibrary: () -> Unit,
    val openScreen: () -> Unit,
    val ignoreStale: () -> Unit,
    val forceCleanup: () -> Unit,
    /** 顶栏的端口按钮：跳到设置页的 MCP 服务区块看地址、令牌与状态。 */
    val openMcpSettings: () -> Unit,
)

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

    GlasmioPinnedListPage(
        title = "设备",
        bottomPadding = 144.dp,
        subtitle = "iPhone X · iOS 16.5 (20F66)",
        modifier = modifier.testTag("screen_device"),
        actions = {
            GlasmioButton(
                onClick = actions.openMcpSettings,
                size = null,
                emphasis = GlassEmphasis.Regular,
            ) {
                IosGlyph(Glyph.Port, size = 16.dp)
            }
            Spacer(Modifier.width(8.dp))
            GlasmioButton(onClick = actions.openLogs, size = null, emphasis = GlassEmphasis.Regular) {
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
                    icon = Glyph.Power,
                    // A missing image is not a retry problem: the next useful action is on the
                    // 资源 page, so the banner sends the user there instead of failing again.
                    primaryLabel = when {
                        qemu.failure.kind == QemuFailureKind.MissingPayload -> "去资源页"
                        qemu.failure.kind == QemuFailureKind.AssetsMissing -> "重新部署"
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
                        "直接启动会跑旧镜像：iOS 照样开机，但看不到这次的改动。",
                    tint = accents.orange,
                    icon = Glyph.Circle,
                    primaryLabel = "编译镜像",
                    onPrimary = actions.compile,
                    secondaryLabel = "仍然启动",
                    onSecondary = actions.ignoreStale,
                )
            }

            else -> Unit
        }

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
                    Text(
                        if (running) Fmt.elapsed(qemu.sinceEpochMs) else "TCG · 无 GPU",
                        style = IosTypography.footnote,
                        color = LocalContentColor.current.copy(alpha = 0.55f),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    when {
                        running -> phase?.label ?: "正在引导 iOS"
                        qemu is QemuUi.Starting -> qemu.step
                        else -> "在这个设备上启动 iOS"
                    },
                    style = IosTypography.title2,
                    color = LocalContentColor.current,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        running -> "串口日志驱动的阶段判定，下面会随启动推进"
                        qemu is QemuUi.Starting -> "以下步骤都在后台进行，界面保持可操作"
                        else -> "下载固件 → 编译镜像 → 启动 QEMU。全部在本机完成。"
                    },
                    style = IosTypography.footnote,
                    color = LocalContentColor.current.copy(alpha = 0.6f),
                )

                Spacer(Modifier.height(16.dp))
                IosProgressBar(
                    fraction = phase?.fraction ?: if (qemu is QemuUi.Starting) 0.06f else 0f,
                    tint = if (running) accents.blue else LocalContentColor.current.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(10.dp))
                StageDots(stage = phase?.stage ?: 0, active = running, tint = accents.blue)

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlasmioButton(
                        onClick = {
                            if (running) actions.stop() else actions.start()
                        },
                        modifier = Modifier.weight(1f).testTag("btn_power"),
                        emphasis = GlassEmphasis.Prominent,
                    ) {
                        IosGlyph(Glyph.Power, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (running) "停止" else "启动 iOS",
                            style = IosTypography.headline,
                            color = if (running) accents.red else LocalContentColor.current,
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
                )
                GlasmioListRow(
                    title = "虚拟网络",
                    detail = if (state.settings.netEnabled) "已开启" else "关闭",
                    leading = { IosGlyph(Glyph.Network, size = 20.dp, tint = accents.green) },
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
                "HackiOS 在 Android 上以 TCG 软件模拟运行 iOS 16.5。首次启动约 2–3 分钟出现锁屏，期间可随时查看实时画面。",
                style = IosTypography.caption1,
                color = LocalContentColor.current.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp),
            )
        }
    }
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
