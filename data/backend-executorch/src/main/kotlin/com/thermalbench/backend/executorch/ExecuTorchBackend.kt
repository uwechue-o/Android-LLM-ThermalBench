package com.thermalbench.backend.executorch

import com.thermalbench.domain.model.BackendCapabilities
import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.GenerationResult
import com.thermalbench.domain.model.LoadedModel
import com.thermalbench.domain.model.ModelFormat
import com.thermalbench.domain.model.ModelRef
import com.thermalbench.domain.model.Workload
import com.thermalbench.domain.port.InferenceBackend
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ExecuTorch `.pte` adapter — phase 2 in the build order (GUIDE.md §8).
 *
 * The module exists now so the topology, the dependency rules and the DI wiring
 * are complete and the backend appears in the registry. Until the LLaMA runner
 * is wired it reports itself unavailable with the reason stated, which is the
 * same contract a device missing a native library gets: a recorded gap, never a
 * crash and never a silent omission from a comparison table.
 */
@Singleton
class ExecuTorchBackend @Inject constructor() : InferenceBackend {

    override val id: BackendId = BackendId.EXECUTORCH

    override suspend fun capabilities(): BackendCapabilities = BackendCapabilities(
        id = id,
        available = false,
        unavailableReason = NOT_IMPLEMENTED,
        formats = setOf(ModelFormat.EXECUTORCH_PTE),
        delegates = setOf(Delegate.CPU, Delegate.GPU, Delegate.NPU),
        supportsTokenCountStop = true,
        supportsStreaming = true,
        runtimeVersion = BuildConfig.EXECUTORCH_VERSION,
    )

    override suspend fun load(model: ModelRef, delegate: Delegate, options: Map<String, String>): LoadedModel =
        error(NOT_IMPLEMENTED)

    override suspend fun generate(model: LoadedModel, workload: Workload, seed: Long): GenerationResult =
        error(NOT_IMPLEMENTED)

    override suspend fun unload(model: LoadedModel) = Unit

    private companion object {
        const val NOT_IMPLEMENTED =
            "ExecuTorch adapter is phase 2 and not yet wired; see GUIDE.md §8 build order."
    }
}
