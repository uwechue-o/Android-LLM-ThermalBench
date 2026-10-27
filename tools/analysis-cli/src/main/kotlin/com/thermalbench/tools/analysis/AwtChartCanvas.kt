package com.thermalbench.tools.analysis

import com.thermalbench.analysis.chart.ChartCanvas
import com.thermalbench.analysis.chart.ChartPage
import com.thermalbench.analysis.chart.ChartText
import com.thermalbench.analysis.chart.SustainedChartPainter
import com.thermalbench.analysis.chart.TextAlign
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** [ChartCanvas] over Java2D, so the desktop draws the same chart the app shares. */
class AwtChartCanvas(private val g: Graphics2D) : ChartCanvas {

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, dashed: Boolean) {
        g.color = Color(color, true)
        g.stroke = if (dashed) {
            BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(width * 4, width * 3), 0f)
        } else {
            BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        }
        g.draw(java.awt.geom.Line2D.Float(x1, y1, x2, y2))
    }

    override fun polyline(xs: FloatArray, ys: FloatArray, color: Int, width: Float) {
        if (xs.isEmpty()) return
        val path = Path2D.Float().apply {
            moveTo(xs[0], ys[0])
            for (i in 1 until xs.size) lineTo(xs[i], ys[i])
        }
        g.color = Color(color, true)
        g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(path)
    }

    override fun dot(cx: Float, cy: Float, radius: Float, color: Int, ring: Int, ringWidth: Float) {
        val outer = radius + ringWidth
        g.color = Color(ring, true)
        g.fill(Ellipse2D.Float(cx - outer, cy - outer, outer * 2, outer * 2))
        g.color = Color(color, true)
        g.fill(Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2))
    }

    override fun fill(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        g.color = Color(color, true)
        g.fill(Rectangle2D.Float(left, top, right - left, bottom - top))
    }

    override fun text(value: String, x: Float, baseline: Float, style: ChartText, align: TextAlign) {
        g.font = font(style)
        g.color = Color(style.color, true)
        val width = textWidth(value, style)
        val left = when (align) {
            TextAlign.LEFT -> x
            TextAlign.CENTER -> x - width / 2
            TextAlign.RIGHT -> x - width
        }
        g.drawString(value, left, baseline)
    }

    override fun textWidth(value: String, style: ChartText): Float =
        g.getFontMetrics(font(style)).getStringBounds(value, g).width.toFloat()

    private fun font(style: ChartText) = Font(Font.SANS_SERIF, if (style.bold) Font.BOLD else Font.PLAIN, 1)
        .deriveFont(style.size)

    companion object {
        fun writePng(page: ChartPage, file: File, width: Int, height: Int) {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                SustainedChartPainter(width.toFloat(), height.toFloat()).paint(AwtChartCanvas(g), page)
            } finally {
                g.dispose()
            }
            ImageIO.write(image, "png", file)
        }
    }
}
