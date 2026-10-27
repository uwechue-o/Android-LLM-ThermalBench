package com.thermalbench.backend.llamacpp

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.tracing.trace
import com.thermalbench.backend.api.GenerationTimer
import com.thermalbench.backend.api.ModelFiles
import com.thermalbench.domain.model.BackendCapabilities
import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.BackendRef
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.GenerationResult
import com.thermalbench.domain.model.LoadedModel
import com.thermalbench.domain.model.ModelFormat
import com.thermalbench.domain.model.ModelRef
import com.thermalbench.domain.model.StopCondition
import com.thermalbench.domain.model.Workload
import com.thermalbench.domain.port.InferenceBackend
import com.thermalbench.domain.port.MonotonicClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * llama.cpp adapter for GGUF — the format the Google stack does not ship, and
 * the reason this tool covers models the vendor benchmarks cannot.
 *
 * The native half is built only when `thermalbench.nativeLlama=true`, so the
 * project builds on a machine without an NDK. Without it this backend reports
 * itself unavailable, which the result document records as a gap in coverage
 * rather than passing over in silence.
 */
@Singleton
class LlamaCppBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: MonotonicClock,
    private val modelFiles: ModelFiles,
) : InferenceBackend {

    override val id: BackendId = BackendId.LLAMA_CPP

    private class LlamaLoadedModel(
        ref: ModelRef,
        delegate: Delegate,
        loadNanos: Long,
        computeDevice: String,
        val handle: Long,
    ) : LoadedModel(ref, delegate, loadNanos, computeDevice)

    /** One GPU as llama.cpp sees it: the API driving it, and the device. */
    private data class Gpu(val api: String, val device: String, val description: String) {
        val label: String get() = "$description via $api"
    }

    /**
     * The GPUs this build can drive on this device, preferred first. OpenCL
     * leads because llama.cpp's OpenCL backend is tuned for Adreno, the only
     * GPU it accepts; Vulkan is the portable path.
     */
    private val gpus: List<Gpu> by lazy {
        if (!LlamaCppNative.available) return@lazy emptyList()
        runCatching { LlamaCppNative.gpuDevices() }.getOrDefault(emptyArray())
            .mapNotNull { entry ->
                val parts = entry.split('	')
                if (parts.size == GPU_ENTRY_FIELDS) Gpu(parts[0], parts[1], parts[2]) else null
            }
            .let(::withoutBrokenOpenCl)
            .let(::withoutOldVulkan)
            .sortedBy { gpu -> preferenceRank(gpu.api) }
    }

    /**
     * llama.cpp's OpenCL backend (b11879) aborts the process on Adreno 6xx:
     * the driver's compiler rejects its flash-attention repack kernel, which
     * writes 3D images (seen on an Adreno 650, OpenCL 2.0). OpenCL is not
     * offered there; Vulkan still drives the same GPU. The OpenCL device does
     * not name its Adreno model, so the Vulkan device's name identifies it.
     */
    private fun withoutBrokenOpenCl(gpus: List<Gpu>): List<Gpu> {
        val adreno6xx = gpus.any { ADRENO_6XX.containsMatchIn(it.description) }
        if (!adreno6xx) return gpus
        return gpus.filterNot { it.api.equals("OpenCL", ignoreCase = true) }
            .also { Log.i(TAG, "OpenCL not offered: llama.cpp's OpenCL kernels fail on Adreno 6xx") }
    }

    /**
     * The GPU a spec asked for, or the preferred one when it named no API.
     * Which one ran is recorded either way, so it is never left to be guessed.
     */
    private fun gpuFor(requested: String?): Gpu =
        gpus.firstOrNull { requested == null || it.api.equals(requested, ignoreCase = true) }
            ?: error("llama.cpp has no ${requested ?: "GPU"} device here; it offers ${gpus.map { it.api }}")

    /**
     * ggml's Vulkan backend calls Vulkan 1.2 entry points. A 1.1 driver lacks
     * them, and the first GPU buffer allocation jumps through a null pointer
     * (seen on a OnePlus 8T, Adreno 650, Vulkan 1.1). Android declares the
     * driver's version as a system feature, so 1.1 devices are not offered it.
     */
    private fun withoutOldVulkan(gpus: List<Gpu>): List<Gpu> {
        val vulkan12 = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_2)
        if (vulkan12) return gpus
        return gpus.filterNot { it.api.equals("Vulkan", ignoreCase = true) }
            .also { Log.i(TAG, "Vulkan not offered: llama.cpp needs Vulkan 1.2 and this driver is older") }
    }

    private fun preferenceRank(api: String): Int =
        GPU_API_PREFERENCE.indexOfFirst { it.equals(api, ignoreCase = true) }.takeIf { it >= 0 } ?: Int.MAX_VALUE

    /**
     * GPU only when llama.cpp found a GPU it can drive; there is no NPU
     * backend in llama.cpp. Without one, offloading is silently ignored, and a
     * run recorded as GPU was once really a CPU run at CPU speed. Offering the
     * delegate only when it is real lets the validator reject such a spec.
     */
    private val delegates: Set<Delegate> by lazy {
        if (gpus.isNotEmpty()) setOf(Delegate.CPU, Delegate.GPU) else setOf(Delegate.CPU)
    }

    override suspend fun capabilities(): BackendCapabilities = BackendCapabilities(
        id = id,
        available = LlamaCppNative.available,
        unavailableReason = LlamaCppNative.loadFailure,
        formats = setOf(ModelFormat.GGUF),
        delegates = delegates,
        supportsTokenCountStop = true,
        supportsStreaming = true,
        runtimeVersion = if (LlamaCppNative.available) {
            runCatching { LlamaCppNative.runtimeVersion() }.getOrNull()
        } else {
            null
        },
        gpuApis = gpus.map { it.api }.distinct(),
    )

    override suspend fun load(model: ModelRef, delegate: Delegate, options: Map<String, String>): LoadedModel =
        withContext(Dispatchers.Default) {
            check(LlamaCppNative.available) { LlamaCppNative.loadFailure ?: "llama.cpp unavailable" }
            // The validator rejects this first; this guards any path around it.
            check(delegate in delegates) { "llama.cpp in this build cannot run on $delegate; it offers $delegates" }
            when (val verification = modelFiles.verify(model)) {
                is ModelFiles.Verification.Ok -> Unit
                else -> error("Model file rejected: $verification")
            }
            val threads = options[OPTION_THREADS]?.toIntOrNull() ?: defaultThreadCount()
            val contextSize = options[OPTION_CONTEXT]?.toIntOrNull() ?: DEFAULT_CONTEXT
            val gpuLayers = when (delegate) {
                Delegate.CPU -> 0
                Delegate.GPU -> options[OPTION_GPU_LAYERS]?.toIntOrNull() ?: ALL_LAYERS
                Delegate.NPU -> error("llama.cpp has no NPU delegate; validate the spec first")
            }
            val gpu = if (delegate == Delegate.GPU) gpuFor(options[BackendRef.OPTION_GPU_API]) else null
            val start = clock.nanos()
            val handle = trace("$TRACE_PREFIX.load") {
                LlamaCppNative.loadModel(model.path, threads, contextSize, gpuLayers, gpu?.api)
            }
            check(handle != 0L) { "llama.cpp failed to load ${model.path}" }
            LlamaLoadedModel(model, delegate, clock.nanos() - start, gpu?.label ?: "CPU", handle)
        }

    override suspend fun generate(model: LoadedModel, workload: Workload, seed: Long): GenerationResult =
        withContext(Dispatchers.Default) {
            val loaded = model as LlamaLoadedModel
            val targetTokens = (workload.stopCondition as StopCondition.TokenCount).tokens
            val timer = GenerationTimer(clock)

            // Each iteration starts from an empty context, so iteration N does
            // not measure a longer sequence than iteration 1.
            LlamaCppNative.resetContext(loaded.handle)

            timer.start()
            val promptTokens = trace("$TRACE_PREFIX.prefill") {
                LlamaCppNative.prefill(loaded.handle, workload.prompt, seed)
            }
            trace("$TRACE_PREFIX.decode") {
                repeat(targetTokens) {
                    val piece = LlamaCppNative.decodeToken(loaded.handle) ?: return@trace
                    timer.onToken(piece)
                }
            }
            timer.finish(
                promptTokenCount = if (promptTokens > 0) promptTokens else workload.promptTokens,
                outputTokenCount = timer.observedTokenCount,
            )
        }

    override suspend fun unload(model: LoadedModel) = withContext(Dispatchers.Default) {
        runCatching { LlamaCppNative.freeModel((model as LlamaLoadedModel).handle) }
        Unit
    }

    /**
     * Big cores only by default. Spreading llama.cpp across little cores makes
     * a run slower and hotter at once, which muddies exactly the effect this
     * tool measures.
     */
    private fun defaultThreadCount(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)

    private companion object {
        const val TRACE_PREFIX = "ThermalBench.llamacpp"
        const val OPTION_THREADS = "threads"
        const val OPTION_CONTEXT = "contextSize"
        const val OPTION_GPU_LAYERS = "gpuLayers"
        const val GPU_ENTRY_FIELDS = 3
        val GPU_API_PREFERENCE = listOf("OpenCL", "Vulkan")
        val ADRENO_6XX = Regex("""Adreno\s*\(TM\)\s*6\d\d""")
        const val TAG = "ThermalBenchLlama"

        /** VK_MAKE_API_VERSION(0, 1, 2, 0), as FEATURE_VULKAN_HARDWARE_VERSION encodes it. */
        const val VULKAN_1_2 = 0x402000
        const val DEFAULT_CONTEXT = 2048
        const val ALL_LAYERS = 999
    }
}
