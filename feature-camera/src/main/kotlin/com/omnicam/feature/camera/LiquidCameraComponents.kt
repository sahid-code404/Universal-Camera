package com.omnicam.feature.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnicam.camera.camerax.LightningFocusState
import com.omnicam.camera.camerax.LightningFocusStatus
import java.util.Locale
import kotlin.math.roundToInt

internal val LiquidGlass = Color(0x66151518)
internal val LiquidGlassStrong = Color(0xA61B1B1F)
internal val LiquidBorder = Color.White.copy(alpha = 0.26f)
internal val LiquidTextDim = Color.White.copy(alpha = 0.70f)
internal const val LIQUID_MAX_UPSCALE = 3f

@Composable
internal fun LiquidBottomScrim(modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.35f to Color.Black.copy(alpha = 0.08f),
                1f to Color.Black.copy(alpha = 0.72f),
            ),
        ),
    )
}

@Composable
internal fun GlassCircleButton(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    sizeDp: Int = 48,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(sizeDp.dp).clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = if (selected) Color.White.copy(alpha = 0.20f) else LiquidGlass,
        border = BorderStroke(1.dp, if (selected) Color.White.copy(alpha = 0.46f) else LiquidBorder),
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.32f),
                fontSize = if (text.length > 2) 10.sp else 20.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun GlassPillButton(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = if (selected) Color.White.copy(alpha = 0.18f) else LiquidGlass,
        border = BorderStroke(1.dp, if (selected) Color.White.copy(alpha = 0.42f) else LiquidBorder),
        shadowElevation = 3.dp,
    ) {
        Text(
            text,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.34f),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
internal fun LiquidLensButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = spring(stiffness = 500f),
        label = "lensScale",
    )
    Surface(
        modifier = Modifier.size((38f * scale).dp).clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) Color.Black.copy(alpha = 0.58f) else Color.Black.copy(alpha = 0.24f),
        border = if (selected) BorderStroke(1.dp, Color.White.copy(alpha = 0.40f)) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = Color.White,
                fontSize = if (label.length > 4) 8.sp else 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun LiquidPresetSelector(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = LiquidGlass,
        border = BorderStroke(1.dp, LiquidBorder),
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { index, label ->
                Surface(
                    modifier = Modifier.clickable { onSelected(index) },
                    shape = RoundedCornerShape(22.dp),
                    color = if (index == selectedIndex) Color.White.copy(alpha = 0.18f) else Color.Transparent,
                    border = if (index == selectedIndex) BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)) else null,
                ) {
                    Text(
                        label,
                        color = if (index == selectedIndex) Color.White else Color.White.copy(alpha = 0.58f),
                        fontSize = 11.sp,
                        fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun LiquidShutter(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(90.dp)
            .border(2.dp, Color.White.copy(alpha = 0.92f), CircleShape)
            .padding(6.dp)
            .border(1.dp, Color.Black.copy(alpha = 0.22f), CircleShape)
            .padding(4.dp)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.34f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (capturing) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = Color.Black,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
internal fun LiquidFocusIndicator(state: LightningFocusState, modifier: Modifier = Modifier) {
    if (state.status == LightningFocusStatus.IDLE) return
    Canvas(modifier) {
        val center = Offset(size.width * state.normalizedX, size.height * state.normalizedY)
        val side = 56.dp.toPx()
        val left = center.x - side / 2f
        val top = center.y - side / 2f
        val color = if (state.status == LightningFocusStatus.FAILED) Color(0xFFFF625A) else Color.White
        drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(side, side),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()),
            style = Stroke(width = 1.8.dp.toPx()),
        )
        drawCircle(color, radius = 2.4.dp.toPx(), center = center)
    }
}

@Composable
internal fun LiquidGrid(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val paint = Color.White.copy(alpha = 0.22f)
        val stroke = 0.65.dp.toPx()
        drawLine(paint, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), stroke)
        drawLine(paint, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), stroke)
        drawLine(paint, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), stroke)
        drawLine(paint, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), stroke)
    }
}

@Composable
internal fun DrawerSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValue: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = LiquidTextDim, fontSize = 10.sp)
            Text(valueText, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth().height(30.dp),
        )
    }
}

internal fun percentText(value: Float): String = "${(value * 100).roundToInt()}%"
internal fun upscaleText(value: Float): String = String.format(Locale.US, "%.1f×", value)
