package com.thermalbench.backend.litert

import android.content.Context
import android.os.Build
import android.system.Os
import androidx.tracing.trace
import com.google.ai.edge.litertlm.Backend as LiteRtBackend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import com.thermalbench.backend.api.GenerationTimer
import com.thermalbench.backend.api.ModelFiles
import com.thermalbench.domain.model.BackendCapabilities
import com.thermalbench.domain.model.BackendId
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
import dalvik.system.BaseDexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT-LM adapter (GUIDE.md §7, the Google runtime path).
 *
 * This backend is the reference implementation for the project because its
 * session API separates prefill from decode. `runPrefill` then exactly
 * [Workload.maxOutputTokens] calls to `runDecode` give an unambiguous
 * time-to-first-token, a real prefill rate, and a decode rate measured over a
 * token count fixed by the spec rather than by the model's end-of-sequence
 * behaviour.
 */
@Singleton
class LiteRtLmBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: MonotonicClock,
    private val modelFiles: ModelFiles,
) : InferenceBackend {

    override val id: BackendId = BackendId.LITERT_LM

    private class LiteRtLoadedModel(
        ref: ModelRef,
        delegate: Delegate,
        loadNanos: Long,
        val engine: Engine,
    ) : LoadedModel(ref, delegate, loadNanos)

    override suspend fun capabilities(): BackendCapabilities {
        val failure = nativeLoadFailure()
        return BackendCapabilities(
            id = id,
            available = failure == null,
            unavailableReason = failure,
            formats = setOf(ModelFormat.LITERT_LM),
            delegates = if (npuDispatch != null) {
                setOf(Delegate.CPU, Delegate.GPU, Delegate.NPU)
            } else {
                setOf(Delegate.CPU, Delegate.GPU)
            },
            supportsTokenCountStop = true,
            supportsStreaming = true,
            runtimeVersion = BuildConfig.LITERT_LM_VERSION,
        )
    }

    override suspend fun load(model: ModelRef, delegate: Delegate, options: Map<String, String>): LoadedModel =
        withContext(Dispatchers.Default) {
            when (val verification = modelFiles.verify(model)) {
                is ModelFiles.Verification.Ok -> Unit
                else -> error("Model file rejected: $verification")
            }
            val start = clock.nanos()
            val engine = trace("$TRACE_PREFIX.load") {
                Engine(
                    EngineConfig(
                        modelPath = model.path,
                        backend = delegate.toLiteRtBackend(options),
                        maxNumTokens = options[OPTION_MAX_TOKENS]?.toIntOrNull(),
                        cacheDir = context.cacheDir.absolutePath,
                    ),
                ).also { it.initialize() }
            }
            LiteRtLoadedModel(model, delegate, clock.nanos() - start, engine)
        }

    override suspend fun generate(model: LoadedModel, workload: Workload, seed: Long): GenerationResult =
        withContext(Dispatchers.Default) {
            val loaded = model as LiteRtLoadedModel
            val targetTokens = (workload.stopCondition as StopCondition.TokenCount).tokens
            val timer = GenerationTimer(clock)

            // A fresh session per iteration: a benchmark iteration must not
            // inherit the KV cache of the one before it, or iteration 2 onward
            // would measure a different workload than iteration 1.
            val session: Session = loaded.engine.createSession(
                SessionConfig(
                    samplerConfig = SamplerConfig(
                        topK = DEFAULT_TOP_K,
                        topP = DEFAULT_TOP_P,
                        temperature = DEFAULT_TEMPERATURE,
                        seed = seed.toInt(),
                    ),
                ),
            )

            try {
                timer.start()
                trace("$TRACE_PREFIX.prefill") {
                    session.runPrefill(listOf(InputData.Text(workload.prompt)))
                }
                trace("$TRACE_PREFIX.decode") {
                    repeat(targetTokens) {
                        timer.onToken(session.runDecode())
                    }
                }
                timer.finish(promptTokenCount = workload.promptTokens, outputTokenCount = targetTokens)
            } finally {
                runCatching { session.close() }
            }
        }

    override suspend fun unload(model: LoadedModel) = withContext(Dispatchers.Default) {
        runCatching { (model as LiteRtLoadedModel).engine.close() }
        Unit
    }

    /**
     * NPU maps to the vendor accelerator; Pixel silicon takes the dedicated
     * GOOGLE_TENSOR path. The thread count is left unset unless the spec names
     * one, because "whatever the runtime chooses" is itself the configuration
     * most users will run, and it is recorded in the spec either way.
     */
    private fun Delegate.toLiteRtBackend(options: Map<String, String>): LiteRtBackend = when (this) {
        Delegate.CPU -> LiteRtBackend.CPU(threadCount = options[OPTION_THREADS]?.toIntOrNull())
        Delegate.GPU -> LiteRtBackend.GPU()
        Delegate.NPU ->
            if (isGoogleTensor()) {
                LiteRtBackend.GOOGLE_TENSOR()
            } else {
                val libraries = context.applicationInfo.nativeLibraryDir
                // Qualcomm's runtime finds its DSP-side libraries (the Hexagon
                // "skel") through this variable, not the dynamic linker.
                Os.setenv(ADSP_LIBRARY_PATH, libraries, true)
                LiteRtBackend.NPU(nativeLibraryDir = libraries)
            }
    }

    /**
     * `Build.SOC_MANUFACTURER` only exists from API 31, and minSdk here is 29,
     * so it is read behind a version check. Older Tensor devices are identified
     * by their board name instead: `gs101`/`gs201` for Tensor G1 and G2, `zuma`
     * and `zumapro` for G3 onward.
     */
    private fun isGoogleTensor(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            Build.SOC_MANUFACTURER.equals("Google", ignoreCase = true)
        ) {
            return true
        }
        return Build.HARDWARE.startsWith("zuma") || Build.HARDWARE.startsWith("gs")
    }

    /**
     * The NPU vendor's dispatch library, when this build carries one. The
     * Maven LiteRT-LM package does not: an NPU engine started without it
     * aborts the whole process natively, so NPU is offered only when the
     * library is actually packaged (with the vendor runtime it loads, such as
     * Qualcomm's QAIRT libraries). The model must also be compiled for this
     * SoC, which only loading it can confirm.
     */
    private val npuDispatch: String? by lazy {
        val loader = context.classLoader as? BaseDexClassLoader ?: return@lazy null
        NPU_DISPATCH_LIBRARIES.firstOrNull { loader.findLibrary(it) != null }
    }

    /**
     * Returns a human-readable reason when the runtime cannot be used on this
     * device, or null when it can. A missing native library is a recorded gap,
     * never a crash mid-run.
     */
    private fun nativeLoadFailure(): String? = try {
        Class.forName("com.google.ai.edge.litertlm.Engine")
        null
    } catch (e: Throwable) {
        "LiteRT-LM runtime not loadable on this device: ${e.javaClass.simpleName}: ${e.message}"
    }

    private companion object {
        const val TRACE_PREFIX = "ThermalBench.litert"
        const val ADSP_LIBRARY_PATH = "ADSP_LIBRARY_PATH"
        val NPU_DISPATCH_LIBRARIES = listOf(
            "LiteRtDispatch_Qualcomm",
            "LiteRtDispatch_MediaTek",
            "LiteRtDispatch_GoogleTensor",
        )
        const val OPTION_MAX_TOKENS = "maxNumTokens"
        const val OPTION_THREADS = "threads"
        const val DEFAULT_TOP_K = 1
        const val DEFAULT_TOP_P = 1.0
        // Greedy decoding: sampling noise would show up as throughput noise.
        const val DEFAULT_TEMPERATURE = 0.0
    }
}
