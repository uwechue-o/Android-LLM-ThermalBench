package com.thermalbench.domain.usecase

import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.BenchmarkSpec
import com.thermalbench.domain.model.DeviceConditions
import com.thermalbench.domain.model.DeviceDescriptor
import com.thermalbench.domain.model.IterationSample
import com.thermalbench.domain.model.LoadedModel
import com.thermalbench.domain.model.Phase
import com.thermalbench.domain.model.PowerSample
import com.thermalbench.domain.model.ResultId
import com.thermalbench.domain.model.RunEnvironment
import com.thermalbench.domain.model.RunProgress
import com.thermalbench.domain.model.ScreenPolicy
import com.thermalbench.domain.model.TerminationReason
import com.thermalbench.domain.model.ThermalSample
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.ThermalTimeline
import com.thermalbench.domain.model.ThermalTransition
import com.thermalbench.domain.port.BackendRegistry
import com.thermalbench.domain.port.DegradationAnalyzer
import com.thermalbench.domain.port.DeviceConditionSource
import com.thermalbench.domain.port.DeviceProfiler
import com.thermalbench.domain.port.FrequencySource
import com.thermalbench.domain.port.MemorySource
import com.thermalbench.domain.port.MonotonicClock
import com.thermalbench.domain.port.PowerTelemetrySource
import com.thermalbench.domain.port.ThermalTelemetrySource
import com.thermalbench.domain.port.WallClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds

/**
 * How the run was set up by whoever invoked the engine. The engine records it
 * verbatim; it does not decide any of it.
 */
data class RunContext(
    val preconditionsSatisfied: Boolean,
    val unmetAtStart: List<String>,
    val conditionsAtStart: DeviceConditions?,
    val wakeLockHeld: Boolean,
    val screenPolicy: ScreenPolicy,
    val executionPath: String,
    val liveChartEnabled: Boolean,
)

/**
 * The sustained-load engine (GUIDE.md §4.3): warmup → N measured iterations,
 * emitting [RunProgress] as it goes and a final [RunProgress.Finished] carrying
 * the complete [BenchmarkResult].
 *
 * Telemetry sampling runs on its own dispatcher and its own coroutine so a
 * long generation call never starves the thermal time series. Both the
 * sampler and the inference loop write into one bounded channel; the single
 * consumer owns ordering and assembles the result.
 */
