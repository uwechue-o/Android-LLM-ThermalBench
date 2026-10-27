package com.thermalbench.domain.model

enum class Phase { WARMUP, MEASURED }

/**
 * One inference iteration. All timestamps are monotonic
 * (`SystemClock.elapsedRealtimeNanos()`), never wall-clock (GUIDE.md §1).
 * Nullable fields are null where the platform withholds the value — never
 * fabricated, never defaulted.
 */
data class IterationSample(
    val index: Int,
    val phase: Phase,
    val startElapsedNanos: Long,
    val endElapsedNanos: Long,
    val timeToFirstTokenMicros: Long,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
    val promptTokenCount: Int,
    val outputTokenCount: Int,
    val thermalStatusAtStart: ThermalStatus,
    val thermalStatusAtEnd: ThermalStatus,
    val thermalHeadroomAtStart: Float?,
    val batteryTemperatureDeciC: Int?,
    val batteryPercent: Int,
    val cpuFrequenciesKhz: List<Long>?,
    val peakRssBytes: Long,
    val throttleObserved: Boolean,
    /** GPU clock at the start of the iteration; null where the platform withholds it. */
    val gpuFrequencyKhz: Long? = null,
    /** Every readable temperature sensor at the start of the iteration; null if none are. */
    val sensorTemperatures: List<SensorTemperature>? = null,
) {
    val durationNanos: Long get() = endElapsedNanos - startElapsedNanos
}

/** Polled thermal reading — a regular time series for plotting. */
data class ThermalSample(
    val elapsedNanos: Long,
    val status: ThermalStatus,
    val headroom: Float?,
    val batteryTemperatureDeciC: Int?,
    val cpuFrequenciesKhz: List<Long>?,
    val gpuFrequencyKhz: Long? = null,
    val sensorTemperatures: List<SensorTemperature>? = null,
)

/**
 * One temperature sensor, named as the device names it (a thermal zone type
 * such as "cpu-1-0-usr" or "gpu0-usr"). Names and coverage differ between
 * vendors, so readings compare across runs on one device, not across devices.
 */
data class SensorTemperature(val name: String, val celsius: Float)

/** Event-driven status change from the platform listener — exact throttle onset. */
data class ThermalTransition(
    val elapsedNanos: Long,
    val from: ThermalStatus,
    val to: ThermalStatus,
)

/**
 * Two independent thermal streams: polled samples give a regular series,
 * listener transitions give exact onset timestamps that polling would blur.
 */
data class ThermalTimeline(
    val samples: List<ThermalSample>,
    val transitions: List<ThermalTransition>,
) {
    companion object {
        val EMPTY = ThermalTimeline(emptyList(), emptyList())
    }
}

data class PowerSample(
    val elapsedNanos: Long,
    val batteryPercent: Int,
    /** Instantaneous current in microamps; sign convention is platform-defined. Null if unreadable. */
    val currentNowMicroAmps: Long?,
    /** Remaining charge in microampere-hours. Null if unreadable. */
    val chargeCounterMicroAh: Long?,
    val voltageMillivolts: Int?,
    val plugged: Boolean,
)

data class MemorySample(
    val elapsedNanos: Long,
    val rssBytes: Long,
    val availableSystemBytes: Long,
    val lowMemory: Boolean,
)
