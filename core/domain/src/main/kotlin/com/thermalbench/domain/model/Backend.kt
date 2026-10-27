package com.thermalbench.domain.model

data class BackendCapabilities(
    val id: BackendId,
    /** False when the runtime is absent on this build/device (e.g. no native lib). */
    val available: Boolean,
    val unavailableReason: String?,
    val formats: Set<ModelFormat>,
    val delegates: Set<Delegate>,
    /** True if the adapter can enforce an exact output token count. */
    val supportsTokenCountStop: Boolean,
    val supportsStreaming: Boolean,
    val runtimeVersion: String?,
    /**
     * GPU APIs the GPU delegate can run through on this device, preferred
     * first. llama.cpp on a Snapdragon offers both "OpenCL" and "Vulkan"; a
     * spec chooses with [BackendRef.OPTION_GPU_API], or gets the first. Empty
     * for backends that do not expose the choice.
     */
    val gpuApis: List<String> = emptyList(),
)

/** Opaque handle to a model the backend has loaded. Adapters subclass this. */
abstract class LoadedModel(
    val ref: ModelRef,
    val delegate: Delegate,
    val loadNanos: Long,
    /** The device the model actually runs on, as the runtime names it; null if not known. */
    val computeDevice: String? = null,
)

/**
 * Raw timing of one generation, on the monotonic clock. The engine derives
 * TTFT and tokens/sec from these; adapters only report what they observed.
 */
data class GenerationResult(
    val startElapsedNanos: Long,
    /** Null if no token was produced. */
    val firstTokenElapsedNanos: Long?,
    val endElapsedNanos: Long,
    val promptTokenCount: Int,
    val outputTokenCount: Int,
    /** Generated text, kept for debugging and for verifying the stop condition. */
    val text: String,
    /** Per-token timestamps when the backend streams; empty otherwise. */
    val tokenElapsedNanos: List<Long> = emptyList(),
) {
    val timeToFirstTokenMicros: Long
        get() = firstTokenElapsedNanos?.let { (it - startElapsedNanos) / 1_000 } ?: -1L

    val prefillTokensPerSecond: Double
        get() {
            val first = firstTokenElapsedNanos ?: return 0.0
            val seconds = (first - startElapsedNanos) / 1e9
            return if (seconds > 0) promptTokenCount / seconds else 0.0
        }

    val decodeTokensPerSecond: Double
        get() {
            val first = firstTokenElapsedNanos ?: return 0.0
            val decodeTokens = outputTokenCount - 1
            val seconds = (endElapsedNanos - first) / 1e9
            return if (seconds > 0 && decodeTokens > 0) decodeTokens / seconds else 0.0
        }
}
