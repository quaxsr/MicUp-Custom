package com.micup.custom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.micup.custom.data.BufferPreset
import com.micup.custom.data.CaptureSourceMode
import com.micup.custom.data.MaterialStyle
import com.micup.custom.data.MicUpSettings
import com.micup.custom.data.ThemeMode
import com.micup.custom.data.VisualizerStyle

/** 设置页：外观 / 可视化 / 采集与缓冲 / 行为 / 关于，全部即时持久化 */
@Composable
fun SettingsScreen(
    settings: MicUpSettings,
    onCommit: (MicUpSettings) -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("设置", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "全部选项即时保存，重启保留",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        GlassCard {
            SectionHeader("外观")
            Spacer(Modifier.height(12.dp))
            Text("主题模式", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                ThemeMode.entries.toList(),
                { it.label },
                settings.themeMode,
                onSelect = { onCommit(settings.copy(themeMode = it)) }
            )
            Spacer(Modifier.height(14.dp))
            Text("界面材质", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                MaterialStyle.entries.toList(),
                { it.label },
                settings.materialStyle,
                onSelect = { onCommit(settings.copy(materialStyle = it)) }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                settings.materialStyle.desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))

        GlassCard {
            SectionHeader("可视化")
            Spacer(Modifier.height(12.dp))
            ChoiceRow(
                VisualizerStyle.entries.toList(),
                { it.label },
                settings.visualizerStyle,
                onSelect = { onCommit(settings.copy(visualizerStyle = it)) }
            )
        }
        Spacer(Modifier.height(12.dp))

        GlassCard {
            SectionHeader("采集与缓冲")
            Spacer(Modifier.height(12.dp))
            Text("默认采集源", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                CaptureSourceMode.entries.toList(),
                { it.label },
                settings.captureSource,
                onSelect = { onCommit(settings.copy(captureSource = it)) }
            )
            Spacer(Modifier.height(12.dp))
            Text("缓冲档位", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                BufferPreset.entries.toList(),
                { it.label },
                settings.bufferPreset,
                onSelect = { onCommit(settings.copy(bufferPreset = it)) }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                settings.bufferPreset.desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))

        GlassCard {
            SectionHeader("行为")
            Spacer(Modifier.height(6.dp))
            GlassSwitchRow(
                title = "保持屏幕常亮",
                subtitle = "运行期间不熄屏，避免后台采集被打断",
                checked = settings.keepScreenOn,
                onCheckedChange = { onCommit(settings.copy(keepScreenOn = it)) }
            )
            GlassSwitchRow(
                title = "记住音效调节",
                subtitle = "与主界面输出路由中的开关同步",
                checked = settings.rememberTuning,
                onCheckedChange = { onCommit(settings.copy(rememberTuning = it)) }
            )
        }
        Spacer(Modifier.height(12.dp))

        GlassCard {
            SectionHeader("关于")
            Spacer(Modifier.height(10.dp))
            AboutRow("版本", "1.0.0 (1)")
            AboutRow("技术栈", "Jetpack Compose · DataStore · Shizuku")
            AboutRow("采样规格", "48kHz / 单声道 / PCM Float")
            Spacer(Modifier.height(8.dp))
            Text(
                "系统内录基于 MediaProjection（Android 10+）；Shizuku 增强用于 shell 级预授权加速。" +
                    "无 root 设备上，第三方应用无法直接注入其他应用的麦克风通道，相关路由能力属实验特性。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
