package com.thermalbench.analysis.chart

import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.BackendRef
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.SustainedChart
import com.thermalbench.domain.model.ValidityVerdict
import com.thermalbench.domain.model.sustainedChart
import java.util.Locale

/** One run on a shareable chart: what to call it, its curve, and whether it counts. */
data class ChartSeries(
    val label: String,
    val chart: SustainedChart,
    val validity: ValidityVerdict?,
)

/**
 * Everything printed on a shareable sustained-load chart. The words are
 * derived from the results, never typed in: the title names only what every
 * run had in common, and each run is labelled by what set it apart.
 */
data class ChartPage(
    val title: String,
    val subtitle: String,
    val footer: String,
    val series: List<ChartSeries>,
) {
    companion object {
        /** Runs past this many are left off; their colours would not stay distinguishable. */
        const val MAX_SERIES = 8

        fun of(results: List<BenchmarkResult>): ChartPage {
            require(results.isNotEmpty()) { "A chart needs at least one result" }
            val shown = results.take(MAX_SERIES)
            val labels = labels(shown)
            val series = shown.mapIndexed { i, result ->
                ChartSeries(labels[i], result.sustainedChart(), result.profile?.validity)
            }
            return ChartPage(
                title = title(shown),
                subtitle = subtitle(shown, series),
                footer = footer(shown, series),
                series = series,
            )
        }

        private fun BenchmarkResult.deviceName(): String =
            if (device.model.startsWith(device.manufacturer, ignoreCase = true)) {
                device.model
            } else {
                "${device.manufacturer} ${device.model}"
            }

        private fun BenchmarkResult.computeUnit(): String {
            val api = spec.backend.options[BackendRef.OPTION_GPU_API]?.let { " ($it)" }.orEmpty()
            return "${spec.backend.delegate.name}$api"
        }

        private fun backendName(id: BackendId): String = when (id) {
            BackendId.LITERT_LM -> "LiteRT-LM"
            BackendId.MEDIAPIPE -> "MediaPipe"
            BackendId.LLAMA_CPP -> "llama.cpp"
            BackendId.QUALCOMM_GENIE -> "Qualcomm Genie"
            BackendId.EXECUTORCH -> "ExecuTorch"
            else -> id.value
        }

        /** Each run named by the dimensions that differ between runs, in a fixed order. */
        private fun labels(results: List<BenchmarkResult>): List<String> {
            if (results.size == 1) {
                val only = results.single()
                return listOf("${backendName(only.spec.backend.id)} · ${only.computeUnit()}")
            }
            val dimensions: List<(BenchmarkResult) -> String> = listOf(
                { it.deviceName() },
                { it.spec.model.name },
                { backendName(it.spec.backend.id) },
                { it.computeUnit() },
            )
            val varying = dimensions.filter { dimension -> results.map(dimension).distinct().size > 1 }
            if (varying.isEmpty()) return results.indices.map { "Run ${it + 1}" }
            return results.map { result -> varying.joinToString(" · ") { it(result) } }
        }

        private fun title(results: List<BenchmarkResult>): String {
            val models = results.map { it.spec.model.name }.distinct()
            val devices = results.map { it.deviceName() }.distinct()
            return when {
                models.size == 1 && devices.size == 1 -> "${models.single()} on ${devices.single()}"
                models.size == 1 -> "${models.single()} across ${devices.size} devices"
                devices.size == 1 -> "${models.size} models on ${devices.single()}"
                else -> "Sustained decode throughput"
            }
        }

        private fun subtitle(results: List<BenchmarkResult>, series: List<ChartSeries>): String {
            val tokens = results.map { it.spec.workload.maxOutputTokens }.distinct()
            val minutes = series.maxOf { it.chart.speed.lastOrNull()?.x ?: 0.0 }
            val parts = mutableListOf<String>()
            // What every run shared and the labels do not already say.
            val backends = results.map { backendName(it.spec.backend.id) }.distinct()
            when {
                series.size == 1 -> parts += series.single().label
                backends.size == 1 -> parts += backends.single()
            }
            parts += if (tokens.size == 1) "${tokens.single()}-token answers back to back" else "Answers back to back"
            parts[parts.lastIndex] += " for ${String.format(Locale.US, "%.0f", minutes)} minutes"
            val starts = results.map { it.environment.conditionsAtStart }
            if (starts.all { it != null && !it.plugged }) parts += "unplugged"
            if (starts.all { it != null && !it.screenOn }) parts += "screen off"
            return parts.joinToString(", ")
        }

        private fun footer(results: List<BenchmarkResult>, series: List<ChartSeries>): String {
            val tool = "Measured with ThermalBench ${results.first().toolVersion}"
            val verdicts = series.map { it.validity }
            val validity = when {
                verdicts.all { it == ValidityVerdict.VALID } ->
                    if (series.size == 1) "rated VALID" else "every run rated VALID"
                series.size == 1 -> "rated ${verdicts.single()?.name ?: "without a verdict"}"
                else -> series.joinToString(", ") { "${it.label}: ${it.validity?.name ?: "no verdict"}" }
            }
            return "$tool · $validity"
        }
    }
}
