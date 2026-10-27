package com.thermalbench.ui.chart

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.thermalbench.ui.Point
import com.thermalbench.ui.theme.MeasurementSmallTextStyle
import java.util.Locale

/**
 * Several decay curves on one pair of axes: decode tokens/s against minutes
 * since each run's first measured iteration. The axes are shared and the
 * vertical one starts at zero, so a curve that sits higher really was faster
 * and a steeper fall really was a larger loss; autoscaling each run on its own
 * would make any two curves look alike.
 */
@Composable
fun ComparisonChart(
    series: List<Pair<Color, List<Point>>>,
    modifier: Modifier = Modifier,
    /**
     * Words for the end of each line, in [series] order, each placed at the
     * height of [endValues]' value; empty draws no labels. Labels are nudged
     * apart rather than allowed to overlap.
     */
    endLabels: List<String> = emptyList(),
    endValues: List<Float> = emptyList(),
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val drawable = series.filter { (_, points) -> points.size >= 2 }
    if (drawable.isEmpty()) {
        ChartPlaceholder("Too few measured iterations to draw.", modifier)
        return
    }
    val maxX = drawable.maxOf { (_, points) -> points.maxOf { it.x } }.takeIf { it > 0f } ?: 1f
    val maxY = drawable.maxOf { (_, points) -> points.maxOf { it.y } } * Y_HEADROOM
    val axisColor = MaterialTheme.colorScheme.outline

    Column(modifier.fillMaxWidth()) {
        Text(
            "${maxY.format(0)} tok/s",
            style = MeasurementSmallTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT.dp)
                .padding(vertical = 4.dp),
        ) {
            val labelled = endLabels.size == series.size && endValues.size == series.size
            val plotWidth = if (labelled) size.width - LABEL_GUTTER.dp.toPx() else size.width
            fun px(point: Point) = Offset(
                x = point.x / maxX * plotWidth,
                y = size.height - point.y / maxY * size.height,
            )
            drawLine(axisColor, Offset(0f, size.height), Offset(plotWidth, size.height), strokeWidth = 1f)
            drawLine(axisColor, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f)
            drawable.forEach { (color, points) ->
                val path = Path().apply {
                    val first = px(points.first())
                    moveTo(first.x, first.y)
                    points.drop(1).forEach { point -> px(point).let { lineTo(it.x, it.y) } }
                }
                drawPath(path, color = color, style = Stroke(width = 3f, cap = StrokeCap.Round))
            }

            if (labelled) {
                val gap = LABEL_GAP.dp.toPx()
                val swatch = SWATCH.dp.toPx()
                val layouts = endLabels.map { measurer.measure(it, labelStyle) }
                val height = layouts.maxOf { it.size.height } + gap
                // Each label centred on its line's closing value, then pushed apart.
                val order = series.indices
                    .filter { series[it].second.size >= 2 }
                    .sortedBy { size.height - endValues[it] / maxY * size.height }
                val centres = order.map { size.height - endValues[it] / maxY * size.height }.toFloatArray()
                for (i in 1 until centres.size) centres[i] = maxOf(centres[i], centres[i - 1] + height)
                val overflow = (centres.lastOrNull() ?: 0f) + height / 2 - size.height
                if (overflow > 0f) for (i in centres.indices) centres[i] -= overflow
                order.forEachIndexed { i, index ->
                    val x = plotWidth + gap
                    val y = centres[i]
                    drawLine(series[index].first, Offset(x, y), Offset(x + swatch, y), strokeWidth = 3f)
                    val layout = layouts[index]
                    drawText(layout, topLeft = Offset(x + swatch + gap, y - layout.size.height / 2f))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("0", style = MeasurementSmallTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${maxX.format(1)} min",
                style = MeasurementSmallTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/**
 * Line colours for up to six runs, after Okabe and Ito: distinguishable with
 * the common colour-vision deficiencies and on light or dark backgrounds.
 */
val SeriesPalette = listOf(
    Color(0xFF0072B2),
    Color(0xFFE69F00),
    Color(0xFF009E73),
    Color(0xFFCC79A7),
    Color(0xFF56B4E9),
    Color(0xFFD55E00),
)

private fun Float.format(decimals: Int) = String.format(Locale.US, "%.${decimals}f", this)

private const val CHART_HEIGHT = 200
private const val LABEL_GUTTER = 104
private const val LABEL_GAP = 4
private const val SWATCH = 10
private const val Y_HEADROOM = 1.1f
