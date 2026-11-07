package com.waizusora.flowery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@androidx.compose.runtime.Composable
internal fun VerticalEqualizerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = MaterialTheme.colorScheme
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            fun valueAt(y: Float): Float =
                (12f - (y / size.height.coerceAtLeast(1)) * 24f).coerceIn(-12f, 12f)
            detectTapGestures { offset -> onValueChange(valueAt(offset.y)) }
        }.pointerInput(Unit) {
            fun valueAt(y: Float): Float =
                (12f - (y / size.height.coerceAtLeast(1)) * 24f).coerceIn(-12f, 12f)
            detectDragGestures { change, _ ->
                change.consume()
                onValueChange(valueAt(change.position.y))
            }
        }
    ) {
        val centerX = size.width / 2f
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        val centerY = (top + bottom) / 2f
        val thumbY = bottom - ((value + 12f) / 24f) * (bottom - top)

        drawLine(
            color = palette.outline,
            start = Offset(centerX, top),
            end = Offset(centerX, bottom),
            strokeWidth = 3.dp.toPx()
        )
        listOf(top + 24.dp.toPx(), bottom - 24.dp.toPx()).forEach { y ->
            drawLine(
                color = palette.onSurfaceVariant,
                start = Offset(centerX - 21.dp.toPx(), y),
                end = Offset(centerX - 16.dp.toPx(), y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = palette.onSurfaceVariant,
                start = Offset(centerX + 16.dp.toPx(), y),
                end = Offset(centerX + 21.dp.toPx(), y),
                strokeWidth = 1.dp.toPx()
            )
        }
        drawRoundRect(
            color = palette.primary,
            topLeft = Offset(centerX - 15.dp.toPx(), thumbY - 22.dp.toPx()),
            size = Size(30.dp.toPx(), 44.dp.toPx()),
            cornerRadius = CornerRadius(10.dp.toPx()),
            style = Stroke(width = 2.5.dp.toPx())
        )
        drawLine(
            color = Color.White,
            start = Offset(centerX - 5.dp.toPx(), thumbY),
            end = Offset(centerX + 5.dp.toPx(), thumbY),
            strokeWidth = 2.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}
