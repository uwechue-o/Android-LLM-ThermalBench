package com.thermalbench.ui

import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.Phase
import com.thermalbench.domain.model.SustainedChart
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.sustainedChart

/**
 * Performance against time for one finished run: decode tokens/s for every
 * iteration, warm-up included, on minutes since the first one started. Warm-up
 * is kept, and marked by [warmupEndMinutes], so the chart shows the whole
 * session rather than starting at an already-warm device.
 */
data class PerformanceSeries(
    val decode: List<Point>,
    /** Where the warm-up iterations end; null when there were none. */
    val warmupEndMinutes: Float?,
    /** Throttle onsets: platform thermal-status rises, in minutes. */
    val throttleMinutes: List<Float>,
    /** Thermal status ordinal over time, for the strip under the chart. */
    val thermal: List<Point>,
    /** Battery temperature in °C on the same time axis; empty when it was not readable. */
    val battery: List<Point> = emptyList(),
    /** Where the chart labels the peak: the profile's peak, at the first iteration that reached it. */
    val peak: Point? = null,
    /** Where the run ended up, from [SustainedChart]; drawn as a label at the end of the line. */
    val closing: SustainedChart? = null,
    /** The status the first throttle rose to, for the label beside its marker. */
    val firstThrottleStatus: ThermalStatus? = null,
) {
    val isEmpty: Boolean get() = decode.size < 2
}

fun BenchmarkResult.performanceSeries(): PerformanceSeries {
    val origin = iterations.firstOrNull()?.startElapsedNanos
        ?: return PerformanceSeries(emptyList(), null, emptyList(), emptyList())
    fun minutes(nanos: Long) = ((nanos - origin) / NANOS_PER_MINUTE).toFloat()
    val lastWarmup = iterations.lastOrNull { it.phase == Phase.WARMUP }
    val lastStart = iterations.last().startElapsedNanos
    // SustainedChart counts from the first measured iteration; this axis from the first of all.
    val chart = sustainedChart()
    val measuredOffset = measuredIterations.firstOrNull()?.let { minutes(it.startElapsedNanos) } ?: 0f
    return PerformanceSeries(
        decode = iterations.map { Point(minutes(it.startElapsedNanos), it.decodeTokensPerSecond.toFloat()) },
        warmupEndMinutes = lastWarmup?.let { minutes(it.endElapsedNanos) },
        throttleMinutes = thermalTimeline.transitions
            .filter { it.to > it.from && it.to.isThrottling }
            .map { minutes(it.elapsedNanos) }
            .filter { it >= 0f },
        thermal = thermalTimeline.samples
            .filter { it.elapsedNanos >= origin }
            .map { Point(minutes(it.elapsedNanos), it.status.ordinal.toFloat()) },
        battery = thermalTimeline.samples
            .filter { it.elapsedNanos in origin..lastStart }
            .mapNotNull { sample ->
                sample.batteryTemperatureDeciC?.let { Point(minutes(sample.elapsedNanos), it / DECI) }
            },
        peak = chart.peak?.let { Point(measuredOffset + it.x.toFloat(), it.y.toFloat()) },
        closing = chart.takeUnless { it.isEmpty },
        firstThrottleStatus = chart.firstThrottle?.to,
    )
}

private const val NANOS_PER_MINUTE = 60e9
private const val DECI = 10f
