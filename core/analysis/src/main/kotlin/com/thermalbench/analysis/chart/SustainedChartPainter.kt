package com.thermalbench.analysis.chart

import com.thermalbench.domain.model.ChartPoint
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The surface a [SustainedChartPainter] draws on: Android's Canvas in the app,
 * Java2D on the desktop. Colours are ARGB; coordinates are pixels, y down.
 */
interface ChartCanvas {
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, dashed: Boolean = false)
    fun polyline(xs: FloatArray, ys: FloatArray, color: Int, width: Float)
    fun dot(cx: Float, cy: Float, radius: Float, color: Int, ring: Int, ringWidth: Float)
    fun fill(left: Float, top: Float, right: Float, bottom: Float, color: Int)
    fun text(value: String, x: Float, baseline: Float, style: ChartText, align: TextAlign = TextAlign.LEFT)
    fun textWidth(value: String, style: ChartText): Float
}

data class ChartText(val size: Float, val color: Int, val bold: Boolean = false)

enum class TextAlign { LEFT, CENTER, RIGHT }

/**
 * Draws a [ChartPage] as one image: decode speed over time above, battery
 * temperature on the same time axis below, each run labelled at the end of its
 * line rather than in a legend box alone. The same code draws the image the
 * app shares and the one `thermalbench-analyze plot` writes, so the two match.
 *
 * The speed axis starts at zero, as it does everywhere else in ThermalBench: a
 * cropped axis makes a mild decline look like a collapse. Temperature has no
 * meaningful zero and is scaled to its range.
 */
class SustainedChartPainter(private val width: Float, private val height: Float) {

    private val s = width / BASE_WIDTH

    fun paint(canvas: ChartCanvas, page: ChartPage) {
        canvas.fill(0f, 0f, width, height, SURFACE)

        val left = MARGIN * s
        val right = width - GUTTER * s
        var y = MARGIN * s

        y += TITLE * s
        canvas.text(fit(canvas, page.title, TITLE_STYLE, width - 2 * left), left, y, TITLE_STYLE.scaled())
        y += GAP * 1.5f * s + SUBTITLE * s
        canvas.text(fit(canvas, page.subtitle, SUBTITLE_STYLE, width - 2 * left), left, y, SUBTITLE_STYLE.scaled())
        if (page.series.size > 1) {
            y += GAP * s + LABEL * s
            legend(canvas, page, left, y)
        }
        y += GAP * 2 * s + LABEL * s
        canvas.text("Decode speed (tokens/s) per answer", left, y, LABEL_STYLE.scaled())
        y += GAP * s

        val drawn = page.series.filterNot { it.chart.isEmpty }
        val hasTemperature = drawn.any { it.chart.batteryCelsius.size >= 2 }
        val footerTop = height - (MARGIN + LABEL + GAP) * s
        val axisTitleBaseline = footerTop - GAP * s
        val plotBottom = axisTitleBaseline - (LABEL + GAP * 2 + TICK + GAP) * s
        val speedBottom = if (hasTemperature) y + (plotBottom - y) * SPEED_SHARE else plotBottom
        val speedPanel = Panel(left, y + GAP * s, right, speedBottom)

        // Battery readings run to the end of the last iteration, past its start.
        val maxMinutes = drawn
            .maxOfOrNull { series -> maxOf(series.chart.speed.last().x, series.chart.batteryCelsius.lastOrNull()?.x ?: 0.0) }
            ?.takeIf { it > 0 } ?: 1.0
        val xAxis = niceAxis(0.0, maxMinutes, X_TICKS)

        if (drawn.isEmpty()) {
            canvas.text("Too few measured iterations to draw.", left, speedPanel.middle, LABEL_STYLE.scaled())
        } else {
            val maxSpeed = drawn.maxOf { series -> series.chart.speed.maxOf { it.y } }
            val speedAxis = niceAxis(0.0, maxSpeed * HEADROOM, Y_TICKS)
            speedPanel(canvas, speedPanel, xAxis, speedAxis, page, drawn)
            if (hasTemperature) {
                val tempPanel = Panel(left, speedBottom + PANEL_GAP * s, right, plotBottom)
                temperaturePanel(canvas, tempPanel, xAxis, page, drawn)
            }
            xTicks(canvas, Panel(left, speedPanel.top, right, plotBottom), xAxis)
        }

        canvas.text("Minutes since the first measured answer", (left + right) / 2, axisTitleBaseline, LABEL_STYLE.scaled(), TextAlign.CENTER)
        canvas.text(fit(canvas, page.footer, FOOTER_STYLE, width - 2 * left), left, height - MARGIN * s, FOOTER_STYLE.scaled())
    }

