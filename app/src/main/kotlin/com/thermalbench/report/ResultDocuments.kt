package com.thermalbench.report

import android.content.Context
import android.net.Uri
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.port.ResultCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What a result can be saved as. */
enum class DocumentKind(val mimeType: String, val extension: String, val description: String) {
    PDF("application/pdf", "pdf", "one-page report"),
    JSON("application/json", "json", "result document"),
    CSV("text/csv", "csv", "per-iteration table"),
}

/**
 * Writes a result to a file the operator chose through the system's save
 * dialog. The app holds no storage permission; the dialog grants access to
 * that one file, wherever it was put.
 */
@Singleton
class ResultDocuments @Inject constructor(
    @ApplicationContext private val context: Context,
    private val codec: ResultCodec,
) {
    suspend fun write(result: BenchmarkResult, kind: DocumentKind, target: Uri) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(target, "wt")
            ?: error("Could not open the chosen file for writing")
        out.use { stream ->
            when (kind) {
                DocumentKind.PDF -> ReportPdf.write(result.reportContent(), stream)
                DocumentKind.JSON -> stream.write(codec.encodeResult(result).toByteArray(Charsets.UTF_8))
                DocumentKind.CSV -> stream.write(codec.resultCsv(result).toByteArray(Charsets.UTF_8))
            }
        }
    }

    companion object {
        /** A name the save dialog suggests: what ran, on what, and when. */
        fun suggestedName(result: BenchmarkResult, kind: DocumentKind): String {
            val model = result.spec.model.name.replace(UNSAFE, "-")
            val unit = result.spec.backend.delegate.name.lowercase()
            return "thermalbench-$model-$unit-${result.resultId.value.take(ID_PREFIX)}.${kind.extension}"
        }

        private val UNSAFE = Regex("[^A-Za-z0-9._-]+")
        private const val ID_PREFIX = 8
    }
}
