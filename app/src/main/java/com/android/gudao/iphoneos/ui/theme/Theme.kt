package com.android.gudao.iphoneos.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint as AndroidPaint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.gudao.iphoneos.state.GlassStrength
import com.android.gudao.iphoneos.state.ThemeMode
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.GlassAppearance
import io.github.neoruaa.glasmio.core.defaultGlassColors
import io.github.neoruaa.glasmio.core.glasmioColorScheme
import io.github.neoruaa.glasmio.core.glasmioTypography

/**
 * iOS 27 semantic accents. Glasmio supplies the glass materials and component geometry; these are
 * the three colours iOS itself uses for status, layered on top of the system palette.
 */
@Immutable
data class IosAccents(
    val blue: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val purple: Color,
    val teal: Color,
)

val IosAccentsDark = IosAccents(
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    red = Color(0xFFFF453A),
    purple = Color(0xFFBF5AF2),
    teal = Color(0xFF64D2FF),
)

val IosAccentsLight = IosAccents(
    blue = Color(0xFF007AFF),
    green = Color(0xFF34C759),
    orange = Color(0xFFFF9500),
    red = Color(0xFFFF3B30),
    purple = Color(0xFFAF52DE),
    teal = Color(0xFF32ADE6),
)

val LocalIosAccents = compositionLocalOf { IosAccentsDark }

val LocalIsDark = compositionLocalOf { true }

/** Current Liquid Glass strength, so the wallpaper and custom surfaces can match the theme. */
val LocalGlassStrength = compositionLocalOf { GlassStrength.Standard }

/** Extra-small / small / medium / large radii already come from Glasmio's GlassDimensions. */
val ScreenCornerRadius = 34.dp
val CardCornerRadius = 26.dp
val ChipCornerRadius = 999.dp

@Composable
fun resolveDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/**
 * Root theme: Glasmio's iOS 27 token set plus the app's accent palette.
 *
 * `reduceTransparency` stays false — the whole point is the Liquid Glass material — but the
 * wallpaper is a controlled mesh gradient rather than a photo, so the refraction has large, smooth
 * areas to bend and stays legible at every text size.
 */
@Composable
fun HackIosTheme(
    mode: ThemeMode = ThemeMode.System,
    strength: GlassStrength = GlassStrength.Standard,
    content: @Composable () -> Unit,
) {
    val dark = resolveDark(mode)
    val accents = if (dark) IosAccentsDark else IosAccentsLight

    // Each step is a different amount of GPU work, not a tint (see GlassStrength). Adaptive colour
    // is off everywhere: the material is meant to be *white glass*, not a sample of whatever is
    // behind it, so the surface never picks up the wallpaper's blue/violet.
    //
    // `reduceTransparency` is false at every strength, and that is not cosmetic. Glasmio draws a
    // non-sampling material at alpha = 1 (GlassSurfaceKt: `if (reduceTransparency) color.copy(
    // alpha = 1f)`), and our container is *white* -- so choosing 弱 used to paint solid white
    // cards, with the dark theme's white text on top of them. The whole app read as blank panels.
    // 弱 means "no blur, no refraction", which `liquidGlassEnabled = false` already says; the fill
    // stays translucent, so it still lets the wallpaper through and costs almost nothing.
    val appearance = remember(strength) {
        when (strength) {
            GlassStrength.Subtle -> GlassAppearance(
                liquidGlassEnabled = false,
                reduceTransparency = false,
                adaptiveColorScheme = false,
            )

            GlassStrength.Standard -> GlassAppearance(
                liquidGlassEnabled = true,
                reduceTransparency = false,
                adaptiveColorScheme = false,
            )

            GlassStrength.Strong -> GlassAppearance(
                liquidGlassEnabled = true,
                reduceTransparency = false,
                adaptiveColorScheme = false,
            )
        }
    }

    // Pure white glass, tinted by nothing. Only the opacity changes between strengths, and a
    // hairline white stroke keeps the panel readable against the near-white light wallpaper.
    val glassColors = remember(dark, strength) {
        val white = Color.White
        val container = when (strength) {
            GlassStrength.Subtle -> if (dark) 0.14f else 0.62f
            GlassStrength.Standard -> if (dark) 0.09f else 0.50f
            GlassStrength.Strong -> if (dark) 0.06f else 0.38f
        }
        val prominent = when (strength) {
            GlassStrength.Subtle -> if (dark) 0.22f else 0.78f
            GlassStrength.Standard -> if (dark) 0.15f else 0.66f
            GlassStrength.Strong -> if (dark) 0.11f else 0.54f
        }
        val base = defaultGlassColors(dark, accents.blue)
        base.copy(
            container = white.copy(alpha = container),
            prominentContainer = white.copy(alpha = prominent),
            subtleStroke = if (dark) white.copy(alpha = 0.26f) else white.copy(alpha = 0.92f),
            groupedBackground = white.copy(alpha = if (dark) 0.05f else 0.34f),
            elevatedBackground = white.copy(alpha = if (dark) 0.12f else 0.62f),
            // Separators follow the text colour so they stay visible on white-on-white, and are
            // not part of the glass material itself.
            separator = (if (dark) white else Color.Black).copy(alpha = if (dark) 0.12f else 0.07f),
        )
    }

    GlasmioTheme(
        isDark = dark,
        colorScheme = glasmioColorScheme(isDark = dark, accent = accents.blue),
        typography = glasmioTypography(),
        colors = glassColors,
        appearance = appearance,
    ) {
        CompositionLocalProvider(
            LocalIosAccents provides accents,
            LocalIsDark provides dark,
            LocalGlassStrength provides strength,
            content = content,
        )
    }
}

