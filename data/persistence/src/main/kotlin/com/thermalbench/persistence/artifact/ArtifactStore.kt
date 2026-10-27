package com.thermalbench.persistence.artifact

import android.content.Context
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.ResultId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-disk home of canonical result documents. The document is authoritative:
 * the database holds an index and the in-flight samples, but what a reviewer
 * receives, and what a digest is computed over, is the file written here.
 *
 * Documents go to the app's external files directory so `adb pull` can retrieve
 * them from the headless harness without root.
 */
@Singleton
class ArtifactStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Falls back to internal storage when no external volume is mounted. */
    val root: File
        get() = (context.getExternalFilesDir(null) ?: context.filesDir).resolve(DIRECTORY)
            .also { it.mkdirs() }

    fun fileNameFor(result: BenchmarkResult): String =
        "thermalbench-${result.spec.specId}-${result.resultId}.json"

    fun file(fileName: String): File = root.resolve(fileName)

    suspend fun write(fileName: String, document: String): File = withContext(Dispatchers.IO) {
        val target = file(fileName)
        // Write to a sibling then rename: a crash mid-write must not leave a
        // truncated document that later parses as a shorter run.
        val temporary = File(target.parentFile, "${target.name}.part")
        temporary.sink().buffer().use { it.writeUtf8(document) }
        check(temporary.renameTo(target)) { "Could not finalize ${target.absolutePath}" }
        target
    }

    suspend fun read(fileName: String): String? = withContext(Dispatchers.IO) {
        val source = file(fileName)
        if (!source.isFile) return@withContext null
        source.source().buffer().use { it.readUtf8() }
    }

    suspend fun writeSidecar(fileName: String, content: String): File = withContext(Dispatchers.IO) {
        val target = file(fileName)
        target.sink().buffer().use { it.writeUtf8(content) }
        target
    }

    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        file(fileName).delete()
        Unit
    }

    suspend fun list(): List<File> = withContext(Dispatchers.IO) {
        root.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
    }

    fun csvNameFor(id: ResultId): String = "thermalbench-$id-iterations.csv"

    private companion object {
        const val DIRECTORY = "results"
    }
}
