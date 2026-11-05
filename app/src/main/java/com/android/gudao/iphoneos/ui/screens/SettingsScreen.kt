package com.android.gudao.iphoneos.ui.screens

import com.android.gudao.iphoneos.engine.BootTimeline
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.android.gudao.iphoneos.mcp.McpConfig
import com.android.gudao.iphoneos.state.AppUiState
import com.android.gudao.iphoneos.state.GlassStrength
import com.android.gudao.iphoneos.state.QemuUi
import com.android.gudao.iphoneos.state.ThemeMode
import com.android.gudao.iphoneos.ui.components.GlassPanel
import com.android.gudao.iphoneos.ui.components.Glyph
import com.android.gudao.iphoneos.ui.components.IosGlyph
import com.android.gudao.iphoneos.ui.components.SectionLabel
import com.android.gudao.iphoneos.ui.theme.LocalIosAccents
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioActionSheetContent
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import io.github.neoruaa.glasmio.ui.GlasmioSlider
import io.github.neoruaa.glasmio.ui.GlasmioSwitch

data class SettingsActions(
    val setTheme: (ThemeMode) -> Unit,
    val setGlass: (GlassStrength) -> Unit,
    val setSmp: (Int) -> Unit,
    val setHostCores: (Int) -> Unit,
    val setMttcg: (Boolean) -> Unit,
    val setAutoFallback: (Boolean) -> Unit,
    val setNet: (Boolean) -> Unit,
    val setFollow: (Boolean) -> Unit,
    val forceCleanup: () -> Unit,
    val openDiagnostics: () -> Unit,
    val copyPath: () -> Unit,
    /** 复制一段文本到剪贴板，第二个参数是提示里显示的标签。 */
    val copyText: (String, String) -> Unit,
    val setMcpEnabled: (Boolean) -> Unit,
    val setMcpPort: (Int) -> Unit,
    val setMcpBindLan: (Boolean) -> Unit,
    val setMcpAllowWrite: (Boolean) -> Unit,
    val setMcpFullAccess: (Boolean) -> Unit,
    val regenerateToken: () -> String,
    val restartMcp: () -> Unit,
    /** 返回 false 表示校验没过（错误提示由 ViewModel 负责），编辑框不关闭。 */
    val applyQemuFlags: (String) -> Boolean,
    val applyBootArgs: (String) -> Boolean,
)

/** 正在编辑的文本项。放在一个 data class 里，避免为每个字段各写一个 bool。 */
private data class Editor(
    val title: String,
    val hint: String,
    val initial: String,
    val secret: Boolean,
    val placeholder: String,
    val onConfirm: (String) -> Boolean,
)

