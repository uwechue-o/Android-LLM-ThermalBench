package com.thermalbench.ui

import com.thermalbench.backend.api.CatalogModel
import com.thermalbench.backend.api.DiscoveredModel
import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.BenchmarkSpec
import com.thermalbench.domain.model.DegradationProfile
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.ModelFormat
import com.thermalbench.domain.model.ResultId
import com.thermalbench.domain.model.TerminationReason
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.UnmetCondition
import com.thermalbench.domain.model.ValidityVerdict
import com.thermalbench.domain.usecase.SpecProblem
import com.thermalbench.report.DocumentKind
import kotlin.time.Duration

/** A point on a live chart. Kept minimal: this list is rebuilt on every frame it changes. */
data class Point(val x: Float, val y: Float)

/** What the spec composer is holding, before it becomes a [com.thermalbench.domain.model.BenchmarkSpec]. */
data class SpecForm(
    val modelPath: String = "",
    val modelName: String = "",
    val modelFormat: ModelFormat = ModelFormat.LITERT_LM,
    val backendId: BackendId = BackendId.LITERT_LM,
    val delegate: Delegate = Delegate.GPU,
    val warmupIterations: Int = 3,
    val measuredIterations: Int = 50,
    val outputTokens: Int = 128,
    val interIterationDelaySeconds: Int = 0,
    val abortOnCriticalThermal: Boolean = true,
    val requireUnplugged: Boolean = true,
    val minBatteryPercent: Int = 50,
    val label: String = "",
    /** Which GPU API a GPU run uses, where the backend offers a choice; null otherwise. */
    val gpuApi: String? = null,
    val comparison: Comparison = Comparison.SINGLE,
    /** The second model of a [Comparison.OTHER_MODEL] comparison. */
    val compareModelPath: String = "",
)

/** One run Start will perform: the form it runs, and how it is named beside the others. */
data class PlannedRun(val label: String, val form: SpecForm)

/** What Start runs: one spec, or several back to back with a cool-down between each. */
enum class Comparison {
    SINGLE,

    /** The same model on every compute unit the backend offers, and each GPU API. */
    COMPUTE_UNITS,

    /** The chosen settings, then the same settings with a second model. */
    OTHER_MODEL,
}

data class BackendOption(
    val id: BackendId,
    val available: Boolean,
    val unavailableReason: String?,
    val delegates: Set<Delegate>,
    val formats: Set<ModelFormat>,
    /** GPU APIs to choose between, preferred first; empty when there is no choice to make. */
    val gpuApis: List<String> = emptyList(),
)

/**
 * A model file arriving in the models directory, from the document picker or
 * from a catalog download.
 *
 * The picker returns a `content://` URI and the inference runtimes need a real
 * path, so choosing a file means copying it. These files are large enough that
 * the transfer has to be visible and cancellable rather than a frozen screen.
 */
