package com.android.gudao.iphoneos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.gudao.iphoneos.state.AppUiState
import com.android.gudao.iphoneos.state.LogSource
import com.android.gudao.iphoneos.state.QemuUi
import kotlinx.coroutines.flow.StateFlow
import com.android.gudao.iphoneos.ui.components.GlassActionButton
import com.android.gudao.iphoneos.ui.components.Glyph
import com.android.gudao.iphoneos.ui.components.IosGlyph
import com.android.gudao.iphoneos.ui.theme.LocalIosAccents
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedPage
import io.github.neoruaa.glasmio.ui.GlasmioActionSheetContent
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow

/**
 * 折叠连续重复行：guest 里的 lsd 等守护进程忙等时会把同一行刷成几十上百行，
 * 这里把连续相同的行合并成 `原始行  (×N)`，消除视觉风暴。
 * 只影响显示用的列表；复制/导出走磁盘原始 serial.log，不受影响。
 */
private fun collapseRepeats(lines: List<String>): List<String> {
    if (lines.size < 2) return lines
    val out = ArrayList<String>(lines.size)
    var prev = lines[0]
    var run = 1
    for (i in 1 until lines.size) {
        val cur = lines[i]
        if (cur == prev) {
            run++
        } else {
            out.add(if (run > 1) "$prev  (×$run)" else prev)
            prev = cur
            run = 1
        }
    }
    out.add(if (run > 1) "$prev  (×$run)" else prev)
    return out
}

/**
 * Logs page.
 *
 * The list is a `LazyColumn` over an immutable snapshot, so 3000 lines cost nothing to scroll; the
 * previous implementation re-set an 8000-character string on a TextView every two seconds, which
 * both reset the scroll position and re-laid-out the whole text on the UI thread.
 */
