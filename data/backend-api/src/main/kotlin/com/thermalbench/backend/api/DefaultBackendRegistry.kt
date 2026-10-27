package com.thermalbench.backend.api

import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.port.BackendRegistry
import com.thermalbench.domain.port.InferenceBackend
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The registry is assembled by Hilt multibinding, so adding a backend module to
 * the build adds it to the registry. An absent runtime is still registered and
 * reports itself unavailable, which is what makes "this device has no NPU path"
 * a recorded fact rather than a missing row.
 */
@Singleton
class DefaultBackendRegistry @Inject constructor(
    private val backends: Set<@JvmSuppressWildcards InferenceBackend>,
) : BackendRegistry {

    override fun all(): List<InferenceBackend> = backends.sortedBy { it.id.value }

    override fun find(id: BackendId): InferenceBackend? = backends.firstOrNull { it.id == id }
}
