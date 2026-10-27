package com.thermalbench.telemetry

import android.os.PowerManager
import com.thermalbench.domain.model.ThermalStatus

/** `PowerManager.THERMAL_STATUS_*` ⇄ [ThermalStatus]. Unknown ints stay UNKNOWN; never guessed. */
object ThermalStatusMapping {

    fun fromPlatform(status: Int): ThermalStatus = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
        else -> ThermalStatus.UNKNOWN
    }
}
