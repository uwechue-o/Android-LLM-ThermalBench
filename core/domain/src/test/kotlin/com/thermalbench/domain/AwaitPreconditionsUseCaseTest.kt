package com.thermalbench.domain

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.thermalbench.domain.model.DeviceConditions
import com.thermalbench.domain.model.PreconditionPolicy
import com.thermalbench.domain.model.PreconditionState
import com.thermalbench.domain.model.ScreenPolicy
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.UnmetCondition
import com.thermalbench.domain.port.DeviceConditionSource
import com.thermalbench.domain.port.MonotonicClock
import com.thermalbench.domain.usecase.AwaitPreconditionsUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AwaitPreconditionsUseCaseTest {

    /** Advances a fixed step on every read, so the window arithmetic is deterministic. */
    private class SteppingClock(private val stepNanos: Long) : MonotonicClock {
        private var now = 0L
        override fun nanos(): Long {
            val value = now
            now += stepNanos
            return value
        }
    }

    private class FixedConditions(private val readings: List<DeviceConditions>) : DeviceConditionSource {
        override fun observe(interval: Duration): Flow<DeviceConditions> = readings.asFlow()
        override suspend fun snapshot(): DeviceConditions = readings.last()
    }

    private fun reading(plugged: Boolean = false, battery: Int = 80) = DeviceConditions(
        elapsedNanos = 0L,
        thermalStatus = ThermalStatus.NONE,
        thermalHeadroom = 0.2f,
        batteryPercent = battery,
        plugged = plugged,
        screenOn = false,
        batteryTemperatureDeciC = 300,
    )

    private val policy = PreconditionPolicy(
        requiredThermalStatus = ThermalStatus.NONE,
        minThermalHeadroom = null,
        requireUnplugged = true,
        minBatteryPercent = 50,
        screenPolicy = ScreenPolicy.OFF,
        stabilizationWindow = 10.seconds,
        maxWaitForPreconditions = 60.seconds,
    )

    @Test
    fun `satisfied once conditions have held for the whole window`() = runTest {
        // 5 s per clock read; the window closes on the third good reading.
        val clock = SteppingClock(stepNanos = 5_000_000_000L)
        val useCase = AwaitPreconditionsUseCase(
            FixedConditions(List(5) { reading() }),
            clock,
        )
        useCase(policy).test {
            assertThat(awaitItem()).isInstanceOf(PreconditionState.Waiting::class.java)
            assertThat(awaitItem()).isInstanceOf(PreconditionState.Waiting::class.java)
            assertThat(awaitItem()).isInstanceOf(PreconditionState.Satisfied::class.java)
            // Terminal: the flow stops immediately rather than waiting for
            // another poll interval to elapse.
            awaitComplete()
        }
    }

    @Test
    fun `the stabilization window restarts when a condition fails again`() = runTest {
        val clock = SteppingClock(stepNanos = 5_000_000_000L)
        val readings = listOf(
            reading(),
            reading(),
            reading(plugged = true), // resets the window
            reading(),
            reading(),
            reading(),
        )
        AwaitPreconditionsUseCase(FixedConditions(readings), clock)(policy).test {
            assertThat(awaitItem()).isInstanceOf(PreconditionState.Waiting::class.java)
            assertThat(awaitItem()).isInstanceOf(PreconditionState.Waiting::class.java)

            val interrupted = awaitItem() as PreconditionState.Waiting
            assertThat(interrupted.unmet).contains(UnmetCondition.Charging)
            assertThat(interrupted.elapsedInWindow).isEqualTo(Duration.ZERO)

            // Two more good readings are needed again after the reset.
            assertThat(awaitItem()).isInstanceOf(PreconditionState.Waiting::class.java)
            assertThat(awaitItem()).isInstanceOf(PreconditionState.Waiting::class.java)
            assertThat(awaitItem()).isInstanceOf(PreconditionState.Satisfied::class.java)
            awaitComplete()
        }
    }

    @Test
    fun `times out with the conditions still blocking the run`() = runTest {
        // 30 s per read exceeds the 60 s maximum wait on the third reading.
        val clock = SteppingClock(stepNanos = 30_000_000_000L)
        val readings = List(5) { reading(plugged = true) }
        AwaitPreconditionsUseCase(FixedConditions(readings), clock)(policy).test {
            assertThat(awaitItem()).isInstanceOf(PreconditionState.Waiting::class.java)
            assertThat(awaitItem()).isInstanceOf(PreconditionState.Waiting::class.java)
            val timedOut = awaitItem() as PreconditionState.TimedOut
            assertThat(timedOut.unmet).contains(UnmetCondition.Charging)
            awaitComplete()
        }
    }
}
