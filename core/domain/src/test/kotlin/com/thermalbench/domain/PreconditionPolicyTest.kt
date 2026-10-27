package com.thermalbench.domain

import com.google.common.truth.Truth.assertThat
import com.thermalbench.domain.model.DeviceConditions
import com.thermalbench.domain.model.PreconditionPolicy
import com.thermalbench.domain.model.ScreenPolicy
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.UnmetCondition
import com.thermalbench.domain.model.evaluate
import com.thermalbench.domain.model.formatThermalMargin
import org.junit.jupiter.api.Test

class PreconditionPolicyTest {

    private val policy = PreconditionPolicy.DEFAULT

    private fun conditions(
        thermal: ThermalStatus = ThermalStatus.NONE,
        headroom: Float? = 0.2f,
        battery: Int = 80,
        plugged: Boolean = false,
        screenOn: Boolean = false,
    ) = DeviceConditions(
        elapsedNanos = 1_000L,
        thermalStatus = thermal,
        thermalHeadroom = headroom,
        batteryPercent = battery,
        plugged = plugged,
        screenOn = screenOn,
        batteryTemperatureDeciC = 300,
    )

    @Test
    fun `a cold unplugged device with the screen off satisfies the default policy`() {
        assertThat(policy.evaluate(conditions())).isEmpty()
    }

    @Test
    fun `charging blocks a run because it is a heat source`() {
        assertThat(policy.evaluate(conditions(plugged = true)))
            .contains(UnmetCondition.Charging)
    }

    @Test
    fun `a warm device blocks a run`() {
        val unmet = policy.evaluate(conditions(thermal = ThermalStatus.MODERATE))
        assertThat(unmet).hasSize(1)
        assertThat(unmet.single()).isInstanceOf(UnmetCondition.ThermalStatusTooHigh::class.java)
    }

    @Test
    fun `an unknown thermal status blocks a run rather than being read as cold`() {
        val unmet = policy.evaluate(conditions(thermal = ThermalStatus.UNKNOWN))
        assertThat(unmet).isNotEmpty()
    }

    @Test
    fun `unavailable headroom does not block a run`() {
        // Vendors that return NaN would otherwise make the tool unusable on
        // their devices; the absence is recorded instead.
        assertThat(policy.evaluate(conditions(headroom = null))).isEmpty()
    }

    @Test
    fun `low headroom blocks a run`() {
        val unmet = policy.evaluate(conditions(headroom = 0.8f))
        assertThat(unmet.single()).isInstanceOf(UnmetCondition.ThermalHeadroomTooLow::class.java)
    }

    @Test
    fun `the headroom message reports the margin the check tests`() {
        // The platform said 0.72 (close to throttling); the margin is 0.2794,
        // shown rounded down. Printing 0.72 beside "need >= 0.5" read as a
        // broken comparison.
        val unmet = policy.evaluate(conditions(headroom = 0.7206128f)).single()
        assertThat(unmet.description).isEqualTo("Thermal headroom margin is 0.27; need >= 0.5")
    }

    @Test
    fun `a margin is shown rounded down, never as a threshold it has not reached`() {
        assertThat(formatThermalMargin(0.499f)).isEqualTo("0.49")
        assertThat(formatThermalMargin(0.5f)).isEqualTo("0.50")
        assertThat(formatThermalMargin(0.2096f)).isEqualTo("0.20")
    }

    @Test
    fun `float error does not knock a margin down a step`() {
        // A platform headroom of 0.6 leaves 1 - 0.6 = 0.39999998 in float.
        assertThat(formatThermalMargin(1f - 0.6f)).isEqualTo("0.40")
        assertThat(formatThermalMargin(1f - 0.78f)).isEqualTo("0.22")
    }

    @Test
    fun `the screen message says how to satisfy the policy`() {
        val unmet = policy.evaluate(conditions(screenOn = true)).single()
        assertThat(unmet.description).startsWith("Screen is on; press the power button.")
    }

    @Test
    fun `low battery blocks a run because it triggers its own throttling`() {
        val unmet = policy.evaluate(conditions(battery = 20))
        assertThat(unmet.single()).isInstanceOf(UnmetCondition.BatteryTooLow::class.java)
    }

    @Test
    fun `screen policy is enforced in both directions`() {
        assertThat(policy.evaluate(conditions(screenOn = true)).single())
            .isInstanceOf(UnmetCondition.ScreenState::class.java)

        val onDimmed = policy.copy(screenPolicy = ScreenPolicy.ON_DIMMED)
        assertThat(onDimmed.evaluate(conditions(screenOn = false)).single())
            .isInstanceOf(UnmetCondition.ScreenState::class.java)
        assertThat(onDimmed.evaluate(conditions(screenOn = true))).isEmpty()
    }

    @Test
    fun `thermal status ordinals are in severity order`() {
        assertThat(ThermalStatus.NONE < ThermalStatus.LIGHT).isTrue()
        assertThat(ThermalStatus.LIGHT < ThermalStatus.MODERATE).isTrue()
        assertThat(ThermalStatus.SEVERE < ThermalStatus.CRITICAL).isTrue()
        assertThat(ThermalStatus.NONE.isThrottling).isFalse()
        assertThat(ThermalStatus.LIGHT.isThrottling).isTrue()
    }
}
