package com.micup.custom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micup.custom.audio.EngineBus
import com.micup.custom.audio.EngineFrame
import com.micup.custom.audio.EngineState
import com.micup.custom.data.CaptureSourceMode
import com.micup.custom.data.EffectPreset
import com.micup.custom.data.MicUpSettings
import com.micup.custom.data.VisualizerStyle
import com.micup.custom.shizuku.ShizukuStatus
import kotlin.math.roundToInt

/** 主控台：采集源 / 引擎状态与可视化 / 音频调节 / 输出路由 */
@Composable
fun MainScreen(
    settings: MicUpSettings,
    onPreview: (MicUpSettings) -> Unit,
    onCommit: (MicUpSettings) -> Unit,
    engineState: EngineState,
    frame: EngineFrame?,
    shizuku: ShizukuStatus,
    onStart: (CaptureSourceMode) -> Unit,
    onStop: () -> Unit,
    onShizukuAuth: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        HeaderRow(engineState, onOpenSettings)
        Spacer(Modifier.height(14.dp))

        ErrorBanner(engineState)

        SourceCard(settings, engineState, shizuku, onStart, onShizukuAuth)
        Spacer(Modifier.height(12.dp))
        EngineCard(settings, engineState, frame, onStart, onStop)
        Spacer(Modifier.height(12.dp))
        TuningCard(settings, onPreview, onCommit)
        Spacer(Modifier.height(12.dp))
        OutputCard(settings, onCommit)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HeaderRow(engineState: EngineState, onOpenSettings: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "MicUp",
                style = TextStyle(
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    brush = Brush.horizontalGradient(listOf(cs.primary, cs.secondary))
                )
            )
            Text(
                "系统音频 · 内录 · 音效路由台",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }
        StatusPill(statusText(engineState), engineState is EngineState.Running)
        Spacer(Modifier.width(10.dp))
        GlassIconButton(Icons.Filled.Settings, "设置", onOpenSettings)
    }
}

private fun statusText(state: EngineState): String = when (state) {
    is EngineState.Running -> "运行中"
    is EngineState.Starting -> "启动中"
    is EngineState.Error -> "已停止"
    else -> "待命"
}

