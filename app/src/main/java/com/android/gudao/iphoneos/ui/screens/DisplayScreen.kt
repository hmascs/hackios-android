package com.android.gudao.iphoneos.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.gudao.iphoneos.Constants
import com.android.gudao.iphoneos.engine.CapturePipeline
import com.android.gudao.iphoneos.engine.QemuTopology
import com.android.gudao.iphoneos.engine.TouchMapper
import com.android.gudao.iphoneos.state.AppUiState
import com.android.gudao.iphoneos.state.QemuUi
import com.android.gudao.iphoneos.ui.components.EmptyState
import com.android.gudao.iphoneos.ui.components.GlassActionButton
import com.android.gudao.iphoneos.ui.components.GlassKey
import com.android.gudao.iphoneos.ui.components.Glyph
import com.android.gudao.iphoneos.ui.components.IosGlyph
import com.android.gudao.iphoneos.ui.components.StatusChip
import com.android.gudao.iphoneos.ui.theme.LocalIosAccents
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.ui.GlasmioActionSheetContent
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioListRow

/**
 * The iOS screen itself.
 *
 * The frame is drawn in Compose rather than on a `SurfaceView` on purpose: the old view needed
 * `setZOrderOnTop(true)` to beat opaque ancestors, and a surface above the window would also sit
 * above the tab bar, every sheet and every glass control. Drawing into the composition keeps the
 * guest screen inside the UI hierarchy, so the Liquid Glass chrome floats *over* iOS the way it does
 * in a native app.
 */
