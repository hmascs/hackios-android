package com.android.gudao.iphoneos.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import com.android.gudao.iphoneos.state.ToastMessage
import com.android.gudao.iphoneos.ui.theme.LocalIosAccents
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.ui.GlasmioCard
import io.github.neoruaa.glasmio.ui.glasmioGroupedListBackground

/**
 * A grouped-list material panel, the iOS "inset grouped" container.
 *
 * [onClick] makes the *whole* card a target, which is what iOS does: on a real device tapping the
 * title of a "日志 / 资源" card opens it, you do not have to hit the small button underneath.
 * The E2E run caught exactly this — tapping the label did nothing because only the inner button
 * was clickable.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .glasmioGroupedListBackground(RoundedCornerShape(corner))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

/** Thin iOS progress capsule with a spring-animated fill. */
@Composable
fun IosProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    tint: Color = LocalIosAccents.current.blue,
    height: Dp = 6.dp,
    track: Color = LocalContentColor.current.copy(alpha = 0.12f),
    /**
     * A step that cannot report a percentage (7z listing a 4.7 GB image, mkapfs packing 5.4 GB).
     * A bar frozen at 0 % reads as "stuck"; a sweeping segment reads as "working", which is what
     * those minutes actually are.
     */
    indeterminate: Boolean = false,
) {
    if (indeterminate) {
        BoxWithConstraints(
            modifier
                .fillMaxWidth()
                .height(height)
                .clip(CircleShape)
                .background(track)
        ) {
            val sweep by rememberInfiniteTransition(label = "indeterminate").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "sweep",
            )
            val segment = maxWidth * 0.34f
            Box(
                Modifier
                    .offset(x = (maxWidth + segment) * sweep - segment)
                    .width(segment)
                    .height(height)
                    .clip(CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(tint.copy(alpha = 0.30f), tint, tint.copy(alpha = 0.30f))
                        )
                    )
            )
        }
        return
    }

    // tween, not spring: a spring keeps invalidating the bar for ~600 ms after every 1 % step,
    // and each invalidate re-composites the glass panel it sits in.
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.tween(260),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(track)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(height)
                .clip(CircleShape)
                .background(tint)
                .then(
                    if (animated > 0.02f) Modifier.background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(tint.copy(alpha = 0.75f), tint)
                        )
                    ) else Modifier
                )
        )
    }
}

/** iOS status pill: translucent tinted capsule with a 1 dp inner stroke. */
@Composable
fun StatusChip(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    icon: Glyph? = null,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IosGlyph(icon, size = 12.dp, tint = tint, stroke = 2.dp)
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = IosTypography.caption1, color = tint)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
        style = IosTypography.footnote,
        color = LocalContentColor.current.copy(alpha = 0.5f),
        fontWeight = FontWeight.Medium,
    )
}

/** Label / value row used inside panels where a grouped list would be overkill. */
@Composable
fun MetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalContentColor.current,
    leading: Glyph? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            IosGlyph(leading, size = 16.dp, tint = LocalContentColor.current.copy(alpha = 0.55f))
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = IosTypography.subheadline, color = LocalContentColor.current.copy(alpha = 0.65f))
        Spacer(Modifier.weight(1f))
        Text(value, style = IosTypography.subheadline, color = valueColor, textAlign = TextAlign.End)
    }
}

/**
 * Error / warning banner. Every failure in the app renders through this so the user always gets
 * three things: what happened, why, and the next action.
 */
@Composable
fun GlassBanner(
    title: String,
    message: String,
    tint: Color,
    modifier: Modifier = Modifier,
    icon: Glyph = Glyph.Circle,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    GlasmioCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    IosGlyph(icon, size = 15.dp, tint = tint)
                }
                Spacer(Modifier.width(10.dp))
                Text(title, style = IosTypography.headline, color = LocalContentColor.current)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = IosTypography.footnote,
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
            if (primaryLabel != null && onPrimary != null) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassActionButton(primaryLabel, onPrimary, tint, Modifier.weight(1f))
                    if (secondaryLabel != null && onSecondary != null) {
                        GlassActionButton(secondaryLabel, onSecondary, LocalContentColor.current.copy(alpha = 0.6f), Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Compact capsule action used inside banners and cards. */
@Composable
fun GlassActionButton(
    label: String,
    onClick: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(tint.copy(alpha = if (enabled) 0.16f else 0.08f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(vertical = 9.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = IosTypography.subheadline,
            color = if (enabled) tint else tint.copy(alpha = 0.4f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** Circular glass key for the display toolbar (Home / Lock / Volume / Swipe). */
@Composable
fun GlassKey(
    glyph: Glyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessHigh),
        label = "key",
    )
    val haptics = LocalHapticFeedback.current
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(18.dp))
            .background(tint.copy(alpha = if (enabled) 0.10f else 0.04f))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics { contentDescription = label }
            .testTag("key_$label")
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IosGlyph(glyph, size = 20.dp, tint = tint.copy(alpha = if (enabled) 1f else 0.35f))
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = IosTypography.caption2,
            color = tint.copy(alpha = if (enabled) 0.75f else 0.3f),
            maxLines = 1,
        )
    }
}

/**
 * Stacked transient messages: iOS-style pills that drop in from the top and leave on their own.
 * Stacked transient messages.
 *
 * A single-slot host printed the newest message over the previous one for the last frames of the
 * old one's exit animation, and either way everything landed at the very top edge -- where a notch
 * or a punch-hole lives, and under the system status bar on phones whose inset is reported as
 * smaller than the cutout. This renders a column instead: the newest message is appended *below*
 * the previous one, the whole stack starts below the status bar, and two messages can never
 * occupy the same pixels.
 */
@Composable
fun ToastHost(messages: List<ToastMessage>, onDismiss: (Long) -> Unit) {
    val accents = LocalIosAccents.current
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        messages.forEach { m ->
            androidx.compose.runtime.key(m.id) {
                AnimatedVisibility(
                    visible = true,
                    enter = slideInVertically(
                        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
                    ) { -it / 2 } + fadeIn(),
                    exit = slideOutVertically(
                        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
                    ) { -it / 2 } + fadeOut(),
                ) {
                    val tint = when (m.kind) {
                        ToastMessage.Kind.Info -> accents.blue
                        ToastMessage.Kind.Success -> accents.green
                        ToastMessage.Kind.Error -> accents.red
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(CircleShape)
                            .background(tint.copy(alpha = 0.26f))
                            .clickable { onDismiss(m.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("toast"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IosGlyph(
                            when (m.kind) {
                                ToastMessage.Kind.Success -> Glyph.Check
                                ToastMessage.Kind.Error -> Glyph.Lock
                                else -> Glyph.Circle
                            },
                            size = 16.dp,
                            tint = tint,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(m.text, style = IosTypography.subheadline, color = LocalContentColor.current)
                    }
                }
            }
        }
    }
}

/** Centred empty state with a glass action. */
@Composable
fun EmptyState(
    glyph: Glyph,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(LocalContentColor.current.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            IosGlyph(glyph, size = 28.dp, tint = LocalContentColor.current.copy(alpha = 0.5f))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = IosTypography.title3, color = LocalContentColor.current)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = IosTypography.footnote,
            color = LocalContentColor.current.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            GlassActionButton(actionLabel, onAction, LocalIosAccents.current.blue)
        }
    }
}

/** Subtle "card" used for the device hero: glass material plus a top specular edge. */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlasmioCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 7.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun SpacerH(width: Dp) = Spacer(Modifier.width(width))
