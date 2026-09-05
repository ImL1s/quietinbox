package dev.quietinbox.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Single-hue bar chart (one series, no rainbow), with an accessible description that reads
 * the values so TalkBack users get the same information (dataviz guidance).
 */
@Composable
fun BarChart(
    values: List<Int>,
    modifier: Modifier = Modifier,
    labels: Map<Int, String> = emptyMap(),
    description: String = "",
    barColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    height: androidx.compose.ui.unit.Dp = 140.dp,
) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier.semantics { contentDescription = description }) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            if (values.isEmpty()) return@Canvas
            val gap = 3.dp.toPx()
            val barW = (size.width - gap * (values.size - 1)) / values.size
            values.forEachIndexed { i, v ->
                val x = i * (barW + gap)
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(x, 0f),
                    size = Size(barW, size.height),
                    cornerRadius = CornerRadius(barW / 2, barW / 2),
                )
                val h = size.height * v / max
                if (v > 0) {
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, size.height - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW / 2, barW / 2),
                    )
                }
            }
        }
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                values.indices.forEach { i ->
                    Text(
                        labels[i] ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Horizontal proportion bar used in rankings (share of the counted sample). */
@Composable
fun ShareBar(share: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier = modifier.fillMaxWidth().height(8.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(4.dp.toPx()))
        drawRoundRect(color, size = Size(size.width * share.coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(4.dp.toPx()))
    }
}
