package com.thermalbench.telemetry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Zone names and readings as a OnePlus 8T (Snapdragon 865) reports them. */
class ThermalZoneSourceTest {

    @Test
    fun `real sensors are kept`() {
        assertThat(ThermalZoneSource.isTemperatureZone("cpu-1-4-usr", 62.4f)).isTrue()
        assertThat(ThermalZoneSource.isTemperatureZone("gpuss-0-usr", 45.7f)).isTrue()
        assertThat(ThermalZoneSource.isTemperatureZone("cpu-gold-0-0-step", 56.2f)).isTrue()
        assertThat(ThermalZoneSource.isTemperatureZone("shell_back", 28.696f)).isTrue()
        assertThat(ThermalZoneSource.isTemperatureZone("battery", 30.8f)).isTrue()
        assertThat(ThermalZoneSource.isTemperatureZone("pm8150b_tz", 43.069f)).isTrue()
    }

    @Test
    fun `battery charge and limiter levels are not temperatures`() {
        assertThat(ThermalZoneSource.isTemperatureZone("soc", 99f)).isFalse()
        assertThat(ThermalZoneSource.isTemperatureZone("pm8150b-vbat-lvl0", 4.185f)).isFalse()
        assertThat(ThermalZoneSource.isTemperatureZone("pm8150b-bcl-lvl2", 0f)).isFalse()
        assertThat(ThermalZoneSource.isTemperatureZone("pm8150b-ibat-lvl1", 0f)).isFalse()
        assertThat(ThermalZoneSource.isTemperatureZone("pm8150l-vph-lvl1", 0f)).isFalse()
    }

    @Test
    fun `unpopulated and implausible sensors are dropped`() {
        assertThat(ThermalZoneSource.isTemperatureZone("xo-therm-usr", -40f)).isFalse()
        assertThat(ThermalZoneSource.isTemperatureZone("mmw-pa1-usr", -40f)).isFalse()
        assertThat(ThermalZoneSource.isTemperatureZone("cpu-0-0-usr", 999f)).isFalse()
    }
}
