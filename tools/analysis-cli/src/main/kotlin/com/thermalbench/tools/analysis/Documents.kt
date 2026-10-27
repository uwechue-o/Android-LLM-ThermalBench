package com.thermalbench.tools.analysis

import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.schema.JsonResultCodec
import java.io.File

/** Loads result documents from a file or a directory tree. */
object Documents {

    private val codec = JsonResultCodec()

    data class Loaded(val file: File, val result: BenchmarkResult)

    data class LoadReport(val loaded: List<Loaded>, val failures: List<Pair<File, String>>)

    /**
     * A document that fails to parse is reported, never skipped in silence: a
     * comparison table missing a device because its file was malformed is worse
     * than one that says so.
     */
    fun load(paths: List<File>): LoadReport {
        val files = paths.flatMap { path ->
            when {
                path.isDirectory -> path.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
                path.isFile -> listOf(path)
                else -> emptyList()
            }
        }.sortedBy { it.path }

        val loaded = mutableListOf<Loaded>()
        val failures = mutableListOf<Pair<File, String>>()
        files.forEach { file ->
            runCatching { codec.decodeResult(file.readText()) }
                .onSuccess { loaded += Loaded(file, it) }
                .onFailure { failures += file to (it.message ?: it::class.java.simpleName) }
        }
        return LoadReport(loaded, failures)
    }

    fun encode(result: BenchmarkResult): String = codec.encodeResult(result)

    fun csv(result: BenchmarkResult): String = codec.resultCsv(result)
}
