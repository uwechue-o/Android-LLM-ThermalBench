package com.thermalbench.telemetry

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.thermalbench.domain.model.SensorTemperature
import com.thermalbench.domain.model.ThermalSample
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.ThermalTransition
import com.thermalbench.domain.port.FrequencySource
import com.thermalbench.domain.port.MonotonicClock
import com.thermalbench.domain.port.ThermalTelemetrySource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * The primary thermal signal (GUIDE.md §6): `PowerManager.getCurrentThermalStatus()`
 * for the coarse status and `getThermalHeadroom()` for the continuous 0..1 value
 * on API 30+. Some OEMs return NaN for headroom; that becomes null.
 */
@Singleton
class PowerManagerThermalSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: MonotonicClock,
    private val frequency: FrequencySource,
    private val battery: BatteryPowerSource,
    private val sensors: ThermalZoneSource,
) : ThermalTelemetrySource {

    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    override suspend fun currentStatus(): ThermalStatus =
        ThermalStatusMapping.fromPlatform(powerManager.currentThermalStatus)

    override suspend fun headroom(forecastSeconds: Int): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val value = try {
            powerManager.getThermalHeadroom(forecastSeconds)
        } catch (_: Exception) {
            return null
        }
        return if (value.isNaN() || value < 0f) null else value
    }

    override fun poll(interval: Duration): Flow<ThermalSample> = flow {
        while (true) {
            emit(
                ThermalSample(
                    elapsedNanos = clock.nanos(),
                    status = currentStatus(),
                    headroom = headroom(HEADROOM_FORECAST_SECONDS),
                    batteryTemperatureDeciC = battery.temperatureDeciC(),
                    cpuFrequenciesKhz = frequency.readCpuFrequenciesKhz(),
                    gpuFrequencyKhz = frequency.readGpuFrequencyKhz(),
                    sensorTemperatures = sensors.read(),
                ),
            )
            delay(interval)
        }
    }

    override suspend fun sensorTemperatures(): List<SensorTemperature>? = sensors.read()

    override fun transitions(): Flow<ThermalTransition> = callbackFlow {
        var last = ThermalStatusMapping.fromPlatform(powerManager.currentThermalStatus)
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            val now = clock.nanos()
            val next = ThermalStatusMapping.fromPlatform(status)
            if (next != last) {
                trySend(ThermalTransition(now, last, next))
                last = next
            }
        }
        // A direct executor: the callback is tiny and we want the timestamp
        // taken as close to the platform event as possible.
        val executor = Executor { it.run() }
        powerManager.addThermalStatusListener(executor, listener)
        awaitClose { powerManager.removeThermalStatusListener(listener) }
    }

    val headroomSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private companion object {
        const val HEADROOM_FORECAST_SECONDS = 10
    }
}
