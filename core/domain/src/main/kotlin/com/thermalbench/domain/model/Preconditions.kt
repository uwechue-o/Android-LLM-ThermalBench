package com.thermalbench.domain.model

import java.util.Locale
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Mirrors `PowerManager.THERMAL_STATUS_*` (API 29+). Ordinal order is severity
 * order, so comparisons like `status >= SEVERE` are meaningful.
 */
enum class ThermalStatus {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,

    /** The platform returned a value this tool does not know. Recorded, never mapped. */
    UNKNOWN,
    ;

    val isThrottling: Boolean get() = this != NONE && this != UNKNOWN
}

enum class ScreenPolicy {
    /** Screen on at minimum brightness; the app path's default. */
    ON_DIMMED,

    /** Screen off; the headless harness's default. */
    OFF,
}

/**
 * Preconditions are part of the measurement (GUIDE.md §1). A run that starts
 * without satisfying its policy is not a failed run — it is an invalid run.
 */
data class PreconditionPolicy(
    /** Typically NONE. */
    val requiredThermalStatus: ThermalStatus,
    /** API 30+, 0.0..1.0. Null skips the check (and records that it was skipped). */
    val minThermalHeadroom: Float?,
    /** Charging generates heat — default true. */
    val requireUnplugged: Boolean,
    /** Default 50; low battery triggers its own throttling. */
    val minBatteryPercent: Int,
    /** Must be fixed, not incidental. */
    val screenPolicy: ScreenPolicy,
    /** How long every condition must hold before the run starts. */
    val stabilizationWindow: Duration,
    val maxWaitForPreconditions: Duration,
) {
    init {
        require(minBatteryPercent in 0..100) { "minBatteryPercent must be 0..100" }
        minThermalHeadroom?.let { require(it in 0f..1f) { "minThermalHeadroom must be 0..1" } }
        require(!stabilizationWindow.isNegative()) { "stabilizationWindow must be >= 0" }
        require(maxWaitForPreconditions.isPositive()) { "maxWaitForPreconditions must be > 0" }
    }

    companion object {
        val DEFAULT: PreconditionPolicy = PreconditionPolicy(
            requiredThermalStatus = ThermalStatus.NONE,
            minThermalHeadroom = 0.5f,
            requireUnplugged = true,
            minBatteryPercent = 50,
            screenPolicy = ScreenPolicy.OFF,
            stabilizationWindow = 30.seconds,
            maxWaitForPreconditions = 15.minutes,
        )
    }
}

/** One instantaneous reading of everything the precondition gate cares about. */
data class DeviceConditions(
    val elapsedNanos: Long,
    val thermalStatus: ThermalStatus,
    /** Null when the API is unavailable or the OEM returns NaN. */
    val thermalHeadroom: Float?,
    val batteryPercent: Int,
    val plugged: Boolean,
    val screenOn: Boolean,
    val batteryTemperatureDeciC: Int?,
)

sealed interface UnmetCondition {
    data class ThermalStatusTooHigh(val current: ThermalStatus, val required: ThermalStatus) : UnmetCondition
    data class ThermalHeadroomTooLow(val current: Float?, val required: Float) : UnmetCondition
    data object Charging : UnmetCondition
    data class BatteryTooLow(val current: Int, val required: Int) : UnmetCondition
    data class ScreenState(val current: Boolean, val required: ScreenPolicy) : UnmetCondition

    val description: String
        get() = when (this) {
            is ThermalStatusTooHigh -> "Thermal status is $current; need $required"
            // Reports the margin the check tests, not the raw platform value:
            // printing 0.72 beside "need >= 0.5" read as a broken comparison.
            is ThermalHeadroomTooLow ->
                "Thermal headroom margin is ${current?.let { formatThermalMargin(1f - it) } ?: "unavailable"}; " +
                    "need >= $required"
            Charging -> "Device is charging; unplug it"
            is BatteryTooLow -> "Battery at $current%; need >= $required%"
            // Says what to do, like "unplug it" does. A bare "policy requires OFF"
            // left people wondering how a run continues with the screen off; it
            // does, because the run holds the CPU awake in the background.
            is ScreenState -> when {
                current && required == ScreenPolicy.OFF ->
                    "Screen is on; press the power button. The run carries on with the screen off " +
                        "and starts by itself"
                !current && required == ScreenPolicy.ON_DIMMED -> "Screen is off; turn it on and leave it dimmed"
                else -> "Screen is ${if (current) "on" else "off"}; policy requires $required"
            }
        }
}

sealed interface PreconditionState {
    data class Waiting(
        val unmet: List<UnmetCondition>,
        /** How long every condition has held so far (resets whenever one fails). */
        val elapsedInWindow: Duration,
        val estimatedReady: Duration?,
        val latest: DeviceConditions,
    ) : PreconditionState

    data class Satisfied(val at: DeviceConditions) : PreconditionState

    data class TimedOut(val unmet: List<UnmetCondition>, val latest: DeviceConditions) : PreconditionState
}

/** Evaluates a policy against a reading. Pure, so it is unit-testable without a device. */
fun PreconditionPolicy.evaluate(c: DeviceConditions): List<UnmetCondition> = buildList {
    if (c.thermalStatus == ThermalStatus.UNKNOWN || c.thermalStatus > requiredThermalStatus) {
        add(UnmetCondition.ThermalStatusTooHigh(c.thermalStatus, requiredThermalStatus))
    }
    minThermalHeadroom?.let { required ->
        val headroom = c.thermalHeadroom
        // The platform value is closeness to throttling: 0.0 is cool and 1.0 is
        // severe throttling now. The policy is a minimum *margin*, 1 - headroom.
        if (headroom != null && (1f - headroom) < required) {
            add(UnmetCondition.ThermalHeadroomTooLow(headroom, required))
        }
    }
    if (requireUnplugged && c.plugged) add(UnmetCondition.Charging)
    if (c.batteryPercent < minBatteryPercent) add(UnmetCondition.BatteryTooLow(c.batteryPercent, minBatteryPercent))
    val screenOk = when (screenPolicy) {
        ScreenPolicy.ON_DIMMED -> c.screenOn
        ScreenPolicy.OFF -> !c.screenOn
    }
    if (!screenOk) add(UnmetCondition.ScreenState(c.screenOn, screenPolicy))
}

/**
 * A thermal headroom margin as every surface shows it: the precondition
 * message, the gate screen, the notification and the spoken readout. One
 * formatter, so they never disagree about the same reading.
 *
 * Two decimals, rounded *down*, so a margin is never shown as having reached
 * a threshold it has not: 0.499 reads 0.49, not the 0.50 target. The slack
 * absorbs float error, since a platform headroom of 0.6 leaves a margin of
 * 0.39999998, which is 0.40. Fixed locale, so a device set to German does not
 * print 0,40.
 */
fun formatThermalMargin(margin: Float): String {
    val hundredths = floor(margin * HUNDREDTHS + FLOAT_SLACK) / HUNDREDTHS
    return String.format(Locale.US, "%.2f", hundredths)
}

private const val HUNDREDTHS = 100.0
private const val FLOAT_SLACK = 1e-4