@Composable
fun SettingsScreen(
    state: AppUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val accents = LocalIosAccents.current
    val context = LocalContext.current
    val version = rememberVersion(context)
    val running = state.qemu is QemuUi.Running
    val mcp = state.settings.mcp
    val mcpState = state.mcp

    var editor by remember { mutableStateOf<Editor?>(null) }
    var confirmWrite by remember { mutableStateOf(false) }
    var confirmFull by remember { mutableStateOf(false) }
    var confirmToken by remember { mutableStateOf(false) }

    GlasmioPinnedListPage(
        title = "设置",
        bottomPadding = 144.dp,
        subtitle = "性能、外观与存储",
        modifier = modifier.testTag("screen_settings"),
    ) {
        item { SectionLabel("性能 · Guest 与宿主解耦") }
        item {
            GlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IosGlyph(Glyph.Cpu, size = 20.dp, tint = accents.blue)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Guest vCPU（SMP 拓扑）", style = IosTypography.subheadline, fontWeight = FontWeight.Medium)
                        Text(
                            if (running) "运行中不可修改"
                            else "XNU 看到的处理器数，决定 SMP bringup 稳定性。默认 2 核最稳；调高会加重 IPI / 锁竞争",
                            style = IosTypography.caption2,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                    Text(
                        "${state.settings.smp} vCPU",
                        style = IosTypography.subheadline,
                        color = accents.blue,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                GlasmioSlider(
                    value = state.settings.smp.toFloat(),
                    onValueChange = { actions.setSmp(it.toInt().coerceIn(1, 8)) },
                    modifier = Modifier.fillMaxWidth().testTag("slider_smp"),
                    valueRange = 1f..8f,
                    enabled = !running,
                )
            }
        }
        item {
            GlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IosGlyph(Glyph.Cpu, size = 20.dp, tint = accents.green)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("宿主算力池（Host compute pool）", style = IosTypography.subheadline, fontWeight = FontWeight.Medium)
                        Text(
                            if (running) "运行中不可修改"
                            else "允许 QEMU 进程族调度到的物理核数 + TCG 翻译缓存预算。默认 8 核；不增加 vCPU，也不重建镜像",
                            style = IosTypography.caption2,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                    Text(
                        "${state.settings.hostCores} 核",
                        style = IosTypography.subheadline,
                        color = accents.green,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                GlasmioSlider(
                    value = state.settings.hostCores.toFloat(),
                    onValueChange = { actions.setHostCores(it.toInt().coerceIn(2, 8)) },
                    modifier = Modifier.fillMaxWidth().testTag("slider_host_cores"),
                    valueRange = 2f..8f,
                    enabled = !running,
                )
                Text(
                    "当前档：TB 缓存按 ${state.settings.hostCores} 核规格分配。" +
                        "启动时先读系统放行的核（Cpus_allowed_list）并优先选大核钉核，例如 4-7，" +
                        "而不再是写死的 0-${state.settings.hostCores - 1}——小核编号通常在前，钉错会比不钉更慢。" +
                        "系统本就放行全部核、或 setaffinity 被 cpuset 拒绝时不钉核，TB 缓存仍全额生效（详见日志与 perf_stats）。",
                    style = IosTypography.caption2,
                    color = LocalContentColor.current.copy(alpha = 0.5f),
                )
            }
        }
        item {
            GlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("多线程 TCG", style = IosTypography.subheadline, fontWeight = FontWeight.Medium)
                        Text(
                            "thread=multi：每个 Guest vCPU 一条宿主翻译线程。关闭回到单线程基线排障",
                            style = IosTypography.caption2,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                    GlasmioSwitch(
                        checked = state.settings.mttcg,
                        onCheckedChange = { if (!running) actions.setMttcg(it) },
                        enabled = !running,
                        backdrop = LocalGlassBackdrop.current,
                        onTintColor = accents.blue,
                    )
                }
            }
        }
        item {
            GlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("卡住自动降核", style = IosTypography.subheadline, fontWeight = FontWeight.Medium)
                        Text(
                            "画面 ${com.android.gudao.iphoneos.Constants.STALL_FALLBACK_MS / 1000} 秒无变化且未进桌面时，" +
                                "把 Guest vCPU 砍半重启一次（8→4→2 到底即停）。默认 2 核已是下限；" +
                                "软件渲染下启动可能十几分钟，别把它当卡死",
                            style = IosTypography.caption2,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                    GlasmioSwitch(
                        checked = state.settings.autoFallback,
                        onCheckedChange = { actions.setAutoFallback(it) },
                        backdrop = LocalGlassBackdrop.current,
                        onTintColor = accents.blue,
                    )
                }
            }
        }

        // ---- 启动分段计时（0.24.0）----------------------------------------------------------------
        if (state.bootTimeline.active) {
            item { SectionLabel("启动计时 · 预算 Logo 90s / 桌面 180s") }
            item {
                GlassPanel {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IosGlyph(Glyph.Cpu, size = 18.dp, tint = accents.blue)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${state.bootTimeline.smp} 核 · " +
                                    (if (state.bootTimeline.mttcg) "MTTCG 并行" else "单线程 TCG"),
                                style = IosTypography.subheadline,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "亮 Logo：" + when (val ms = state.bootTimeline.logoMs) {
                                null -> "未到达（预算 ${BootTimeline.LOGO_BUDGET_MS / 1000}s）"
                                else -> "${ms / 1000}s " +
                                    if (state.bootTimeline.logoVerdict == true) "✓ 达标" else "✗ 超预算"
                            },
                            style = IosTypography.caption1,
                            color = LocalContentColor.current.copy(alpha = 0.8f),
                        )
                        Text(
                            "进桌面：" + when (val ms = state.bootTimeline.desktopMs) {
                                null -> "未到达（预算 180s）"
                                else -> "${ms / 1000}s " +
                                    if (state.bootTimeline.desktopVerdict == true) "✓ 达标" else "✗ 超预算"
                            },
                            style = IosTypography.caption1,
                            color = LocalContentColor.current.copy(alpha = 0.8f),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "逐段耗时见导出的日志或 MCP 的 boot_timeline 工具。云端无法测真机速度，这里以真机实测为准。",
                            style = IosTypography.caption2,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }

        item { SectionLabel("连接") }
        item {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = "虚拟网络",
                    subtitle = "slirp NAT，无需 root",
                    leading = { IosGlyph(Glyph.Network, size = 20.dp, tint = accents.green) },
                    showTopSeparator = false,
                    trailing = {
                        GlasmioSwitch(
                            checked = state.settings.netEnabled,
                            onCheckedChange = actions.setNet,
                            enabled = !running,
                            backdrop = LocalGlassBackdrop.current,
                            onTintColor = accents.green,
                        )
                    },
                )
            }
        }

        // ---- MCP 服务 ---------------------------------------------------------------------------
        item { SectionLabel("MCP 服务") }
        item {
            GlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IosGlyph(Glyph.Port, size = 20.dp, tint = accents.blue)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (mcpState.running) "端口已监听 · ${mcpState.toolCount} 个工具" else "端口未监听",
                            style = IosTypography.subheadline,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (mcpState.running) {
                                "${mcpState.host}:${mcpState.port} · ${mcpState.requests} 次请求"
                            } else {
                                mcpState.message ?: "打开下方开关即可启动"
                            },
                            style = IosTypography.caption2,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                    com.android.gudao.iphoneos.ui.components.GlassActionButton(
                        "重启",
                        actions.restartMcp,
                        accents.blue,
                    )
                }
                if (mcpState.lastCall != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "最近调用：${mcpState.lastCall}",
                        style = IosTypography.caption2,
                        color = LocalContentColor.current.copy(alpha = 0.6f),
                    )
                }
                if (mcpState.lastError != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "错误：${mcpState.lastError}",
                        style = IosTypography.caption2,
                        color = accents.red,
                    )
                }
            }
        }
        item {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = "服务开关",
                    subtitle = if (mcp.enabled) "MCP 客户端可连接并管理整条链路" else "关闭后不监听任何端口",
                    leading = { IosGlyph(Glyph.Power, size = 20.dp, tint = accents.green) },
                    showTopSeparator = false,
                    trailing = {
                        GlasmioSwitch(
                            checked = mcp.enabled,
                            onCheckedChange = actions.setMcpEnabled,
                            backdrop = LocalGlassBackdrop.current,
                            onTintColor = accents.green,
                        )
                    },
                )
                GlasmioListRow(
                    title = "连接地址",
                    subtitle = "点按复制；在 MCP 客户端里填这个 URL",
                    detail = mcp.url,
                    onClick = { actions.copyText(mcp.url, "连接地址") },
                )
                mcpState.lanUrl?.let { lan ->
                    GlasmioListRow(
                        title = "局域网地址",
                        subtitle = "同一网络的客户端用这个地址，并带 Bearer token",
                        detail = lan,
                        onClick = { actions.copyText(lan, "局域网地址") },
                    )
                }
                GlasmioListRow(
                    title = "adb forward",
                    subtitle = "手机连电脑后在电脑上执行，然后连 127.0.0.1",
                    detail = mcp.adbCommand,
                    onClick = { actions.copyText(mcp.adbCommand, "adb 命令") },
                )
                GlasmioListRow(
                    title = "端口",
                    subtitle = "改完自动重启监听，需同步更新 adb forward",
                    detail = "${mcp.port}",
                    onClick = {
                        editor = Editor(
                            title = "MCP 端口",
                            hint = "范围 ${McpConfig.MIN_PORT}–${McpConfig.MAX_PORT}，默认 ${McpConfig.DEFAULT_PORT}。改端口后请重新执行 adb forward。",
                            initial = mcp.port.toString(),
                            secret = false,
                            placeholder = "${McpConfig.DEFAULT_PORT}",
                            onConfirm = {
                                val p = it.toIntOrNull()
                                if (p == null || p !in McpConfig.MIN_PORT..McpConfig.MAX_PORT) {
                                    false
                                } else {
                                    actions.setMcpPort(p)
                                    true
                                }
                            },
                        )
                    },
                )
                GlasmioListRow(
                    title = "局域网访问",
                    subtitle = if (mcp.bindLan) {
                        "绑定 0.0.0.0：所有连接必须带 Bearer token"
                    } else {
                        "仅 127.0.0.1：通过 adb forward 连接"
                    },
                    trailing = {
                        GlasmioSwitch(
                            checked = mcp.bindLan,
                            onCheckedChange = actions.setMcpBindLan,
                            backdrop = LocalGlassBackdrop.current,
                            onTintColor = accents.orange,
                        )
                    },
                )
                GlasmioListRow(
                    title = "访问令牌",
                    subtitle = "点按复制完整令牌；局域网连接必须带它",
                    detail = if (mcp.token.length > 10) {
                        mcp.token.take(6) + "…" + mcp.token.takeLast(4)
                    } else {
                        mcp.token.ifBlank { "未生成" }
                    },
                    onClick = { actions.copyText(mcp.token, "令牌") },
                )
                GlasmioListRow(
                    title = "重新生成令牌",
                    subtitle = "旧令牌立即失效，客户端需要更新 Authorization 头",
                    onClick = { confirmToken = true },
                )
            }
        }

        item { SectionLabel("MCP 权限") }
        item {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = "允许修改与执行",
                    subtitle = if (mcp.allowWrite) {
                        "可启停 QEMU、改参数与配置、编译镜像、下载固件、写文件"
                    } else {
                        "只读：客户端只能读日志、状态、配置与截帧"
                    },
                    leading = { IosGlyph(Glyph.Terminal, size = 20.dp, tint = accents.orange) },
                    showTopSeparator = false,
                    trailing = {
                        GlasmioSwitch(
                            checked = mcp.allowWrite,
                            onCheckedChange = { on ->
                                if (on) confirmWrite = true else actions.setMcpAllowWrite(false)
                            },
                            backdrop = LocalGlassBackdrop.current,
                            onTintColor = accents.orange,
                        )
                    },
                )
                if (mcp.allowWrite) {
                    GlasmioListRow(
                        title = "完全访问（进阶）",
                        subtitle = if (mcp.fullAccess) {
                            "已放开：整台 Android 的路径/命令 + delete_chain 删链"
                        } else {
                            "路径与命令不再限制在运行目录内，并开放删除整条链路"
                        },
                        showTopSeparator = true,
                        trailing = {
                            GlasmioSwitch(
                                checked = mcp.fullAccess,
                                onCheckedChange = { on ->
                                    if (on) confirmFull = true else actions.setMcpFullAccess(false)
                                },
                                backdrop = LocalGlassBackdrop.current,
                                onTintColor = accents.red,
                            )
                        },
                    )
                }
            }
        }
        item {
            Text(
                "标准 MCP 端口：POST /mcp 走 tools/list 与 tools/call，GET /sse + POST /message 兼容旧客户端，GET /health 可在浏览器确认端口存活。工具覆盖下载固件 → 编译镜像 → 部署资源 → 启停/重启 QEMU → 取帧读日志 → 改参数与配置 → 导出日志 → 删除整条链路。端口随 App 进程存活，排障时请保持 App 在前台。",
                style = IosTypography.caption2,
                color = LocalContentColor.current.copy(alpha = 0.45f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }

        item { SectionLabel("QEMU 调试") }
        item {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = "诊断参数",
                    subtitle = "只开放 -d / -D / -trace / -no-shutdown / -singlestep / -gdb",
                    detail = state.settings.qemuFlags.ifBlank { "无" },
                    showTopSeparator = false,
                    onClick = {
                        editor = Editor(
                            title = "QEMU 诊断参数",
                            hint = "下一次启动生效。例：-d int,guest_errors -D qemu-debug.log。" +
                                "留空表示不带任何诊断开关。",
                            initial = state.settings.qemuFlags,
                            secret = false,
                            placeholder = "-d int,guest_errors",
                            onConfirm = actions.applyQemuFlags,
                        )
                    },
                )
                GlasmioListRow(
                    title = "内核启动参数",
                    subtitle = "追加到 payload 的 boot-args，下次启动生效",
                    detail = state.settings.bootArgs.ifBlank { "无" },
                    onClick = {
                        editor = Editor(
                            title = "内核启动参数",
                            hint = "key=value 形式，例如 debug=0x144 -v。cpus= / rd= / epoch= 由 App 管理。",
                            initial = state.settings.bootArgs,
                            secret = false,
                            placeholder = "debug=0x144",
                            onConfirm = actions.applyBootArgs,
                        )
                    },
                )
            }
        }

        item { SectionLabel("外观") }
        item {
            GlassPanel {
                GlasmioSegmentedControl(
                    items = listOf(
                        ThemeMode.System to "自动",
                        ThemeMode.Light to "浅色",
                        ThemeMode.Dark to "深色",
                    ),
                    selected = state.settings.themeMode,
                    onSelected = actions.setTheme,
                    modifier = Modifier.fillMaxWidth().testTag("seg_theme"),
                    height = 34.dp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "自动跟随系统外观；深色下液态玻璃会降低高光、提高折射对比。",
                    style = IosTypography.caption2,
                    color = LocalContentColor.current.copy(alpha = 0.5f),
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    "液态玻璃",
                    style = IosTypography.subheadline,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                GlasmioSegmentedControl(
                    items = GlassStrength.entries.map { it to it.label },
                    selected = state.settings.glassStrength,
                    onSelected = actions.setGlass,
                    modifier = Modifier.fillMaxWidth().testTag("seg_glass"),
                    height = 34.dp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    state.settings.glassStrength.note,
                    style = IosTypography.caption2,
                    color = LocalContentColor.current.copy(alpha = 0.5f),
                )
            }
        }

        item { SectionLabel("日志") }
        item {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = "自动跟随最新",
                    subtitle = "关闭后向上滚动不会被拉回底部",
                    leading = { IosGlyph(Glyph.Terminal, size = 20.dp, tint = accents.teal) },
                    showTopSeparator = false,
                    trailing = {
                        GlasmioSwitch(
                            checked = state.settings.followLog,
                            onCheckedChange = actions.setFollow,
                            backdrop = LocalGlassBackdrop.current,
                            onTintColor = accents.blue,
                        )
                    },
                )
            }
        }

        item { SectionLabel("存储") }
        item {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = "固件与镜像位置",
                    subtitle = state.artifacts.storagePath,
                    leading = { IosGlyph(Glyph.Folder, size = 20.dp, tint = accents.orange) },
                    showTopSeparator = false,
                    onClick = actions.copyPath,
                )
                GlasmioListRow(
                    title = "工具链自检",
                    subtitle = state.diagnostics.summary ?: "检查 bash / python3 / 7zz / unzbm",
                    leading = { IosGlyph(Glyph.Check, size = 20.dp, tint = accents.blue) },
                    onClick = actions.openDiagnostics,
                )
            }
        }

        item { SectionLabel("维护") }
        item {
            GlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("清理残留进程", style = IosTypography.subheadline, fontWeight = FontWeight.Medium)
                        Text(
                            "结束占用 4444/4445 端口的旧 QEMU",
                            style = IosTypography.caption2,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                    com.android.gudao.iphoneos.ui.components.GlassActionButton(
                        "清理",
                        actions.forceCleanup,
                        accents.orange,
                    )
                }
            }
        }

        item { SectionLabel("关于") }
        item {
            GlasmioGroupedList {
                GlasmioListRow("HackiOS", detail = version, showTopSeparator = false)
                GlasmioListRow("界面框架", detail = "Glasmio 0.1.1")
                GlasmioListRow("运行环境", detail = "QEMU · TCG")
                GlasmioListRow("Android", detail = android.os.Build.VERSION.RELEASE)
            }
        }

        item {
            Text(
                "targetSdk 固定为 28：Android 10 起，SELinux 域由 targetSdk 决定，只有 untrusted_app_27 允许执行应用私有目录里的二进制。这是 QEMU 能跑起来的前提。",
                style = IosTypography.caption2,
                color = LocalContentColor.current.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }

    // ---- sheets & dialogs ----------------------------------------------------------------------

    EditorSheet(editor = editor, onDismiss = { editor = null }, accent = accents.blue)

    if (confirmToken) {
        GlasmioAlertDialog(
            visible = true,
            onDismissRequest = { confirmToken = false },
            title = "重新生成令牌？",
            message = "旧令牌立即失效，已连接的客户端需要更新 Authorization: Bearer <新令牌>。新令牌会自动复制到剪贴板。",
            buttons = listOf(
                GlasmioAlertButtonSpec("取消", { confirmToken = false }, GlasmioAlertButtonRole.Cancel),
                GlasmioAlertButtonSpec(
                    "重新生成",
                    {
                        confirmToken = false
                        actions.copyText(actions.regenerateToken(), "新令牌")
                    },
                    GlasmioAlertButtonRole.Destructive,
                ),
            ),
        )
    }

    if (confirmFull) {
        GlasmioAlertDialog(
            visible = true,
            onDismissRequest = { confirmFull = false },
            title = "开启完全访问？",
            message = "MCP 客户端将可以使用整台 Android 的命令与路径（toybox、/proc、logcat、已 root 时还有 su），" +
                "并额外看到 delete_chain —— 分级删除 payload、镜像、6.1 GB 固件与整个运行目录。" +
                "删链默认只预览，真正执行仍需确认串。请只在你信任连上这个端口的客户端时开启。",
            buttons = listOf(
                // 与「允许修改与执行」弹窗一致：取消不关闭，保持打开。
                GlasmioAlertButtonSpec("取消", { /* 保持打开 */ }, GlasmioAlertButtonRole.Cancel),
                GlasmioAlertButtonSpec(
                    "开启",
                    {
                        confirmFull = false
                        actions.setMcpFullAccess(true)
                    },
                    GlasmioAlertButtonRole.Destructive,
                ),
            ),
        )
    }

    if (confirmWrite) {
        GlasmioAlertDialog(
            visible = true,
            onDismissRequest = { confirmWrite = false },
            title = "允许 MCP 客户端动手修改？",
            message = "打开后，客户端能启停/重启 QEMU、改核数与启动参数、编译镜像、下载固件、导出日志，" +
                "并在 run/ 内写文件（会先备份 .bak）、执行 shell 命令（路径受限、危险命令被拒）。" +
                "这是授信操作：任何连上这个端口的人都能改动这台手机上的模拟器。",
            buttons = listOf(
                // 产品要求：MCP 权限弹窗点「取消」不关闭，保持打开——授权必须显式做出决定，
                // 不能被误触的取消顺手带过。弹窗仍可点弹窗外区域关闭。
                GlasmioAlertButtonSpec("取消", { /* 保持打开 */ }, GlasmioAlertButtonRole.Cancel),
                GlasmioAlertButtonSpec(
                    "允许",
                    {
                        confirmWrite = false
                        actions.setMcpAllowWrite(true)
                    },
                    GlasmioAlertButtonRole.Destructive,
                ),
            ),
        )
    }
}


