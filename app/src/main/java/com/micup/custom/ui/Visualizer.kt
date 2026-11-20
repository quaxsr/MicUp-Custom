package com.micup.custom.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import kotlin.math.abs

/**
 * 音频可视化：实时波形 / 频谱柱。
 * 数据全部来自 AudioEngine 实时 PCM 环（EngineFrame：波形采样 + FFT 频谱 + RMS 电平）。
 * 无采集数据时不做任何伪造动画：波形呈平滑直线，频谱呈基线。
 */
@Composable
fun AudioVisualizer(
    frame: EngineFrame?,
    style: VisualizerStyle,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Canvas(modifier) {
        when (style) {
            VisualizerStyle.WAVEFORM -> drawWave(this, frame, cs)
            VisualizerStyle.SPECTRUM -> drawSpectrum(this, frame, cs)
        }
    }
}

private fun drawWave(scope: DrawScope, frame: EngineFrame?, cs: ColorScheme) {
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
    } // 无数据：samples 保持全 0，绘制结果即一条平滑直线（待机态）

    val amp = h * 0.38f
    val line = Path()
    for (i in 0 until n) {
        val x = w * i / (n - 1).toFloat()
        val y = cy - samples[i] * amp
        if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }
    if (src != null) {
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
    }
    scope.drawPath(
        line,
        Brush.horizontalGradient(listOf(cs.primary, cs.tertiary)),
        style = Stroke(width = with(scope) { 2.5.dp.toPx() }, cap = StrokeCap.Round)
    )
}

private fun drawSpectrum(scope: DrawScope, frame: EngineFrame?, cs: ColorScheme) {
    val w = scope.size.width
    val h = scope.size.height
    val n = AudioEngine.SPEC_BARS
    val gap = with(scope) { 3.dp.toPx() }
    val barW = (w - gap * (n - 1)) / n
    val maxHeight = h * 0.86f
    val base = 0.05f
    val spectrum = frame?.spectrum
    val peaks = frame?.peaks
    val gradient = Brush.verticalGradient(
        listOf(cs.tertiary.copy(alpha = 0.9f), cs.primary),
        startY = 0f,
        endY = h
    )
    val idleColor = cs.onSurfaceVariant.copy(alpha = 0.25f)
    for (i in 0 until n) {
        val hasData = spectrum != null
        val raw = spectrum?.get(i) ?: 0f
        val v = raw.coerceIn(0f, 1f)
        val bh = (base + v * 0.95f) * maxHeight
        val x = i * (barW + gap)
        val y0 = h - bh
        if (hasData) {
            scope.drawRoundRect(
                brush = gradient,
                topLeft = Offset(x, y0),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        } else {
            // 待机：仅基线高度，静态呈现
            scope.drawRoundRect(
                color = idleColor,
                topLeft = Offset(x, y0),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
        if (peaks != null) {
            val pk = peaks[i].coerceIn(0f, 1f)
            val py = h - (base + pk * 0.95f) * maxHeight
            scope.drawRoundRect(
                color = cs.onSurfaceVariant.copy(alpha = 0.55f),
                topLeft = Offset(x, (py - with(scope) { 3.dp.toPx() }).coerceAtLeast(0f)),
                size = Size(barW, with(scope) { 3.dp.toPx() }),
                cornerRadius = CornerRadius(with(scope) { 2.dp.toPx() }, with(scope) { 2.dp.toPx() })
            )
        }
    }
}
