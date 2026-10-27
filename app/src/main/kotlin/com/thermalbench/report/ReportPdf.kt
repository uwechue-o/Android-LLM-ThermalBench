package com.thermalbench.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.thermalbench.domain.model.ThermalStatus
import java.io.OutputStream

/**
 * Draws a [ReportContent] onto a single A4 page with the platform's own
 * PdfDocument, so the report needs no library and no network. Everything is
 * laid out in PostScript points (1/72 inch).
 */
object ReportPdf {

    fun write(content: ReportContent, out: OutputStream) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create())
            Layout(page.canvas).draw(content)
            document.finishPage(page)
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    private class Layout(private val canvas: Canvas) {
        private var y = MARGIN

        private val title = text(TITLE_SIZE, bold = true)
        private val subtitle = text(BODY_SIZE + 1f, color = MUTED)
        private val body = text(BODY_SIZE)
        private val label = text(BODY_SIZE, color = MUTED)
        private val heading = text(HEADING_SIZE, bold = true, color = ACCENT)
        private val small = text(SMALL_SIZE, color = MUTED)
        private val big = text(BIG_SIZE, bold = true)

        fun draw(content: ReportContent) {
            canvas.drawText(content.title, MARGIN, y + TITLE_SIZE, title)
            y += TITLE_SIZE + LINE_GAP
            drawFitted(content.subtitle, MARGIN, y + BODY_SIZE, CONTENT_WIDTH, subtitle)
            y += BODY_SIZE + LINE_GAP
            canvas.drawText("Started ${content.startedAt}", MARGIN, y + SMALL_SIZE, small)
            y += SMALL_SIZE + SECTION_GAP

            val verdict = text(BODY_SIZE + 1f, bold = true, color = if (content.valid) VALID else INVALID)
            canvas.drawText(content.verdict, MARGIN, y + BODY_SIZE, verdict)
            y += BODY_SIZE + SECTION_GAP

            drawHeadline(content.headline)
            drawChart(content)
            drawSections(content.sections)

            val footerY = PAGE_HEIGHT - MARGIN
            canvas.drawLine(MARGIN, footerY - SMALL_SIZE - LINE_GAP, PAGE_WIDTH - MARGIN, footerY - SMALL_SIZE - LINE_GAP, rule())
            drawFitted(content.footer, MARGIN, footerY, CONTENT_WIDTH, small)
        }

        private fun drawHeadline(tiles: List<Pair<String, String>>) {
            val width = CONTENT_WIDTH / tiles.size
            tiles.forEachIndexed { index, (name, value) ->
                val x = MARGIN + index * width
                canvas.drawText(name, x, y + SMALL_SIZE, small)
                drawFitted(value, x, y + SMALL_SIZE + LINE_GAP + BIG_SIZE, width - TILE_GAP, big)
            }
            y += SMALL_SIZE + LINE_GAP + BIG_SIZE + SECTION_GAP
        }

        /** Decode tokens/s over minutes, warm-up shaded, throttle onsets marked, thermal strip below. */
        private fun drawChart(content: ReportContent) {
            canvas.drawText("Performance over time", MARGIN, y + HEADING_SIZE, heading)
            y += HEADING_SIZE + LINE_GAP
            val series = content.performance
            val left = MARGIN + AXIS_LABEL_WIDTH
            val right = PAGE_WIDTH - MARGIN
            val top = y
            val bottom = y + CHART_HEIGHT
            if (series.isEmpty) {
                canvas.drawText("Too few iterations to draw.", left, top + CHART_HEIGHT / 2, label)
            } else {
                val maxX = series.decode.maxOf { it.x }.takeIf { it > 0f } ?: 1f
                // Zero-based, so a shallow decline is not drawn as a collapse.
                val maxY = series.decode.maxOf { it.y } * Y_HEADROOM
                fun px(x: Float) = left + x / maxX * (right - left)
                fun py(v: Float) = bottom - v / maxY * (bottom - top)

                series.warmupEndMinutes?.let { end ->
                    canvas.drawRect(left, top, px(end).coerceAtMost(right), bottom, fill(WARMUP_SHADE))
                    canvas.drawText("warm-up", left + LINE_GAP, top + SMALL_SIZE + LINE_GAP, small)
                }
                series.throttleMinutes.forEach { m ->
                    canvas.drawLine(px(m), top, px(m), bottom, stroke(INVALID, 1f))
                }
                canvas.drawLine(left, bottom, right, bottom, rule())
                canvas.drawLine(left, top, left, bottom, rule())
                val path = Path()
                series.decode.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(px(p.x), py(p.y)) else path.lineTo(px(p.x), py(p.y))
                }
                canvas.drawPath(path, stroke(ACCENT, 1.5f))

                canvas.drawText(String.format(java.util.Locale.US, "%.0f", maxY), MARGIN, top + SMALL_SIZE, small)
                canvas.drawText("0", MARGIN, bottom, small)
                canvas.drawText("tok/s", MARGIN, top + SMALL_SIZE * 2 + LINE_GAP, small)
                val minutes = String.format(java.util.Locale.US, "%.1f min", maxX)
                canvas.drawText(minutes, right - small.measureText(minutes), bottom + SMALL_SIZE + LINE_GAP, small)
                canvas.drawText("0", left, bottom + SMALL_SIZE + LINE_GAP, small)

                // Thermal status strip: one colour per platform status, on the
                // chart's time axis. Samples after the last iteration started
                // are clipped to the axis rather than drawn past it.
                val stripTop = bottom + SMALL_SIZE + LINE_GAP * 2
                series.thermal.zipWithNext().forEach { (a, b) ->
                    val status = ThermalStatus.entries.getOrElse(a.y.toInt()) { ThermalStatus.UNKNOWN }
                    val from = px(a.x).coerceIn(left, right)
                    val to = (px(b.x) + 0.5f).coerceIn(left, right)
                    if (to > from) canvas.drawRect(from, stripTop, to, stripTop + STRIP_HEIGHT, fill(statusColor(status)))
                }
                canvas.drawText("Thermal status", left, stripTop + STRIP_HEIGHT + SMALL_SIZE + LINE_GAP, small)
                y = stripTop + STRIP_HEIGHT + SMALL_SIZE + LINE_GAP
            }
            y = maxOf(y, bottom) + SECTION_GAP
        }

        /** Sections in two columns, each filled top to bottom in order. */
        private fun drawSections(sections: List<ReportSection>) {
            val columnWidth = (CONTENT_WIDTH - COLUMN_GAP) / 2
            val half = (sections.size + 1) / 2
            val start = y
            listOf(sections.take(half), sections.drop(half)).forEachIndexed { column, list ->
                val x = MARGIN + column * (columnWidth + COLUMN_GAP)
                var cy = start
                list.forEach { section ->
                    canvas.drawText(section.title, x, cy + HEADING_SIZE, heading)
                    cy += HEADING_SIZE + LINE_GAP
                    section.rows.forEach { (name, value) ->
                        drawFitted(name, x, cy + BODY_SIZE, columnWidth * LABEL_SHARE, label)
                        drawFitted(value, x + columnWidth * LABEL_SHARE, cy + BODY_SIZE, columnWidth * (1 - LABEL_SHARE), body)
                        cy += BODY_SIZE + ROW_GAP
                    }
                    cy += SECTION_GAP
                }
            }
        }

        /** Draws [value], cut short with an ellipsis if it would run past [width]. */
        private fun drawFitted(value: String, x: Float, baseline: Float, width: Float, paint: Paint) {
            val fitted = if (paint.measureText(value) <= width) {
                value
            } else {
                val count = paint.breakText(value, true, width - paint.measureText(ELLIPSIS), null)
                value.take(count).trimEnd() + ELLIPSIS
            }
            canvas.drawText(fitted, x, baseline, paint)
        }
    }

    private fun text(size: Float, bold: Boolean = false, color: Int = INK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    private fun fill(color: Int) = Paint().apply { style = Paint.Style.FILL; this.color = color }

    private fun rule() = stroke(RULE, 0.5f)

    /** The same colour scale as the app's thermal strip, cool to hot. */
    private fun statusColor(status: ThermalStatus): Int = when (status) {
        ThermalStatus.NONE -> Color.rgb(0x2E, 0x7D, 0x32)
        ThermalStatus.LIGHT -> Color.rgb(0x9E, 0x9D, 0x24)
        ThermalStatus.MODERATE -> Color.rgb(0xF9, 0xA8, 0x25)
        ThermalStatus.SEVERE -> Color.rgb(0xEF, 0x6C, 0x00)
        ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN -> Color.rgb(0xC6, 0x28, 0x28)
        ThermalStatus.UNKNOWN -> Color.rgb(0x9E, 0x9E, 0x9E)
    }

    // A4 in points.
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN

    private const val TITLE_SIZE = 18f
    private const val HEADING_SIZE = 10.5f
    private const val BODY_SIZE = 8.5f
    private const val SMALL_SIZE = 7.5f
    private const val BIG_SIZE = 16f

    private const val LINE_GAP = 4f
    private const val ROW_GAP = 3.5f
    private const val SECTION_GAP = 12f
    private const val TILE_GAP = 8f
    private const val COLUMN_GAP = 24f
    private const val LABEL_SHARE = 0.38f

    private const val AXIS_LABEL_WIDTH = 28f
    private const val CHART_HEIGHT = 170f
    private const val STRIP_HEIGHT = 6f
    private const val Y_HEADROOM = 1.1f
    private const val ELLIPSIS = "…"

    private val INK = Color.rgb(0x1C, 0x1B, 0x1F)
    private val MUTED = Color.rgb(0x5F, 0x5E, 0x66)
    private val ACCENT = Color.rgb(0x00, 0x72, 0xB2)
    private val VALID = Color.rgb(0x2E, 0x7D, 0x32)
    private val INVALID = Color.rgb(0xC6, 0x28, 0x28)
    private val RULE = Color.rgb(0xBD, 0xBD, 0xBD)
    private val WARMUP_SHADE = Color.argb(0x22, 0x5F, 0x5E, 0x66)
}