class RunSustainedBenchmarkUseCase(
    private val backends: BackendRegistry,
    private val thermal: ThermalTelemetrySource,
    private val power: PowerTelemetrySource,
    private val frequency: FrequencySource,
    private val memory: MemorySource,
    private val conditions: DeviceConditionSource,
    private val profiler: DeviceProfiler,
    private val analyzer: DegradationAnalyzer,
    private val clock: MonotonicClock,
    private val wallClock: WallClock,
    private val newResultId: () -> ResultId,
    private val telemetryDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * The longest one generation may take before the run is ended as stalled.
     * Far above any real iteration (128 tokens at 1 token/s is about two
     * minutes), and short enough that a runtime that never answers costs
     * minutes rather than the whole night.
     */
    private val generationLimit: Duration = 5.minutes,
) {
    private sealed interface Event {
        data class Progress(val progress: RunProgress) : Event
        data class Done(val terminated: TerminationReason, val error: String?) : Event
    }

    operator fun invoke(spec: BenchmarkSpec, context: RunContext): Flow<RunProgress> = flow {
        val backend = backends.find(spec.backend.id)
            ?: error("No adapter registered for backend '${spec.backend.id}'")
        val device: DeviceDescriptor = profiler.profile()
        val startedAtEpochMillis = wallClock.epochMillis() // the single wall-clock read
        val runStartNanos = clock.nanos()

        val iterations = ArrayList<IterationSample>(spec.protocol.totalIterations)
        val thermalSamples = ArrayList<ThermalSample>()
        val transitions = ArrayList<ThermalTransition>()
        val powerSamples = ArrayList<PowerSample>()
        var terminated = TerminationReason.COMPLETED
        var errorMessage: String? = null
        var backendVersion: String? = null
        var computeDevice: String? = null

        coroutineScope {
            val events = Channel<Event>(capacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.SUSPEND)

            val sampler: Job = launch(telemetryDispatcher) {
                launch {
                    thermal.poll(spec.protocol.telemetryInterval).collect {
                        events.send(Event.Progress(RunProgress.Thermal(it)))
                    }
                }
                launch {
                    thermal.transitions().collect {
                        events.send(Event.Progress(RunProgress.ThermalChanged(it)))
                    }
                }
                launch {
                    power.stream(spec.protocol.telemetryInterval).collect {
                        events.send(Event.Progress(RunProgress.Power(it)))
                    }
                }
            }

            val runner: Job = launch(inferenceDispatcher) {
                var loaded: LoadedModel? = null
                // A stalled generation still holds the model inside the
                // runtime; unloading it then would block on the same call.
                var stalled = false
                try {
                    backendVersion = backend.capabilities().runtimeVersion
                    events.send(Event.Progress(RunProgress.LoadingModel(backend.id)))
                    val loadStart = clock.nanos()
                    loaded = backend.load(spec.model, spec.backend.delegate, spec.backend.options)
                    computeDevice = loaded.computeDevice
                    events.send(Event.Progress(RunProgress.ModelLoaded(clock.nanos() - loadStart)))

                    val outcome = runIterations(spec, backend, loaded, runStartNanos) { progress ->
                        events.send(Event.Progress(progress))
                    }
                    events.send(Event.Done(outcome, null))
                } catch (e: CancellationException) {
                    events.send(Event.Done(TerminationReason.ABORTED_BY_USER, null))
                    throw e
                } catch (e: GenerationStalledException) {
                    stalled = true
                    events.send(Event.Done(TerminationReason.ERROR, e.message))
                } catch (e: Exception) {
                    events.send(Event.Done(TerminationReason.ERROR, e.message ?: e::class.simpleName))
                } finally {
                    loaded?.takeUnless { stalled }?.let { model ->
                        runCatching { withContext(kotlinx.coroutines.NonCancellable) { backend.unload(model) } }
                    }
                }
            }

            // Single consumer: owns ordering, assembles the result.
            for (event in events) {
                when (event) {
                    is Event.Progress -> {
                        when (val p = event.progress) {
                            is RunProgress.IterationCompleted -> iterations += p.sample
                            is RunProgress.Thermal -> thermalSamples += p.sample
                            is RunProgress.ThermalChanged -> transitions += p.transition
                            is RunProgress.Power -> powerSamples += p.sample
                            else -> Unit
                        }
                        emit(event.progress)
                    }
                    is Event.Done -> {
                        terminated = event.terminated
                        errorMessage = event.error
                        break
                    }
                }
            }
            sampler.cancel()
            runner.join()
            events.close()
        }

        val conditionsAtEnd = runCatching { conditions.snapshot() }.getOrNull()
        val timeline = ThermalTimeline(thermalSamples.toList(), transitions.toList())
        val profile = analyzer.analyze(
            iterations = iterations,
            timeline = timeline,
            terminated = terminated,
            preconditionsSatisfied = context.preconditionsSatisfied,
            minMeasuredIterations = spec.protocol.measuredIterations,
        )
        val result = BenchmarkResult(
            resultId = newResultId(),
            spec = spec,
            device = device,
            environment = RunEnvironment(
                preconditionsSatisfied = context.preconditionsSatisfied,
                unmetAtStart = context.unmetAtStart,
                conditionsAtStart = context.conditionsAtStart,
                conditionsAtEnd = conditionsAtEnd,
                wakeLockHeld = context.wakeLockHeld,
                screenPolicy = context.screenPolicy,
                executionPath = context.executionPath,
                liveChartEnabled = context.liveChartEnabled,
                backendVersion = backendVersion,
                computeDevice = computeDevice,
            ),
            startedAtEpochMillis = startedAtEpochMillis,
            iterations = iterations.toList(),
            thermalTimeline = timeline,
            powerSamples = powerSamples.toList(),
            terminated = terminated,
            errorMessage = errorMessage,
            profile = profile,
            toolVersion = spec.toolVersion,
            schemaVersion = SCHEMA_VERSION,
        )
        emit(RunProgress.Finished(result))
    }

    private suspend fun runIterations(
        spec: BenchmarkSpec,
        backend: com.thermalbench.domain.port.InferenceBackend,
        model: LoadedModel,
        runStartNanos: Long,
        report: suspend (RunProgress) -> Unit,
    ): TerminationReason {
        val protocol = spec.protocol
        val total = protocol.totalIterations
        val deadlineNanos = runStartNanos + protocol.maxRunDuration.inWholeNanoseconds

        for (index in 0 until total) {
            currentCoroutineContext().ensureActive()
            val phase = if (index < protocol.warmupIterations) Phase.WARMUP else Phase.MEASURED
            report(RunProgress.IterationStarted(index, phase, total))

            val statusAtStart = thermal.currentStatus()
            if (protocol.abortOnCriticalThermal && statusAtStart >= ThermalStatus.CRITICAL) {
                return TerminationReason.THERMAL_CRITICAL
            }
            val headroomAtStart = thermal.headroom(HEADROOM_FORECAST_SECONDS)
            val powerAtStart = power.snapshot()
            val conditionsAtStart = conditions.snapshot()
            val freqAtStart = frequency.readCpuFrequenciesKhz()
            val gpuFreqAtStart = frequency.readGpuFrequencyKhz()
            val sensorsAtStart = thermal.sensorTemperatures()

            val generation = withStallLimit(generationLimit, inferenceDispatcher) {
                backend.generate(model, spec.workload, spec.seed + index)
            } ?: throw GenerationStalledException(index, generationLimit)

            val statusAtEnd = thermal.currentStatus()
            val mem = memory.snapshot()

            val sample = IterationSample(
                index = index,
                phase = phase,
                startElapsedNanos = generation.startElapsedNanos,
                endElapsedNanos = generation.endElapsedNanos,
                timeToFirstTokenMicros = generation.timeToFirstTokenMicros,
                prefillTokensPerSecond = generation.prefillTokensPerSecond,
                decodeTokensPerSecond = generation.decodeTokensPerSecond,
                promptTokenCount = generation.promptTokenCount,
                outputTokenCount = generation.outputTokenCount,
                thermalStatusAtStart = statusAtStart,
                thermalStatusAtEnd = statusAtEnd,
                thermalHeadroomAtStart = headroomAtStart,
                batteryTemperatureDeciC = conditionsAtStart.batteryTemperatureDeciC,
                batteryPercent = powerAtStart.batteryPercent,
                cpuFrequenciesKhz = freqAtStart,
                peakRssBytes = mem.rssBytes,
                throttleObserved = statusAtStart.isThrottling || statusAtEnd.isThrottling,
                gpuFrequencyKhz = gpuFreqAtStart,
                sensorTemperatures = sensorsAtStart,
            )
            report(RunProgress.IterationCompleted(sample, total))

            if (protocol.abortOnCriticalThermal && statusAtEnd >= ThermalStatus.CRITICAL) {
                return TerminationReason.THERMAL_CRITICAL
            }
            if (clock.nanos() >= deadlineNanos) {
                return TerminationReason.TIMEOUT
            }
            if (protocol.interIterationDelay.isPositive() && index < total - 1) {
                delay(protocol.interIterationDelay)
            }
        }
        return TerminationReason.COMPLETED
    }

    companion object {
        /** Bumped whenever the result document shape changes. Mirrored by core/schema. */
        const val SCHEMA_VERSION: Int = 2
        private const val EVENT_BUFFER = 512
        private const val HEADROOM_FORECAST_SECONDS = 10
    }
}

