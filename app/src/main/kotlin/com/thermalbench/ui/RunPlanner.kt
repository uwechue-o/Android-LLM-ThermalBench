package com.thermalbench.ui

import com.thermalbench.backend.api.DiscoveredModel
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.ModelFormat
import com.thermalbench.domain.model.sustainedChart
import com.thermalbench.ui.screen.composer.presentation
import java.io.File

/**
 * Expands the form into the runs Start performs. A single run is the form as
 * it stands. A comparison holds everything fixed except the one thing being
 * compared, so a difference in the results can only come from that.
 */
fun SpecForm.planRuns(backends: List<BackendOption>, models: List<DiscoveredModel>): List<PlannedRun> =
    when (comparison) {
        Comparison.SINGLE -> listOf(PlannedRun(computeLabel(), this))
        Comparison.COMPUTE_UNITS ->
            computeUnitRuns(backends, builtFor = models.firstOrNull { it.path == modelPath }?.builtFor)
        Comparison.OTHER_MODEL -> listOfNotNull(
            PlannedRun(modelLabel(), this),
            secondModel(backends, models)?.let { PlannedRun(it.modelLabel(), it) },
        )
    }.map { run ->
        // Labels are not part of a spec's identity, so naming the run in it
        // costs nothing and keeps the run recognisable in the history.
        val label = if (comparison == Comparison.SINGLE) this.label else listOf(this.label, run.label)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        run.copy(form = run.form.copy(comparison = Comparison.SINGLE, compareModelPath = "", label = label))
    }

/**
 * Every delegate the backend offers, and for the GPU, every API it can use. A
 * model compiled for one compute unit ([builtFor], a Genie bundle) has only
 * that one to compare.
 */
private fun SpecForm.computeUnitRuns(backends: List<BackendOption>, builtFor: Delegate?): List<PlannedRun> {
    val option = backends.firstOrNull { it.id == backendId } ?: return listOf(PlannedRun(computeLabel(), this))
    val delegates = option.delegates.filter { builtFor == null || it == builtFor }
    return delegates.sortedBy { it.ordinal }.flatMap { delegate ->
        if (delegate == Delegate.GPU && option.gpuApis.isNotEmpty()) {
            option.gpuApis.map { api -> copy(delegate = delegate, gpuApi = api) }
        } else {
            listOf(copy(delegate = delegate, gpuApi = null))
        }
    }.map { PlannedRun(it.computeLabel(), it) }
}

/**
 * The form with the second model swapped in. Its backend follows its format;
 * the compute unit and GPU API are kept where that backend offers them.
 * Null until a second model is chosen.
 */
private fun SpecForm.secondModel(backends: List<BackendOption>, models: List<DiscoveredModel>): SpecForm? {
    if (compareModelPath.isBlank()) return null
    val model = models.firstOrNull { it.path == compareModelPath }
    val name = when {
        model == null -> File(compareModelPath).nameWithoutExtension
        model.format == ModelFormat.GENIE_BUNDLE -> model.name
        else -> model.name.substringBeforeLast('.')
    }
    val format = model?.format ?: modelFormat
    val swapped = copy(
        modelPath = compareModelPath,
        modelName = name,
        modelFormat = format,
        delegate = model?.builtFor ?: delegate,
    )
    return swapped.withBackendFor(format, backends)
}

/**
 * Keeps the chosen backend if it can load [format], otherwise moves to the
 * first available one that can, then keeps the delegate and GPU API only
 * where the backend offers them.
 */
internal fun SpecForm.withBackendFor(format: ModelFormat, options: List<BackendOption>): SpecForm {
    val fit = options.firstOrNull { it.id == backendId && it.available && format in it.formats }
        ?: options.firstOrNull { it.available && format in it.formats }
        ?: return this
    val delegate = delegate.takeIf { it in fit.delegates } ?: fit.delegates.minByOrNull { it.ordinal } ?: delegate
    return copy(backendId = fit.id, delegate = delegate).withGpuApiFor(fit)
}

/**
 * A GPU run names its API whenever the backend can tell them apart, so the
 * spec, and with it the result, says which one ran. Any other run names none.
 */
internal fun SpecForm.withGpuApiFor(option: BackendOption?): SpecForm {
    val apis = option?.gpuApis.orEmpty()
    val api = when {
        delegate != Delegate.GPU || apis.isEmpty() -> null
        apis.any { it.equals(gpuApi, ignoreCase = true) } -> gpuApi
        else -> apis.first()
    }
    return if (api == gpuApi) this else copy(gpuApi = api)
}

/** "llama.cpp · GPU (OpenCL)": what a compute-unit comparison varies. */
internal fun SpecForm.computeLabel(): String {
    val api = gpuApi?.let { " ($it)" }.orEmpty()
    return "${backendId.presentation().displayName} · ${delegate.name}$api"
}

/** The model name first: what a model comparison varies. */
private fun SpecForm.modelLabel(): String =
    "${modelName.ifBlank { File(modelPath).nameWithoutExtension }} · ${computeLabel()}"

/** A finished comparison, in the order the runs ran, ready to draw. */
fun List<BenchmarkResult>.toSeriesRuns(): List<SeriesRun> = map { result ->
    val profile = result.profile
    val chart = result.sustainedChart()
    SeriesRun(
        label = result.spec.label.ifBlank { result.spec.model.name },
        resultId = result.resultId,
        validity = profile?.validity,
        peakDecodeTokensPerSecond = profile?.peakDecodeTokensPerSecond,
        steadyStateTokensPerSecond = profile?.steadyStateTokensPerSecond,
        retentionRatio = profile?.retentionRatio,
        iterationsToFirstThrottle = profile?.iterationsToFirstThrottle,
        computeDevice = result.environment.computeDevice,
        decodeSeries = chart.speed.map { Point(it.x.toFloat(), it.y.toFloat()) },
        batterySeries = chart.batteryCelsius.map { Point(it.x.toFloat(), it.y.toFloat()) },
        closingTokensPerSecond = chart.closingTokensPerSecond,
        closingShareOfPeak = chart.closingShareOfPeak,
        closingWindow = chart.closingWindow,
    )
}
