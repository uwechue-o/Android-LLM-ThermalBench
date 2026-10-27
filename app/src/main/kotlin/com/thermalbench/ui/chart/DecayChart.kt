package com.thermalbench.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.ui.Point
import com.thermalbench.ui.common.color

/**
 * The decay curve, drawn directly on a Canvas.
 *
 * A charting library would pull in its own animation and layout machinery, and
 * this chart is on screen precisely while the device is under measurement. One
 * path and a handful of line segments is the cheapest honest way to show the
 * curve; the "minimal UI" toggle removes even this.
 */
@Composable
fun DecayChart(
    series: List<Point>,
    throttleMarkers: List<Float> = emptyList(),
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    markerColor: Color = ThermalStatus.SEVERE.color(),
    labels: DecayLabels? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (series.size < 2) {
        Box(modifier = modifier.height(CHART_HEIGHT.dp), contentAlignment = Alignment.Center) {
            Text("Collecting…", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    val minX = series.first().x
    val maxX = series.last().x
    val maxY = series.maxOf { it.y }
    val minY = series.minOf { it.y }
    // Anchor the axis at zero so a shallow decline cannot be made to look like
    // a collapse by autoscaling.
    val top = maxY * Y_HEADROOM
    val bottom = 0f
    val spanX = (maxX - minX).takeIf { it > 0f } ?: 1f
    val spanY = (top - bottom).takeIf { it > 0f } ?: 1f

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT.dp)
            .padding(vertical = 4.dp),
    ) {
        fun px(point: Point) = Offset(
            x = (point.x - minX) / spanX * size.width,
            y = size.height - (point.y - bottom) / spanY * size.height,
        )

        val path = Path().apply {
            val first = px(series.first())
            moveTo(first.x, first.y)
            for (i in 1 until series.size) {
                val p = px(series[i])
                lineTo(p.x, p.y)
            }
        }
        drawPath(path, color = lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))

        // Horizontal reference at the minimum observed value: the floor so far.
        val floorY = size.height - (minY - bottom) / spanY * size.height
        drawLine(
            color = lineColor.copy(alpha = 0.3f),
            start = Offset(0f, floorY),
            end = Offset(size.width, floorY),
            strokeWidth = 1f,
        )

        throttleMarkers.forEach { seconds ->
            val x = (seconds - minX) / spanX * size.width
            drawLine(
                color = markerColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f,
            )
        }

        labels?.let {
            val throttleX = throttleMarkers.firstOrNull()?.let { m -> (m - minX) / spanX * size.width }
            drawLabels(it, measurer, labelStyle, ::px, throttleX, series.last())
        }
    }
}

/**
 * Words on the chart beside what they describe, so the numbers that matter
 * are read off the curve rather than looked up below it.
 */
data class DecayLabels(
    val peakText: String?,
    val peak: Point?,
    /** Drawn above the end of the line: where the run ended up. */
    val endText: String?,
    /** Drawn beside the first throttle marker. */
    val throttleText: String?,
)

private fun DrawScope.drawLabels(
    labels: DecayLabels,
    measurer: TextMeasurer,
    style: TextStyle,
    px: (Point) -> Offset,
    throttleX: Float?,
    last: Point,
) {
    val gap = LABEL_GAP.dp.toPx()
    labels.peakText?.let { text ->
        val anchor = px(labels.peak ?: return@let)
        val layout = measurer.measure(text, style)
        val x = (anchor.x - layout.size.width / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
        val y = (anchor.y - layout.size.height - gap).coerceAtLeast(0f)
        drawText(layout, topLeft = Offset(x, y))
    }
    labels.endText?.let { text ->
        val anchor = px(last)
        val layout = measurer.measure(text, style)
        val x = (size.width - layout.size.width).coerceAtLeast(0f)
        // Below the line when there is no room above it.
        val above = anchor.y - layout.size.height - gap
        val y = if (above >= 0f) above else anchor.y + gap
        drawText(layout, topLeft = Offset(x, y))
    }
    labels.throttleText?.let { text ->
        val x = throttleX ?: return@let
        val layout = measurer.measure(text, style)
        val left = (x + gap).coerceAtMost((size.width - layout.size.width).coerceAtLeast(0f))
        drawText(layout, topLeft = Offset(left, size.height - layout.size.height - gap))
    }
}

/**
 * Battery temperature over time, one stepped line per run, on the time axis of
 * the chart above it. Temperature has no meaningful zero, so this axis, unlike
 * the throughput axis, spans only the range observed.
 */
@Composable
fun TemperatureChart(
    series: List<Pair<Color, List<Point>>>,
    minX: Float,
    maxX: Float,
    modifier: Modifier = Modifier,
) {
    val drawable = series.filter { (_, points) -> points.size >= 2 }
    if (drawable.isEmpty()) return
    val low = drawable.minOf { (_, points) -> points.minOf { it.y } }
    val high = drawable.maxOf { (_, points) -> points.maxOf { it.y } }
    val bottom = kotlin.math.floor(low) - 1f
    val top = kotlin.math.ceil(high) + 1f
    val spanX = (maxX - minX).takeIf { it > 0f } ?: 1f

    Text(
        "Battery °C, ${"%.0f".format(java.util.Locale.US, bottom)} to ${"%.0f".format(java.util.Locale.US, top)}",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(TEMPERATURE_HEIGHT.dp)
            .padding(vertical = 4.dp),
    ) {
        fun px(point: Point) = Offset(
            x = ((point.x - minX) / spanX * size.width).coerceIn(0f, size.width),
            y = size.height - (point.y - bottom) / (top - bottom) * size.height,
        )
        drawable.forEach { (color, points) ->
            val path = Path().apply {
                val first = px(points.first())
                moveTo(first.x, first.y)
                points.zipWithNext().forEach { (a, b) ->
                    // Reported in steps, so drawn as steps rather than slopes between readings.
                    lineTo(px(b).x, px(a).y)
                    lineTo(px(b).x, px(b).y)
                }
            }
            drawPath(path, color = color, style = Stroke(width = 3f, cap = StrokeCap.Round))
        }
    }
}

/** Thermal status over time, as a step plot; severity is the vertical axis. */
@Composable
fun ThermalStrip(series: List<Point>, modifier: Modifier = Modifier) {
    if (series.isEmpty()) return
    val statuses = ThermalStatus.entries
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT.dp),
    ) {
        val widthPerSample = size.width / series.size
        series.forEachIndexed { index, point ->
            val status = statuses.getOrElse(point.y.toInt()) { ThermalStatus.UNKNOWN }
            drawRect(
                color = status.color(),
                topLeft = Offset(index * widthPerSample, 0f),
                size = androidx.compose.ui.geometry.Size(widthPerSample + 1f, size.height),
            )
        }
    }
}

@Composable
fun ChartPlaceholder(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

private const val CHART_HEIGHT = 180
private const val STRIP_HEIGHT = 16
private const val TEMPERATURE_HEIGHT = 72
private const val LABEL_GAP = 4
private const val Y_HEADROOM = 1.1f