data class ModelImport(
    val fileName: String,
    /** "copied" or "downloaded", so the progress line says what is happening. */
    val verb: String = "copied",
    val copiedBytes: Long,
    /** Zero when the provider would not tell us the size. */
    val totalBytes: Long,
) {
    val fraction: Float?
        get() = if (totalBytes > 0) (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

sealed interface BenchUiState {

    data object Idle : BenchUiState

    /** The open-source licences of everything this build ships. */
    data object Licences : BenchUiState

    data class Composing(
        val form: SpecForm,
        val problems: List<SpecProblem>,
        val backends: List<BackendOption>,
        /** Model files found in the app's own models directory. */
        val models: List<DiscoveredModel> = emptyList(),
        /** Files sitting in that directory whose extension names no known format. */
        val unrecognisedFiles: List<String> = emptyList(),
        /** Where to put model files, shown when the directory is empty. */
        val pushCommand: String = "",
        /** The directory actually scanned, shown so a rescan can be trusted. */
        val modelsDirectory: String = "",
        /** False when storage fell back somewhere adb cannot reach. */
        val modelsDirectoryAdbAccessible: Boolean = true,
        val scanning: Boolean = false,
        /** Non-null while a picked file is being copied into the models directory. */
        val import: ModelImport? = null,
        /** Why the last import was refused, in words fit for the screen. */
        val importError: String? = null,
        /** Published models this tool can fetch and verify, or link to. */
        val catalog: List<CatalogModel> = emptyList(),
        /**
         * The specs the form produced when it last passed validation, one per
         * run. Cleared on any change to the form, so they never describe
         * settings since edited.
         */
        val validSpecs: List<BenchmarkSpec> = emptyList(),
        /**
         * Wall-clock time of the last scan. Its only job is to make every scan
         * visibly change something, so a scan that finds nothing still reads as
         * a scan rather than as a dead button.
         */
        val lastScanEpochMillis: Long = 0L,
    ) : BenchUiState

    data class Preconditioning(
        val unmet: List<UnmetCondition>,
        val currentThermal: ThermalStatus,
        val headroom: Float?,
        val batteryPercent: Int,
        val elapsedInWindow: Duration,
        val estimatedReady: Duration?,
    ) : BenchUiState

    data class Warmup(val iteration: Int, val total: Int) : BenchUiState

    data class Running(
        val iteration: Int,
        val total: Int,
        val liveDecodeTps: Double,
        val decaySeries: List<Point>,
        val thermalSeries: List<Point>,
        val currentThermal: ThermalStatus,
        val throttled: Boolean,
        val batteryPercent: Int,
        val chartsEnabled: Boolean,
    ) : BenchUiState

    data class Complete(
        val resultId: ResultId,
        val profile: DegradationProfile,
        val telemetry: TelemetrySummary? = null,
        val performance: PerformanceSeries? = null,
        /** File names the save dialog suggests, by kind. */
        val suggestedNames: Map<DocumentKind, String> = emptyMap(),
        /** What the last save did, in words fit for the screen; null before any. */
        val saveMessage: String? = null,
        /** Why the run ended early, when it ended in an error; null otherwise. */
        val errorMessage: String? = null,
    ) : BenchUiState

    /** Every run of a comparison, side by side. */
    data class SeriesComplete(val runs: List<SeriesRun>) : BenchUiState

    data class Aborted(
        val reason: TerminationReason,
        val message: String?,
        /** Iterations that finished before the end; null when not known. */
        val completedIterations: Int? = null,
    ) : BenchUiState {
        /** The operator pressed Cancel; nothing went wrong. */
        val cancelled: Boolean get() = reason == TerminationReason.ABORTED_BY_USER
    }
}

/** One run of a finished comparison: what it was, how it did, and its decay curve. */
data class SeriesRun(
    val label: String,
    val resultId: ResultId,
    val validity: ValidityVerdict?,
    val peakDecodeTokensPerSecond: Double?,
    val steadyStateTokensPerSecond: Double?,
    val retentionRatio: Double?,
    val iterationsToFirstThrottle: Int?,
    val computeDevice: String?,
    /** Decode tokens/s per measured iteration, against minutes since the run's first. */
    val decodeSeries: List<Point>,
    /** Battery °C on the same axis; empty when it was not readable. */
    val batterySeries: List<Point> = emptyList(),
    /** Mean of the last [closingWindow] measured iterations: where the run ended up. */
    val closingTokensPerSecond: Double? = null,
    val closingShareOfPeak: Double? = null,
    val closingWindow: Int = 0,
)

/** Everything the UI can ask for. Composables receive this function, never the ViewModel. */
sealed interface BenchIntent {
    data object Compose : BenchIntent
    data object ShowLicences : BenchIntent
    data class UpdateForm(val form: SpecForm) : BenchIntent
    data class SelectModel(val model: DiscoveredModel) : BenchIntent
    data object RefreshModels : BenchIntent

    /** A file chosen from the document picker, to be copied in and selected. */
    data class ImportModel(val uri: android.net.Uri) : BenchIntent
    data object CancelImport : BenchIntent

    /** A catalog entry to download, verify and select. */
    data class DownloadModel(val entry: CatalogModel) : BenchIntent
    data object DismissImportError : BenchIntent
    data object Validate : BenchIntent
    data object Start : BenchIntent
    data object Abort : BenchIntent
    data object Dismiss : BenchIntent
    data class SetLiveCharts(val enabled: Boolean) : BenchIntent
    /** Writes the result to a file the operator picked in the system save dialog. */
    data class SaveDocument(val resultId: ResultId, val kind: DocumentKind, val target: android.net.Uri) : BenchIntent

    /** Opens a past result from the history. */
    data class OpenResult(val resultId: ResultId) : BenchIntent

    /** Draws these results' chart as an image and opens the share sheet with it. */
    data class ShareChart(val resultIds: List<ResultId>) : BenchIntent
}
