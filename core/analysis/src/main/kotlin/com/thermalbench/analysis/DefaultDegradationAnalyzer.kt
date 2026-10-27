package com.thermalbench.analysis

import com.thermalbench.domain.model.DegradationProfile
import com.thermalbench.domain.model.IterationSample
import com.thermalbench.domain.model.Phase
import com.thermalbench.domain.model.TerminationReason
import com.thermalbench.domain.model.ThermalTimeline
import com.thermalbench.domain.model.ThrottleEvent
import com.thermalbench.domain.model.ValidityVerdict
import com.thermalbench.domain.port.DegradationAnalyzer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The one implementation of the analysis (GUIDE.md §2): it runs on-device for
 * the live chart and offline in tools/analysis-cli. Two implementations of the
 * same math is how discrepancies get published.
 */
class DefaultDegradationAnalyzer(
    private val steadyState: SteadyStateDetector = SteadyStateDetector(),
    private val peakWindow: Int = DEFAULT_PEAK_WINDOW,
    private val minIterationsForAnalysis: Int = MIN_ITERATIONS_FOR_ANALYSIS,
) : DegradationAnalyzer {

    override fun analyze(
        iterations: List<IterationSample>,
        timeline: ThermalTimeline,
        terminated: TerminationReason,
        preconditionsSatisfied: Boolean,
        minMeasuredIterations: Int,
    ): DegradationProfile {
        val measured = iterations.filter { it.phase == Phase.MEASURED }.sortedBy { it.index }
        // A run that ended before its first iteration finished still started:
        // its telemetry began with it. Without either there is no start, and a
        // throttle has no time to report.
        val runStartNanos = iterations.minOfOrNull { it.startElapsedNanos }
            ?: timeline.samples.minOfOrNull { it.elapsedNanos }
        val throttleEvents = throttleEvents(iterations, timeline)
        val firstThrottle = throttleEvents.firstOrNull()
        val timeToFirstThrottle = firstThrottle?.let { event ->
            runStartNanos?.let { (event.elapsedNanos - it).nanoseconds.coerceAtLeast(Duration.ZERO) }
        }

        val validity = when {
            !preconditionsSatisfied -> ValidityVerdict.PRECONDITIONS_UNMET
            measured.size < minIterationsForAnalysis -> ValidityVerdict.INSUFFICIENT_DATA
            // A run cut short by the OS thermal floor is a finding, not a gap;
            // any other early termination that misses the protocol is a gap.
            measured.size < minMeasuredIterations && terminated != TerminationReason.THERMAL_CRITICAL ->
                ValidityVerdict.INSUFFICIENT_DATA
            else -> ValidityVerdict.VALID
        }

        if (measured.isEmpty()) {
            return DegradationProfile(
                peakDecodeTokensPerSecond = 0.0,
                steadyStateTokensPerSecond = null,
                retentionRatio = null,
                timeToFirstThrottle = timeToFirstThrottle,
                iterationsToFirstThrottle = firstThrottle?.iterationIndex,
                decayModel = null,
                ttftDriftMicrosPerIteration = 0.0,
                throttleEvents = throttleEvents,
                terminated = terminated,
                validity = validity,
                measuredIterationCount = 0,
                meanDecodeTokensPerSecond = 0.0,
                minDecodeTokensPerSecond = 0.0,
                batteryDrainPercent = null,
                batteryTemperatureRiseDeciC = null,
            )
        }

        val decode = DoubleArray(measured.size) { measured[it].decodeTokensPerSecond }
        val firstStartNanos = iterations.minOf { it.startElapsedNanos }
        val timesSeconds = DoubleArray(measured.size) { (measured[it].startElapsedNanos - firstStartNanos) / 1e9 }
        val ttft = DoubleArray(measured.size) { measured[it].timeToFirstTokenMicros.toDouble() }
        val index = DoubleArray(measured.size) { it.toDouble() }

        // Peak: best sustained window, not a single outlier.
        val rolling = Statistics.rollingMedian(decode, minOf(peakWindow, decode.size))
        val peak = if (rolling.isEmpty()) Statistics.median(decode) else rolling.max()

        val steady = steadyState.detect(decode)
        val steadyValue = steady?.value
        val retention = steadyValue?.let { if (peak > 0.0) it / peak else null }

        val fit = ExponentialDecayFitter.fit(timesSeconds, decode)
        val drift = Statistics.linearRegression(index, ttft).slope

        val first = measured.first()
        val last = measured.last()
        val batteryDrain = (first.batteryPercent - last.batteryPercent).takeIf { it >= 0 }
        val tempRise = if (first.batteryTemperatureDeciC != null && last.batteryTemperatureDeciC != null) {
            last.batteryTemperatureDeciC!! - first.batteryTemperatureDeciC!!
        } else {
            null
        }

        return DegradationProfile(
            peakDecodeTokensPerSecond = peak,
            steadyStateTokensPerSecond = steadyValue,
            retentionRatio = retention,
            timeToFirstThrottle = timeToFirstThrottle,
            iterationsToFirstThrottle = firstThrottle?.iterationIndex?.let { idx -> measuredIndexAtOrAfter(measured, idx) },
            decayModel = fit,
            ttftDriftMicrosPerIteration = drift,
            throttleEvents = throttleEvents,
            terminated = terminated,
            validity = validity,
            measuredIterationCount = measured.size,
            meanDecodeTokensPerSecond = Statistics.mean(decode),
            minDecodeTokensPerSecond = decode.min(),
            batteryDrainPercent = batteryDrain,
            batteryTemperatureRiseDeciC = tempRise,
        )
    }

    /**
     * Listener transitions give exact onset timestamps; iteration boundaries
     * are the fallback when the platform delivered no listener events.
     */
    private fun throttleEvents(iterations: List<IterationSample>, timeline: ThermalTimeline): List<ThrottleEvent> {
        val fromListener = timeline.transitions
            .filter { it.to.isThrottling && it.to > it.from }
            .map { t ->
                ThrottleEvent(
                    iterationIndex = iterations.firstOrNull { it.endElapsedNanos >= t.elapsedNanos }?.index,
                    elapsedNanos = t.elapsedNanos,
                    from = t.from,
                    to = t.to,
                )
            }
        if (fromListener.isNotEmpty()) return fromListener.sortedBy { it.elapsedNanos }

        return iterations.sortedBy { it.index }
            .zipWithNext()
            .filter { (prev, next) -> next.thermalStatusAtEnd > prev.thermalStatusAtEnd && next.thermalStatusAtEnd.isThrottling }
            .map { (prev, next) ->
                ThrottleEvent(next.index, next.endElapsedNanos, prev.thermalStatusAtEnd, next.thermalStatusAtEnd)
            }
    }

    /** Position within the measured series (0-based) of the first measured iteration at or after [absoluteIndex]. */
    private fun measuredIndexAtOrAfter(measured: List<IterationSample>, absoluteIndex: Int): Int? {
        val pos = measured.indexOfFirst { it.index >= absoluteIndex }
        return if (pos < 0) null else pos
    }

    companion object {
        const val DEFAULT_PEAK_WINDOW = 5
        const val MIN_ITERATIONS_FOR_ANALYSIS = 10
    }
}