@Composable
private fun ErrorBanner(state: EngineState) {
    if (state !is EngineState.Error) return
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cs.errorContainer.copy(alpha = 0.9f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = cs.onErrorContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        Text(
            "知道了",
            modifier = Modifier.clickable { EngineBus.state.value = EngineState.Idle },
            color = cs.onErrorContainer,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SourceCard(
    settings: MicUpSettings,
    engineState: EngineState,
    shizuku: ShizukuStatus,
    onStart: (CaptureSourceMode) -> Unit,
    onShizukuAuth: () -> Unit
) {
    val running = engineState is EngineState.Running
    GlassCard {
        SectionHeader("采集源")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceOption(CaptureSourceMode.MICROPHONE, Icons.Filled.Mic, settings.captureSource, running, Modifier.weight(1f), onSelect = onStart)
            SourceOption(CaptureSourceMode.INTERNAL_PLAYBACK, Icons.Filled.GraphicEq, settings.captureSource, running, Modifier.weight(1f), onSelect = onStart)
            SourceOption(CaptureSourceMode.SHIZUKU_ENHANCED, Icons.Filled.Terminal, settings.captureSource, running, Modifier.weight(1f), onSelect = onStart)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            settings.captureSource.desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (settings.captureSource != CaptureSourceMode.MICROPHONE) {
            Spacer(Modifier.height(6.dp))
            Text(
                "开始时将请求系统投屏（MediaProjection）授权；仅采集系统内播放的音频。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (settings.captureSource == CaptureSourceMode.SHIZUKU_ENHANCED) {
            Spacer(Modifier.height(12.dp))
            ShizukuInline(shizuku, onShizukuAuth)
        }
    }
}

@Composable
private fun RowScope.SourceOption(
    mode: CaptureSourceMode,
    icon: ImageVector,
    current: CaptureSourceMode,
    disabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (CaptureSourceMode) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val selected = mode == current
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(
                when {
                    selected -> Brush.verticalGradient(listOf(cs.primary.copy(alpha = 0.92f), cs.secondary.copy(alpha = 0.78f)))
                    else -> SolidColor(cs.surfaceVariant.copy(alpha = 0.6f))
                }
            )
            .clickable(enabled = !disabled) { onSelect(mode) }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = mode.label, tint = if (selected) Color.White else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            mode.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else cs.onSurface
        )
    }
}

@Composable
private fun ShizukuInline(status: ShizukuStatus, onAuth: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cs.primary.copy(alpha = 0.10f))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    !status.installed -> "未检测到 Shizuku（可从 shizuku.rikka.app 安装）"
                    !status.binderAlive -> "Shizuku 未运行，请先启动 Shizuku 服务"
                    !status.authorized -> "等待 Shizuku 授权"
                    else -> "Shizuku 已就绪（API v${status.version}）"
                },
                style = MaterialTheme.typography.labelLarge,
                color = cs.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (status.installed && status.binderAlive && !status.authorized) {
                Text(
                    "去授权",
                    modifier = Modifier.clickable(onClick = onAuth),
                    color = cs.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text(
            "授权后自动执行 shell 级预授权：麦克风免弹窗直授，投屏授权多数 ROM 自动通过；失败将回退标准授权流程。",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant
        )
    }
}

@Composable
private fun EngineCard(
    settings: MicUpSettings,
    engineState: EngineState,
    frame: EngineFrame?,
    onStart: (CaptureSourceMode) -> Unit,
    onStop: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val running = engineState is EngineState.Running
    GlassCard {
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            AudioVisualizer(frame, settings.visualizerStyle, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val db = frame?.rmsDb ?: -120f
            Text(
                if (running) "输出电平 %+.1f dB".format(db.coerceIn(-60f, 6f)) else "待机中",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant
            )
            val state = engineState
            if (state is EngineState.Running) {
                Text(
                    "${state.source.label} · ${state.sampleRate / 1000}kHz",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.primary,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Text("48kHz · 单声道", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        GlowButton(
            text = if (running) "停止处理" else "开始处理",
            container = if (running) {
                Brush.horizontalGradient(listOf(Color(0xFF5D6780), Color(0xFF3C4459)))
            } else {
                Brush.horizontalGradient(listOf(cs.primary, cs.secondary))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = engineState !is EngineState.Starting,
            onClick = { if (running) onStop() else onStart(settings.captureSource) }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (running) "处理流水线运行中，所有参数调节实时生效"
            else "先选择上方采集源，再点开始；首次使用需完成系统授权",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TuningCard(
    settings: MicUpSettings,
    onPreview: (MicUpSettings) -> Unit,
    onCommit: (MicUpSettings) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    GlassCard {
        SectionHeader("音频调节")
        Spacer(Modifier.height(12.dp))
        GlassSlider(
            value = settings.gainDb,
            onValueChange = { onPreview(settings.copy(gainDb = ((it * 2f).roundToInt()) / 2f)) },
            valueRange = -24f..12f,
            label = "总增益",
            valueText = "%+.1f dB".format(settings.gainDb),
            accent = cs.primary,
            onFinish = { onCommit(settings) }
        )
        Spacer(Modifier.height(6.dp))
        GlassSlider(
            value = settings.eqLowDb,
            onValueChange = { onPreview(settings.copy(eqLowDb = it.roundToInt().toFloat())) },
            valueRange = -15f..15f,
            label = "低频 · 120Hz",
            valueText = "%+.0f dB".format(settings.eqLowDb),
            accent = cs.tertiary,
            onFinish = { onCommit(settings) }
        )
        Spacer(Modifier.height(6.dp))
        GlassSlider(
            value = settings.eqMidDb,
            onValueChange = { onPreview(settings.copy(eqMidDb = it.roundToInt().toFloat())) },
            valueRange = -15f..15f,
            label = "中频 · 1.2kHz",
            valueText = "%+.0f dB".format(settings.eqMidDb),
            accent = cs.primary,
            onFinish = { onCommit(settings) }
        )
        Spacer(Modifier.height(6.dp))
        GlassSlider(
            value = settings.eqHighDb,
            onValueChange = { onPreview(settings.copy(eqHighDb = it.roundToInt().toFloat())) },
            valueRange = -15f..15f,
            label = "高频 · 6kHz",
            valueText = "%+.0f dB".format(settings.eqHighDb),
            accent = cs.secondary,
            onFinish = { onCommit(settings) }
        )
        Spacer(Modifier.height(16.dp))
        SectionHeader("预设音效")
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EffectPreset.entries.forEach { p ->
                PresetChip(
                    text = p.label,
                    selected = settings.preset == p,
                    onClick = { onCommit(settings.copy(preset = p)) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            settings.preset.desc,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "预设与三段 EQ 叠加生效；拖动滑杆可继续微调，松手自动保存。",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant
        )
    }
}

@Composable
private fun OutputCard(settings: MicUpSettings, onCommit: (MicUpSettings) -> Unit) {
    GlassCard {
        SectionHeader("输出路由")
        Spacer(Modifier.height(6.dp))
        GlassSwitchRow(
            title = "外放监听",
            subtitle = "处理后的音频实时经扬声器/耳机输出（监听用途）",
            checked = settings.monitorEnabled,
            onCheckedChange = { onCommit(settings.copy(monitorEnabled = it)) }
        )
        GlassSwitchRow(
            title = "记住音效调节",
            subtitle = "关闭后每次启动回到默认参数",
            checked = settings.rememberTuning,
            onCheckedChange = { onCommit(settings.copy(rememberTuning = it)) }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "提示：无 root 时系统不允许第三方直接注入其他应用的麦克风通道；监听输出走系统提示音通道，避免被自身内录再次捕获造成回授。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
