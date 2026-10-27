package com.thermalbench.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thermalbench.backend.api.CatalogModel
import com.thermalbench.backend.api.DiscoveredModel
import com.thermalbench.backend.api.ModelCatalog
import com.thermalbench.backend.api.ModelLibrary
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.BenchmarkSpec
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.ModelFormat
import com.thermalbench.domain.model.ModelRef
import com.thermalbench.domain.model.Phase
import com.thermalbench.domain.model.PreconditionPolicy
import com.thermalbench.domain.model.PreconditionState
import com.thermalbench.domain.model.RunProgress
import com.thermalbench.domain.model.ResultId
import com.thermalbench.domain.model.RunProtocol
import com.thermalbench.domain.model.Sha256
import com.thermalbench.domain.model.SpecId
import com.thermalbench.domain.model.TerminationReason
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.port.BackendRegistry
import com.thermalbench.domain.port.ResultRepository
import com.thermalbench.domain.port.SpecRepository
import com.thermalbench.domain.usecase.SpecProblem
import com.thermalbench.domain.usecase.SpecValidation
import com.thermalbench.domain.usecase.ValidateSpecUseCase
import com.thermalbench.persistence.settings.SettingsStore
import com.thermalbench.report.ChartImages
import com.thermalbench.report.DocumentKind
import com.thermalbench.report.ResultDocuments
import com.thermalbench.runner.RunController
import com.thermalbench.runner.RunPhase
import com.thermalbench.schema.Fixtures
import com.thermalbench.schema.JsonResultCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Holds no domain logic and no Context (GUIDE.md §5.1). Composables receive
 * [BenchUiState] plus `(BenchIntent) -> Unit`, never this class, so screenshot
 * tests can render every state without a graph.
 */
