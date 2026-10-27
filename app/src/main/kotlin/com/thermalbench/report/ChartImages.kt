package com.thermalbench.report

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.thermalbench.analysis.chart.ChartCanvas
import com.thermalbench.analysis.chart.ChartPage
import com.thermalbench.analysis.chart.ChartText
import com.thermalbench.analysis.chart.SustainedChartPainter
import com.thermalbench.analysis.chart.TextAlign
import com.thermalbench.domain.model.BenchmarkResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The chart image a result is shared as: decode speed and battery temperature
 * over time, drawn by the same painter as `thermalbench-analyze plot`, so a
 * chart posted from the phone and one made on a desktop from the same files
 * look alike.
 */
@Singleton
class ChartImages @Inject constructor(@ApplicationContext private val context: Context) {

    /** Draws [results] to a PNG in the cache and returns a share-sheet intent for it. */
    suspend fun shareIntent(results: List<BenchmarkResult>): Intent = withContext(Dispatchers.Default) {
        val page = ChartPage.of(results)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        SustainedChartPainter(WIDTH.toFloat(), HEIGHT.toFloat()).paint(AndroidChartCanvas(Canvas(bitmap)), page)

        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, SHARED_DIRECTORY).apply { mkdirs() }
            // One image at a time: the share sheet has taken its copy by the next share.
            directory.listFiles()?.forEach { it.delete() }
            File(directory, fileName(results)).also { file ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            }
        }
        bitmap.recycle()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.$AUTHORITY_SUFFIX", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, page.title)
            clipData = android.content.ClipData.newRawUri(page.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "Share chart")
    }

    private fun fileName(results: List<BenchmarkResult>): String {
        val first = results.first()
        val model = first.spec.model.name.replace(UNSAFE, "-")
        val suffix = if (results.size == 1) first.spec.backend.delegate.name.lowercase() else "${results.size}-runs"
        return "thermalbench-$model-$suffix-${first.resultId.value.take(ID_PREFIX)}.png"
    }

    private companion object {
        // 16:10, wide enough to read on a phone and sharp in a LinkedIn or Slack post.
        const val WIDTH = 1600
        const val HEIGHT = 1000
        const val PNG_QUALITY = 100
        const val SHARED_DIRECTORY = "shared"
        const val AUTHORITY_SUFFIX = "files"
        const val ID_PREFIX = 8
        val UNSAFE = Regex("[^A-Za-z0-9._-]+")
    }
}

/** [ChartCanvas] over Android's Canvas. */
private class AndroidChartCanvas(private val canvas: Canvas) : ChartCanvas {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val type = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, dashed: Boolean) {
        stroke.color = color
        stroke.strokeWidth = width
        stroke.strokeCap = if (dashed) Paint.Cap.BUTT else Paint.Cap.ROUND
        stroke.pathEffect = if (dashed) DashPathEffect(floatArrayOf(width * 4, width * 3), 0f) else null
        canvas.drawLine(x1, y1, x2, y2, stroke)
        stroke.pathEffect = null
    }

    override fun polyline(xs: FloatArray, ys: FloatArray, color: Int, width: Float) {
        if (xs.isEmpty()) return
        val path = Path().apply {
            moveTo(xs[0], ys[0])
            for (i in 1 until xs.size) lineTo(xs[i], ys[i])
        }
        stroke.color = color
        stroke.strokeWidth = width
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(path, stroke)
    }

    override fun dot(cx: Float, cy: Float, radius: Float, color: Int, ring: Int, ringWidth: Float) {
        solid.color = ring
        canvas.drawCircle(cx, cy, radius + ringWidth, solid)
        solid.color = color
        canvas.drawCircle(cx, cy, radius, solid)
    }

    override fun fill(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        solid.color = color
        canvas.drawRect(left, top, right, bottom, solid)
    }

    override fun text(value: String, x: Float, baseline: Float, style: ChartText, align: TextAlign) {
        apply(style)
        type.textAlign = when (align) {
            TextAlign.LEFT -> Paint.Align.LEFT
            TextAlign.CENTER -> Paint.Align.CENTER
            TextAlign.RIGHT -> Paint.Align.RIGHT
        }
        canvas.drawText(value, x, baseline, type)
    }

    override fun textWidth(value: String, style: ChartText): Float {
        apply(style)
        return type.measureText(value)
    }

    private fun apply(style: ChartText) {
        type.textSize = style.size
        type.color = style.color
        type.typeface = if (style.bold) BOLD else REGULAR
    }

    private companion object {
        val REGULAR: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        val BOLD: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
}