/**
 * 文本编辑用的底部弹层。
 *
 * 用 sheet 而不是 Dialog：Dialog 是独立窗口，拿不到主窗口的 backdrop，玻璃材质在它上面会变成
 * 一块没有采样来源的半透明板；sheet 在同一棵组合树里，glass 正常。
 */
@Composable
private fun EditorSheet(editor: Editor?, onDismiss: () -> Unit, accent: Color) {
    var text by remember(editor) { mutableStateOf(editor?.initial.orEmpty()) }

    GlasmioBottomSheet(visible = editor != null, fitContent = true, onDismissRequest = onDismiss) {
        val current = editor ?: return@GlasmioBottomSheet
        GlasmioActionSheetContent(current.title, message = current.hint) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(LocalContentColor.current.copy(alpha = 0.07f))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (text.isEmpty()) {
                    Text(
                        current.placeholder,
                        style = IosTypography.subheadline,
                        color = LocalContentColor.current.copy(alpha = 0.3f),
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = IosTypography.subheadline.copy(color = LocalContentColor.current),
                    cursorBrush = SolidColor(accent),
                    singleLine = true,
                    visualTransformation = if (current.secret) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    modifier = Modifier.fillMaxWidth().testTag("field_editor"),
                )
            }
            Spacer(Modifier.height(6.dp))
            GlasmioListRow(
                "保存",
                onClick = {
                    if (current.onConfirm(text.trim())) onDismiss()
                },
            )
            GlasmioListRow("取消", onClick = onDismiss)
        }
    }
}

@Composable
private fun rememberVersion(context: android.content.Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
} catch (t: Throwable) {
    "—"
}
