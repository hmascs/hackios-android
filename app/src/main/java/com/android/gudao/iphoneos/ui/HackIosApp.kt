package com.android.gudao.iphoneos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import com.android.gudao.iphoneos.state.AppTab
import com.android.gudao.iphoneos.state.AppUiState
import com.android.gudao.iphoneos.state.AppViewModel
import com.android.gudao.iphoneos.state.LogSource
import com.android.gudao.iphoneos.state.QemuUi
import com.android.gudao.iphoneos.ui.components.ToastHost
import com.android.gudao.iphoneos.ui.screens.DeviceActions
import com.android.gudao.iphoneos.ui.screens.DeviceScreen
import com.android.gudao.iphoneos.ui.screens.DiagnosticsScreen
import com.android.gudao.iphoneos.ui.screens.DisplayScreen
import com.android.gudao.iphoneos.ui.screens.LibraryActions
import com.android.gudao.iphoneos.ui.screens.LibraryScreen
import com.android.gudao.iphoneos.ui.screens.LogsScreen
import com.android.gudao.iphoneos.ui.screens.SettingsActions
import com.android.gudao.iphoneos.ui.screens.SettingsScreen
import com.android.gudao.iphoneos.ui.theme.HackIosTheme
import com.android.gudao.iphoneos.ui.theme.IosWallpaper
import com.android.gudao.iphoneos.ui.theme.resolveDark
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioNavigationBar
import io.github.neoruaa.glasmio.ui.GlasmioTabItem

/**
 * Root of the iOS 27 presentation layer.
 *
 * Navigation is deliberately two-level, like iOS itself: four root tabs, plus pages that are pushed
 * onto a stack with the standard horizontal spring. The display tab is immersive (no tab bar) so the
 * guest screen and its glass toolbar are never squeezed between two bars.
 */
