package dev.quietinbox.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single-hue matrix chart: one row per [rowLabels] entry, one column per value in a row. Intensity
 * is the cell's share of the largest cell, interpolated from [lowColor] to [highColor], so colour
 * carries magnitude only — the accessible [description] repeats the numbers for TalkBack, the same
 * contract [BarChart] follows.
 */
@Composable
fun HeatMapChart(
    values: List<List<Int>>,
    rowLabels: List<String>,
    description: String,
    modifier: Modifier = Modifier,
    columnLabels: Map<Int, String> = emptyMap(),
    cellHeight: Dp = 16.dp,
    gap: Dp = 2.dp,
    rowLabelWidth: Dp = 30.dp,
    lowColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    highColor: Color = MaterialTheme.colorScheme.primary,
) {
    val rows = values.size
    val columns = values.maxOfOrNull { it.size } ?: 0
    if (rows == 0 || columns == 0) return
    val max = (values.maxOfOrNull { row -> row.maxOrNull() ?: 0 } ?: 0).coerceAtLeast(1)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridHeight = cellHeight * rows + gap * (rows - 1)

    Column(modifier = modifier.semantics { contentDescription = description }) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.width(rowLabelWidth)) {
                for (index in 0 until rows) {
                    Box(Modifier.height(cellHeight), contentAlignment = Alignment.CenterStart) {
                        Text(
                            rowLabels.getOrElse(index) { "" },
                            style = MaterialTheme.typography.labelSmall,
                            color = labelColor,
                            maxLines = 1,
                        )
                    }
                    if (index < rows - 1) Spacer(Modifier.height(gap))
                }
            }
            Canvas(Modifier.weight(1f).height(gridHeight)) {
                val gapPx = gap.toPx()
                val cellW = (size.width - gapPx * (columns - 1)) / columns
                val cellH = (size.height - gapPx * (rows - 1)) / rows
                val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                values.forEachIndexed { row, counts ->
                    for (column in 0 until columns) {
                        val count = counts.getOrElse(column) { 0 }
                        drawRoundRect(
                            color = if (count <= 0) lowColor else lerp(lowColor, highColor, count.toFloat() / max),
                            topLeft = Offset(column * (cellW + gapPx), row * (cellH + gapPx)),
                            size = Size(cellW, cellH),
                            cornerRadius = radius,
                        )
                    }
                }
            }
        }
        if (columnLabels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Spacer(Modifier.width(rowLabelWidth))
                for (column in 0 until columns) {
                    Text(
                        columnLabels[column] ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** "less → more" key for a [HeatMapChart]; both end labels are supplied by the caller. */
@Composable
fun HeatMapLegend(
    lowLabel: String,
    highLabel: String,
    modifier: Modifier = Modifier,
    steps: Int = 5,
    lowColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    highColor: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(lowLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (step in 0 until steps) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(lerp(lowColor, highColor, step / (steps - 1f))),
            )
        }
        Text(highLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
