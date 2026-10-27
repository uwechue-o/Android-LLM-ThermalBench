package com.thermalbench.domain.port

import com.thermalbench.domain.model.BackendCapabilities
import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.GenerationResult
import com.thermalbench.domain.model.LoadedModel
import com.thermalbench.domain.model.ModelRef
import com.thermalbench.domain.model.Workload

/**
 * Port for one inference runtime. Adapters live in `data/backend-*`.
 *
 * Contract: [generate] must honour [Workload.stopCondition] exactly (token
 * count, never EOS) and must report timings on the monotonic clock supplied by
 * [MonotonicClock]. If it cannot enforce the stop condition it must say so in
 * [capabilities], and the spec validator will reject the pairing.
 */
interface InferenceBackend {
    val id: BackendId

    suspend fun capabilities(): BackendCapabilities

    suspend fun load(model: ModelRef, delegate: Delegate, options: Map<String, String> = emptyMap()): LoadedModel

    suspend fun generate(model: LoadedModel, workload: Workload, seed: Long): GenerationResult

    suspend fun unload(model: LoadedModel)
}

/** Finds the adapter for a [BackendId]; the set is assembled by DI. */
interface BackendRegistry {
    fun all(): List<InferenceBackend>

    fun find(id: BackendId): InferenceBackend?
}