@HiltViewModel
class BenchViewModel @Inject constructor(
    private val validateSpec: ValidateSpecUseCase,
    private val specs: SpecRepository,
    private val results: ResultRepository,
    private val backends: BackendRegistry,
    private val modelLibrary: ModelLibrary,
    private val runController: RunController,
    private val settings: SettingsStore,
    private val codec: JsonResultCodec,
    private val documents: ResultDocuments,
    private val chartImages: ChartImages,
) : ViewModel() {

    private val _state = MutableStateFlow<BenchUiState>(BenchUiState.Idle)
    val state: StateFlow<BenchUiState> = _state.asStateFlow()

    private val shareRequests = Channel<Intent>(Channel.BUFFERED)

    /** Share sheets to open, once each; the screen host starts them. */
    val shares: Flow<Intent> = shareRequests.receiveAsFlow()

    /** Which run of a comparison is under way; null for a single run. */
    val series = runController.series

    val history = results.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val decay = RingBuffer<Point>(CHART_CAPACITY)
    private val thermal = RingBuffer<Point>(CHART_CAPACITY)
    private var chartsEnabled = true
    private var currentSpec: BenchmarkSpec? = null
    private var importJob: kotlinx.coroutines.Job? = null

    init {
        observeRun()
        viewModelScope.launch {
            settings.settings.collect { chartsEnabled = it.liveChartsEnabled }
        }
    }

    fun onIntent(intent: BenchIntent) {
        when (intent) {
            BenchIntent.Compose -> viewModelScope.launch { openComposer() }
            BenchIntent.ShowLicences -> _state.value = BenchUiState.Licences
            is BenchIntent.UpdateForm -> updateForm(intent.form)
            is BenchIntent.SelectModel -> selectModel(intent.model)
            BenchIntent.RefreshModels -> viewModelScope.launch { refreshModels() }
            is BenchIntent.ImportModel,
            is BenchIntent.DownloadModel,
            BenchIntent.CancelImport,
            BenchIntent.DismissImportError,
            -> onTransferIntent(intent)
            BenchIntent.Validate -> viewModelScope.launch { toggleValidation() }
            BenchIntent.Start -> viewModelScope.launch { start() }
            BenchIntent.Abort -> runController.abort()
            BenchIntent.Dismiss -> _state.value = BenchUiState.Idle
            is BenchIntent.SetLiveCharts -> viewModelScope.launch { settings.setLiveCharts(intent.enabled) }
            is BenchIntent.SaveDocument -> viewModelScope.launch { save(intent) }
            is BenchIntent.OpenResult -> viewModelScope.launch { openResult(intent.resultId) }
            is BenchIntent.ShareChart -> viewModelScope.launch { shareChart(intent.resultIds) }
        }
    }

    /** Getting a model file into the models directory: picked, downloaded, or abandoned. */
    private fun onTransferIntent(intent: BenchIntent) {
        when (intent) {
            is BenchIntent.ImportModel -> importModel(intent.uri)
            is BenchIntent.DownloadModel -> downloadModel(intent.entry)
            BenchIntent.CancelImport -> cancelImport()
            BenchIntent.DismissImportError -> updateComposing { it.copy(importError = null) }
            else -> Unit
        }
    }

    private suspend fun openComposer() {
        val options = backends.all()
            .map { backend ->
                val capabilities = backend.capabilities()
                BackendOption(
                    id = backend.id,
                    available = capabilities.available,
                    unavailableReason = capabilities.unavailableReason,
                    delegates = capabilities.delegates,
                    formats = capabilities.formats,
                    gpuApis = capabilities.gpuApis,
                )
            }
            // Usable backends first. Leading with a disabled one and its error
            // makes the screen look broken before it has been read.
            .sortedWith(compareByDescending<BackendOption> { it.available }.thenBy { it.id.value })

        val first = options.firstOrNull { it.available }
        val form = SpecForm(backendId = first?.id ?: SpecForm().backendId).withGpuApiFor(first)
        _state.value = BenchUiState.Composing(
            form = form,
            problems = emptyList(),
            backends = options,
            pushCommand = modelLibrary.pushCommand(),
            catalog = ModelCatalog.entries,
        )
        refreshModels()
    }

    /**
     * Re-scans the app's models directory, so a freshly pushed file shows up.
     *
     * The scan reports its result even when the result is "nothing", because a
     * silent scan over an empty directory is indistinguishable from a button
     * that does not work.
     */
    private suspend fun refreshModels() {
        val started = _state.value as? BenchUiState.Composing ?: return
        _state.value = started.copy(scanning = true)

        val models = modelLibrary.list()
        val unrecognised = modelLibrary.unrecognised()
        val location = modelLibrary.location()

        val current = _state.value as? BenchUiState.Composing ?: return
        _state.value = current.copy(
            models = models,
            unrecognisedFiles = unrecognised,
            pushCommand = modelLibrary.pushCommand(),
            modelsDirectory = location.path,
            modelsDirectoryAdbAccessible = location.adbAccessible,
            scanning = false,
            lastScanEpochMillis = System.currentTimeMillis(),
        )
    }

    /**
     * Selecting a discovered file fills in the path, the name and the format
     * together. The format is what the old free-text field could never set, so
     * a GGUF file used to be submitted as a LiteRT-LM model and rejected.
     */
    private fun selectModel(model: DiscoveredModel) {
        val current = _state.value as? BenchUiState.Composing ?: return
        val form = current.form.copy(
            modelPath = model.path,
            modelName = if (model.format == ModelFormat.GENIE_BUNDLE) model.name else model.name.substringBeforeLast('.'),
            modelFormat = model.format,
            // A Genie bundle was compiled for one compute unit; preselect it.
            delegate = model.builtFor ?: current.form.delegate,
        )
        // Leaving a GGUF file paired with LiteRT-LM would only surface as a
        // rejected spec after the operator pressed Start.
        _state.value = current.copy(
            form = form.withBackendFor(model.format, current.backends),
            problems = emptyList(),
            validSpecs = emptyList(),
        )
    }

    /** Copies a picked file into the models directory and selects it. */
    private fun importModel(uri: android.net.Uri) {
        val initial = ModelImport("Copying model", copiedBytes = 0L, totalBytes = 0L)
        transfer(initial) { onProgress -> modelLibrary.importFrom(uri, onProgress) }
    }

    /** Downloads a catalog entry, verifies it against its published digest, and selects it. */
    private fun downloadModel(entry: CatalogModel) {
        val initial = ModelImport(entry.fileName, verb = "downloaded", copiedBytes = 0L, totalBytes = entry.sizeBytes)
        transfer(initial) { onProgress -> modelLibrary.download(entry, onProgress) }
    }

    /**
     * Runs one transfer into the models directory, then selects what arrived.
     *
     * Progress updates are throttled. A multi-gigabyte transfer produces hundreds
     * of thousands of buffer writes, and republishing state on each one would
     * put more load on the device than the transfer itself.
     */
    private fun transfer(
        initial: ModelImport,
        block: suspend (onProgress: (Long, Long) -> Unit) -> ModelLibrary.ImportOutcome,
    ) {
        importJob?.cancel()
        updateComposing { it.copy(import = initial, importError = null) }
        importJob = viewModelScope.launch {
            var lastPublishMillis = 0L
            try {
                val outcome = block { copied, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastPublishMillis >= IMPORT_PROGRESS_INTERVAL_MILLIS || copied == total) {
                        lastPublishMillis = now
                        updateComposing { state ->
                            state.copy(
                                import = (state.import ?: initial).copy(copiedBytes = copied, totalBytes = total),
                            )
                        }
                    }
                }
                when (outcome) {
                    is ModelLibrary.ImportOutcome.Imported -> {
                        updateComposing { it.copy(import = null, importError = null) }
                        refreshModels()
                        selectModel(outcome.model)
                    }
                    is ModelLibrary.ImportOutcome.AlreadyPresent -> {
                        updateComposing { it.copy(import = null, importError = null) }
                        refreshModels()
                        selectModel(outcome.model)
                    }
                    is ModelLibrary.ImportOutcome.Rejected ->
                        updateComposing { it.copy(import = null, importError = outcome.reason) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                updateComposing { it.copy(import = null) }
                throw e
            } catch (e: Exception) {
                updateComposing {
                    it.copy(import = null, importError = e.message ?: e::class.java.simpleName)
                }
            }
        }
    }

    private fun cancelImport() {
        importJob?.cancel()
        importJob = null
        updateComposing { it.copy(import = null) }
    }

    /** Applies a change only while the composer is on screen. */
    private inline fun updateComposing(transform: (BenchUiState.Composing) -> BenchUiState.Composing) {
        val current = _state.value as? BenchUiState.Composing ?: return
        _state.value = transform(current)
    }

    private fun updateForm(form: SpecForm) {
        val current = _state.value
        if (current is BenchUiState.Composing) {
            val normalized = form.withGpuApiFor(current.backends.firstOrNull { it.id == form.backendId })
            _state.value = current.copy(form = normalized, problems = emptyList(), validSpecs = emptyList())
        }
    }

    /**
     * Validate shows the verdict, and pressing it again puts the verdict away.
     * A panel on screen is always for the form as it stands, because any edit
     * clears it, so hiding it loses nothing a fresh check would not restore.
     */
    private suspend fun toggleValidation() {
        val current = _state.value as? BenchUiState.Composing ?: return
        if (current.validSpecs.isNotEmpty() || current.problems.isNotEmpty()) {
            _state.value = current.copy(problems = emptyList(), validSpecs = emptyList())
        } else {
            validateCurrent()
        }
    }

    /**
     * Validates every run Start would perform. A comparison starts only if all
     * of its runs can; finding out at run three that it cannot, after an hour
     * of the first two, would waste the hour.
     */
    private suspend fun validateCurrent(): List<BenchmarkSpec>? {
        val current = _state.value as? BenchUiState.Composing ?: return null
        val runs = current.form.planRuns(current.backends, current.models)
        if (current.form.comparison == Comparison.OTHER_MODEL && runs.size < 2) {
            val missing = SpecProblem("comparison", "Choose a second model to compare against")
            _state.value = current.copy(problems = listOf(missing), validSpecs = emptyList())
            return null
        }
        val specs = runs.map { it.form.toSpec(codec) }
        val problems = runs.zip(specs).flatMap { (run, spec) ->
            when (val validation = validateSpec(spec)) {
                is SpecValidation.Valid -> emptyList()
                // Each problem names its run, so a comparison says which one failed.
                is SpecValidation.Invalid -> validation.problems.map { problem ->
                    if (runs.size > 1) problem.copy(field = "${run.label}: ${problem.field}") else problem
                }
            }
        }
        return if (problems.any { it.fatal }) {
            _state.value = current.copy(problems = problems, validSpecs = emptyList())
            null
        } else {
            currentSpec = specs.first()
            // Said out loud: a check that passes in silence looks like a
            // button that does nothing.
            _state.value = current.copy(problems = emptyList(), validSpecs = specs)
            specs
        }
    }

    private suspend fun start() {
        val planned = validateCurrent() ?: return
        planned.forEach { specs.save(it) }
        decay.clear()
        thermal.clear()
        runController.startSeries(planned.map { it.specId })
    }

    /**
     * Chart series are appended here, but the state object is only republished
     * on iteration boundaries and at most once per [CHART_MIN_INTERVAL_NANOS].
     * Recomposing on every telemetry sample is the largest observer effect the
     * app path has (GUIDE.md §5.3).
     */
    private fun observeRun() {
        viewModelScope.launch {
            runController.phase.collect { phase ->
                when (phase) {
                    RunPhase.Idle -> Unit
                    is RunPhase.AwaitingPreconditions -> _state.value = phase.state.toUiState()
                    is RunPhase.Failed, is RunPhase.Cancelled -> _state.value = phase.toAbortedState()
                    is RunPhase.Finished, is RunPhase.SeriesFinished -> _state.value = phase.toFinishedState()
                    is RunPhase.Running -> Unit // Series come from the progress flow below.
                }
            }
        }
        viewModelScope.launch {
            var lastPublishNanos = 0L
            var runStartNanos = 0L
            var latestThermal = ThermalStatus.NONE
            var batteryPercent = 100

            runController.progress.collect { progress ->
                when (progress) {
                    is RunProgress.IterationStarted ->
                        if (progress.phase == Phase.WARMUP) {
                            _state.value = BenchUiState.Warmup(progress.index + 1, progress.total)
                        }

                    is RunProgress.IterationCompleted -> {
                        val sample = progress.sample
                        if (runStartNanos == 0L) runStartNanos = sample.startElapsedNanos
                        val seconds = (sample.startElapsedNanos - runStartNanos) / 1e9f
                        decay.add(Point(seconds, sample.decodeTokensPerSecond.toFloat()))
                        latestThermal = sample.thermalStatusAtEnd
                        batteryPercent = sample.batteryPercent
                        _state.value = BenchUiState.Running(
                            iteration = sample.index + 1,
                            total = progress.total,
                            liveDecodeTps = sample.decodeTokensPerSecond,
                            decaySeries = if (chartsEnabled) decay.toList() else emptyList(),
                            thermalSeries = if (chartsEnabled) thermal.toList() else emptyList(),
                            currentThermal = latestThermal,
                            throttled = latestThermal.isThrottling,
                            batteryPercent = batteryPercent,
                            chartsEnabled = chartsEnabled,
                        )
                        lastPublishNanos = sample.endElapsedNanos
                    }

                    is RunProgress.Thermal -> {
                        if (runStartNanos == 0L) runStartNanos = progress.sample.elapsedNanos
                        val seconds = (progress.sample.elapsedNanos - runStartNanos) / 1e9f
                        thermal.add(Point(seconds, progress.sample.status.ordinal.toFloat()))
                        latestThermal = progress.sample.status
                        val running = _state.value as? BenchUiState.Running ?: return@collect
                        // Throttled to roughly 1 Hz.
                        if (progress.sample.elapsedNanos - lastPublishNanos < CHART_MIN_INTERVAL_NANOS) {
                            return@collect
                        }
                        lastPublishNanos = progress.sample.elapsedNanos
                        if (!chartsEnabled) return@collect
                        _state.value = running.copy(
                            thermalSeries = thermal.toList(),
                            currentThermal = latestThermal,
                            throttled = latestThermal.isThrottling,
                        )
                    }

                    else -> Unit
                }
            }
        }
    }

    /** A run, or a comparison, that ran to its end. */
    private fun RunPhase.toFinishedState(): BenchUiState = when (this) {
        is RunPhase.SeriesFinished -> BenchUiState.SeriesComplete(results.toSeriesRuns())
        is RunPhase.Finished -> result.toCompleteState()
        else -> error("not a finished run: $this")
    }

    /** The result screen for [this], or the aborted screen when there is no profile to show. */
    private fun BenchmarkResult.toCompleteState(): BenchUiState = profile
        ?.let { profile ->
            BenchUiState.Complete(
                resultId = resultId,
                profile = profile,
                telemetry = telemetrySummary(),
                performance = performanceSeries(),
                suggestedNames = DocumentKind.entries.associateWith { ResultDocuments.suggestedName(this, it) },
                errorMessage = errorMessage.takeIf { terminated == TerminationReason.ERROR },
            )
        }
        ?: BenchUiState.Aborted(terminated, errorMessage)

    private suspend fun openResult(id: ResultId) {
        val result = results.load(id) ?: return
        _state.value = result.toCompleteState()
    }

    private suspend fun save(intent: BenchIntent.SaveDocument) {
        val message = try {
            val result = results.load(intent.resultId) ?: error("This result is no longer stored")
            documents.write(result, intent.kind, intent.target)
            "Saved the ${intent.kind.description}."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            "Could not save: ${e.message ?: e::class.java.simpleName}"
        } catch (e: IllegalStateException) {
            "Could not save: ${e.message}"
        }
        val current = _state.value as? BenchUiState.Complete ?: return
        if (current.resultId == intent.resultId) _state.value = current.copy(saveMessage = message)
    }

    private suspend fun shareChart(ids: List<ResultId>) {
        val failure = try {
            val loaded = ids.mapNotNull { results.load(it) }
            check(loaded.isNotEmpty()) { "These results are no longer stored" }
            shareRequests.send(chartImages.shareIntent(loaded))
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            "Could not draw the chart: ${e.message ?: e::class.java.simpleName}"
        } catch (e: IllegalStateException) {
            "Could not draw the chart: ${e.message}"
        }
        val current = _state.value as? BenchUiState.Complete ?: return
        if (failure != null && current.resultId in ids) _state.value = current.copy(saveMessage = failure)
    }

    /** A run that ended early: by the operator's Cancel, or by a failure. */
    private fun RunPhase.toAbortedState(): BenchUiState = when (this) {
        is RunPhase.Cancelled -> BenchUiState.Aborted(
            reason = TerminationReason.ABORTED_BY_USER,
            message = null,
            completedIterations = completedIterations,
        )
        is RunPhase.Failed -> BenchUiState.Aborted(TerminationReason.ERROR, message, completedIterations)
        else -> error("not an early end: $this")
    }

    private fun PreconditionState.toUiState(): BenchUiState = when (this) {
        is PreconditionState.Waiting -> BenchUiState.Preconditioning(
            unmet = unmet,
            currentThermal = latest.thermalStatus,
            headroom = latest.thermalHeadroom,
            batteryPercent = latest.batteryPercent,
            elapsedInWindow = elapsedInWindow,
            estimatedReady = estimatedReady,
        )
        is PreconditionState.Satisfied -> BenchUiState.Warmup(0, currentSpec?.protocol?.totalIterations ?: 0)
        is PreconditionState.TimedOut -> BenchUiState.Preconditioning(
            unmet = unmet,
            currentThermal = latest.thermalStatus,
            headroom = latest.thermalHeadroom,
            batteryPercent = latest.batteryPercent,
            elapsedInWindow = kotlin.time.Duration.ZERO,
            estimatedReady = null,
        )
    }

    private companion object {
        const val CHART_CAPACITY = 600
        const val CHART_MIN_INTERVAL_NANOS = 1_000_000_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val IMPORT_PROGRESS_INTERVAL_MILLIS = 200L
    }
}

/** Builds a spec from the composer form and derives its identity from its content. */
internal fun SpecForm.toSpec(codec: JsonResultCodec): BenchmarkSpec {
    val file = File(modelPath)
    // A hand-typed path still gets its format from the extension, so the two
    // ways of choosing a model behave the same.
    val format = ModelLibrary.formatOf(file.name) ?: modelFormat
    // A file that is exactly a catalog entry, by name and size, gets that
    // entry's published digest pinned, so the spec names the bytes rather than
    // the filename. Loading then verifies the file against it.
    val known = if (file.isFile) ModelCatalog.match(file.name, file.length()) else null
    val workload = Fixtures.referenceWorkload(outputTokens)
    // A GPU API is named only where the backend offers a choice of them.
    val options = gpuApi?.takeIf { delegate == Delegate.GPU }
        ?.let { mapOf(com.thermalbench.domain.model.BackendRef.OPTION_GPU_API to it) }
        .orEmpty()
    return codec.withDerivedId(
        BenchmarkSpec(
            specId = SpecId("pending"),
            model = ModelRef(
                name = modelName.ifBlank { file.nameWithoutExtension },
                format = format,
                // Any other file is recorded as-is rather than blocking the UI
                // on hashing a multi-gigabyte model; the CLI can pin it later.
                sha256 = Sha256(known?.sha256 ?: com.thermalbench.backend.api.ModelFiles.UNPINNED),
                sizeBytes = if (file.isFile) file.length() else 0L,
                path = modelPath,
                quantization = known?.quantization,
                parameterCount = known?.parameterCount,
            ),
            backend = com.thermalbench.domain.model.BackendRef(backendId, delegate, options),
            workload = workload,
            protocol = RunProtocol(
                warmupIterations = warmupIterations,
                measuredIterations = measuredIterations,
                interIterationDelay = interIterationDelaySeconds.seconds,
                abortOnCriticalThermal = abortOnCriticalThermal,
                maxRunDuration = RunProtocol.DEFAULT.maxRunDuration,
            ),
            preconditions = PreconditionPolicy.DEFAULT.copy(
                requireUnplugged = requireUnplugged,
                minBatteryPercent = minBatteryPercent,
            ),
            seed = DEFAULT_SEED,
            toolVersion = Fixtures.TOOL_VERSION,
            label = label,
        ),
    )
}

private const val DEFAULT_SEED = 42L

/** Bounded series storage, so a long run cannot grow the UI's memory without limit. */
internal class RingBuffer<T>(private val capacity: Int) {
    private val items = ArrayDeque<T>(capacity)

    fun add(item: T) {
        if (items.size == capacity) items.removeFirst()
        items.addLast(item)
    }

    fun clear() = items.clear()

    fun toList(): List<T> = items.toList()
}

/** Convenience for previews and tests that need a delegate label. */
internal fun Delegate.displayName(): String = name
