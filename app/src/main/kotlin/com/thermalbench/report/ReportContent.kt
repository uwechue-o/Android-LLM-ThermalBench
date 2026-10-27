package com.thermalbench.report

import com.thermalbench.domain.model.BackendRef
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.ValidityVerdict
import com.thermalbench.ui.PerformanceSeries
import com.thermalbench.ui.performanceSeries
import com.thermalbench.ui.screen.composer.presentation
import com.thermalbench.ui.telemetrySummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A titled list of label/value rows on the report. */
data class ReportSection(val title: String, val rows: List<Pair<String, String>>)

/**
 * Everything the one-page report says, worked out from the result before any
 * drawing happens, so the wording can be tested without a PDF.
 */
data class ReportContent(
    val title: String,
    val subtitle: String,
    val startedAt: String,
    val verdict: String,
    val valid: Boolean,
    /** The four numbers across the top: retained, peak, steady state, first throttle. */
    val headline: List<Pair<String, String>>,
    val performance: PerformanceSeries,
    val sections: List<ReportSection>,
    val footer: String,
)

fun BenchmarkResult.reportContent(zone: ZoneId = ZoneId.systemDefault()): ReportContent {
    val backend = spec.backend
    val api = backend.options[BackendRef.OPTION_GPU_API]?.let { " ($it)" }.orEmpty()
    val verdict = profile?.validity
    return ReportContent(
        title = "ThermalBench sustained-load report",
        subtitle = "${spec.model.name} · ${backend.id.presentation().displayName} · ${backend.delegate.name}$api · " +
            "${device.manufacturer} ${device.model}",
        startedAt = DATE.withZone(zone).format(Instant.ofEpochMilli(startedAtEpochMillis)),
        verdict = when (verdict) {
            ValidityVerdict.VALID -> "Valid run"
            null -> "No result: ${terminated.name}"
            else -> "Not valid for comparison: ${verdict.name}"
        },
        valid = verdict == ValidityVerdict.VALID,
        headline = headline(),
        performance = performanceSeries(),
        sections = listOf(runSection(), conditionsSection(), deviceSection(), telemetrySection()),
        footer = "Retained = steady-state ÷ peak decode throughput. Spec ${spec.specId} · result $resultId · " +
            "ThermalBench $toolVersion",
    )
}

private fun BenchmarkResult.headline(): List<Pair<String, String>> {
    val profile = profile
    return listOf(
        "Throughput retained" to (profile?.retentionRatio?.let { percent(it) } ?: "—"),
        "Peak decode" to (profile?.peakDecodeTokensPerSecond?.let { "${decimal(it)} tok/s" } ?: "—"),
        "Steady state" to (profile?.steadyStateTokensPerSecond?.let { "${decimal(it)} tok/s" } ?: "never stable"),
        "First throttle" to (profile?.iterationsToFirstThrottle?.let { "iteration $it" } ?: "never"),
    )
}

private fun BenchmarkResult.runSection(): ReportSection {
    val profile = profile
    return ReportSection(
        "Run",
        listOfNotNull(
            "Iterations" to "${spec.protocol.warmupIterations} warm-up + ${measuredIterations.size} measured",
            "Output tokens" to spec.workload.maxOutputTokens.toString(),
            profile?.let {
                "Mean / min decode" to
                    "${decimal(it.meanDecodeTokensPerSecond)} / ${decimal(it.minDecodeTokensPerSecond)} tok/s"
            },
            profile?.let { "TTFT drift" to "${decimal(it.ttftDriftMicrosPerIteration)} µs/iteration" },
            "Ended" to terminated.name,
            profile?.batteryDrainPercent?.let { "Battery drain" to "$it%" },
            environment.computeDevice?.let { "Ran on" to it },
            environment.backendVersion?.let { "Runtime" to it },
        ),
    )
}

private fun BenchmarkResult.conditionsSection(): ReportSection {
    val start = environment.conditionsAtStart
    return ReportSection(
        "Conditions at start",
        listOfNotNull(
            "Preconditions" to if (environment.preconditionsSatisfied) "met" else "NOT met",
            environment.unmetAtStart.takeIf { it.isNotEmpty() }?.let { "Unmet" to it.joinToString("; ") },
            "Screen" to environment.screenPolicy.name,
            "Path" to environment.executionPath,
            start?.batteryTemperatureDeciC?.let { "Battery temp" to "${it / DECI} °C" },
            start?.let { "Battery" to "${it.batteryPercent}%" },
        ),
    )
}

private fun BenchmarkResult.deviceSection(): ReportSection = ReportSection(
    "Device",
    listOfNotNull(
        "Model" to "${device.manufacturer} ${device.model} (${device.device})",
        listOfNotNull(device.socManufacturer, device.socModel).takeIf { it.isNotEmpty() }
            ?.let { "SoC" to it.joinToString(" ") },
        "Android" to "${device.androidRelease} (API ${device.sdkInt})",
        "Memory" to "${device.totalRamBytes / BYTES_PER_GIB} GiB",
        "CPU cores" to device.cpuCoreCount.toString(),
    ),
)

private fun BenchmarkResult.telemetrySection(): ReportSection {
    val telemetry = telemetrySummary()
    val clocks = telemetry.clocks.map {
        it.label to "${mhz(it.startKhz)} → ${mhz(it.endKhz)} MHz (low ${mhz(it.minKhz)})"
    }
    val sensors = telemetry.sensors.map {
        it.name to "${decimal(it.startCelsius.toDouble())} → ${decimal(it.endCelsius.toDouble())} °C"
    }
    val absent = if (telemetry.sensors.isEmpty()) listOf("Sensors" to "not readable on this device") else emptyList()
    return ReportSection("Temperatures and clocks", clocks + sensors + absent)
}

private fun percent(ratio: Double) = String.format(Locale.US, "%.1f%%", ratio * PERCENT)

private fun decimal(value: Double) = String.format(Locale.US, "%.1f", value)

private fun mhz(khz: Long) = (khz / KHZ_PER_MHZ).toString()

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", Locale.US)
private const val PERCENT = 100
private const val DECI = 10.0
private const val KHZ_PER_MHZ = 1000L
private const val BYTES_PER_GIB = 1L shl 30
