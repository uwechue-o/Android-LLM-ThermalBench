package com.thermalbench.domain.port

import com.thermalbench.domain.model.DeviceConditions
import com.thermalbench.domain.model.DeviceDescriptor
import com.thermalbench.domain.model.MemorySample
import com.thermalbench.domain.model.PowerSample
import com.thermalbench.domain.model.SensorTemperature
import com.thermalbench.domain.model.ThermalSample
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.ThermalTransition
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** Monotonic time only. Wall-clock never enters a duration (GUIDE.md §1). */
interface MonotonicClock {
    fun nanos(): Long
}

/** The single wall-clock read per run, used for labelling the document. */
interface WallClock {
    fun epochMillis(): Long
}

interface ThermalTelemetrySource {
    /** Regular polled series. */
    fun poll(interval: Duration): Flow<ThermalSample>

    /** Event-driven status changes from the platform listener. */
    fun transitions(): Flow<ThermalTransition>

    /** API 30+ headroom forecast; null if unsupported or the OEM returns NaN. */
    suspend fun headroom(forecastSeconds: Int): Float?

    suspend fun currentStatus(): ThermalStatus

    /** Every temperature sensor readable on this device; null when none are. */
    suspend fun sensorTemperatures(): List<SensorTemperature>? = null
}

interface PowerTelemetrySource {
    fun stream(interval: Duration): Flow<PowerSample>

    suspend fun snapshot(): PowerSample
}

/** Returns null where `/sys/.../scaling_cur_freq` is unreadable. Never a guess. */
interface FrequencySource {
    suspend fun readCpuFrequenciesKhz(): List<Long>?

    suspend fun readCpuMaxFrequenciesKhz(): List<Long>?

    /** Current GPU clock; null where no known GPU clock node is readable. */
    suspend fun readGpuFrequencyKhz(): Long? = null
}

interface MemorySource {
    suspend fun snapshot(): MemorySample
}

interface DeviceProfiler {
    suspend fun profile(): DeviceDescriptor
}

/** Everything the precondition gate needs, as one coherent snapshot stream. */
interface DeviceConditionSource {
    fun observe(interval: Duration): Flow<DeviceConditions>

    suspend fun snapshot(): DeviceConditions
}
