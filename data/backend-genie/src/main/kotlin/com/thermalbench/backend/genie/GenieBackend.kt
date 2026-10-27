package com.thermalbench.backend.genie

import android.content.Context
import android.os.Build
import android.system.Os
import androidx.tracing.trace
import com.thermalbench.backend.api.GenerationTimer
import com.thermalbench.backend.api.GenieBundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Qualcomm Genie adapter: LLMs on the Qualcomm AI Runtime, on the Hexagon NPU
 * (QnnHtp bundles) or on Qualcomm's CPU path (QnnGenAiTransformer bundles).
 *
 * Genie streams the response a piece at a time, so time-to-first-token and the
 * decode rate are measured from the pieces as they arrive, on the same clock
 * as every other backend. Generation is capped at the spec's token count; a
 * model that ends its answer sooner is recorded with the count it reached,
 * as a shorter iteration rather than a faster one.
 *
 * The compute unit is the bundle's, fixed when it was compiled. A spec asking
 * a CPU bundle for the NPU is refused at load with that reason, not quietly
 * run on the CPU under an NPU label.
 */
@Singleton
class GenieBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: MonotonicClock,
    private val modelFiles: ModelFiles,
) : InferenceBackend {

    override val id: BackendId = BackendId.QUALCOMM_GENIE

    private class GenieLoadedModel(
        ref: ModelRef,
        delegate: Delegate,
        loadNanos: Long,
        computeDevice: String,
        val handle: Long,
    ) : LoadedModel(ref, delegate, loadNanos, computeDevice)

    /**
     * The compute units Genie can actually drive here, each checked before a
     * run rather than discovered by one. Both limits were found on devices:
     *
     * - CPU bundles use int8 matrix-multiply instructions (`smmla`, ARMv8.6
     *   i8mm). On a CPU without them, such as the Snapdragon 865's Cortex-A77,
     *   the first generation dies with SIGILL and takes the process with it.
     * - NPU bundles need Hexagon v73 or newer (Snapdragon 8 Gen 2 onward).
     */
    private val usableDelegates: Set<Delegate> by lazy {
        buildSet {
            if (cpuHasI8mm()) add(Delegate.CPU)
            if (socModel() in NPU_CAPABLE_SOCS) add(Delegate.NPU)
        }
    }

    /**
     * Why Genie cannot run here, or null when it can. Qualcomm's runtime runs
     * only on Snapdragon: on other silicon even its CPU path refuses to start
     * (QNN_COMMON_ERROR_PLATFORM_NOT_SUPPORTED), so it is reported unavailable
     * up front rather than failing at the first run.
     */
    private val unavailableReason: String? by lazy {
        GenieNative.loadFailure ?: when {
            !isSnapdragon() -> "Qualcomm Genie runs only on Snapdragon; this device's SoC is ${socName()}."
            usableDelegates.isEmpty() ->
                "Qualcomm Genie needs a Snapdragon 8 Gen 1 or newer: its CPU path uses int8 matrix " +
                    "instructions this CPU lacks, and its NPU path needs Hexagon v73 (${socName()})."
            else -> null
        }
    }

    override suspend fun capabilities(): BackendCapabilities = BackendCapabilities(
        id = id,
        available = unavailableReason == null,
        unavailableReason = unavailableReason,
        formats = setOf(ModelFormat.GENIE_BUNDLE),
        // Which of these a run gets is the bundle's; load() holds a spec to it.
        delegates = usableDelegates,
        supportsTokenCountStop = true,
        supportsStreaming = true,
        runtimeVersion = if (GenieNative.available) {
            "QAIRT ${BuildConfig.QAIRT_VERSION} · Genie ${GenieNative.apiVersion()}"
        } else {
            null
        },
    )

    override suspend fun load(model: ModelRef, delegate: Delegate, options: Map<String, String>): LoadedModel =
        withContext(Dispatchers.Default) {
            unavailableReason?.let { error(it) }
            when (val verification = modelFiles.verify(model)) {
                is ModelFiles.Verification.Ok -> Unit
                else -> error("Model bundle rejected: $verification")
            }
            val config = File(model.path)
            val configText = config.readText()
            val backendType = GenieBundle.backendType(configText)
            val builtFor = GenieBundle.delegateOf(backendType)
                ?: error("Genie bundle names backend '$backendType', which this tool does not run")
            check(builtFor == delegate) {
                "This Genie bundle was compiled for $builtFor ($backendType); it cannot run on $delegate"
            }
            check(delegate in usableDelegates) {
                "Genie cannot run $delegate bundles on this device (${socName()}); it can run $usableDelegates"
            }
            if (delegate == Delegate.NPU) {
                // The QNN runtime finds its DSP-side skel through this
                // variable, not the dynamic linker.
                Os.setenv(ADSP_LIBRARY_PATH, context.applicationInfo.nativeLibraryDir, true)
            }
            val prepared = GenieBundle.prepare(configText, config.parentFile!!, seed = 0, maxTokens = DEFAULT_MAX_TOKENS)
            val start = clock.nanos()
            val handle = trace("$TRACE_PREFIX.load") { GenieNative.create(prepared) }
            GenieLoadedModel(
                ref = model,
                delegate = delegate,
                loadNanos = clock.nanos() - start,
                computeDevice = "$backendType via Genie",
                handle = handle,
            )
        }

    override suspend fun generate(model: LoadedModel, workload: Workload, seed: Long): GenerationResult =
        withContext(Dispatchers.Default) {
            val loaded = model as GenieLoadedModel
            val targetTokens = (workload.stopCondition as StopCondition.TokenCount).tokens
            val timer = GenerationTimer(clock)

            // Every iteration starts from an empty context, so iteration N does
            // not measure a longer sequence than iteration 1.
            GenieNative.reset(loaded.handle)
            GenieNative.setMaxTokens(loaded.handle, targetTokens)

            timer.start()
            val status = trace("$TRACE_PREFIX.query") {
                GenieNative.query(loaded.handle, workload.prompt) { text, code ->
                    // One piece per generated token; an empty closing piece
                    // marks the end and is not a token.
                    if (code in TEXT_CODES && (text.isNotEmpty() || code != GenieNative.SENTENCE_END)) {
                        timer.onToken(text)
                    }
                }
            }
            check(status == GenieNative.STATUS_SUCCESS || status == GenieNative.STATUS_CONTEXT_EXCEEDED) {
                "Genie query failed (status $status)"
            }
            val observed = minOf(timer.observedTokenCount, targetTokens)
            // The window closes at the Nth token, as on every other backend.
            val endNanos = timer.timestampOfToken(targetTokens) ?: clock.nanos()
            timer.finish(
                promptTokenCount = workload.promptTokens,
                outputTokenCount = observed,
                endNanos = endNanos,
            )
        }

    override suspend fun unload(model: LoadedModel) = withContext(Dispatchers.Default) {
        runCatching { GenieNative.free((model as GenieLoadedModel).handle) }
        Unit
    }

    private fun isSnapdragon(): Boolean {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else ""
        return soc.equals("QTI", ignoreCase = true) ||
            soc.contains("Qualcomm", ignoreCase = true) ||
            Build.HARDWARE.equals("qcom", ignoreCase = true) ||
            Build.BOARD.startsWith("msm", ignoreCase = true)
    }

    private fun socModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.uppercase() else ""

    /** The kernel's own list of CPU features; `i8mm` is the ARMv8.6 int8 matrix multiply. */
    private fun cpuHasI8mm(): Boolean = runCatching {
        File("/proc/cpuinfo").useLines { lines ->
            lines.filter { it.startsWith("Features") }
                .any { line -> line.substringAfter(':').split(' ').any { it == "i8mm" } }
        }
    }.getOrDefault(false)

    private fun socName(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim()
    } else {
        Build.HARDWARE
    }

    private companion object {
        const val TRACE_PREFIX = "ThermalBench.genie"
        const val ADSP_LIBRARY_PATH = "ADSP_LIBRARY_PATH"
        const val DEFAULT_MAX_TOKENS = 128

        /** Snapdragons with Hexagon v73 or newer: 8 Gen 2, 8 Gen 3, 8 Elite, 8 Elite Gen 5 and their variants. */
        val NPU_CAPABLE_SOCS = setOf("SM8550", "SM8650", "SM8635", "SM8750", "SM8735", "SM8850")
        val TEXT_CODES = setOf(
            GenieNative.SENTENCE_COMPLETE,
            GenieNative.SENTENCE_BEGIN,
            GenieNative.SENTENCE_CONTINUE,
            GenieNative.SENTENCE_END,
        )
    }
}
