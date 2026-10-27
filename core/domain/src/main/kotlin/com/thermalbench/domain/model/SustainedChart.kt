package com.thermalbench.domain.model

/**
 * What a sustained-load chart of one run shows, worked out once so the app's
 * screens, its shared image and the desktop `plot` command print the same
 * numbers. Time is minutes since the first measured iteration started; warm-up
 * is left out, so every run's curve starts from the same point.
 */
data class SustainedChart(
    /** Decode tokens/s per measured iteration, at the minute it started. */
    val speed: List<ChartPoint>,
    /** Battery temperature in °C over the measured iterations; empty when it was not readable. */
    val batteryCelsius: List<ChartPoint>,
    /**
     * The run's peak: the profile's best sustained window, placed at the first
     * iteration that ran within 1% of it. A single fast iteration is not a peak; one
     * spike would otherwise make every later number look like a loss.
     */
    val peak: ChartPoint?,
    /**
     * Mean decode speed over the last [closingWindow] measured iterations: where
     * the run ended up. Reported beside, never instead of, the retention ratio,
     * which needs a detected steady state and is absent when a run never settles.
     */
    val closingTokensPerSecond: Double?,
    val closingWindow: Int,
    /** First rise of the platform's thermal status into throttling, if any. */
    val firstThrottle: ChartThrottle?,
) {
    /** [closingTokensPerSecond] as a share of [peak]. */
    val closingShareOfPeak: Double?
        get() = closingTokensPerSecond?.let { closing -> peak?.y?.takeIf { it > 0 }?.let { closing / it } }

    /** Battery temperature at the end less at the start, in °C. */
    val batteryRiseCelsius: Double?
        get() = if (batteryCelsius.size < 2) null else batteryCelsius.last().y - batteryCelsius.first().y

    val isEmpty: Boolean get() = speed.size < 2

    companion object {
        const val DEFAULT_CLOSING_WINDOW = 10
    }
}

data class ChartPoint(val x: Double, val y: Double)

data class ChartThrottle(val minutes: Double, val to: ThermalStatus)

fun BenchmarkResult.sustainedChart(closingWindow: Int = SustainedChart.DEFAULT_CLOSING_WINDOW): SustainedChart {
    val measured = measuredIterations.sortedBy { it.index }
    val origin = measured.firstOrNull()?.startElapsedNanos
    if (origin == null) {
        return SustainedChart(emptyList(), emptyList(), null, null, closingWindow, null)
    }
    val end = measured.last().endElapsedNanos
    fun minutes(nanos: Long) = (nanos - origin) / NANOS_PER_MINUTE

    val speed = measured.map { ChartPoint(minutes(it.startElapsedNanos), it.decodeTokensPerSecond) }
    val closing = measured.takeLast(closingWindow).map { it.decodeTokensPerSecond }
    return SustainedChart(
        speed = speed,
        batteryCelsius = thermalTimeline.samples
            .filter { it.elapsedNanos in origin..end }
            .mapNotNull { sample ->
                sample.batteryTemperatureDeciC?.let { ChartPoint(minutes(sample.elapsedNanos), it / DECI) }
            },
        peak = peakPoint(speed, profile?.peakDecodeTokensPerSecond),
        closingTokensPerSecond = closing.takeIf { it.isNotEmpty() }?.average(),
        closingWindow = closingWindow,
        firstThrottle = thermalTimeline.transitions
            .firstOrNull { it.elapsedNanos in origin..end && it.to > it.from && it.to.isThrottling }
            ?.let { ChartThrottle(minutes(it.elapsedNanos), it.to) },
    )
}

private fun peakPoint(speed: List<ChartPoint>, profilePeak: Double?): ChartPoint? {
    if (profilePeak == null) return speed.maxByOrNull { it.y }
    // An iteration that ran at about the peak, not a spike that passed through it.
    val reached = speed.firstOrNull { kotlin.math.abs(it.y - profilePeak) <= profilePeak * PEAK_BAND }
        ?: speed.firstOrNull { it.y >= profilePeak }
        ?: speed.maxByOrNull { it.y }
    return reached?.let { ChartPoint(it.x, profilePeak) }
}

private const val PEAK_BAND = 0.01
private const val NANOS_PER_MINUTE = 60e9
private const val DECI = 10.0
