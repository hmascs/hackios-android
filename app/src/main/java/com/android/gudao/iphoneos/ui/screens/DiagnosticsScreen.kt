package com.android.gudao.iphoneos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.gudao.iphoneos.state.AppUiState
import com.android.gudao.iphoneos.ui.components.GlassPanel
import com.android.gudao.iphoneos.ui.components.Glyph
import com.android.gudao.iphoneos.ui.components.IosGlyph
import com.android.gudao.iphoneos.ui.components.SectionLabel
import com.android.gudao.iphoneos.ui.theme.LocalIosAccents
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedPage
import io.github.neoruaa.glasmio.ui.GlasmioButton

@Composable
fun DiagnosticsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalIosAccents.current
    val diag = state.diagnostics
    GlasmioPinnedPage(
        title = "工具链自检",
        subtitle = diag.summary ?: "bash / python3 / 7zz / unzbm / mkapfs",
        onNavigateBack = onBack,
        modifier = modifier.testTag("screen_diagnostics"),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            GlassPanel(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IosGlyph(Glyph.Terminal, size = 20.dp, tint = accents.blue)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("在设备上运行全套检查", style = IosTypography.subheadline, fontWeight = FontWeight.Medium)
                        Text(
                            "真实执行每个二进制，而不是只检查文件是否存在。约 1–2 分钟。",
                            style = IosTypography.caption2,
                            color = LocalContentColor.current.copy(alpha = 0.55f),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                GlasmioButton(
                    onClick = onRun,
                    modifier = Modifier.fillMaxWidth().testTag("btn_run_diagnostics"),
                    enabled = !diag.running,
                ) {
                    Text(if (diag.running) "自检中…" else "开始自检", style = IosTypography.headline)
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionLabel("结果", Modifier.padding(horizontal = 20.dp))
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(diag.lines) { line ->
                    val tint = when {
                        line.startsWith("✓") -> accents.green
                        line.startsWith("✗") -> accents.red
                        line.startsWith("正在") -> accents.orange
                        else -> LocalContentColor.current.copy(alpha = 0.7f)
                    }
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = tint,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
}
