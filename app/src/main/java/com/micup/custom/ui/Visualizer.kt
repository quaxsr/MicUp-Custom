package com.micup.custom.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.micup.custom.audio.AudioEngine
import com.micup.custom.audio.EngineFrame
import com.micup.custom.data.VisualizerStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 音频可视化：实时波形 / 频谱柱。
 * 无数据时播放呼吸感待机动画，界面始终“活着”。
 */
@Composable
fun AudioVisualizer(
    frame: EngineFrame?,
    style: VisualizerStyle,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val idle = rememberInfiniteTransition(label = "idle")
    val idleT by idle.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(5000, easing = LinearEasing)),
        label = "idleT"
    )
    Canvas(modifier) {
        when (style) {
            VisualizerStyle.WAVEFORM -> drawWave(frame, idleT, cs)
            VisualizerStyle.SPECTRUM -> drawSpectrum(frame, idleT, cs)
        }
    }
}

private fun drawWave(scope: DrawScope, frame: EngineFrame?, idleT: Float, cs: ColorScheme) {
    val w = scope.size.width
    val h = scope.size.height
    val cy = h / 2f
    val src = frame?.wave
    val n = src?.size ?: 128
    val samples = FloatArray(n)
    if (src != null) {
        var mx = 0.03f
        for (i in 0 until n) {
            samples[i] = src[i]
            val a = abs(src[i])
            if (a > mx) mx = a
        }
        for (i in 0 until n) samples[i] = (samples[i] / mx).coerceIn(-1f, 1f)
    } else {
        for (i in 0 until n) {
            val phase = i * 0.16f + idleT * 2f * PI.toFloat() * 2f
            samples[i] = sin(phase) * (0.15f + 0.06f * sin(idleT * 2f * PI.toFloat() + i * 0.045f))
        }
    }
    val amp = h * 0.38f
    val line = Path()
    for (i in 0 until n) {
        val x = w * i / (n - 1).toFloat()
        val y = cy - samples[i] * amp
        if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }
    val fill = Path()
    fill.addPath(line)
    fill.lineTo(w, cy)
    fill.lineTo(0f, cy)
    fill.close()
    scope.drawPath(
        fill,
        Brush.verticalGradient(
            listOf(cs.primary.copy(alpha = 0.30f), Color.Transparent),
            startY = cy - amp,
            endY = cy + amp
        )
    )
    scope.drawPath(
        line,
        Brush.horizontalGradient(listOf(cs.primary, cs.tertiary)),
        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
    )
}

private fun drawSpectrum(scope: DrawScope, frame: EngineFrame?, idleT: Float, cs: ColorScheme) {
    val w = scope.size.width
    val h = scope.size.height
    val n = AudioEngine.SPEC_BARS
    val gap = 3.dp.toPx()
    val barW = (w - gap * (n - 1)) / n
    val maxHeight = h * 0.86f
    val base = 0.05f
    val spectrum = frame?.spectrum
    val peaks = frame?.peaks
    for (i in 0 until n) {
        val raw = spectrum?.get(i) ?: idleBar(i, idleT)
        val v = raw.coerceIn(0f, 1f)
        val bh = (base + v * 0.95f) * maxHeight
        val x = i * (barW + gap)
        val y0 = h - bh
        scope.drawRoundRect(
            brush = Brush.verticalGradient(listOf(cs.tertiary.copy(alpha = 0.9f), cs.primary), startY = y0, endY = h),
            topLeft = Offset(x, y0),
            size = Size(barW, bh),
            cornerRadius = CornerRadius(barW / 2f, barW / 2f)
        )
        val pk = (peaks?.get(i) ?: (v + 0.08f)).coerceIn(0f, 1f)
        val py = h - (base + pk * 0.95f) * maxHeight
        scope.drawRoundRect(
            color = cs.onSurfaceVariant.copy(alpha = 0.55f),
            topLeft = Offset(x, (py - 3.dp.toPx()).coerceAtLeast(0f)),
            size = Size(barW, 3.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
    }
}

private fun idleBar(i: Int, t: Float): Float {
    val a = sin(i * 0.42f + t * 2f * PI.toFloat()) * 0.5f + 0.5f
    val b = sin(i * 0.11f - t * 4f * PI.toFloat()) * 0.5f + 0.5f
    return 0.10f + 0.5f * a * b
}
