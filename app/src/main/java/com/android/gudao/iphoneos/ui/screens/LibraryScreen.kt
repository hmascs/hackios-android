package com.android.gudao.iphoneos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.android.gudao.iphoneos.Constants
import com.android.gudao.iphoneos.state.AppUiState
import com.android.gudao.iphoneos.state.BuildUi
import com.android.gudao.iphoneos.state.DownloadUi
import com.android.gudao.iphoneos.ui.Fmt
import com.android.gudao.iphoneos.ui.components.GlassActionButton
import com.android.gudao.iphoneos.ui.components.GlassBanner
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
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSectionTitle

data class LibraryActions(
    val download: () -> Unit,
    val cancelDownload: () -> Unit,
    val verify: () -> Unit,
    val compile: () -> Unit,
    val cancelCompile: () -> Unit,
    val openDiagnostics: () -> Unit,
    val openLogs: () -> Unit,
    /** 无「所有文件访问」权限时，下载按钮变为「授予储存权限」，点它拉起系统授权页。 */
    val requestStorage: () -> Unit = {},
)

@Composable
fun LibraryScreen(
    state: AppUiState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    val accents = LocalIosAccents.current
    val d = state.download
    val b = state.build
    val art = state.artifacts

    GlasmioPinnedListPage(
        title = "资源",
        bottomPadding = 144.dp,
        subtitle = "固件与系统镜像",
        modifier = modifier.testTag("screen_library"),
    ) {
        item {
            HeroCard(Modifier.testTag("firmware_card")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IosGlyph(Glyph.Folder, size = 22.dp, tint = accents.blue)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("iOS 16.5 固件", style = IosTypography.headline)
                        Text(
                            "iPhone10,3 · 20F66 · ${Fmt.bytes(Constants.IPSW_SIZE_BYTES)}",
                            style = IosTypography.caption1,
                            color = LocalContentColor.current.copy(alpha = 0.55f),
                        )
                    }
                    StatusChip(
                        text = when (d) {
                            is DownloadUi.Ready -> "已校验"
                            is DownloadUi.Running, is DownloadUi.Merging, is DownloadUi.Verifying -> "进行中"
                            is DownloadUi.Paused -> "已暂停"
                            is DownloadUi.Failed -> "失败"
                            is DownloadUi.Checking -> "校验中"
                            DownloadUi.Idle -> if (art.firmwarePresent) "已下载" else "未下载"
                        },
                        tint = when (d) {
                            is DownloadUi.Ready -> accents.green
                            is DownloadUi.Failed -> accents.red
                            is DownloadUi.Paused -> accents.orange
                            else -> accents.blue
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))
                when (d) {
                    is DownloadUi.Running -> {
                        IosProgressBar(d.fraction)
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Text(
                                "${Fmt.percent(d.fraction)} · ${Fmt.bytes(d.downloaded)} / ${Fmt.bytes(d.total)}",
                                style = IosTypography.caption1,
                                color = LocalContentColor.current.copy(alpha = 0.6f),
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                // At 100 % the rate is 0 and the ETA is unknown; showing "计算中…"
                                // there is what looked like a hang before the merge phase existed.
                                if (d.fraction >= 0.999f) "收尾中（准备合并）"
                                else "${Fmt.speed(d.bytesPerSec)} · ${Fmt.eta(d.etaSeconds)}",
                                style = IosTypography.caption1,
                                color = LocalContentColor.current.copy(alpha = 0.6f),
                            )
                        }
                        if (d.resumedBytes > 0) {
                            Text(
                                "其中 ${Fmt.bytes(d.resumedBytes)} 来自上次续传 · 分块 ${d.chunksDone}/${d.chunksTotal}",
                                style = IosTypography.caption2,
                                color = accents.green,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }

                    is DownloadUi.Merging -> {
                        IosProgressBar(d.fraction, tint = accents.blue)
                        Text(
                            "正在合并分块为 IPSW · ${Fmt.percent(d.fraction)} · ${Fmt.bytes(d.done)} / ${Fmt.bytes(d.total)}",
                            style = IosTypography.caption1,
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    is DownloadUi.Verifying -> {
                        IosProgressBar(d.fraction, tint = accents.orange)
                        Text(
                            "正在校验 SHA-256 · ${Fmt.percent(d.fraction)}",
                            style = IosTypography.caption1,
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    is DownloadUi.Checking -> {
                        IosProgressBar(0.02f, tint = accents.orange)
                        Text(
                            "正在校验已有文件…",
                            style = IosTypography.caption1,
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    is DownloadUi.Paused -> Text(
                        "已暂停 · 已保留 ${Fmt.bytes(d.resumeBytes)}，继续时从这里开始",
                        style = IosTypography.caption1,
                        color = accents.orange,
                    )

                    is DownloadUi.Ready -> Text(
                        "SHA-256 ${d.sha256.take(16)}… · ${Fmt.bytes(d.sizeBytes)}",
                        style = IosTypography.caption1,
                        color = LocalContentColor.current.copy(alpha = 0.55f),
                    )

                    is DownloadUi.Failed -> Text(
                        buildString {
                            append(d.message)
                            d.detail?.let { append("\n").append(it) }
                        },
                        style = IosTypography.caption1,
                        color = accents.red,
                    )

                    DownloadUi.Idle -> Text(
                        if (art.firmwarePresent)
                            "已存在 ${Fmt.bytes(art.firmwareBytes)}，尚未校验"
                        else "从 Apple CDN 下载，支持断点续传与暂停",
                        style = IosTypography.caption1,
                        color = LocalContentColor.current.copy(alpha = 0.55f),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (d) {
                        is DownloadUi.Running, is DownloadUi.Merging, is DownloadUi.Verifying, is DownloadUi.Checking -> {
                            GlassActionButton("暂停", actions.cancelDownload, accents.orange, Modifier.weight(1f))
                        }

                        is DownloadUi.Paused -> {
                            GlassActionButton("继续下载", actions.download, accents.blue, Modifier.weight(1f))
                            GlassActionButton("校验", actions.verify, LocalContentColor.current.copy(alpha = 0.6f), Modifier.weight(1f))
                        }

                        is DownloadUi.Failed -> {
                            GlassActionButton(
                                if (d.resumableBytes > 0) "继续下载" else "重试",
                                actions.download,
                                accents.blue,
                                Modifier.weight(1f),
                            )
                            GlassActionButton("查看日志", actions.openLogs, LocalContentColor.current.copy(alpha = 0.6f), Modifier.weight(1f))
                        }

                        is DownloadUi.Ready -> {
                            GlassActionButton("重新校验", actions.verify, LocalContentColor.current.copy(alpha = 0.6f), Modifier.weight(1f))
                            GlassActionButton("下一步：编译", actions.compile, accents.blue, Modifier.weight(1f))
                        }

                        else -> {
                            // 没有「所有文件访问」权限时写不进 Download/hackios，先要权限而不是
                            // 让用户点了下载才发现什么都没发生。storageIsPublic 为真才显示下载。
                            if (!art.storageIsPublic && !art.firmwarePresent) {
                                GlassActionButton("授予储存权限", actions.requestStorage, accents.orange, Modifier.weight(1f))
                            } else {
                                GlassActionButton(
                                    if (art.firmwarePresent) "校验" else "下载固件",
                                    if (art.firmwarePresent) actions.verify else actions.download,
                                    accents.blue,
                                    Modifier.weight(1f),
                                )
                            }
                            if (art.firmwarePresent) {
                                GlassActionButton("重新下载", actions.download, LocalContentColor.current.copy(alpha = 0.6f), Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        item { GlasmioSectionTitle("系统镜像") }
        item {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = "system.apfs",
                    subtitle = "Apple 只读根文件系统",
                    detail = if (art.systemImagePresent) Fmt.bytes(art.systemBytes) else "缺失",
                    leading = { IosGlyph(Glyph.Doc, size = 20.dp, tint = accents.green) },
                    showTopSeparator = false,
                )
                GlasmioListRow(
                    title = "root.apfs",
                    subtitle = "redwood 用户态与启动任务",
                    detail = if (art.rootImagePresent) Fmt.bytes(art.rootBytes) else "缺失",
                    leading = { IosGlyph(Glyph.Doc, size = 20.dp, tint = accents.green) },
                )
                GlasmioListRow(
                    title = "镜像版本",
                    subtitle = "与 APK 内脚本匹配",
                    detail = if (art.imageRevMatches) "第 ${art.imageRev} 版" else "第 ${art.imageRev} 版 → 需第 ${art.expectedRev} 版",
                    leading = { IosGlyph(Glyph.Circle, size = 20.dp, tint = accents.purple) },
                )
            }
        }

        item { GlasmioSectionTitle("在设备上编译") }
        item {
            GlassPanel(Modifier.testTag("build_card")) {
                when (b) {
                    is BuildUi.Running -> {
                        // A step with no percentage output (7z listing the 4.7 GB image, mkapfs
                        // packing system.apfs) used to keep the *previous* stage's 100 % on screen
                        // for half an hour. It now shows a sweeping bar and a clock, so "slow" and
                        // "dead" no longer look the same.
                        val unknown = b.fraction < 0f
                        val total = tickerSeconds(b.startedAtMs)
                        val inPhase = tickerSeconds(b.phaseStartedAtMs)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(b.phase, style = IosTypography.headline, modifier = Modifier.weight(1f))
                            StatusChip(if (unknown) "进行中" else Fmt.percent(b.fraction), accents.blue)
                        }
                        Spacer(Modifier.height(10.dp))
                        IosProgressBar(if (unknown) 0f else b.fraction, indeterminate = unknown)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            b.detail.ifBlank {
                                if (unknown) "这一步没有百分比输出，正在工作" else "阶段：${b.stage}"
                            },
                            style = IosTypography.caption1,
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                        )
                        if (b.startedAtMs > 0L) {
                            Text(
                                "本阶段 ${Fmt.duration(inPhase)} · 已运行 ${Fmt.duration(total)}",
                                style = IosTypography.caption1,
                                color = LocalContentColor.current.copy(alpha = 0.45f),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Text(
                            "请插电并保持本页在前台：锁屏会让 CPU 降频，速度慢好几倍。",
                            style = IosTypography.caption1,
                            color = LocalContentColor.current.copy(alpha = 0.45f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        GlassActionButton("取消编译", actions.cancelCompile, accents.red, Modifier.fillMaxWidth())
                    }

                    is BuildUi.Done -> {
                        Text("编译完成", style = IosTypography.headline, color = accents.green)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "system ${b.systemMb} MB · root ${b.rootMb} MB\n已发布到 ${art.storagePath}",
                            style = IosTypography.caption1,
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassActionButton("重新编译", actions.compile, LocalContentColor.current.copy(alpha = 0.6f), Modifier.weight(1f))
                            GlassActionButton("查看日志", actions.openLogs, accents.blue, Modifier.weight(1f))
                        }
                    }

                    is BuildUi.Cancelled -> {
                        Text("已取消", style = IosTypography.headline, color = accents.orange)
                        Text(
                            "已完成阶段：${b.stage}。再次编译会从这里继续，不会重做已完成的步骤。",
                            style = IosTypography.caption1,
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        GlassActionButton("继续编译", actions.compile, accents.blue, Modifier.fillMaxWidth())
                    }

                    is BuildUi.Failed -> {
                        Text(b.message, style = IosTypography.headline, color = accents.red)
                        b.detail?.let {
                            // A failure carries up to forty lines of a step's output, and the
                            // reason is in the LAST of them. Printed as one paragraph it pushed
                            // the buttons off the screen and grew the card by a screenful, so
                            // the one line worth reading sat below the fold. Bounded height,
                            // scrolled from the bottom: the traceback is what you see first.
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState())
                                    .testTag("build_failure_detail"),
                            ) {
                                Text(
                                    it,
                                    style = IosTypography.caption2,
                                    fontFamily = FontFamily.Monospace,
                                    color = LocalContentColor.current.copy(alpha = 0.62f),
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassActionButton("重试", actions.compile, accents.blue, Modifier.weight(1f))
                            GlassActionButton("日志", actions.openLogs, LocalContentColor.current.copy(alpha = 0.6f), Modifier.weight(1f))
                        }
                    }

                    BuildUi.Idle -> {
                        Text("生成 system.apfs 与 root.apfs", style = IosTypography.headline)
                        Text(
                            "使用 APK 内置的脚本与 Termux 工具链，在手机上解包固件并打包 APFS 镜像。" +
                                "首次要解包 7.4 GB、打包 2.5 GB，手机上通常 1–3 小时（列表与打包阶段没有百分比，属正常）。" +
                                "已完成的阶段会记住，取消后不会重做。",
                            style = IosTypography.caption1,
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        GlassActionButton(
                            if (art.imagesReady) "重新编译镜像" else "编译镜像",
                            actions.compile,
                            accents.blue,
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        item {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = "工具链自检",
                    subtitle = "bash / python3 / 7zz / unzbm / mkapfs",
                    leading = { IosGlyph(Glyph.Terminal, size = 20.dp, tint = accents.teal) },
                    onClick = actions.openDiagnostics,
                    showTopSeparator = false,
                )
            }
        }

        item {
            Column(Modifier.padding(top = 8.dp, bottom = 24.dp)) {
                Text(
                    "存储位置：${art.storagePath}",
                    style = IosTypography.caption1,
                    color = LocalContentColor.current.copy(alpha = 0.45f),
                )
                Text(
                    if (art.storageIsPublic) "公共目录 · 应用卸载后仍保留" else "应用私有目录 · 未授予存储权限",
                    style = IosTypography.caption2,
                    color = LocalContentColor.current.copy(alpha = 0.35f),
                )
            }
        }
    }
}

/** Seconds elapsed since [sinceMs], re-read once a second while the card is on screen. */
@Composable
private fun tickerSeconds(sinceMs: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sinceMs) {
        if (sinceMs <= 0L) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    if (sinceMs <= 0L) return 0L
    return ((now - sinceMs) / 1000).coerceAtLeast(0L)
}
