package com.thermalbench.domain.model

import kotlin.time.Duration

enum class TerminationReason { COMPLETED, THERMAL_CRITICAL, TIMEOUT, ERROR, ABORTED_BY_USER }

enum class ValidityVerdict { VALID, PRECONDITIONS_UNMET, INSUFFICIENT_DATA }

/** Exponential decay `tps(t) = asymptote + amplitude * exp(-t / timeConstantSeconds)`. */
data class DecayFit(
    val asymptote: Double,
    val amplitude: Double,
    val timeConstantSeconds: Double,
    val rSquared: Double,
)

data class ThrottleEvent(
    val iterationIndex: Int?,
    val elapsedNanos: Long,
    val from: ThermalStatus,
    val to: ThermalStatus,
)

/**
 * The analysis product. [retentionRatio] is the headline number: what fraction
 * of peak throughput the device can actually sustain.
 */
data class DegradationProfile(
    /** Best sustained window, not a single outlier. */
    val peakDecodeTokensPerSecond: Double,
    /** Null if the run never stabilized. */
    val steadyStateTokensPerSecond: Double?,
    /** steadyState / peak. */
    val retentionRatio: Double?,
    val timeToFirstThrottle: Duration?,
    val iterationsToFirstThrottle: Int?,
    val decayModel: DecayFit?,
    val ttftDriftMicrosPerIteration: Double,
    val throttleEvents: List<ThrottleEvent>,
    val terminated: TerminationReason,
    val validity: ValidityVerdict,
    /** Number of measured iterations the analysis was computed over. */
    val measuredIterationCount: Int,
    val meanDecodeTokensPerSecond: Double,
    val minDecodeTokensPerSecond: Double,
    val batteryDrainPercent: Int?,
    val batteryTemperatureRiseDeciC: Int?,
)
