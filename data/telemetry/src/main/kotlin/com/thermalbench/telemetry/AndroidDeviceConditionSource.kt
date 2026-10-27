package com.thermalbench.telemetry

import android.content.Context
import android.os.PowerManager
import com.thermalbench.domain.model.DeviceConditions
import com.thermalbench.domain.port.DeviceConditionSource
import com.thermalbench.domain.port.MonotonicClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/** One coherent snapshot of everything the precondition gate evaluates. */
@Singleton
class AndroidDeviceConditionSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: MonotonicClock,
    private val thermal: PowerManagerThermalSource,
    private val battery: BatteryPowerSource,
) : DeviceConditionSource {

    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    override suspend fun snapshot(): DeviceConditions = DeviceConditions(
        elapsedNanos = clock.nanos(),
        thermalStatus = thermal.currentStatus(),
        thermalHeadroom = thermal.headroom(HEADROOM_FORECAST_SECONDS),
        batteryPercent = battery.percent(),
        plugged = battery.plugged(),
        screenOn = powerManager.isInteractive,
        batteryTemperatureDeciC = battery.temperatureDeciC(),
    )

    override fun observe(interval: Duration): Flow<DeviceConditions> = flow {
        while (true) {
            emit(snapshot())
            delay(interval)
        }
    }

    private companion object {
        const val HEADROOM_FORECAST_SECONDS = 10
    }
}
