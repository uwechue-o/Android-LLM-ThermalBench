package com.thermalbench.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Immutable, hashable description of one benchmark run. The hash of its
 * canonical serialization is the run identity ([specId]) and appears in every
 * result. Anything that could change the numbers belongs in here.
 */
data class BenchmarkSpec(
    val specId: SpecId,
    val model: ModelRef,
    val backend: BackendRef,
    val workload: Workload,
    val protocol: RunProtocol,
    val preconditions: PreconditionPolicy,
    val seed: Long,
    val toolVersion: SemVer,
    /** Free-form, human-readable label. Not part of identity semantics but serialized. */
    val label: String = "",
)

/** On-disk model artifact. The digest is the identity; the path is a hint. */
data class ModelRef(
    val name: String,
    val format: ModelFormat,
    val sha256: Sha256,
    val sizeBytes: Long,
    /** Absolute path on the device. May differ between devices; excluded from hashing. */
    val path: String,
    val quantization: String? = null,
    val parameterCount: Long? = null,
)

enum class ModelFormat {
    /** LiteRT-LM bundle (`.litertlm`). */
    LITERT_LM,

    /** llama.cpp GGUF (`.gguf`). */
    GGUF,

    /** MediaPipe Tasks GenAI bundle (`.task` / `.bin`). */
    MEDIAPIPE_TASK,

    /** ExecuTorch program (`.pte`). */
    EXECUTORCH_PTE,

    /** Qualcomm Genie bundle: a folder whose `genie_config.json` is the model path. */
    GENIE_BUNDLE,
}

/** Which adapter runs the model, and on which compute unit. */
data class BackendRef(
    val id: BackendId,
    val delegate: Delegate,
    /** Backend-specific knobs (thread count, context size, …), serialized verbatim. */
    val options: Map<String, String> = emptyMap(),
) {
    companion object {
        /** Which GPU API a GPU run uses, where the backend offers more than one. */
        const val OPTION_GPU_API = "gpuApi"
    }
}

enum class Delegate { CPU, GPU, NPU }

/**
 * Fixed prompt, fixed length, fixed output length. [stopCondition] is token
 * count only — never EOS, which varies by model and would make tokens/sec
 * incomparable across backends.
 */
data class Workload(
    val promptTokens: Int,
    val maxOutputTokens: Int,
    val promptSha256: Sha256,
    val stopCondition: StopCondition,
    /** The prompt text itself, so a spec is self-contained. */
    val prompt: String,
) {
    init {
        require(promptTokens > 0) { "promptTokens must be positive" }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        require(prompt.isNotEmpty()) { "prompt must not be empty" }
    }
}

sealed interface StopCondition {
    /** Stop after exactly [tokens] generated tokens. The only comparable option. */
    data class TokenCount(val tokens: Int) : StopCondition {
        init {
            require(tokens > 0) { "tokens must be positive" }
        }
    }
}

data class RunProtocol(
    /** Discarded from analysis, recorded anyway. */
    val warmupIterations: Int,
    /** >= 50 for the sustained-load thesis. */
    val measuredIterations: Int,
    /** Zero for back-to-back; non-zero to model a duty cycle. */
    val interIterationDelay: Duration,
    val abortOnCriticalThermal: Boolean,
    val maxRunDuration: Duration,
    /** Telemetry polling period for the independent thermal time series. */
    val telemetryInterval: Duration = 1.seconds,
) {
    init {
        require(warmupIterations >= 0) { "warmupIterations must be >= 0" }
        require(measuredIterations > 0) { "measuredIterations must be > 0" }
        require(!interIterationDelay.isNegative()) { "interIterationDelay must be >= 0" }
        require(maxRunDuration.isPositive()) { "maxRunDuration must be > 0" }
        require(telemetryInterval.isPositive()) { "telemetryInterval must be > 0" }
    }

    val totalIterations: Int get() = warmupIterations + measuredIterations

    companion object {
        /** The minimum measured iteration count the thesis is built on. */
        const val SUSTAINED_LOAD_MIN_ITERATIONS: Int = 50

        val DEFAULT: RunProtocol = RunProtocol(
            warmupIterations = 3,
            measuredIterations = SUSTAINED_LOAD_MIN_ITERATIONS,
            interIterationDelay = Duration.ZERO,
            abortOnCriticalThermal = true,
            maxRunDuration = 45.minutes,
        )
    }
}