    private fun speedPanel(
        canvas: ChartCanvas,
        panel: Panel,
        xAxis: Axis,
        yAxis: Axis,
        page: ChartPage,
        drawn: List<ChartSeries>,
    ) {
        yGrid(canvas, panel, yAxis, ::tick)
        drawn.forEach { series ->
            val throttle = series.chart.firstThrottle ?: return@forEach
            val x = panel.x(throttle.minutes, xAxis)
            canvas.line(x, panel.top, x, panel.bottom, MUTED, 1.5f * s, dashed = true)
        }
        // Throttle notes sit along the bottom of the panel, one line each.
        drawn.filter { it.chart.firstThrottle != null }.forEachIndexed { i, series ->
            val throttle = series.chart.firstThrottle!!
            val who = if (drawn.size == 1) "Throttles" else "${series.label} throttles"
            val note = "$who (${throttle.to.name}) at ${format(throttle.minutes, 1)} min"
            val x = panel.x(throttle.minutes, xAxis) + GAP * s
            val baseline = panel.bottom - GAP * s - i * (NOTE + GAP) * s
            val style = NOTE_STYLE.scaled()
            val clamped = x.coerceAtMost(panel.right - canvas.textWidth(note, style))
            canvas.text(note, clamped, baseline, style)
        }

        val endLabels = mutableListOf<EndLabel>()
        drawn.forEach { series ->
            val color = colorOf(page, series)
            val points = series.chart.speed
            canvas.polyline(
                points.map { panel.x(it.x, xAxis) }.toFloatArray(),
                points.map { panel.y(it.y, yAxis) }.toFloatArray(),
                color,
                LINE * s,
            )
            if (points.size <= MAX_MARKERS) {
                points.forEach { canvas.dot(panel.x(it.x, xAxis), panel.y(it.y, yAxis), MARKER * s, color, SURFACE, 1.5f * s) }
            }
            val closing = series.chart.closingTokensPerSecond ?: points.last().y
            val share = series.chart.closingShareOfPeak
            endLabels += EndLabel(
                color = color,
                first = if (drawn.size == 1) "Last ${series.chart.closingWindow} answers" else series.label,
                second = "${format(closing, 1)} tok/s" + (share?.let { ", ${percent(it)} of peak" } ?: ""),
                desiredY = panel.y(closing, yAxis),
            )
        }
        drawn.takeIf { it.size <= MAX_PEAK_LABELS }?.forEach { series ->
            val peak = series.chart.peak ?: return@forEach
            val style = NOTE_STYLE.scaled()
            val text = (if (drawn.size == 1) "Peak " else "${series.label} peak ") + "${format(peak.y, 1)} tok/s"
            val half = canvas.textWidth(text, style) / 2
            val x = panel.x(peak.x, xAxis).coerceIn(panel.left + half, panel.right - half)
            canvas.text(text, x, panel.y(peak.y, yAxis) - (MARKER + GAP) * s, style, TextAlign.CENTER)
        }
        endLabels(canvas, panel, endLabels, lines = 2)
    }

