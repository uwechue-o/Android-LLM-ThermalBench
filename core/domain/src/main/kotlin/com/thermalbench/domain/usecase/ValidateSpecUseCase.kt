package com.thermalbench.domain.usecase

import com.thermalbench.domain.model.BackendCapabilities
import com.thermalbench.domain.model.BackendRef
import com.thermalbench.domain.model.BenchmarkSpec
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.RunProtocol
import com.thermalbench.domain.model.StopCondition
import com.thermalbench.domain.port.BackendRegistry

sealed interface SpecValidation {
    data object Valid : SpecValidation
    data class Invalid(val problems: List<SpecProblem>) : SpecValidation
}

data class SpecProblem(val field: String, val message: String, val fatal: Boolean = true)

/**
 * Rejects specs that cannot produce comparable results before burning an hour
 * of device time. Purely structural checks plus a capability check against the
 * resolved backend.
 */
class ValidateSpecUseCase(private val backends: BackendRegistry) {

    suspend operator fun invoke(spec: BenchmarkSpec): SpecValidation {
        val problems = mutableListOf<SpecProblem>()

        val stop = spec.workload.stopCondition
        if (stop is StopCondition.TokenCount && stop.tokens != spec.workload.maxOutputTokens) {
            problems += SpecProblem(
                "workload.stopCondition",
                "TokenCount(${stop.tokens}) must equal maxOutputTokens (${spec.workload.maxOutputTokens}) " +
                    "so every backend produces the same number of tokens",
            )
        }
        if (spec.protocol.measuredIterations < RunProtocol.SUSTAINED_LOAD_MIN_ITERATIONS) {
            problems += SpecProblem(
                "protocol.measuredIterations",
                "Fewer than ${RunProtocol.SUSTAINED_LOAD_MIN_ITERATIONS} measured iterations; " +
                    "the result will be marked INSUFFICIENT_DATA for sustained-load analysis",
                fatal = false,
            )
        }
        if (spec.preconditions.stabilizationWindow > spec.preconditions.maxWaitForPreconditions) {
            problems += SpecProblem(
                "preconditions.stabilizationWindow",
                "Stabilization window exceeds the maximum wait; the gate can never be satisfied",
            )
        }

        val backend = backends.find(spec.backend.id)
        if (backend == null) {
            problems += SpecProblem("backend.id", "No adapter registered for backend '${spec.backend.id}'")
        } else {
            val caps = backend.capabilities()
            if (!caps.available) {
                problems += SpecProblem(
                    "backend.id",
                    "Backend '${spec.backend.id}' is unavailable: ${caps.unavailableReason ?: "unknown reason"}",
                )
            }
            if (spec.model.format !in caps.formats) {
                problems += SpecProblem(
                    "model.format",
                    "Backend '${spec.backend.id}' does not load ${spec.model.format}; supports ${caps.formats}",
                )
            }
            if (spec.backend.delegate !in caps.delegates) {
                problems += SpecProblem(
                    "backend.delegate",
                    "Backend '${spec.backend.id}' has no ${spec.backend.delegate} delegate; supports ${caps.delegates}",
                )
            }
            gpuApiProblem(spec, caps)?.let { problems += it }
            if (!caps.supportsTokenCountStop) {
                problems += SpecProblem(
                    "backend.id",
                    "Backend '${spec.backend.id}' cannot enforce a token-count stop; its tokens/sec are not comparable",
                )
            }
        }

        return if (problems.any { it.fatal }) SpecValidation.Invalid(problems) else SpecValidation.Valid
    }

    /** A named GPU API must be one this device offers, and only on a GPU run. */
    private fun gpuApiProblem(spec: BenchmarkSpec, caps: BackendCapabilities): SpecProblem? {
        val api = spec.backend.options[BackendRef.OPTION_GPU_API] ?: return null
        val field = "backend.options.${BackendRef.OPTION_GPU_API}"
        return when {
            spec.backend.delegate != Delegate.GPU ->
                SpecProblem(field, "A GPU API is set, but the delegate is ${spec.backend.delegate}")
            caps.gpuApis.none { it.equals(api, ignoreCase = true) } ->
                SpecProblem(field, "'$api' is not available here; this device offers ${caps.gpuApis.ifEmpty { listOf("none") }}")
            else -> null
        }
    }
}
