package com.thermalbench.analysis

import com.google.common.truth.Truth.assertThat
import com.thermalbench.domain.model.IterationSample
import com.thermalbench.domain.model.Phase
import com.thermalbench.domain.model.TerminationReason
import com.thermalbench.domain.model.ThermalSample
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.ThermalTimeline
import com.thermalbench.domain.model.ThermalTransition
import com.thermalbench.domain.model.ValidityVerdict
import org.junit.jupiter.api.Test
import kotlin.math.exp

class DefaultDegradationAnalyzerTest {

    private val analyzer = DefaultDegradationAnalyzer()

    /** tps(t) = 8 + 12 * exp(-t / 60s), one iteration every ~4 s, throttle at iteration 15. */
    private fun syntheticRun(count: Int = 60, warmup: Int = 3): List<IterationSample> {
        val iterationNanos = 4_000_000_000L
        return (0 until count).map { i ->
            val t = i * 4.0
            val tps = 8.0 + 12.0 * exp(-t / 60.0)
            val status = if (i >= 15 + warmup) ThermalStatus.MODERATE else ThermalStatus.NONE
            IterationSample(
                index = i,
                phase = if (i < warmup) Phase.WARMUP else Phase.MEASURED,
                startElapsedNanos = 1_000_000_000L + i * iterationNanos,
                endElapsedNanos = 1_000_000_000L + (i + 1) * iterationNanos,
                timeToFirstTokenMicros = 120_000L + i * 500L,
                prefillTokensPerSecond = 400.0,
                decodeTokensPerSecond = tps,
                promptTokenCount = 128,
                outputTokenCount = 64,
                thermalStatusAtStart = status,
                thermalStatusAtEnd = status,
                thermalHeadroomAtStart = 0.5f,
                batteryTemperatureDeciC = 300 + i,
                batteryPercent = 90 - i / 10,
                cpuFrequenciesKhz = null,
                peakRssBytes = 1_500_000_000L,
                throttleObserved = status.isThrottling,
            )
        }
    }

    @Test
    fun `retention ratio is steady state over peak`() {
        val profile = analyzer.analyze(
            iterations = syntheticRun(),
            timeline = ThermalTimeline.EMPTY,
            terminated = TerminationReason.COMPLETED,
            preconditionsSatisfied = true,
            minMeasuredIterations = 50,
        )
        assertThat(profile.validity).isEqualTo(ValidityVerdict.VALID)
        assertThat(profile.measuredIterationCount).isEqualTo(57)
        assertThat(profile.peakDecodeTokensPerSecond).isWithin(1.0).of(16.6)
        assertThat(profile.steadyStateTokensPerSecond).isNotNull()
        assertThat(profile.steadyStateTokensPerSecond!!).isWithin(0.8).of(8.4)
        assertThat(profile.retentionRatio!!).isWithin(0.1).of(0.5)
        assertThat(profile.ttftDriftMicrosPerIteration).isWithin(1.0).of(500.0)
        assertThat(profile.decayModel).isNotNull()
        assertThat(profile.decayModel!!.timeConstantSeconds).isWithin(6.0).of(60.0)
        assertThat(profile.decayModel!!.rSquared).isGreaterThan(0.98)
    }

    @Test
    fun `throttle onset prefers listener transitions over iteration boundaries`() {
        val onsetNanos = 1_000_000_000L + 18L * 4_000_000_000L + 1_500_000_000L
        val timeline = ThermalTimeline(
            samples = emptyList(),
            transitions = listOf(ThermalTransition(onsetNanos, ThermalStatus.NONE, ThermalStatus.LIGHT)),
        )
        val profile = analyzer.analyze(syntheticRun(), timeline, TerminationReason.COMPLETED, true, 50)
        assertThat(profile.throttleEvents).hasSize(1)
        assertThat(profile.throttleEvents.single().to).isEqualTo(ThermalStatus.LIGHT)
        assertThat(profile.timeToFirstThrottle!!.inWholeSeconds).isEqualTo(73)
        assertThat(profile.iterationsToFirstThrottle).isEqualTo(15)
    }

    @Test
    fun `a run with no iterations times its throttle from its first telemetry sample`() {
        // Seen on a OnePlus 8T: the GPU never produced a token, the device
        // reached SEVERE five minutes in, and the time reported was the
        // device's uptime because the run's start was taken as zero.
        val startNanos = 35_012_000_000_000L
        val timeline = ThermalTimeline(
            samples = listOf(ThermalSample(startNanos, ThermalStatus.NONE, 0.49f, 288, null)),
            transitions = listOf(
                ThermalTransition(startNanos + 340_000_000_000L, ThermalStatus.NONE, ThermalStatus.SEVERE),
            ),
        )
        val profile = analyzer.analyze(emptyList(), timeline, TerminationReason.ERROR, true, 50)
        assertThat(profile.timeToFirstThrottle!!.inWholeSeconds).isEqualTo(340)

        val noSamples = analyzer.analyze(emptyList(), timeline.copy(samples = emptyList()), TerminationReason.ERROR, true, 50)
        assertThat(noSamples.timeToFirstThrottle).isNull()
    }

    @Test
    fun `unmet preconditions make the result invalid regardless of data`() {
        val profile = analyzer.analyze(syntheticRun(), ThermalTimeline.EMPTY, TerminationReason.COMPLETED, false, 50)
        assertThat(profile.validity).isEqualTo(ValidityVerdict.PRECONDITIONS_UNMET)
    }

    @Test
    fun `short run is insufficient data unless the thermal floor ended it`() {
        val short = syntheticRun(count = 25)
        val timedOut = analyzer.analyze(short, ThermalTimeline.EMPTY, TerminationReason.TIMEOUT, true, 50)
        assertThat(timedOut.validity).isEqualTo(ValidityVerdict.INSUFFICIENT_DATA)

        val thermal = analyzer.analyze(short, ThermalTimeline.EMPTY, TerminationReason.THERMAL_CRITICAL, true, 50)
        assertThat(thermal.validity).isEqualTo(ValidityVerdict.VALID)
    }

    @Test
    fun `steady state detector returns null while still decaying`() {
        val decaying = DoubleArray(30) { 20.0 - it * 0.5 }
        assertThat(SteadyStateDetector().detect(decaying)).isNull()
        val flat = DoubleArray(30) { 10.0 + (it % 2) * 0.1 }
        assertThat(SteadyStateDetector().detect(flat)!!.value).isWithin(0.1).of(10.05)
    }
}