@Composable
fun HackIosApp(
    vm: AppViewModel,
    requestStorage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    // 0.29.6 性能闸门：本次会话是否已确认。与 settings.perfGateDismissed（永久）分开，
    // 这样点「确认」只会关掉这一次，下次打开 App 仍会提醒。
    var gateAck by remember { mutableStateOf(false) }

    // Keep the panel awake only while iOS is actually running: before that the user is reading
    // progress, and a screen that never sleeps would be a battery bug rather than a feature.
    val view = androidx.compose.ui.platform.LocalView.current
    val running = state.qemu is QemuUi.Running
    LaunchedEffect(running) { view.keepScreenOn = running }

    HackIosTheme(state.settings.themeMode, state.settings.glassStrength) {
        val dark = resolveDark(state.settings.themeMode)

        // The theme is chosen in-app, so the status/navigation bar icons cannot follow the system
        // dark mode flag that enableEdgeToEdge() sets once at startup. Re-assert them whenever the
        // user switches appearance, otherwise light mode keeps white icons on a white wallpaper.
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }

        GlasmioBackdropHost(
            modifier = modifier.fillMaxSize(),
            sampledContent = { IosWallpaper(dark = dark, strength = state.settings.glassStrength) },
            overlayContent = {
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = detail,
                        transitionSpec = {
                            if (targetState != null) {
                                (slideInHorizontally(
                                    spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
                                ) { it } + fadeIn()) togetherWith
                                    (slideOutHorizontally(
                                        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
                                    ) { -it / 4 } + fadeOut())
                            } else {
                                (slideInHorizontally(
                                    spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
                                ) { -it / 4 } + fadeIn()) togetherWith
                                    (slideOutHorizontally(
                                        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
                                    ) { it } + fadeOut())
                            }
                        },
                        label = "push",
                    ) { route ->
                        when (route) {
                            "logs" -> LogsScreen(
                                state = state,
                                logLines = vm.logLines,
                                onBack = { detail = null },
                                onCopy = vm::copyLog,
                                onFollowChange = vm::setFollowLog,
                                onExport = vm::exportLogs,
                                onShareLast = vm::shareExportedLog,
                                onClear = vm::clearLogs,
                            )

                            "diagnostics" -> DiagnosticsScreen(
                                state = state,
                                onBack = { detail = null },
                                onRun = vm::runDiagnostics,
                            )

                            else -> Tabs(
                                state = state,
                                frameTick = vm.frameTick,
                                vm = vm,
                                onOpenLogs = { detail = "logs" },
                                onOpenDiagnostics = { detail = "diagnostics" },
                                onOpenMcpSettings = { vm.selectTab(AppTab.Settings) },
                                onRequestStop = { confirmStop = true },
                            )
                        }
                    }

                    // The tab bar belongs to every root tab, including 屏幕. Hiding it there was
                    // what made the switch look like the bar "just vanished"; it now stays put and
                    // only slides away for pushed detail pages, matching iOS. It is a plain `if`
                    // rather than AnimatedVisibility: a fade here re-composites the whole bar's
                    // glass for 200 ms on every tab switch, and the bar does not move in iOS anyway.
                    if (detail == null) {
                        BottomNav(
                            tab = state.tab,
                            onSelect = vm::selectTab,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }

                    // 状态栏下再留 8dp：只靠 statusBarsPadding() 时，提示条紧贴着刘海/挖孔的
                    // 边缘，读起来像被裁掉了一条。
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 8.dp)
                    ) {
                        ToastHost(state.toasts) { vm.dismissToast(it) }
                    }

                    // 0.25.0：确认框必须挂在 GlasmioBackdropHost 的 overlayContent **内部**。
                    // 旧版把它放在 Host 之外（只在 HackIosTheme 里），拿不到 LocalBlurBackdrop，
                    // 用户一点「停止 iOS」就抛
                    //   IllegalStateException: Blur glass must be hosted by the app backdrop provider
                    // 真机日志 0.22.0（2026-11-04）在 17:35 / 18:00 / 18:09 各崩一次。
                    if (confirmStop) {
                        GlasmioAlertDialog(
                            visible = true,
                            onDismissRequest = { confirmStop = false },
                            title = "停止 iOS？",
                            message = "QEMU 会优雅退出，未保存的访客状态会丢失。镜像与固件不受影响。",
                            buttons = listOf(
                                GlasmioAlertButtonSpec("取消", { confirmStop = false }, GlasmioAlertButtonRole.Cancel),
                                GlasmioAlertButtonSpec(
                                    "停止",
                                    {
                                        confirmStop = false
                                        vm.stopQemu()
                                    },
                                    GlasmioAlertButtonRole.Destructive,
                                ),
                            ),
                        )
                    }

                    // 0.29.6 性能闸门：每次打开都弹，直到用户点「不再弹出」。
                    // 文案只陈述实测事实——这份镜像全靠 Mesa softpipe 在 CPU 上光栅化
                    // 1125x2436，处理器弱时开机可以拖到半小时，更糟是 launchd 反复重启。
                    if (!gateAck && !state.settings.perfGateDismissed) {
                        GlasmioAlertDialog(
                            visible = true,
                            onDismissRequest = { gateAck = true },
                            title = "性能提示",
                            message = "这台模拟器不依赖 GPU，全靠 CPU 软件渲染，对处理器要求很高。\n\n" +
                                "高通骁龙 8 Gen 2、天玑 9200、玄戒 O1 及以上的机型，模拟效果更好；" +
                                "处理器低于这个档位时，开机可能慢到半小时，更严重会触发 LSD 风暴" +
                                "（launchd 反复重启、系统卡死）。\n\n" +
                                "已经装了较弱的设备也仍可继续，只是需要多等。",
                            buttons = listOf(
                                GlasmioAlertButtonSpec(
                                    "不再弹出",
                                    { vm.setPerfGateDismissed(true) },
                                    GlasmioAlertButtonRole.Cancel,
                                ),
                                GlasmioAlertButtonSpec(
                                    "确认",
                                    { gateAck = true },
                                    GlasmioAlertButtonRole.Destructive,
                                ),
                            ),
                        )
                    }

                    // 构建完成后立即询问：清不清 5+GB 构件残留（中间产物，最终 apfs 镜像不动）。
                    if (state.cleanupPrompt) {
                        GlasmioAlertDialog(
                            visible = true,
                            onDismissRequest = { vm.dismissCleanupPrompt() },
                            title = "清除构建残留？",
                            message = "这次编译留下了 5+GB 中间产物（解包的 rootfs、payload、构建日志等），" +
                                "占着下载目录的空间。清除它们不影响刚编好的 system.apfs / root.apfs，" +
                                "下次编译会按需重新生成。",
                            buttons = listOf(
                                GlasmioAlertButtonSpec("保留", { vm.dismissCleanupPrompt() }, GlasmioAlertButtonRole.Cancel),
                                GlasmioAlertButtonSpec(
                                    "清除",
                                    { vm.clearBuildResidue() },
                                    GlasmioAlertButtonRole.Destructive,
                                ),
                            ),
                        )
                    }
                }
            },
        )
    }

    LaunchedEffect(state.permissionPending) {
        if (state.permissionPending) requestStorage()
    }

    // Coming back from a file manager or Termux: the firmware / image files may have changed
    // underneath us, so re-stat them instead of showing a stale 缺失 badge.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshArtifacts() }

    BackHandler(enabled = detail != null) { detail = null }
    BackHandler(enabled = detail == null && state.tab == AppTab.Screen) { vm.selectTab(AppTab.Device) }
}