@Composable
fun LogsScreen(
    state: AppUiState,
    logLines: StateFlow<Map<LogSource, List<String>>>,
    onBack: () -> Unit,
    onCopy: (LogSource) -> Unit,
    onFollowChange: (Boolean) -> Unit,
    onExport: (Boolean) -> Unit,
    onShareLast: () -> Unit,
    onClear: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val accents = LocalIosAccents.current
    var source by remember { mutableStateOf(LogSource.Serial) }
    var sheet by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // Subscribed here, not in the app shell: one tail flush per 400 ms only recomposes this page,
    // even when the shell has no interest in the new line at all.
    val all by logLines.collectAsStateWithLifecycle()
    val lines = all[source].orEmpty()
    val displayLines = remember(lines) { collapseRepeats(lines) }
    val listState = rememberLazyListState()

    LaunchedEffect(displayLines.size, state.followLog, source) {
        if (state.followLog && displayLines.isNotEmpty()) {
            listState.scrollToItem(displayLines.lastIndex)
        }
    }

    GlasmioPinnedPage(
        title = "日志",
        subtitle = "${displayLines.size} 行 · ${source.label}" +
            if (state.export.busy) " · 导出中…" else "",
        onNavigateBack = onBack,
        modifier = modifier.testTag("screen_logs"),
        actions = {
            // 复制只带走手上这几千行；磁盘上的日志多一个数量级，所以导出是单独的入口。
            GlasmioIconButton(onClick = { sheet = true }, size = 34.dp) {
                IosGlyph(Glyph.Download, size = 17.dp)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 0.29.6：QEMU 这一段永远是空的（它的 stdout 被 -serial 抢走，qemu.log 也不该
                // 给用户看），所以不再出现在分段里；文件仍保留供导出排障。
                val tabs = LogSource.entries.filter { it != LogSource.Qemu }
                tabs.forEach { s ->
                    val selected = s == source
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(
                                if (selected) accents.blue.copy(alpha = 0.18f)
                                else LocalContentColor.current.copy(alpha = 0.07f)
                            )
                            .clickable { source = s }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("log_tab_${s.name}")
                    ) {
                        Text(
                            s.label,
                            style = IosTypography.caption1,
                            color = if (selected) accents.blue else LocalContentColor.current.copy(alpha = 0.7f),
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                GlassActionButton(
                    if (state.followLog) "跟随中" else "已暂停",
                    { onFollowChange(!state.followLog) },
                    if (state.followLog) accents.green else LocalContentColor.current.copy(alpha = 0.55f),
                )
            }

            Box(Modifier.fillMaxSize()) {
                if (lines.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        IosGlyph(Glyph.Terminal, size = 28.dp, tint = LocalContentColor.current.copy(alpha = 0.35f))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "还没有${source.label}日志",
                            style = IosTypography.footnote,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("log_list"),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        items(displayLines) { line ->
                            Text(
                                line,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                lineHeight = 15.sp,
                                color = when {
                                    line.startsWith("✗") || line.contains("error=13") -> accents.red
                                    line.startsWith("✓") -> accents.green
                                    line.startsWith("stage:") -> accents.blue
                                    else -> LocalContentColor.current.copy(alpha = 0.78f)
                                },
                            )
                        }
                    }
                }

                // 0.29.6：清除日志入口挪到右下角。QEMU 运行时置灰不可点——这一轮还在写，
                // 清了就看不到当次启动过程；停掉以后再点才问「是否删除」。
                val running = state.qemu is QemuUi.Running
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp)
                        .clip(CircleShape)
                        .background(
                            if (running) LocalContentColor.current.copy(alpha = 0.06f)
                            else accents.red.copy(alpha = 0.16f)
                        )
                        .then(if (running) Modifier else Modifier.clickable { confirmClear = true })
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("btn_clear_logs")
                ) {
                    Text(
                        "清除日志",
                        style = IosTypography.caption1,
                        color = if (running) LocalContentColor.current.copy(alpha = 0.3f) else accents.red,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (!state.followLog) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 20.dp)
                            .clip(CircleShape)
                            .background(accents.blue.copy(alpha = 0.9f))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("btn_jump_latest")
                            .clickable { onFollowChange(true) }
                    ) {
                        Text(
                            "跳到最新",
                            style = IosTypography.caption1,
                            color = androidx.compose.ui.graphics.Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }

    GlasmioBottomSheet(
        visible = sheet,
        fitContent = true,
        onDismissRequest = { sheet = false },
    ) {
        GlasmioActionSheetContent(
            "完整日志",
            message = "这个列表只保留最近一段（滚动时被淘汰的行不会回来）。" +
                "导出读的是磁盘上的原文件：完整串口、QEMU 自己的输出，以及最近 5 次构建日志。",
        ) {
            GlasmioListRow(
                if (state.export.busy) "正在导出…" else "分享完整日志",
                showTopSeparator = false,
                onClick = {
                    if (!state.export.busy) {
                        sheet = false
                        onExport(true)
                    }
                },
            )
            GlasmioListRow(
                "保存到 Download/hackios/logs",
                onClick = {
                    if (!state.export.busy) {
                        sheet = false
                        onExport(false)
                    }
                },
            )
            GlasmioListRow(
                "复制当前列表（${lines.size} 行）",
                onClick = {
                    sheet = false
                    onCopy(source)
                },
            )
            state.export.lastPath?.let { path ->
                GlasmioListRow(
                    "再次分享上次导出 · ${state.export.summary.orEmpty()}",
                    onClick = {
                        sheet = false
                        onShareLast()
                    },
                )
            }
        }
    }

    // 清空日志确认：QEMU 正在运行时必须问一句——新一轮报错正在写入，清了就看不到了。
    if (confirmClear) {
        GlasmioAlertDialog(
            visible = true,
            onDismissRequest = { confirmClear = false },
            title = "清空日志？",
            message = if (state.qemu is QemuUi.Running) {
                "QEMU 正在运行，guest 的输出仍在写入——清空后这一轮启动的过程日志就看不到了。" +
                    "若正在排障，建议先导出完整日志。确定要清空吗？"
            } else {
                "内存里的日志列表与磁盘上的 serial.log / 构建日志都会清空。"
            },
            buttons = listOf(
                GlasmioAlertButtonSpec("取消", { confirmClear = false }, GlasmioAlertButtonRole.Cancel),
                GlasmioAlertButtonSpec(
                    "清空",
                    {
                        confirmClear = false
                        onClear()
                    },
                    GlasmioAlertButtonRole.Destructive,
                ),
            ),
        )
    }
}
