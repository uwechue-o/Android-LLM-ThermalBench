package com.thermalbench.runner

import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.PreconditionState
import com.thermalbench.domain.model.RunProgress
import com.thermalbench.domain.model.SpecId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the run looks like from outside the service. The ViewModel binds to this
 * rather than to the service class, so the UI layer holds no Android service
 * reference and stays testable.
 */
interface RunController {

    val phase: StateFlow<RunPhase>

    /** Progress events as they happen. Replay is 0: the UI keeps its own series. */
    val progress: SharedFlow<RunProgress>

    /** Which run of a comparison is under way; null for a single run. */
    val series: StateFlow<SeriesPosition?>

    fun start(specId: SpecId) = startSeries(listOf(specId))

    /**
     * Runs [specIds] one after another, each behind its own precondition gate,
     * which is the cool-down between them. Ends in [RunPhase.SeriesFinished]
     * when there is more than one.
     */
    fun startSeries(specIds: List<SpecId>)

    fun abort()
}

sealed interface RunPhase {
    data object Idle : RunPhase
    data class AwaitingPreconditions(val state: PreconditionState) : RunPhase
    data class Running(val progress: RunProgress?) : RunPhase
    data class Finished(val result: BenchmarkResult) : RunPhase

    /** Every run of a comparison, in the order they ran. */
    data class SeriesFinished(val results: List<BenchmarkResult>) : RunPhase
    data class Failed(val message: String, val completedIterations: Int) : RunPhase

    /**
     * Stopped by the operator. Kept apart from [Failed]: coroutine cancellation
     * arrives as an exception, and reporting it as one showed people an ERROR
     * and "StandaloneCoroutine was cancelled" for pressing Cancel.
     */
    data class Cancelled(val completedIterations: Int) : RunPhase
}

/** Where a comparison has got to: run [index] (0-based) of [total]. */
data class SeriesPosition(val index: Int, val total: Int) {
    val label: String get() = "Run ${index + 1} of $total"
}

/** Shared state between the service and whatever is observing it. */
object RunState {
    val phase = MutableStateFlow<RunPhase>(RunPhase.Idle)

    /** Null for a single run. */
    val series = MutableStateFlow<SeriesPosition?>(null)
}