    private fun temperaturePanel(canvas: ChartCanvas, panel: Panel, xAxis: Axis, page: ChartPage, drawn: List<ChartSeries>) {
        val withTemperature = drawn.filter { it.chart.batteryCelsius.size >= 2 }
        val low = withTemperature.minOf { series -> series.chart.batteryCelsius.minOf { it.y } }
        val high = withTemperature.maxOf { series -> series.chart.batteryCelsius.maxOf { it.y } }
        val yAxis = niceAxis(floor(low) - 1, ceil(high) + 1, TEMP_TICKS)
        yGrid(canvas, panel, yAxis, ::tick)
        canvas.text("Battery °C", panel.left, panel.top - GAP * s, NOTE_STYLE.scaled())
        drawn.forEach { series ->
            val throttle = series.chart.firstThrottle ?: return@forEach
            val x = panel.x(throttle.minutes, xAxis)
            canvas.line(x, panel.top, x, panel.bottom, MUTED, 1.5f * s, dashed = true)
        }
        val labels = withTemperature.map { series ->
            val points = series.chart.batteryCelsius.steps()
            canvas.polyline(
                points.map { panel.x(it.x, xAxis) }.toFloatArray(),
                points.map { panel.y(it.y, yAxis) }.toFloatArray(),
                colorOf(page, series),
                LINE * s,
            )
            val rise = series.chart.batteryRiseCelsius ?: 0.0
            val sign = if (rise >= 0) "+" else "−"
            EndLabel(
                color = colorOf(page, series),
                first = (if (drawn.size == 1) "" else "${series.label}  ") + "$sign${format(kotlin.math.abs(rise), 1)} °C",
                second = null,
                desiredY = panel.y(points.last().y, yAxis),
            )
        }
        endLabels(canvas, panel, labels, lines = 1)
    }

    /** Labels in the right gutter beside where each line ends, nudged apart so none overlap. */
    private fun endLabels(canvas: ChartCanvas, panel: Panel, labels: List<EndLabel>, lines: Int) {
        val lineHeight = (LABEL + GAP) * s
        val block = lineHeight * lines
        val placed = labels.sortedBy { it.desiredY }.toMutableList()
        val ys = placed.map { it.desiredY }.toFloatArray()
        for (i in 1 until ys.size) ys[i] = maxOf(ys[i], ys[i - 1] + block)
        val overflow = ys.lastOrNull()?.let { it + block / 2 - panel.bottom } ?: 0f
        if (overflow > 0) for (i in ys.indices) ys[i] -= overflow
        for (i in ys.indices.reversed().drop(1)) ys[i] = minOf(ys[i], ys[i + 1] - block)

        val x = panel.right + GAP * 2 * s
        placed.forEachIndexed { i, label ->
            val top = ys[i] - block / 2
            canvas.line(x, top + lineHeight * 0.35f, x + SWATCH * s, top + lineHeight * 0.35f, label.color, LINE * s)
            val textX = x + (SWATCH + GAP) * s
            canvas.text(label.first, textX, top + LABEL * s * 0.8f, LABEL_STRONG_STYLE.scaled())
            label.second?.let { canvas.text(it, textX, top + lineHeight + LABEL * s * 0.8f, LABEL_STYLE.scaled()) }
        }
    }

    private fun legend(canvas: ChartCanvas, page: ChartPage, left: Float, baseline: Float) {
        var x = left
        val style = LABEL_STYLE.scaled()
        page.series.forEach { series ->
            val color = colorOf(page, series)
            canvas.line(x, baseline - LABEL * s * 0.35f, x + SWATCH * s, baseline - LABEL * s * 0.35f, color, LINE * s)
            x += (SWATCH + GAP) * s
            canvas.text(series.label, x, baseline, style)
            x += canvas.textWidth(series.label, style) + GAP * 3 * s
        }
    }

    private fun yGrid(canvas: ChartCanvas, panel: Panel, axis: Axis, label: (Double) -> String) {
        axis.ticks.forEach { value ->
            val y = panel.y(value, axis)
            canvas.line(panel.left, y, panel.right, y, GRID, 1f * s)
            canvas.text(label(value), panel.left - GAP * s, y + TICK * s * 0.35f, TICK_STYLE.scaled(), TextAlign.RIGHT)
        }
    }

    private fun xTicks(canvas: ChartCanvas, panel: Panel, axis: Axis) {
        canvas.line(panel.left, panel.bottom, panel.right, panel.bottom, AXIS, 1.5f * s)
        axis.ticks.forEach { value ->
            canvas.text(tick(value), panel.x(value, axis), panel.bottom + (GAP + TICK) * s, TICK_STYLE.scaled(), TextAlign.CENTER)
        }
    }

    private fun colorOf(page: ChartPage, series: ChartSeries): Int = SERIES_COLORS[page.series.indexOf(series) % SERIES_COLORS.size]

    private fun fit(canvas: ChartCanvas, value: String, style: ChartText, maxWidth: Float): String {
        val scaled = style.scaled()
        if (canvas.textWidth(value, scaled) <= maxWidth) return value
        var end = value.length
        while (end > 1 && canvas.textWidth(value.take(end) + ELLIPSIS, scaled) > maxWidth) end--
        return value.take(end).trimEnd() + ELLIPSIS
    }

