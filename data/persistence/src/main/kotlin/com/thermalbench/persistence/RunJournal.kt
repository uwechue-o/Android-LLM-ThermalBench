package com.thermalbench.persistence

import com.thermalbench.domain.model.IterationSample
import com.thermalbench.domain.model.PowerSample
import com.thermalbench.domain.model.ResultId
import com.thermalbench.domain.model.ThermalSample
import com.thermalbench.domain.model.ThermalTransition
import com.thermalbench.persistence.db.EntityMappers.toEntity
import com.thermalbench.persistence.db.SampleDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crash-safe, batched writing of samples while a run is in flight (GUIDE.md §4.2).
 *
 * Samples accumulate in memory and are flushed in one transaction every
 * [flushEvery] iterations. The batching matters twice over: it keeps the
 * database off the critical path of an iteration, where its I/O would show up
 * as heat and as latency in the very numbers being measured, and it bounds what
 * a crash can destroy to less than one batch.
 */
@Singleton
class RunJournal @Inject constructor(
    private val sampleDao: SampleDao,
) {
    private val mutex = Mutex()
    private val iterations = mutableListOf<IterationSample>()
    private val thermalSamples = mutableListOf<ThermalSample>()
    private val transitions = mutableListOf<ThermalTransition>()
    private val power = mutableListOf<PowerSample>()

    var flushEvery: Int = DEFAULT_FLUSH_EVERY
        private set

    private var pendingIterations = 0

    fun configure(flushEvery: Int) {
        require(flushEvery > 0) { "flushEvery must be positive" }
        this.flushEvery = flushEvery
    }

    suspend fun reset() = mutex.withLock {
        iterations.clear()
        thermalSamples.clear()
        transitions.clear()
        power.clear()
        pendingIterations = 0
    }

    suspend fun record(resultId: ResultId, sample: IterationSample) {
        val flushNow = mutex.withLock {
            iterations += sample
            pendingIterations++
            pendingIterations >= flushEvery
        }
        if (flushNow) flush(resultId)
    }

    suspend fun record(sample: ThermalSample) = mutex.withLock { thermalSamples += sample; Unit }

    suspend fun record(transition: ThermalTransition) = mutex.withLock { transitions += transition; Unit }

    suspend fun record(sample: PowerSample) = mutex.withLock { power += sample; Unit }

    /** Writes everything buffered so far and clears the buffers. */
    suspend fun flush(resultId: ResultId) {
        val batch = mutex.withLock {
            val snapshot = Batch(
                iterations.toList(),
                thermalSamples.toList(),
                transitions.toList(),
                power.toList(),
            )
            iterations.clear()
            thermalSamples.clear()
            transitions.clear()
            power.clear()
            pendingIterations = 0
            snapshot
        }
        if (batch.isEmpty()) return
        sampleDao.flush(
            iterations = batch.iterations.map { it.toEntity(resultId.value) },
            thermalSamples = batch.thermalSamples.map { it.toEntity(resultId.value) },
            transitions = batch.transitions.map { it.toEntity(resultId.value) },
            power = batch.power.map { it.toEntity(resultId.value) },
        )
    }

    /**
     * Drops the rows written under [IN_FLIGHT] once the finished run has been
     * saved under its real id. Without this the journal would keep a full copy
     * of the last completed run forever, which is confusing to anyone who opens
     * the database looking for a crash to recover.
     */
    suspend fun discardPersisted(resultId: ResultId = IN_FLIGHT) {
        sampleDao.deleteAllFor(resultId.value)
    }

    private data class Batch(
        val iterations: List<IterationSample>,
        val thermalSamples: List<ThermalSample>,
        val transitions: List<ThermalTransition>,
        val power: List<PowerSample>,
    ) {
        fun isEmpty(): Boolean =
            iterations.isEmpty() && thermalSamples.isEmpty() && transitions.isEmpty() && power.isEmpty()
    }

    companion object {
        /**
         * A run's real id is derived from the finished document, which does not
         * exist yet while the run is in flight, so journal rows are written
         * under this reserved id and re-keyed on save.
         */
        val IN_FLIGHT = ResultId("in-flight")

        private const val DEFAULT_FLUSH_EVERY = 5
    }
}