/**
 * The sampled backdrop every glass surface refracts.
 *
 * A neutral white/grey mesh, deliberately **uncoloured**: the glass itself is pure white, so the
 * backdrop only has to provide soft luminance steps for the refraction to bend. Tinting it blue or
 * violet was what made the panels look tinted — a white surface over a blue backdrop reads blue.
 *
 * **Rendered once into a bitmap.** `GlasmioBackdropHost` re-draws this layer whenever it re-samples
 * the backdrop, and four full-screen radial gradients per redraw is a measurable cost. Baking it to
 * a half-resolution bitmap turns every redraw into a single texture blit, and gradients this soft
 * lose nothing when upscaled.
 */
@Composable
fun IosWallpaper(
    modifier: Modifier = Modifier,
    dark: Boolean,
    strength: GlassStrength = GlassStrength.Standard,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val bitmap = remember(size, dark, strength) {
        if (size.width <= 0 || size.height <= 0) null
        else renderWallpaper(size.width, size.height, dark, strength)
    }
    Box(modifier.fillMaxSize().onSizeChanged { size = it }) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

/** Half-resolution bake: soft gradients lose nothing, and the blit gets 4x cheaper. */
private fun renderWallpaper(width: Int, height: Int, dark: Boolean, strength: GlassStrength): ImageBitmap {
    val w = (width / 2).coerceAtLeast(1)
    val h = (height / 2).coerceAtLeast(1)
    val boost = when (strength) {
        GlassStrength.Subtle -> 0.75f
        GlassStrength.Standard -> 1.00f
        GlassStrength.Strong -> 1.25f
    }

    val baseTop = if (dark) Color(0xFF101215) else Color(0xFFF7F8FA)
    val baseBottom = if (dark) Color(0xFF05060A) else Color(0xFFE4E7EE)
    val high = if (dark) Color(0xFF3A3F47) else Color(0xFFFFFFFF)
    val low = if (dark) Color(0xFF1B1E23) else Color(0xFFD3D8E2)

    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)

    paint.shader = LinearGradient(
        0f, 0f, 0f, h.toFloat(),
        intArrayOf(baseTop.toArgb(), baseBottom.toArgb()),
        null,
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

    fun blob(cxF: Float, cyF: Float, rF: Float, color: Color, alpha: Float) {
        paint.shader = RadialGradient(
            w * cxF, h * cyF, w * rF,
            color.copy(alpha = (alpha * boost).coerceIn(0f, 1f)).toArgb(),
            Color.Transparent.toArgb(),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(w * cxF, h * cyF, w * rF, paint)
    }

    // Two soft steps are enough for the refraction to bite; fewer draws than four blobs too.
    blob(0.22f, 0.16f, 0.85f, high, if (dark) 0.55f else 0.85f)
    blob(0.78f, 0.82f, 0.80f, low, if (dark) 0.65f else 0.70f)

    return bmp.asImageBitmap()
}
