package com.thermalbench.telemetry

import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import com.thermalbench.domain.model.ThermalStatus
import org.junit.Test

class ThermalStatusMappingTest {

    @Test
    fun `platform constants map in severity order`() {
        assertThat(ThermalStatusMapping.fromPlatform(PowerManager.THERMAL_STATUS_NONE)).isEqualTo(ThermalStatus.NONE)
        assertThat(ThermalStatusMapping.fromPlatform(PowerManager.THERMAL_STATUS_SEVERE)).isEqualTo(ThermalStatus.SEVERE)
        assertThat(ThermalStatusMapping.fromPlatform(PowerManager.THERMAL_STATUS_SHUTDOWN)).isEqualTo(ThermalStatus.SHUTDOWN)
        assertThat(ThermalStatus.SEVERE > ThermalStatus.LIGHT).isTrue()
    }

    @Test
    fun `unknown platform values are never guessed`() {
        assertThat(ThermalStatusMapping.fromPlatform(99)).isEqualTo(ThermalStatus.UNKNOWN)
        assertThat(ThermalStatus.UNKNOWN.isThrottling).isFalse()
    }
}
