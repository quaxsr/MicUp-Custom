package com.micup.custom.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageVector
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micup.custom.data.MaterialStyle
import com.micup.custom.ui.theme.DarkAurora
import com.micup.custom.ui.theme.LightAurora
import com.micup.custom.ui.theme.LocalMaterialStyle
import com.micup.custom.ui.theme.isDarkTheme
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** 氛围光斑背景：液态玻璃材质专属；M3 材质回落纯色 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    if (LocalMaterialStyle.current != MaterialStyle.LIQUID_GLASS) {
        Box(modifier.background(MaterialTheme.colorScheme.background))
        return
    }
    val dark = isDarkTheme()
    val colors = if (dark) DarkAurora else LightAurora
    val alphaScale = if (dark) 0.5f else 0.75f
    val transition = rememberInfiniteTransition(label = "aurora")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(18000, easing = LinearEasing)),
        label = "auroraT"
    )
    val angle = t * 2f * PI.toFloat()

    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        AuroraBlob(colors[0], 420.dp, 380.dp, Alignment(-0.55f, -0.65f), angle, 0.0f, 46.dp, 34.dp, alphaScale * 0.9f)
        AuroraBlob(colors[1], 460.dp, 420.dp, Alignment(0.45f, -0.35f), angle, 2.1f, -38.dp, 30.dp, alphaScale * 0.8f)
        AuroraBlob(colors[2], 400.dp, 460.dp, Alignment(0.1f, 0.6f), angle, 4.2f, 30.dp, -26.dp, alphaScale * 0.7f)
    }
}

@Composable
private fun AuroraBlob(
    color: Color,
    width: Dp,
    height: Dp,
    anchor: Alignment,
    angle: Float,
    phase: Float,
    driftX: Dp,
    driftY: Dp,
    alpha: Float
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(anchor)
                .offset(x = driftX * sin(angle + phase), y = driftY * sin(angle * 0.6f + phase + 1.3f))
                .size(width, height)
                .alpha(alpha)
                .background(Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0f))))
        )
    }
}

/** 卡片容器：液态玻璃（半透明 + 大圆角 + 描边高光）或 M3 纯色无阴影，自动适配材质 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val resolved = shape ?: MaterialTheme.shapes.large
    when (LocalMaterialStyle.current) {
        MaterialStyle.MATERIAL3 -> {
            Surface(
                modifier = modifier,
                shape = resolved,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(Modifier.padding(contentPadding), content = content)
            }
        }
        MaterialStyle.LIQUID_GLASS -> {
            val dark = isDarkTheme()
            val fill = if (dark) {
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f)))
            } else {
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.62f), Color.White.copy(alpha = 0.34f)))
            }
            val border = if (dark) {
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.05f)))
            } else {
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0.28f)))
            }
            Box(
                modifier
                    .clip(resolved)
                    .background(fill, resolved)
                    .border(1.dp, border, resolved)
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.White.copy(alpha = if (dark) 0.06f else 0.14f),
                                0.35f to Color.Transparent
                            ),
                            resolved
                        )
                )
                Column(Modifier.padding(contentPadding), content = content)
            }
        }
    }
}

/** 玻璃质感滑杆：拖动实时回调，手势结束触发 onFinish（用于持久化） */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    onFinish: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    val dark = isDarkTheme()
    val span = valueRange.endInclusive - valueRange.start
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    var trackWidth by remember { mutableFloatStateOf(1f) }
    var pressed by remember { mutableStateOf(false) }
    val thumbScale by animateFloatAsState(if (pressed) 1.25f else 1f, label = "thumbScale")

    // pointerInput 只捕获首次组合的 lambda，用 rememberUpdatedState 保证回调始终最新
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinish by rememberUpdatedState(onFinish)

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .onSizeChanged { trackWidth = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(valueRange) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        down.consume()
                        val f0 = (down.position.x / trackWidth).coerceIn(0f, 1f)
                        currentOnChange(valueRange.start + f0 * span)
                        drag(down.id) { change ->
                            change.consume()
                            val f = (change.position.x / trackWidth).coerceIn(0f, 1f)
                            currentOnChange(valueRange.start + f * span)
                        }
                        pressed = false
                        currentOnFinish?.invoke()
                    }
                }
        ) {
            val corner = RoundedCornerShape(5.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .align(Alignment.CenterStart)
                    .clip(corner)
                    .background(if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f))
            )
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .align(Alignment.CenterStart)
                    .clip(corner)
                    .background(Brush.horizontalGradient(listOf(cs.tertiary, accent)))
            )
            val thumb = 20.dp
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(((fraction * trackWidth) - thumb.toPx() / 2f * thumbScale).roundToInt(), 0) }
                    .graphicsLayer {
                        scaleX = thumbScale
                        scaleY = thumbScale
                    }
                    .size(thumb)
                    .shadow(6.dp, CircleShape, clip = false, ambientColor = accent, spotColor = accent)
                    .clip(CircleShape)
                    .background(if (dark) Color(0xFFEDE6FF) else Color.White)
                    .border(2.dp, accent.copy(alpha = 0.45f), CircleShape)
            )
        }
    }
}

/** 玻璃卡片内的开关行 */
@Composable
fun GlassSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 分组标题 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp
    )
}

/** 状态胶囊 */
@Composable
fun StatusPill(text: String, active: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(
                if (active) Brush.horizontalGradient(listOf(cs.primary, cs.secondary))
                else SolidColor(cs.surfaceVariant)
            )
            .border(1.dp, cs.outline.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (active) Color.White else cs.onSurfaceVariant.copy(alpha = 0.6f))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) Color.White else cs.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 预设音效胶囊（按压回弹动效） */
@Composable
fun PresetChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "chipScale")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                if (selected) Brush.horizontalGradient(listOf(cs.primary, cs.secondary))
                else SolidColor(cs.surfaceVariant.copy(alpha = 0.85f))
            )
            .border(1.dp, if (selected) Color.Transparent else cs.outline.copy(alpha = 0.4f), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) Color.White else cs.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/** 渐变发光主按钮 */
@Composable
fun GlowButton(
    text: String,
    container: Brush,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "btnScale")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(14.dp, shape, clip = false, ambientColor = Color(0xFF7C5CE0), spotColor = Color(0xFFE58FB1))
            .clip(shape)
            .background(if (enabled) container else SolidColor(Color.Gray.copy(alpha = 0.4f)))
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .height(56.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

/** 圆形玻璃图标按钮（齿轮 / 返回） */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val dark = isDarkTheme()
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (dark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.14f else 0.7f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = cs.onSurface, modifier = Modifier.size(22.dp))
    }
}

/** 设置页分段选择器 */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    label: (T) -> String,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val dark = isDarkTheme()
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        if (isSelected) Brush.horizontalGradient(listOf(cs.primary, cs.secondary))
                        else SolidColor(Color.Transparent)
                    )
                    .clickable { onSelect(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Color.White else cs.onSurfaceVariant,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}