@Composable
fun DisplayScreen(
    state: AppUiState,
    frameTick: kotlinx.coroutines.flow.StateFlow<Long>,
    frameProvider: () -> android.graphics.Bitmap?,
    onStart: () -> Unit,
    onKey: (String) -> Unit,
    onSwipeUnlock: () -> Unit,
    onTouchDown: (Float, Float) -> Unit,
    onTouchMove: (Float, Float) -> Unit,
    onTouchUp: (Float, Float) -> Unit,
    onRetryCapture: () -> Unit,
    onUseStableCpu: () -> Unit,
    onOpenLogs: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val accents = LocalIosAccents.current
    val running = state.qemu is QemuUi.Running
    var chrome by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(false) }

    val capture = state.capture

    // The top inset comes from the platform resource *and* the window insets, whichever is larger.
    // `statusBarsPadding()` alone was not enough: this content sits inside a host that has already
    // consumed the insets, so it reported zero and the panel began at y = 0 -- under the status bar
    // and under any notch or punch-hole, which is precisely where a screen must not begin.
    val context = androidx.compose.ui.platform.LocalContext.current
    val statusBarTop = remember(context) {
        val metrics = context.resources.displayMetrics
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val px = if (id > 0) context.resources.getDimensionPixelSize(id).toFloat() else 0f
        // A device without that dimen still has a status bar; 24dp is its smallest usual height.
        val height = if (px > 0f) px else 24f * metrics.density
        (height / metrics.density).dp
    }
    // The frame is kept in a State that is read *inside* the draw lambda only: at 8 fps this
    // invalidates the draw phase, not the composition, so the tab bar and glass chrome never
    // recompose because iOS repainted.
    val image = remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(frameTick) {
        frameTick.collect {
            val bmp = frameProvider()
            image.value = bmp?.asImageBitmap()
        }
    }

    Box(modifier.fillMaxSize().testTag("screen_display")) {
        if (!running) {
            EmptyState(
                glyph = Glyph.Screen,
                title = "QEMU 没有运行",
                message = "启动后这里会实时显示 iOS 的画面，可直接触摸操作。",
                actionLabel = "启动 iOS",
                onAction = onStart,
            )
        } else {
            // ---- guest screen ----
            // The screen sits *between* the two chrome bars instead of behind them. It used to be
            // fillMaxSize, so its top edge disappeared under the status bar -- and under a notch or
            // punch-hole -- and its bottom edge ran on beneath the control strip. Top now starts
            // below the status bar; bottom stops one gap above the controls.
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
            ) {
                Spacer(Modifier.height(statusBarTop + 6.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(30.dp))
                        .background(Color.Black)
                        .testTag("guest_surface")
                        .pointerInput(running) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                fun map(o: Offset): Pair<Float, Float>? =
                                    TouchMapper.toGuest(w, h, Constants.SCREEN_W, Constants.SCREEN_H, o.x, o.y)
                                        ?.let { it.x to it.y }

                                map(down.position)?.let { (x, y) -> onTouchDown(x, y) }
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.pressed) {
                                        map(change.position)?.let { (x, y) -> onTouchMove(x, y) }
                                        change.consume()
                                    } else {
                                        map(change.position)?.let { (x, y) -> onTouchUp(x, y) }
                                        break
                                    }
                                }
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val img = image.value ?: return@Canvas
                        val scale = minOf(size.width / img.width, size.height / img.height)
                        val dw = img.width * scale
                        val dh = img.height * scale
                        drawImage(
                            image = img,
                            srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
                            dstOffset = androidx.compose.ui.unit.IntOffset(
                                ((size.width - dw) / 2f).toInt(),
                                ((size.height - dh) / 2f).toInt(),
                            ),
                            dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt()),
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                        )
                    }

                    if (image.value == null) {
                        WaitingForFirstFrame(capture, onRetryCapture)
                    }

                    // ---- the chrome that floats over iOS ----
                    // Two notices used to live at TopCenter with different padding (14dp and 18dp)
                    // and so printed on top of each other: "取帧…等待画面 8 fps 更多" arrived with
                    // "重试" written through it. They are one column now, so a second notice can
                    // only ever be *below* the first.
                    Column(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(top = 14.dp, start = 16.dp, end = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (chrome) {
                            Row(
                                Modifier
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.28f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusChip(
                                    text = if (capture.health == CapturePipeline.Health.Live) "实时" else "等待画面",
                                    tint = if (capture.health == CapturePipeline.Health.Live) accents.green else accents.orange,
                                    icon = Glyph.Circle,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${capture.resolution} · ${"%.0f".format(capture.fps)} fps",
                                    style = IosTypography.caption2,
                                    color = Color.White.copy(alpha = 0.85f),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "更多",
                                    style = IosTypography.caption2,
                                    color = accents.blue,
                                    modifier = Modifier
                                        .testTag("btn_display_more")
                                        .clickable { sheet = true },
                                )
                            }
                        }

                        // 取帧是好的，但每次拿到的都是同一张图：guest 卡住了，不是画面通道坏了。
                        // 0.24.0：默认六核 MTTCG 下也可能卡，只要还能降一档就给出处置按钮。
                        val stalledFor = capture.unchangedForMs
                        val canFallback = QemuTopology.fallbackCores(state.settings.smp) != null
                        if (running && capture.health == CapturePipeline.Health.Live && stalledFor >= STALL_NOTICE_MS) {
                            Row(
                                Modifier
                                    .clip(CircleShape)
                                    .background(accents.orange.copy(alpha = 0.18f))
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (canFallback)
                                        "画面已 ${stalledFor / 1000} 秒未变化 · ${state.settings.smp} 核（会自动降核）"
                                    else "画面已 ${stalledFor / 1000} 秒未变化（已在稳定核数，guest 没有在画）",
                                    style = IosTypography.caption1,
                                    color = accents.orange,
                                )
                                // 还能降一档才提议；已到 2 核下限再劝降核毫无意义。
                                if (canFallback) {
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "降核重启",
                                        style = IosTypography.caption1,
                                        color = accents.blue,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .testTag("btn_use_stable_cpu")
                                            .clickable { onUseStableCpu() },
                                    )
                                }
                            }
                        }

                        if (capture.health == CapturePipeline.Health.Retrying ||
                            capture.health == CapturePipeline.Health.Stopped
                        ) {
                            Row(
                                Modifier
                                    .clip(CircleShape)
                                    .background(accents.orange.copy(alpha = 0.18f))
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    capture.lastError ?: "取帧异常",
                                    style = IosTypography.caption1,
                                    color = accents.orange,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "重试",
                                    style = IosTypography.caption1,
                                    color = accents.blue,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .testTag("btn_retry_capture")
                                        .clickable { onRetryCapture() },
                                )
                            }
                        }
                    }

                    if (chrome) {
                        Row(
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 12.dp, top = 12.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.3f))
                                .clickable { onExit() }
                                .padding(8.dp)
                                .testTag("btn_exit_screen"),
                        ) {
                            IosGlyph(
                                Glyph.ChevronRight,
                                size = 16.dp,
                                tint = Color.White,
                                modifier = Modifier.graphicsLayer { rotationZ = 180f },
                            )
                        }
                    }
                }

                // The control strip is *under* the screen, not on it: the guest surface stops
                // above the strip, which is what "bottom ends a little above the selector" means.
                // Hiding the chrome gives that room back to the screen, so immersive is still
                // immersive.
                if (chrome) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black.copy(alpha = 0.34f))
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        GlassKey(Glyph.Circle, "主屏", { onKey("home") }, tint = Color.White, modifier = Modifier.weight(1f))
                        GlassKey(Glyph.Lock, "锁屏", { onKey("lock") }, tint = Color.White, modifier = Modifier.weight(1f))
                        GlassKey(Glyph.VolumeUp, "音量+", { onKey("volup") }, tint = Color.White, modifier = Modifier.weight(1f))
                        GlassKey(Glyph.VolumeDown, "音量−", { onKey("voldown") }, tint = Color.White, modifier = Modifier.weight(1f))
                        GlassKey(Glyph.SwipeUp, "上滑", onSwipeUnlock, tint = Color.White, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(bottomInset))
            }

            if (!chrome) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = bottomInset + 8.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.3f))
                        .clickable { chrome = true }
                        .padding(10.dp)
                        .testTag("btn_show_chrome"),
                ) {
                    IosGlyph(Glyph.ChevronRight, size = 18.dp, tint = Color.White)
                }
            }
        }
    }

    GlasmioBottomSheet(
        visible = sheet,
        fitContent = true,
        onDismissRequest = { sheet = false },
    ) {
        GlasmioActionSheetContent("画面", message = "取帧链路与显示设置") {
            GlasmioListRow("重新取帧", showTopSeparator = false, onClick = {
                sheet = false
                onRetryCapture()
            })
            GlasmioListRow("打开日志", onClick = {
                sheet = false
                onOpenLogs()
            })
            GlasmioListRow("隐藏控制条", onClick = {
                sheet = false
                chrome = false
            })
        }
    }
}

@Composable
private fun WaitingForFirstFrame(capture: CapturePipeline.Snapshot, onRetry: () -> Unit) {
    val accents = LocalIosAccents.current
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(accents.blue.copy(alpha = alpha))
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (capture.frames == 0L) "等待第一帧（QEMU 引导中）" else "画面更新中…",
            style = IosTypography.footnote,
            color = Color.White.copy(alpha = 0.7f),
        )
        if (capture.health == CapturePipeline.Health.Stopped) {
            Spacer(Modifier.height(14.dp))
            GlassActionButton("重试取帧", onRetry, accents.blue)
        }
    }
}

/** 静止多久开始怀疑 guest 卡住：锁屏时钟每分钟会跳一次，90 秒已经足够区分"静止"和"卡死"。 */
private const val STALL_NOTICE_MS = 90_000L