/** A generation that produced no result within the limit; the run ends with this as its error. */
class GenerationStalledException(iteration: Int, limit: Duration) : Exception(
    "Iteration ${iteration + 1} produced no result in $limit; the inference runtime stopped responding. " +
        "Its model was left loaded rather than risk blocking on unloading it.",
)

/**
 * Runs [block] on [dispatcher] and waits at most [limit] for its result; null
 * if it is not done by then.
 *
 * Native inference calls cannot be interrupted, so cancelling one achieves
 * nothing and waiting for it can mean waiting forever (seen with LiteRT-LM and
 * MediaPipe on a Snapdragon 865: the first generation never returned). The
 * work therefore runs outside the caller's scope, which stops waiting at the
 * limit and abandons it.
 */
suspend fun <T> withStallLimit(limit: Duration, dispatcher: CoroutineDispatcher, block: suspend () -> T): T? {
    val work = CoroutineScope(dispatcher + SupervisorJob()).async { block() }
    return withTimeoutOrNull(limit) { work.await() }.also { if (it == null) work.cancel() }
}

/** Convenience for callers that want the elapsed run time of a sample list. */
fun List<IterationSample>.elapsedSince(runStartNanos: Long) =
    lastOrNull()?.let { (it.endElapsedNanos - runStartNanos).nanoseconds }
