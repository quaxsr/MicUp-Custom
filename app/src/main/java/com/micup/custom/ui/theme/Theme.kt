package com.micup.custom.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.micup.custom.data.MaterialStyle
import com.micup.custom.data.ThemeMode

/** 当前界面材质，供玻璃组件自适应 */
val LocalMaterialStyle = staticCompositionLocalOf { MaterialStyle.LIQUID_GLASS }

// ————— 浅色：莫兰迪粉紫调（强制固定，显式禁用动态取色，不随壁纸变色） —————
private val LightColors = lightColorScheme(
    primary = Color(0xFFF8C8DC),        // 选中状态：柔和粉
    onPrimary = Color(0xFF4A3241),      // 浅粉底上用深玫瑰灰保证对比度
    primaryContainer = Color(0xFFFAD4E3),
    onPrimaryContainer = Color(0xFF432B38),
    secondary = Color(0xFFD9A2BE),      // 藕粉
    onSecondary = Color(0xFF46283A),
    secondaryContainer = Color(0xFFF3D7E3),
    onSecondaryContainer = Color(0xFF402836),
    tertiary = Color(0xFFB39BC9),       // 灰紫
    onTertiary = Color(0xFF332743),
    tertiaryContainer = Color(0xFFE6DCF0),
    onTertiaryContainer = Color(0xFF2C2240),
    background = Color(0xFFFDF5F7),     // 主背景：极浅柔粉
    onBackground = Color(0xFF332D2F),   // 主文字：深灰褐（非纯黑）
    surface = Color(0xFFFCE8ED),        // 卡片：浅藕粉
    onSurface = Color(0xFF332D2F),
    surfaceVariant = Color(0xFFF6DCE4),
    onSurfaceVariant = Color(0xFF6E5E66),
    outline = Color(0xFFC7B2BC),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DAD6),
    onErrorContainer = Color(0xFF410E0B)
)

// ————— 深色：深邃紫灰（冷调，去除暗红感） + 柔和醒目粉紫点缀 —————
private val DarkColors = darkColorScheme(
    primary = Color(0xFFE3A6D8),        // 选中状态：柔和醒目粉紫
    onPrimary = Color(0xFF3A1F3C),
    primaryContainer = Color(0xFF4A2C50),
    onPrimaryContainer = Color(0xFFF6D9EE),
    secondary = Color(0xFFC79BD4),      // 粉紫
    onSecondary = Color(0xFF33204A),
    secondaryContainer = Color(0xFF442E52),
    onSecondaryContainer = Color(0xFFF3DFF0),
    tertiary = Color(0xFFA99AE0),       // 蓝紫
    onTertiary = Color(0xFF2A2148),
    tertiaryContainer = Color(0xFF3C3158),
    onTertiaryContainer = Color(0xFFE2DBF5),
    background = Color(0xFF171320),     // 主背景：深邃紫灰
    onBackground = Color(0xFFEAE4EE),
    surface = Color(0xFF1F1A28),        // 卡片：深紫灰
    onSurface = Color(0xFFEAE4EE),
    surfaceVariant = Color(0xFF2A2333),
    onSurfaceVariant = Color(0xFFA89CB2),
    outline = Color(0xFF574B62),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DAD6)
)

/** 氛围光斑（液态玻璃背景）：浅色粉紫 / 深色冷调深紫 */
val LightAurora = listOf(Color(0xFFF7C9DB), Color(0xFFE5C4EF), Color(0xFFF3CBE0))
val DarkAurora = listOf(Color(0xFF523C86), Color(0xFF323468), Color(0xFF64418C))

private val MicUpTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 9.sp, letterSpacing = 0.4.sp)
)

private val LiquidShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(38.dp)
)

private val PlainShapes = Shapes()

@Composable
fun MicUpTheme(
    themeMode: ThemeMode,
    materialStyle: MaterialStyle,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    val shapes = if (materialStyle == MaterialStyle.LIQUID_GLASS) LiquidShapes else PlainShapes
    val view = LocalView.current

    DisposableEffect(dark) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
        }
        onDispose { }
    }

    CompositionLocalProvider(LocalMaterialStyle provides materialStyle) {
        MaterialTheme(
            colorScheme = colors,
            typography = MicUpTypography,
            shapes = shapes,
            content = content
        )
    }
}

/** 快捷判断当前是否深色（玻璃材质微调用） */
@Composable
fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
