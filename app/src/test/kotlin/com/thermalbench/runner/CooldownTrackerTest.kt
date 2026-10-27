package com.thermalbench.runner

import com.google.common.truth.Truth.assertThat
import com.thermalbench.domain.model.DeviceConditions
import com.thermalbench.domain.model.PreconditionPolicy
import com.thermalbench.domain.model.PreconditionState
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.evaluate
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class CooldownTrackerTest {

    private val policy = PreconditionPolicy.DEFAULT.copy(requireUnplugged = false)
    private val target = requireNotNull(policy.minThermalHeadroom)

    /** A gate update at [atSeconds] on the gate's clock, with the device at [margin]. */
    private fun waiting(
        atSeconds: Long,
        margin: Float,
        status: ThermalStatus = ThermalStatus.NONE,
        plugged: Boolean = false,
        estimatedReady: Duration? = null,
    ): PreconditionState.Waiting {
        val conditions = DeviceConditions(
            elapsedNanos = atSeconds.seconds.inWholeNanoseconds,
            thermalStatus = status,
            thermalHeadroom = 1f - margin,
            batteryPercent = 80,
            plugged = plugged,
            screenOn = false,
            batteryTemperatureDeciC = 300,
        )
        val strict = PreconditionPolicy.DEFAULT
        return PreconditionState.Waiting(
            unmet = (if (plugged) strict else policy).evaluate(conditions),
            elapsedInWindow = Duration.ZERO,
            estimatedReady = estimatedReady,
            latest = conditions,
        )
    }

    @Test
    fun `the margin is read out at once, then every 20 seconds while cooling`() {
        val tracker = CooldownTracker(target)

        assertThat(tracker.onWaiting(waiting(0, 0.20f)).announcement).isEqualTo("Current thermal margin is 0.20.")
        assertThat(tracker.onWaiting(waiting(2, 0.21f)).announcement).isNull()
        assertThat(tracker.onWaiting(waiting(19, 0.22f)).announcement).isNull()
        assertThat(tracker.onWaiting(waiting(20, 0.23f)).announcement).isEqualTo("Current thermal margin is 0.23.")
        assertThat(tracker.onWaiting(waiting(39, 0.24f)).announcement).isNull()
        assertThat(tracker.onWaiting(waiting(40, 0.27f)).announcement).isEqualTo("Current thermal margin is 0.27.")
    }

    @Test
    fun `the spoken margin rounds down, so the target is heard only once reached`() {
        // Rounded to nearest, 0.499 would be read as the 0.50 target while the
        // run is still blocked.
        assertThat(CooldownTracker(target).onWaiting(waiting(0, 0.499f)).announcement)
            .isEqualTo("Current thermal margin is 0.49.")
        // The helper derives the margin as 1 - (1 - 0.4), which is 0.39999998
        // in float arithmetic; it is still 0.40, not 0.39.
        assertThat(CooldownTracker(target).onWaiting(waiting(0, 0.4f)).announcement)
            .isEqualTo("Current thermal margin is 0.40.")
    }

    @Test
    fun `halfway is announced once, and restarts the 20 second count`() {
        val tracker = CooldownTracker(target)
        tracker.onWaiting(waiting(0, 0.20f))

        val halfway = tracker.onWaiting(waiting(10, 0.36f))
        assertThat(halfway.cue).isEqualTo(CooldownTracker.Cue.HALFWAY)
        assertThat(halfway.announcement).isEqualTo("ThermalBench: halfway cooled. Current thermal margin is 0.36.")
        assertThat(halfway.percent).isEqualTo(53)

        // The next readout counts from the halfway announcement at 10 s, not from 0 s.
        assertThat(tracker.onWaiting(waiting(20, 0.36f)).announcement).isNull()
        assertThat(tracker.onWaiting(waiting(29, 0.37f)).announcement).isNull()
        assertThat(tracker.onWaiting(waiting(30, 0.38f)).announcement).isEqualTo("Current thermal margin is 0.38.")
    }

    @Test
    fun `cool enough is announced once, then the tracker falls silent`() {
        val tracker = CooldownTracker(target)
        tracker.onWaiting(waiting(0, 0.20f))

        val cooled = tracker.onWaiting(waiting(60, 0.55f, estimatedReady = 30.seconds))
        assertThat(cooled.cue).isEqualTo(CooldownTracker.Cue.COOLED)
        assertThat(cooled.announcement).isEqualTo("ThermalBench: cool enough. Starting in about 30 seconds.")
        assertThat(cooled.percent).isEqualTo(100)

        // Hovering at the threshold neither repeats the cue nor resumes the readout.
        assertThat(tracker.onWaiting(waiting(90, 0.49f)).cue).isNull()
        assertThat(tracker.onWaiting(waiting(120, 0.56f)).announcement).isNull()
    }

    @Test
    fun `cool enough names what is still blocking`() {
        val tracker = CooldownTracker(target)
        tracker.onWaiting(waiting(0, 0.20f, plugged = true))

        val cooled = tracker.onWaiting(waiting(60, 0.55f, plugged = true))
        assertThat(cooled.announcement).isEqualTo("ThermalBench: cool enough. Still waiting for: Device is charging.")
    }

    @Test
    fun `a status that is too high is read out when it is the only thermal blocker`() {
        val tracker = CooldownTracker(target)
        val update = tracker.onWaiting(waiting(0, 0.60f, status = ThermalStatus.LIGHT))
        assertThat(update.announcement).isEqualTo("Thermal status light, need none.")
    }

    @Test
    fun `a device that starts cool hears nothing`() {
        val tracker = CooldownTracker(target)
        val update = tracker.onWaiting(waiting(0, 0.70f))
        assertThat(update.cue).isNull()
        assertThat(update.announcement).isNull()
        assertThat(update.percent).isEqualTo(100)
    }
}
