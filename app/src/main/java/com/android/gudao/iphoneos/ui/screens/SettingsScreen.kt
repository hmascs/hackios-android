package com.android.gudao.iphoneos.ui.screens

import com.android.gudao.iphoneos.engine.BootTimeline
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.gudao.iphoneos.mcp.McpConfig
import com.android.gudao.iphoneos.state.AppUiState
import com.android.gudao.iphoneos.state.BuildUi
import com.android.gudao.iphoneos.state.DownloadUi
import com.android.gudao.iphoneos.state.GlassStrength
import com.android.gudao.iphoneos.engine.QemuTopology
import com.android.gudao.iphoneos.state.QemuUi
import com.android.gudao.iphoneos.state.ThemeMode
import com.android.gudao.iphoneos.ui.components.Editor
import com.android.gudao.iphoneos.ui.components.EditorSheet
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
    val setFollow: (Boolean) -> Unit,
    val forceCleanup: () -> Unit,
    val openDiagnostics: () -> Unit,
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
    val hardwareLocked = running || state.qemu is QemuUi.Starting
    val diagnosticsBusy = state.diagnostics.running
    val resourceBusy = hardwareLocked || state.build is BuildUi.Running || diagnosticsBusy
    val cpu = QemuTopology.backendCpu(state.settings.experimentalGpu, state.settings.smp, state.settings.mttcg)
    val guestCpuEditable = !hardwareLocked && cpu.guestSettingsEditable
    val mcp = state.settings.mcp
    val mcpState = state.mcp

    var editor by remember { mutableStateOf<Editor?>(null) }
    var confirmWrite by remember { mutableStateOf(false) }
    var confirmFull by remember { mutableStateOf(false) }
    var confirmToken by remember { mutableStateOf(false) }
    var confirmCleanup by remember { mutableStateOf(false) }

    GlasmioPinnedListPage(
        title = "设置",
        bottomPadding = 144.dp,
        // 存储已经在 0.31.0 挪到首页，副标题还留着「存储」会把人送去错误的地方。
        subtitle = "性能、MCP 与外观",
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
                            when {
                                !cpu.guestSettingsEditable -> "Adreno 固定双核。切回 OSMesa 后恢复之前的设置。"
                                hardwareLocked -> "启动或运行中不可修改"
                                else -> "XNU 看到的 vCPU 数。默认 2 核；增加 vCPU 不一定加快启动。"
                            },
                            style = IosTypography.caption2,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                    Text(
                        "${cpu.guestCores} vCPU",
                        style = IosTypography.subheadline,
                        color = accents.blue,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                GlasmioSlider(
                    value = cpu.guestCores.toFloat(),
                    onValueChange = { if (guestCpuEditable) actions.setSmp(QemuTopology.normalize(it.toInt())) },
                    modifier = Modifier.fillMaxWidth().testTag("slider_smp"),
                    valueRange = QemuTopology.MIN_CORES.toFloat()..QemuTopology.MAX_CORES.toFloat(),
                    enabled = guestCpuEditable,
                )
            }
        }
        item {
            GlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 绿色在本 App 里表示「成功 / 已开启」，宿主算力池只是另一组可调参数，
                    // 用蓝色主操作色，避免看起来像一个成功状态。
                    IosGlyph(Glyph.Cpu, size = 20.dp, tint = accents.blue)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("宿主算力池（Host compute pool）", style = IosTypography.subheadline, fontWeight = FontWeight.Medium)
                        Text(
                            if (hardwareLocked) "启动或运行中不可修改"
                            else "宿主调度与翻译缓存预算，不改变来宾 vCPU，也不重建镜像。",
                            style = IosTypography.caption2,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                    Text(
                        "${state.settings.hostCores} 核",
                        style = IosTypography.subheadline,
                        color = accents.blue,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                GlasmioSlider(
                    value = state.settings.hostCores.toFloat(),
                    onValueChange = { if (!hardwareLocked) actions.setHostCores(QemuTopology.normalizeHostCores(it.toInt())) },
                    modifier = Modifier.fillMaxWidth().testTag("slider_host_cores"),
                    valueRange = QemuTopology.HOST_CORES_MIN.toFloat()..QemuTopology.HOST_CORES_MAX.toFloat(),
                    enabled = !hardwareLocked,
                )
                val tbMiB = if (cpu.mttcg) QemuTopology.tbSize(state.settings.hostCores) else QemuTopology.TB_SIZE_BASE
                Text(
                    "正常启动的 TB 缓存预算：$tbMiB MiB。实际可用宿主核由 Android 限制，见启动日志。",
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
                            if (!cpu.guestSettingsEditable) "Adreno 固定开启；切回 OSMesa 可用单线程排障。"
                            else "每个来宾 vCPU 一条宿主执行线程。关闭后使用单线程排障。",
                            style = IosTypography.caption2,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                    GlasmioSwitch(
                        checked = cpu.mttcg,
                        onCheckedChange = { if (guestCpuEditable) actions.setMttcg(it) },
                        enabled = guestCpuEditable,
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
                            if (!cpu.guestSettingsEditable) "Adreno 不自动降核或回退。"
                            else "未进桌面且画面 ${com.android.gudao.iphoneos.Constants.STALL_FALLBACK_MS / 1000} 秒无变化时，" +
                                "降一档重启，最低 2 核。软件渲染启动可能需要十几分钟。",
                            style = IosTypography.caption2,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                    GlasmioSwitch(
                        // Was `cpu.guestSettingsEditable && autoFallback`, so a running QEMU showed
                        // the switch as off even when the setting was on. Show the stored value;
                        // only Adreno (where fallback is genuinely disabled) forces it off.
                        checked = if (state.settings.experimentalGpu) false else state.settings.autoFallback,
                        onCheckedChange = { if (cpu.guestSettingsEditable) actions.setAutoFallback(it) },
                        enabled = cpu.guestSettingsEditable,
                        backdrop = LocalGlassBackdrop.current,
                        onTintColor = accents.blue,
                    )
                }
            }
        }

        // ---- 启动分段计时（0.24.0）----------------------------------------------------------------
        if (state.bootTimeline.active) {
            // 预算只有一处真相：BootTimeline 的两个常量。这里曾经写死过 90s/180s，
            // 0.29.7 把它们改成 60s/600s 时漏改，于是界面永远显示「✗ 超预算」——
            // 一次正常但慢的启动被自己的文案判成失败。
            item {
                SectionLabel(
                    "启动计时 · 预算 Logo ${BootTimeline.LOGO_BUDGET_MS / 1000}s / " +
                        "桌面 ${BootTimeline.DESKTOP_BUDGET_MS / 1000}s"
                )
            }
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
                                null -> "未到达（预算 ${BootTimeline.DESKTOP_BUDGET_MS / 1000}s）"
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
                    // 关闭状态下写「重启」点下去只会得到「MCP 服务已关闭」；改成真正的启动动作。
                    com.android.gudao.iphoneos.ui.components.GlassActionButton(
                        if (mcp.enabled) "重启" else "启动",
                        if (mcp.enabled) actions.restartMcp else ({ actions.setMcpEnabled(true) }),
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
                            hint = "key=value 形式，例如 debug=0x144 -v。cpus= / rd= / epoch= 由 App 管理。" +
                                "\nAdreno 默认启用命令批处理。填入 glbatch=0 可在下次启动时关闭。",
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

        item { SectionLabel("维护") }
        item {
            GlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("清理残留进程", style = IosTypography.subheadline, fontWeight = FontWeight.Medium)
                        Text(
                            if (running) "会结束当前正在运行的 QEMU（端口 4444/4445）"
                            else "结束占用 4444/4445 端口的旧 QEMU",
                            style = IosTypography.caption2,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                    com.android.gudao.iphoneos.ui.components.GlassActionButton(
                        "清理",
                        { confirmCleanup = true },
                        accents.orange,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("工具链自检", style = IosTypography.subheadline, fontWeight = FontWeight.Medium)
                        Text(
                            when {
                                hardwareLocked -> "QEMU 启动或运行中，停止后才能部署或自检"
                                state.build is BuildUi.Running -> "镜像编译中，完成或取消后才能自检"
                                diagnosticsBusy -> state.diagnostics.summary ?: "自检进行中"
                                else -> state.diagnostics.summary ?: "检查 bash / python3 / 7zz / unzbm"
                            },
                            style = IosTypography.caption2,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                    com.android.gudao.iphoneos.ui.components.GlassActionButton(
                        "自检",
                        actions.openDiagnostics,
                        accents.blue,
                        enabled = !resourceBusy,
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

    if (confirmCleanup) {
        GlasmioAlertDialog(
            visible = true,
            onDismissRequest = { confirmCleanup = false },
            title = "清理残留进程？",
            message = if (running) {
                "QEMU 正在运行，这一步会直接结束当前实例，本次启动进度和未保存的 guest 状态都会丢失。" +
                    "只是想让界面恢复正常的话，先回设备页点「停止」更稳妥。"
            } else {
                "会结束占用 4444/4445 端口的旧 QEMU 进程并释放端口。镜像、固件与数据盘不受影响。"
            },
            buttons = listOf(
                GlasmioAlertButtonSpec("取消", { confirmCleanup = false }, GlasmioAlertButtonRole.Cancel),
                GlasmioAlertButtonSpec(
                    "清理",
                    {
                        confirmCleanup = false
                        actions.forceCleanup()
                    },
                    GlasmioAlertButtonRole.Destructive,
                ),
            ),
        )
    }

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


@Composable
private fun rememberVersion(context: android.content.Context): String = remember(context) {
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
    } catch (t: Throwable) {
        "—"
    }
}
