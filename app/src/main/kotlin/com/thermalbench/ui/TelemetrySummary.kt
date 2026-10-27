package com.thermalbench.ui

import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.IterationSample

/** How one temperature sensor moved over the measured iterations. */
data class SensorRise(val name: String, val startCelsius: Float, val endCelsius: Float, val peakCelsius: Float) {
    val riseCelsius: Float get() = endCelsius - startCelsius
}

/** How one clock moved over the measured iterations, in kHz. */
data class ClockChange(val label: String, val startKhz: Long, val endKhz: Long, val minKhz: Long)

/**
 * The temperatures and clocks behind a result: which sensors heated most, and
 * whether the GPU or the CPU was clocked down while throughput fell. Absent
 * signals stay absent; a device that exposes no sensors gets an empty list and
 * says so, never a row of zeros.
 */
data class TelemetrySummary(
    val computeDevice: String?,
    val sensors: List<SensorRise>,
    val clocks: List<ClockChange>,
    val sensorsReadable: Boolean,
    val gpuClockReadable: Boolean,
)

fun BenchmarkResult.telemetrySummary(maxSensors: Int = DEFAULT_MAX_SENSORS): TelemetrySummary {
    val measured = measuredIterations.ifEmpty { iterations }
    return TelemetrySummary(
        computeDevice = environment.computeDevice,
        sensors = sensorRises(measured).sortedByDescending { it.riseCelsius }.take(maxSensors),
        clocks = listOfNotNull(
            clockChange("GPU", measured) { it.gpuFrequencyKhz },
            clockChange("Fastest CPU core", measured) { sample -> sample.cpuFrequenciesKhz?.maxOrNull() },
        ),
        sensorsReadable = device.thermalSensorNames.isNotEmpty() ||
            measured.any { !it.sensorTemperatures.isNullOrEmpty() },
        gpuClockReadable = device.gpuFrequencyReadable || measured.any { it.gpuFrequencyKhz != null },
    )
}

private fun sensorRises(samples: List<IterationSample>): List<SensorRise> {
    val readings = samples.mapNotNull { it.sensorTemperatures }
    if (readings.isEmpty()) return emptyList()
    val names = readings.flatMap { list -> list.map { it.name } }.distinct()
    return names.mapNotNull { name ->
        val series = readings.mapNotNull { list -> list.firstOrNull { it.name == name }?.celsius }
        if (series.isEmpty()) null else SensorRise(name, series.first(), series.last(), series.max())
    }
}

private fun clockChange(
    label: String,
    samples: List<IterationSample>,
    read: (IterationSample) -> Long?,
): ClockChange? {
    val series = samples.mapNotNull(read)
    if (series.isEmpty()) return null
    return ClockChange(label, series.first(), series.last(), series.min())
}

private const val DEFAULT_MAX_SENSORS = 6