@Composable
private fun Tabs(
    state: AppUiState,
    frameTick: kotlinx.coroutines.flow.StateFlow<Long>,
    vm: AppViewModel,
    onOpenLogs: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenMcpSettings: () -> Unit,
    onRequestStop: () -> Unit,
) {
    // No Crossfade between root tabs: it keeps *both* pages composed while it runs, and every glass
    // surface in both of them samples the backdrop, which is a guaranteed missed frame on any phone
    // that is also doing real work. iOS does not cross-fade root tabs either.
    when (state.tab) {
        AppTab.Device -> DeviceScreen(
            state = state,
            actions = DeviceActions(
                start = vm::startQemu,
                stop = onRequestStop,
                retry = vm::retryQemu,
                compile = vm::compileImages,
                openLogs = onOpenLogs,
                openLibrary = { vm.selectTab(AppTab.Library) },
                openScreen = { vm.selectTab(AppTab.Screen) },
                ignoreStale = vm::ignoreStaleImages,
                forceCleanup = vm::forceCleanup,
                openMcpSettings = onOpenMcpSettings,
            ),
        )

        AppTab.Screen -> DisplayScreen(
            state = state,
            frameTick = frameTick,
            frameProvider = vm::frame,
            onStart = vm::startQemu,
            onKey = vm::sendKey,
            onSwipeUnlock = vm::swipeUpToUnlock,
            onTouchDown = vm::touchDown,
            onTouchMove = vm::touchMove,
            onTouchUp = vm::touchUp,
            onRetryCapture = vm::retryCapture,
            onUseStableCpu = vm::restartWithOneCore,
            onOpenLogs = onOpenLogs,
            onExit = { vm.selectTab(AppTab.Device) },
            bottomInset = 96.dp,
        )

        AppTab.Library -> LibraryScreen(
            state = state,
            actions = LibraryActions(
                download = vm::downloadFirmware,
                cancelDownload = vm::cancelDownload,
                verify = vm::verifyFirmware,
                compile = vm::compileImages,
                cancelCompile = vm::cancelCompile,
                openDiagnostics = onOpenDiagnostics,
                openLogs = onOpenLogs,
                requestStorage = vm::requestStoragePermission,
            ),
        )

        AppTab.Settings -> SettingsScreen(
            state = state,
            actions = SettingsActions(
                setTheme = vm::setThemeMode,
                setGlass = vm::setGlassStrength,
                setSmp = vm::setSmp,
                setHostCores = vm::setHostCores,
                setMttcg = vm::setMttcg,
                setAutoFallback = vm::setAutoFallback,
                setNet = vm::setNetEnabled,
                setFollow = vm::setFollowLog,
                forceCleanup = vm::forceCleanup,
                openDiagnostics = onOpenDiagnostics,
                copyPath = { vm.copyLog(LogSource.Serial) },
                copyText = vm::copyToClipboard,
                setMcpEnabled = vm::setMcpEnabled,
                setMcpPort = vm::setMcpPort,
                setMcpBindLan = vm::setMcpBindLan,
                setMcpAllowWrite = vm::setMcpAllowWrite,
                setMcpFullAccess = vm::setMcpFullAccess,
                regenerateToken = vm::regenerateMcpToken,
                restartMcp = vm::restartMcp,
                applyQemuFlags = vm::applyQemuFlags,
                applyBootArgs = vm::applyBootArgs,
            ),
        )
    }
}

@Composable
private fun BottomNav(
    tab: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val compactProgress = remember { mutableStateOf(0f) }
    val items = remember {
        listOf(
            GlasmioTabItem(AppTab.Device, "设备", SfSymbol.HouseFilled),
            GlasmioTabItem(AppTab.Screen, "屏幕", SfSymbol.Layers),
            GlasmioTabItem(AppTab.Library, "资源", SfSymbol.CloudFilled),
            GlasmioTabItem(AppTab.Settings, "设置", SfSymbol.Gearshape),
        )
    }
    GlasmioNavigationBar(
        items = items,
        selectedKey = tab,
        onSelected = onSelect,
        onExpand = {},
        compactProgress = compactProgress,
        compactSize = 64.dp,
        modifier = modifier,
    )
}
