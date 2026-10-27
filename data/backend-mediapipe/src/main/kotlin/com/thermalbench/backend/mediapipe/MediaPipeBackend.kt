package com.thermalbench.backend.mediapipe

import android.content.Context
import androidx.tracing.trace
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPipe Tasks GenAI adapter — the second Google-stack path.
 *
 * MediaPipe stops on end-of-sequence as well as on its token budget, so unlike
 * LiteRT-LM it cannot be told "produce exactly N tokens". Rather than let that
 * quietly make its numbers incomparable, the adapter timestamps every streamed
 * token and ends the measurement window at the Nth one. Tokens the runtime
 * emits afterwards fall outside the window, and a generation that stopped early
 * is reported with the count it actually reached, which the analysis records as
 * a shorter iteration rather than a faster one.
 */
@Singleton
class MediaPipeBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: MonotonicClock,
    private val modelFiles: ModelFiles,
) : InferenceBackend {

    override val id: BackendId = BackendId.MEDIAPIPE

    private class MediaPipeLoadedModel(
        ref: ModelRef,
        delegate: Delegate,
        loadNanos: Long,
        val inference: LlmInference,
    ) : LoadedModel(ref, delegate, loadNanos)

    override suspend fun capabilities(): BackendCapabilities {
        val failure = nativeLoadFailure()
        return BackendCapabilities(
            id = id,
            available = failure == null,
            unavailableReason = failure,
            formats = setOf(ModelFormat.MEDIAPIPE_TASK),
            // MediaPipe exposes CPU and GPU only; there is no third-party NPU path.
            delegates = setOf(Delegate.CPU, Delegate.GPU),
            supportsTokenCountStop = true,
            supportsStreaming = true,
            runtimeVersion = BuildConfig.MEDIAPIPE_GENAI_VERSION,
        )
    }

    override suspend fun load(model: ModelRef, delegate: Delegate, options: Map<String, String>): LoadedModel =
        withContext(Dispatchers.Default) {
            when (val verification = modelFiles.verify(model)) {
                is ModelFiles.Verification.Ok -> Unit
                else -> error("Model file rejected: $verification")
            }
            val maxTokens = options[OPTION_MAX_TOKENS]?.toIntOrNull() ?: DEFAULT_MAX_TOKENS
            val start = clock.nanos()
            val inference = trace("$TRACE_PREFIX.load") {
                LlmInference.createFromOptions(
                    context,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(model.path)
                        .setMaxTokens(maxTokens)
                        .setMaxTopK(DEFAULT_TOP_K)
                        .setPreferredBackend(delegate.toMediaPipeBackend())
                        .build(),
                )
            }
            MediaPipeLoadedModel(model, delegate, clock.nanos() - start, inference)
        }

    override suspend fun generate(model: LoadedModel, workload: Workload, seed: Long): GenerationResult =
        withContext(Dispatchers.Default) {
            val loaded = model as MediaPipeLoadedModel
            val targetTokens = (workload.stopCondition as StopCondition.TokenCount).tokens
            val timer = GenerationTimer(clock)
            val finished = CountDownLatch(1)
            val ended = AtomicBoolean(false)
            val text = StringBuffer()

            // One session per iteration: a fresh context (iteration N must not
            // measure a longer sequence than iteration 1), greedy sampling with
            // the run's seed, and a generation that can be stopped. The engine's
            // own generateResponseAsync could do none of these: it sampled with
            // top-k 40 at temperature 0.8, and it kept generating past the Nth
            // token, so the next iteration's request waited behind it and, on a
            // OnePlus 8T, never got an answer.
            val session = LlmInferenceSession.createFromOptions(
                loaded.inference,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(DEFAULT_TOP_K)
                    .setTemperature(GREEDY_TEMPERATURE)
                    .setRandomSeed(seed.toInt())
                    .build(),
            )
            try {
                timer.start()
                session.addQueryChunk(workload.prompt)
                val future = trace("$TRACE_PREFIX.generate") {
                    session.generateResponseAsync { partial, done ->
                        // One call per decoded chunk; the timestamp is taken inside
                        // GenerationTimer, as close to arrival as the API allows.
                        if (!ended.get()) {
                            timer.onToken(partial ?: "")
                            text.append(partial ?: "")
                        }
                        if (done || timer.observedTokenCount >= targetTokens) {
                            ended.set(true)
                            finished.countDown()
                        }
                    }
                }

                val completed = finished.await(GENERATION_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                // The measurement window closes at the Nth token, and the
                // generation is stopped there rather than left running into the
                // next iteration.
                runCatching { session.cancelGenerateResponseAsync() }
                runCatching { future.get(FUTURE_DRAIN_SECONDS, TimeUnit.SECONDS) }
                check(completed) { "MediaPipe generation exceeded $GENERATION_TIMEOUT_MINUTES minutes" }
            } finally {
                runCatching { session.close() }
            }

            // A generation that ends before its Nth token measured something
            // else. MediaPipe's GPU path on an Adreno 650 ended every one after
            // a single chunk, and recording that as 0 tok/s hid the failure.
            check(timer.observedTokenCount >= targetTokens) {
                "MediaPipe ended the generation after ${timer.observedTokenCount} of $targetTokens tokens; " +
                    "output: \"${text.toString().take(OUTPUT_EXCERPT_CHARS)}\""
            }
            val endNanos = timer.timestampOfToken(targetTokens) ?: clock.nanos()
            val observed = minOf(timer.observedTokenCount, targetTokens)

            timer.finish(
                promptTokenCount = workload.promptTokens,
                outputTokenCount = observed,
                endNanos = endNanos,
            )
        }

    override suspend fun unload(model: LoadedModel) = withContext(Dispatchers.Default) {
        runCatching { (model as MediaPipeLoadedModel).inference.close() }
        Unit
    }

    /** Counts tokens the way the runtime does, for verifying a spec's promptTokens. */
    fun tokenCount(model: LoadedModel, text: String): Int =
        (model as MediaPipeLoadedModel).inference.sizeInTokens(text)

    private fun Delegate.toMediaPipeBackend(): LlmInference.Backend = when (this) {
        Delegate.CPU -> LlmInference.Backend.CPU
        Delegate.GPU -> LlmInference.Backend.GPU
        Delegate.NPU -> error("MediaPipe Tasks GenAI has no NPU delegate; validate the spec first")
    }

    private fun nativeLoadFailure(): String? = try {
        Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
        null
    } catch (e: Throwable) {
        "MediaPipe GenAI runtime not loadable on this device: ${e.javaClass.simpleName}: ${e.message}"
    }

    private companion object {
        const val TRACE_PREFIX = "ThermalBench.mediapipe"
        const val OUTPUT_EXCERPT_CHARS = 120
        const val OPTION_MAX_TOKENS = "maxTokens"
        const val DEFAULT_MAX_TOKENS = 1024
        const val DEFAULT_TOP_K = 1
        const val GREEDY_TEMPERATURE = 0f
        const val GENERATION_TIMEOUT_MINUTES = 5L
        const val FUTURE_DRAIN_SECONDS = 30L
    }
}
