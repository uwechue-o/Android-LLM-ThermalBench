package com.thermalbench.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.thermalbench.domain.model.PowerSample
import com.thermalbench.domain.port.MonotonicClock
import com.thermalbench.domain.port.PowerTelemetrySource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * Battery telemetry from the sticky `ACTION_BATTERY_CHANGED` broadcast plus
 * `BatteryManager` properties. Battery temperature is coarse (deci-°C) and lags
 * the die by minutes: corroboration, never the primary thermal signal.
 */
@Singleton
class BatteryPowerSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: MonotonicClock,
) : PowerTelemetrySource {

    private val batteryManager: BatteryManager by lazy {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }

    private fun stickyIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    fun percent(): Int {
        val intent = stickyIntent()
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) return (level * 100) / scale
        val fromManager = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (fromManager in 0..100) fromManager else 0
    }

    fun plugged(): Boolean =
        (stickyIntent()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    /** Deci-degrees Celsius, or null when the platform reports nothing. */
    fun temperatureDeciC(): Int? =
        stickyIntent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE && it != 0 }

    fun voltageMillivolts(): Int? =
        stickyIntent()?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE && it > 0 }

    override suspend fun snapshot(): PowerSample {
        val currentNow = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val chargeCounter = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        return PowerSample(
            elapsedNanos = clock.nanos(),
            batteryPercent = percent(),
            currentNowMicroAmps = currentNow.takeIf { it != Long.MIN_VALUE },
            chargeCounterMicroAh = chargeCounter.takeIf { it != Long.MIN_VALUE },
            voltageMillivolts = voltageMillivolts(),
            plugged = plugged(),
        )
    }

    override fun stream(interval: Duration): Flow<PowerSample> = flow {
        while (true) {
            emit(snapshot())
            delay(interval)
        }
    }
}
