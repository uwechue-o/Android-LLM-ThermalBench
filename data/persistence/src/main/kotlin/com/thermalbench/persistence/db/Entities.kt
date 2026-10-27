package com.thermalbench.persistence.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Summary index of every run this device knows about. The authoritative copy of
 * a finished run is its canonical JSON document in the artifact store; this
 * table exists so the history list does not have to parse every document, and
 * so a run that crashed still leaves a trace.
 */
@Entity(tableName = "runs", indices = [Index("specId"), Index("startedAtEpochMillis")])
data class RunEntity(
    @PrimaryKey val resultId: String,
    val specId: String,
    val label: String,
    val deviceModel: String,
    val backendId: String,
    val delegate: String,
    val modelName: String,
    val startedAtEpochMillis: Long,
    val measuredIterations: Int,
    val retentionRatio: Double?,
    val validity: String?,
    val terminated: String,
    /** File name of the canonical document in the artifact store, when written. */
    val documentFileName: String?,
    /** False while a run is still in progress or died before it could be finalized. */
    val complete: Boolean,
)

/**
 * Iteration samples written incrementally during a run. A crash at iteration 47
 * must not lose the 46 that came before it (GUIDE.md §4.2), so these rows are
 * flushed in batched transactions rather than held in memory until the end.
 */
@Entity(tableName = "iteration_samples", primaryKeys = ["resultId", "iterationIndex"])
data class IterationSampleEntity(
    val resultId: String,
    @ColumnInfo(name = "iterationIndex") val index: Int,
    val phase: String,
    val startElapsedNanos: Long,
    val endElapsedNanos: Long,
    val timeToFirstTokenMicros: Long,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
    val promptTokenCount: Int,
    val outputTokenCount: Int,
    val thermalStatusAtStart: String,
    val thermalStatusAtEnd: String,
    val thermalHeadroomAtStart: Float?,
    val batteryTemperatureDeciC: Int?,
    val batteryPercent: Int,
    /** Pipe-separated, or null where the platform withheld the values. */
    val cpuFrequenciesKhz: String?,
    val peakRssBytes: Long,
    val throttleObserved: Boolean,
)

@Entity(tableName = "thermal_samples", indices = [Index("resultId")])
data class ThermalSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val resultId: String,
    val elapsedNanos: Long,
    val status: String,
    val headroom: Float?,
    val batteryTemperatureDeciC: Int?,
    val cpuFrequenciesKhz: String?,
)

@Entity(tableName = "thermal_transitions", indices = [Index("resultId")])
data class ThermalTransitionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val resultId: String,
    val elapsedNanos: Long,
    val fromStatus: String,
    val toStatus: String,
)

@Entity(tableName = "power_samples", indices = [Index("resultId")])
data class PowerSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val resultId: String,
    val elapsedNanos: Long,
    val batteryPercent: Int,
    val currentNowMicroAmps: Long?,
    val chargeCounterMicroAh: Long?,
    val voltageMillivolts: Int?,
    val plugged: Boolean,
)

/** Stored benchmark specs, as canonical JSON so they round-trip byte for byte. */
@Entity(tableName = "specs")
data class SpecEntity(
    @PrimaryKey val specId: String,
    val label: String,
    val document: String,
    val savedAtEpochMillis: Long,
)
