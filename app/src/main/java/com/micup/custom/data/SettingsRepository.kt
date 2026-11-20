package com.micup.custom.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 主题模式 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色")
}

/** 界面材质 */
enum class MaterialStyle(val label: String, val desc: String) {
    LIQUID_GLASS("液态玻璃", "半透明磨砂 + 大圆角，氛围光斑背景，通透现代"),
    MATERIAL3("Material 3", "纯色表面、无阴影，克制简洁")
}

/** 采集来源 */
enum class CaptureSourceMode(val label: String, val desc: String) {
    MICROPHONE("麦克风", "采集机身麦克风输入，实时处理后输出（建议佩戴耳机避免啸叫）"),
    INTERNAL_PLAYBACK("系统内录", "经系统投屏授权捕获媒体播放音频，需 Android 10+"),
    SHIZUKU_ENHANCED("Shizuku 增强", "Shizuku 授权后 shell 级预授权：麦克风免弹窗、投屏授权自动通过（失败自动回退）")
}

/** 预设音效：与三段 EQ 叠加生效 */
enum class EffectPreset(val label: String, val low: Float, val mid: Float, val high: Float, val desc: String) {
    NATURAL("原声", 0f, 0f, 0f, "不加修饰，直出原始音色"),
    RADIO("电台音", -14f, 5f, -9f, "中频凸起 + 削减高低频，复古收音机质感"),
    DEEP("低沉音", 7f, -3f, -5f, "低频增厚，整体压暗，声音更沉"),
    BRIGHT("清亮音", -4f, 2f, 6f, "高频提亮，人声更通透")
}

/** 可视化样式 */
enum class VisualizerStyle(val label: String) {
    WAVEFORM("实时波形"), SPECTRUM("频谱柱")
}

/** 缓冲档位（倍数作用于系统最小缓冲） */
enum class BufferPreset(val label: String, val multiplier: Int, val desc: String) {
    LOW("低延迟", 2, "x2 最小缓冲：响应最快，弱设备偶发抖动"),
    BALANCED("均衡", 4, "x4 最小缓冲：延迟与稳定性兼顾"),
    STABLE("稳定", 8, "x8 最小缓冲：最稳，但延迟最高")
}

/** 全量设置模型 */
data class MicUpSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val materialStyle: MaterialStyle = MaterialStyle.LIQUID_GLASS,
    val captureSource: CaptureSourceMode = CaptureSourceMode.INTERNAL_PLAYBACK,
    val preset: EffectPreset = EffectPreset.NATURAL,
    val gainDb: Float = 0f,
    val eqLowDb: Float = 0f,
    val eqMidDb: Float = 0f,
    val eqHighDb: Float = 0f,
    val monitorEnabled: Boolean = true,
    val visualizerStyle: VisualizerStyle = VisualizerStyle.WAVEFORM,
    val bufferPreset: BufferPreset = BufferPreset.BALANCED,
    val rememberTuning: Boolean = true,
    val keepScreenOn: Boolean = false,
    val shizukuEnhance: Boolean = false
)

private val Context.dataStore by preferencesDataStore(name = "micup_settings")

object SettingsKeys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val MATERIAL_STYLE = stringPreferencesKey("material_style")
    val CAPTURE_SOURCE = stringPreferencesKey("capture_source")
    val PRESET = stringPreferencesKey("effect_preset")
    val GAIN_DB = floatPreferencesKey("gain_db")
    val EQ_LOW = floatPreferencesKey("eq_low_db")
    val EQ_MID = floatPreferencesKey("eq_mid_db")
    val EQ_HIGH = floatPreferencesKey("eq_high_db")
    val MONITOR = booleanPreferencesKey("monitor_enabled")
    val VISUALIZER = stringPreferencesKey("visualizer_style")
    val BUFFER = stringPreferencesKey("buffer_preset")
    val REMEMBER_TUNING = booleanPreferencesKey("remember_tuning")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    val SHIZUKU_ENHANCE = booleanPreferencesKey("shizuku_enhance")
}

/** DataStore 持久化仓库：所有设置项跨启动保存 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<MicUpSettings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings(): MicUpSettings {
        val d = MicUpSettings()
        return MicUpSettings(
            themeMode = getEnum(SettingsKeys.THEME_MODE, d.themeMode),
            materialStyle = getEnum(SettingsKeys.MATERIAL_STYLE, d.materialStyle),
            captureSource = getEnum(SettingsKeys.CAPTURE_SOURCE, d.captureSource),
            preset = getEnum(SettingsKeys.PRESET, d.preset),
            gainDb = this[SettingsKeys.GAIN_DB] ?: d.gainDb,
            eqLowDb = this[SettingsKeys.EQ_LOW] ?: d.eqLowDb,
            eqMidDb = this[SettingsKeys.EQ_MID] ?: d.eqMidDb,
            eqHighDb = this[SettingsKeys.EQ_HIGH] ?: d.eqHighDb,
            monitorEnabled = this[SettingsKeys.MONITOR] ?: d.monitorEnabled,
            visualizerStyle = getEnum(SettingsKeys.VISUALIZER, d.visualizerStyle),
            bufferPreset = getEnum(SettingsKeys.BUFFER, d.bufferPreset),
            rememberTuning = this[SettingsKeys.REMEMBER_TUNING] ?: d.rememberTuning,
            keepScreenOn = this[SettingsKeys.KEEP_SCREEN_ON] ?: d.keepScreenOn,
            shizukuEnhance = this[SettingsKeys.SHIZUKU_ENHANCE] ?: d.shizukuEnhance
        )
    }

    private inline fun <reified T : Enum<T>> Preferences.getEnum(key: Preferences.Key<String>, default: T): T =
        runCatching { this[key]?.let { enumValueOf<T>(it) } }.getOrNull() ?: default

    suspend fun update(s: MicUpSettings) {
        context.dataStore.edit { p ->
            p[SettingsKeys.THEME_MODE] = s.themeMode.name
            p[SettingsKeys.MATERIAL_STYLE] = s.materialStyle.name
            p[SettingsKeys.CAPTURE_SOURCE] = s.captureSource.name
            p[SettingsKeys.PRESET] = s.preset.name
            p[SettingsKeys.GAIN_DB] = s.gainDb
            p[SettingsKeys.EQ_LOW] = s.eqLowDb
            p[SettingsKeys.EQ_MID] = s.eqMidDb
            p[SettingsKeys.EQ_HIGH] = s.eqHighDb
            p[SettingsKeys.MONITOR] = s.monitorEnabled
            p[SettingsKeys.VISUALIZER] = s.visualizerStyle.name
            p[SettingsKeys.BUFFER] = s.bufferPreset.name
            p[SettingsKeys.REMEMBER_TUNING] = s.rememberTuning
            p[SettingsKeys.KEEP_SCREEN_ON] = s.keepScreenOn
            p[SettingsKeys.SHIZUKU_ENHANCE] = s.shizukuEnhance
        }
    }
}
