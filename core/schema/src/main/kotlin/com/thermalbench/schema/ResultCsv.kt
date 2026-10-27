package com.thermalbench.schema

import com.github.doyaaaaaken.kotlincsv.dsl.csvWriter
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.SensorTemperature

/** CSV — the format reviewers will actually open. One row per iteration. */
object ResultCsv {

    val ITERATION_HEADER: List<String> = listOf(
        "result_id", "spec_id", "device", "backend", "delegate", "model",
        "index", "phase", "start_elapsed_nanos", "end_elapsed_nanos", "duration_ms",
        "ttft_micros", "prefill_tps", "decode_tps", "prompt_tokens", "output_tokens",
        "thermal_status_start", "thermal_status_end", "thermal_headroom_start",
        "battery_temp_deci_c", "battery_percent", "cpu_freq_khz", "peak_rss_bytes", "throttle_observed",
        "gpu_freq_khz", "sensor_temps_c",
    )

    fun iterations(result: BenchmarkResult): String {
        val device = "${result.device.manufacturer} ${result.device.model}"
        val rows: List<List<Any?>> = result.iterations.map { s ->
            listOf(
                result.resultId.value,
                result.spec.specId.value,
                device,
                result.spec.backend.id.value,
                result.spec.backend.delegate.name,
                result.spec.model.name,
                s.index,
                s.phase.name,
                s.startElapsedNanos,
                s.endElapsedNanos,
                CanonicalJson.formatDouble(s.durationNanos / 1e6),
                s.timeToFirstTokenMicros,
                CanonicalJson.formatDouble(s.prefillTokensPerSecond),
                CanonicalJson.formatDouble(s.decodeTokensPerSecond),
                s.promptTokenCount,
                s.outputTokenCount,
                s.thermalStatusAtStart.name,
                s.thermalStatusAtEnd.name,
                s.thermalHeadroomAtStart?.let { CanonicalJson.formatDouble(it.toDouble()) } ?: "",
                s.batteryTemperatureDeciC ?: "",
                s.batteryPercent,
                s.cpuFrequenciesKhz?.joinToString("|") ?: "",
                s.peakRssBytes,
                s.throttleObserved,
                s.gpuFrequencyKhz ?: "",
                sensorCell(s.sensorTemperatures),
            )
        }
        return csvWriter { lineTerminator = "\n" }.writeAllAsString(listOf(ITERATION_HEADER) + rows)
    }

    val THERMAL_HEADER: List<String> = listOf(
        "result_id", "elapsed_nanos", "status", "headroom", "battery_temp_deci_c", "cpu_freq_khz",
        "gpu_freq_khz", "sensor_temps_c",
    )

    fun thermalTimeline(result: BenchmarkResult): String {
        val rows: List<List<Any?>> = result.thermalTimeline.samples.map { s ->
            listOf(
                result.resultId.value,
                s.elapsedNanos,
                s.status.name,
                s.headroom?.let { CanonicalJson.formatDouble(it.toDouble()) } ?: "",
                s.batteryTemperatureDeciC ?: "",
                s.cpuFrequenciesKhz?.joinToString("|") ?: "",
                s.gpuFrequencyKhz ?: "",
                sensorCell(s.sensorTemperatures),
            )
        }
        return csvWriter { lineTerminator = "\n" }.writeAllAsString(listOf(THERMAL_HEADER) + rows)
    }

    /** "name:celsius" pairs joined by "|", in the device's own sensor order. */
    private fun sensorCell(sensors: List<SensorTemperature>?): String =
        sensors?.joinToString("|") { "${it.name}:${CanonicalJson.formatDouble(it.celsius.toDouble())}" } ?: ""
}
