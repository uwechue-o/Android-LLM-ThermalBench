package com.thermalbench.telemetry

import com.thermalbench.domain.port.DeviceConditionSource
import com.thermalbench.domain.port.DeviceProfiler
import com.thermalbench.domain.port.FrequencySource
import com.thermalbench.domain.port.MemorySource
import com.thermalbench.domain.port.MonotonicClock
import com.thermalbench.domain.port.PowerTelemetrySource
import com.thermalbench.domain.port.ThermalTelemetrySource
import com.thermalbench.domain.port.WallClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds every telemetry port to its Android implementation. Shared by :app and :harness. */
@Module
@InstallIn(SingletonComponent::class)
abstract class TelemetryModule {

    @Binds abstract fun monotonicClock(impl: ElapsedRealtimeClock): MonotonicClock

    @Binds abstract fun wallClock(impl: SystemWallClock): WallClock

    @Binds abstract fun thermal(impl: PowerManagerThermalSource): ThermalTelemetrySource

    @Binds abstract fun power(impl: BatteryPowerSource): PowerTelemetrySource

    @Binds abstract fun frequency(impl: SysfsFrequencySource): FrequencySource

    @Binds abstract fun memory(impl: AndroidMemorySource): MemorySource

    @Binds abstract fun profiler(impl: AndroidDeviceProfiler): DeviceProfiler

    @Binds abstract fun conditions(impl: AndroidDeviceConditionSource): DeviceConditionSource
}
