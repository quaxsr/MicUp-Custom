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

// ————— 浅色：低饱和粉紫调 —————
private val LightColors = lightColorScheme(
    primary = Color(0xFF8D5FA6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEFDCF6),
    onPrimaryContainer = Color(0xFF311B3D),
    secondary = Color(0xFFB36E8F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFADEEC),
    onSecondaryContainer = Color(0xFF3A1F2E),
    tertiary = Color(0xFF6E8CB8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE7F9),
    onTertiaryContainer = Color(0xFF1C3149),
    background = Color(0xFFFAF6FB),
    onBackground = Color(0xFF37303F),
    surface = Color(0xFFFDFAFE),
    onSurface = Color(0xFF37303F),
    surfaceVariant = Color(0xFFF0E7F3),
    onSurfaceVariant = Color(0xFF675C70),
    outline = Color(0xFFB9ABC1),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DAD6),
    onErrorContainer = Color(0xFF410E0B)
)

// ————— 深色：深蓝灰底 + 紫色点缀 —————
private val DarkColors = darkColorScheme(
    primary = Color(0xFFBDA0F0),
    onPrimary = Color(0xFF261145),
    primaryContainer = Color(0xFF3D2A64),
    onPrimaryContainer = Color(0xFFEBDFFF),
    secondary = Color(0xFFDCAFCB),
    onSecondary = Color(0xFF3B1F31),
    secondaryContainer = Color(0xFF532F46),
    onSecondaryContainer = Color(0xFFFADEEC),
    tertiary = Color(0xFFA8C4F0),
    onTertiary = Color(0xFF173252),
    tertiaryContainer = Color(0xFF304968),
    onTertiaryContainer = Color(0xFFD6E4FA),
    background = Color(0xFF12151E),
    onBackground = Color(0xFFE5E2EE),
    surface = Color(0xFF181C27),
    onSurface = Color(0xFFE5E2EE),
    surfaceVariant = Color(0xFF242B3C),
    onSurfaceVariant = Color(0xFFA9A4BD),
    outline = Color(0xFF5B5675),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DAD6)
)

/** 氛围光斑（液态玻璃背景） */
val LightAurora = listOf(Color(0xFFF6C6E2), Color(0xFFDCC3F4), Color(0xFFC6DCF6))
val DarkAurora = listOf(Color(0xFF5B3F9E), Color(0xFF2F3F76), Color(0xFF7C3F86))

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
