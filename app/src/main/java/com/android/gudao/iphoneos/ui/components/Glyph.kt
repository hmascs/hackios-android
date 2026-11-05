package com.android.gudao.iphoneos.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A small set of SF-Symbol-shaped line glyphs drawn as vectors.
 *
 * Glasmio ships 36 semantic symbols; the emulator needs a handful it does not carry (power, lock,
 * volume, display, CPU, network, folder, terminal). Drawing them here keeps the 1.75 pt rounded
 * stroke that makes iOS iconography look like iOS, instead of falling back to Material's filled
 * shapes.
 */
enum class Glyph {
    Power, Lock, VolumeUp, VolumeDown, Screen, Cpu, Network, Folder, Terminal, Doc,
    ChevronRight, Check, SwipeUp, Circle, Download, Port, Send, Stop,
}

@Composable
fun IosGlyph(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = LocalContentColor.current,
    stroke: Dp = 1.8.dp,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val st = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = st.width, cap = StrokeCap.Round)

        fun arc(l: Float, t: Float, w: Float, h: Float, start: Float, sweep: Float) =
            drawArc(
                color = tint,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = p(l, t),
                size = Size(w * s, h * s),
                style = st,
            )

        when (glyph) {
            Glyph.Power -> {
                arc(3f, 3f, 18f, 18f, 128f, 284f)
                line(12f, 2.6f, 12f, 11.5f)
            }

            Glyph.Lock -> {
                drawRoundRect(
                    color = tint,
                    topLeft = p(4.5f, 10.5f),
                    size = Size(15f * s, 10.5f * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.5f * s, 3.5f * s),
                    style = st,
                )
                arc(7.5f, 2.5f, 9f, 12f, 180f, 180f)
            }

            Glyph.VolumeUp, Glyph.VolumeDown -> {
                val body = Path().apply {
                    moveTo(4f * s, 9.2f * s)
                    lineTo(7.4f * s, 9.2f * s)
                    lineTo(11.4f * s, 5.2f * s)
                    lineTo(11.4f * s, 18.8f * s)
                    lineTo(7.4f * s, 14.8f * s)
                    lineTo(4f * s, 14.8f * s)
                    close()
                }
                drawPath(body, tint, style = st)
                if (glyph == Glyph.VolumeUp) {
                    arc(10.6f, 6.5f, 7.5f, 11f, -55f, 110f)
                    arc(10.6f, 3.5f, 12.5f, 17f, -55f, 110f)
                } else {
                    line(15.5f, 12f, 20.5f, 12f)
                }
            }

            Glyph.Screen -> {
                drawRoundRect(
                    color = tint,
                    topLeft = p(2.6f, 3.6f),
                    size = Size(18.8f * s, 13.4f * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * s, 3f * s),
                    style = st,
                )
                line(9f, 20.4f, 15f, 20.4f)
                line(12f, 17f, 12f, 20.4f)
            }

            Glyph.Cpu -> {
                drawRoundRect(
                    color = tint,
                    topLeft = p(6f, 6f),
                    size = Size(12f * s, 12f * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * s, 3f * s),
                    style = st,
                )
                drawRoundRect(
                    color = tint,
                    topLeft = p(9.6f, 9.6f),
                    size = Size(4.8f * s, 4.8f * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f * s, 1.2f * s),
                    style = st,
                )
                listOf(9f, 12f, 15f).forEach { v ->
                    line(v, 3f, v, 6f); line(v, 18f, v, 21f)
                    line(3f, v, 6f, v); line(18f, v, 21f, v)
                }
            }

            Glyph.Network -> {
                arc(2.5f, 5.5f, 19f, 15f, -140f, 100f)
                arc(6.2f, 10.2f, 11.6f, 10f, -140f, 100f)
                drawCircle(tint, radius = 1.6f * s, center = p(12f, 18.4f))
                line(12f, 18.4f, 12f, 18.4f)
            }

            Glyph.Folder -> {
                val path = Path().apply {
                    moveTo(3f * s, 7.5f * s)
                    lineTo(3f * s, 18.5f * s)
                    lineTo(21f * s, 18.5f * s)
                    lineTo(21f * s, 8.5f * s)
                    lineTo(11.5f * s, 8.5f * s)
                    lineTo(9.5f * s, 6f * s)
                    lineTo(3f * s, 6f * s)
                    close()
                }
                drawPath(path, tint, style = st)
            }

            Glyph.Terminal -> {
                drawRoundRect(
                    color = tint,
                    topLeft = p(2.6f, 4f),
                    size = Size(18.8f * s, 16f * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.2f * s, 3.2f * s),
                    style = st,
                )
                line(6.5f, 9.5f, 9.5f, 12f)
                line(9.5f, 12f, 6.5f, 14.5f)
                line(12.5f, 15f, 17f, 15f)
            }

            Glyph.Doc -> {
                val path = Path().apply {
                    moveTo(5.5f * s, 2.6f * s)
                    lineTo(14f * s, 2.6f * s)
                    lineTo(18.5f * s, 7f * s)
                    lineTo(18.5f * s, 21.2f * s)
                    lineTo(5.5f * s, 21.2f * s)
                    close()
                }
                drawPath(path, tint, style = st)
                line(13.8f, 2.8f, 13.8f, 7.4f)
                line(13.8f, 7.4f, 18.2f, 7.4f)
            }

            Glyph.ChevronRight -> {
                line(9.5f, 5.5f, 16f, 12f)
                line(16f, 12f, 9.5f, 18.5f)
            }

            Glyph.Check -> {
                line(5f, 12.5f, 10f, 17.2f)
                line(10f, 17.2f, 19f, 6.8f)
            }

            Glyph.SwipeUp -> {
                line(12f, 19f, 12f, 5.5f)
                line(7.5f, 10f, 12f, 5.2f)
                line(16.5f, 10f, 12f, 5.2f)
                line(5f, 21.5f, 19f, 21.5f)
            }

            Glyph.Download -> {
                line(12f, 3.5f, 12f, 15f)
                line(7.5f, 10.8f, 12f, 15.2f)
                line(16.5f, 10.8f, 12f, 15.2f)
                line(4.5f, 19f, 19.5f, 19f)
            }

            Glyph.Port -> {
                // 四角星：对外端口 / 连接的通用标记。用描边而不是填充，和其余图标同一个笔触重量。
                val star = Path().apply {
                    moveTo(11f * s, 3.2f * s)
                    lineTo(12.9f * s, 8.6f * s)
                    lineTo(18.3f * s, 10.5f * s)
                    lineTo(12.9f * s, 12.4f * s)
                    lineTo(11f * s, 17.8f * s)
                    lineTo(9.1f * s, 12.4f * s)
                    lineTo(3.7f * s, 10.5f * s)
                    lineTo(9.1f * s, 8.6f * s)
                    close()
                }
                drawPath(star, tint, style = st)
                line(18.4f, 15.6f, 18.4f, 20.4f)
                line(16f, 18f, 20.8f, 18f)
            }

            Glyph.Send -> {
                line(12f, 19f, 12f, 5.4f)
                line(7.2f, 10.2f, 12f, 5.2f)
                line(16.8f, 10.2f, 12f, 5.2f)
            }

            Glyph.Stop -> drawRoundRect(
                color = tint,
                topLeft = p(7f, 7f),
                size = Size(10f * s, 10f * s),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.4f * s, 2.4f * s),
            )

            Glyph.Circle -> drawCircle(tint, radius = 8.5f * s, center = p(12f, 12f), style = st)
        }
    }
}

private fun DrawScope.dot(color: Color, x: Float, y: Float, r: Float) =
    drawCircle(color, radius = r, center = Offset(x, y))