    private fun ChartText.scaled() = copy(size = size * s)

    private class Panel(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val middle: Float get() = (top + bottom) / 2
        fun x(value: Double, axis: Axis) = left + ((value - axis.min) / (axis.max - axis.min) * (right - left)).toFloat()
        fun y(value: Double, axis: Axis) = bottom - ((value - axis.min) / (axis.max - axis.min) * (bottom - top)).toFloat()
    }

    private class EndLabel(val color: Int, val first: String, val second: String?, val desiredY: Float)

    internal data class Axis(val min: Double, val max: Double, val ticks: List<Double>)

    companion object {
        /** Blue, orange, aqua, yellow, magenta, green, violet, red: validated for colour-vision deficiency in this order. */
        val SERIES_COLORS = intArrayOf(
            0xFF2A78D6.toInt(), 0xFFEB6834.toInt(), 0xFF1BAF7A.toInt(), 0xFFEDA100.toInt(),
            0xFFE87BA4.toInt(), 0xFF008300.toInt(), 0xFF6250D6.toInt(), 0xFFE34948.toInt(),
        )

        private const val SURFACE = 0xFFFCFCFB.toInt()
        private const val INK = 0xFF0B0B0B.toInt()
        private const val INK_2 = 0xFF52514E.toInt()
        private const val MUTED = 0xFF8A8985.toInt()
        private const val GRID = 0xFFE6E5E1.toInt()
        private const val AXIS = 0xFFC9C8C3.toInt()

        // Laid out on a 1600-pixel-wide image and scaled to the real width.
        private const val BASE_WIDTH = 1600f
        private const val MARGIN = 56f
        private const val GUTTER = 330f
        private const val GAP = 10f
        private const val PANEL_GAP = 64f
        private const val SPEED_SHARE = 0.68f
        private const val TITLE = 40f
        private const val SUBTITLE = 22f
        private const val LABEL = 21f
        private const val NOTE = 18f
        private const val TICK = 18f
        private const val LINE = 3f
        private const val MARKER = 3.5f
        private const val SWATCH = 22f
        private const val HEADROOM = 1.1
        private const val MAX_MARKERS = 120
        private const val MAX_PEAK_LABELS = 2
        private const val X_TICKS = 6
        private const val Y_TICKS = 5
        private const val TEMP_TICKS = 3
        private const val ELLIPSIS = "…"

        private val TITLE_STYLE = ChartText(TITLE, INK, bold = true)
        private val SUBTITLE_STYLE = ChartText(SUBTITLE, INK_2)
        private val LABEL_STYLE = ChartText(LABEL, INK_2)
        private val LABEL_STRONG_STYLE = ChartText(LABEL, INK, bold = true)
        private val NOTE_STYLE = ChartText(NOTE, INK_2)
        private val TICK_STYLE = ChartText(TICK, INK_2)
        private val FOOTER_STYLE = ChartText(NOTE, MUTED)

        /** An axis over [low, high] with round tick values about [count] apart. */
        internal fun niceAxis(low: Double, high: Double, count: Int): Axis {
            val span = (high - low).takeIf { it > 0 } ?: 1.0
            val raw = span / count
            val magnitude = 10.0.pow(floor(log10(raw)))
            val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
            val min = floor(low / step) * step
            val max = ceil(high / step) * step
            val ticks = generateSequence(min) { it + step }.takeWhile { it <= max + step / 2 }.toList()
            return Axis(min, max, ticks)
        }

        private fun format(value: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", value)

        /** A tick value as written: whole numbers bare, a 2.5 step with its half. */
        private fun tick(value: Double) = if (value % 1.0 == 0.0) format(value, 0) else format(value, 1)

        private fun percent(share: Double) = String.format(Locale.US, "%.0f%%", share * 100)

        /** Battery temperature is reported in steps; draw it as one rather than as slopes between readings. */
        private fun List<ChartPoint>.steps(): List<ChartPoint> = flatMapIndexed { i, point ->
            if (i == 0) listOf(point) else listOf(ChartPoint(point.x, this[i - 1].y), point)
        }
    }
}
