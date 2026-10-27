package com.thermalbench.domain.usecase

import com.thermalbench.domain.model.DeviceConditions
import com.thermalbench.domain.model.PreconditionPolicy
import com.thermalbench.domain.model.PreconditionState
import com.thermalbench.domain.model.evaluate
import com.thermalbench.domain.port.DeviceConditionSource
import com.thermalbench.domain.port.MonotonicClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Emits [PreconditionState] until the policy has held for its stabilization
 * window (→ [PreconditionState.Satisfied]) or the maximum wait elapses
 * (→ [PreconditionState.TimedOut]). The flow completes on the terminal state.
 *
 * The window restarts whenever any condition fails again. Holding for a while,
 * then briefly charging, then holding again is not a stable device, and
 * treating it as one would put a warm start into a run that claims to be cold.
 */
class AwaitPreconditionsUseCase(
    private val conditions: DeviceConditionSource,
    private val clock: MonotonicClock,
    private val pollInterval: Duration = 2.seconds,
) {
    operator fun invoke(policy: PreconditionPolicy): Flow<PreconditionState> = flow {
        // Declared inside the builder so each collection gets its own window.
        val startNanos = clock.nanos()
        var windowStartNanos: Long? = null

        emitAll(
            conditions.observe(pollInterval).transformWhile { reading: DeviceConditions ->
                val now = clock.nanos()
                val unmet = policy.evaluate(reading)
                val waited = (now - startNanos).nanoseconds

                if (unmet.isEmpty()) {
                    val windowStart = windowStartNanos ?: now.also { windowStartNanos = it }
                    val held = (now - windowStart).nanoseconds
                    if (held >= policy.stabilizationWindow) {
                        emit(PreconditionState.Satisfied(reading))
                        return@transformWhile false
                    }
                    emit(
                        PreconditionState.Waiting(
                            unmet = emptyList(),
                            elapsedInWindow = held,
                            estimatedReady = policy.stabilizationWindow - held,
                            latest = reading,
                        ),
                    )
                } else {
                    windowStartNanos = null
                    emit(
                        PreconditionState.Waiting(
                            unmet = unmet,
                            elapsedInWindow = Duration.ZERO,
                            estimatedReady = null,
                            latest = reading,
                        ),
                    )
                }

                if (waited >= policy.maxWaitForPreconditions) {
                    emit(PreconditionState.TimedOut(unmet, reading))
                    false
                } else {
                    true
                }
            },
        )
    }
}
