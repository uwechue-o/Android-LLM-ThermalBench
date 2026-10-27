package com.thermalbench.harness

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.BenchmarkSpec
import com.thermalbench.domain.model.PreconditionState
import com.thermalbench.domain.model.RunProgress
import com.thermalbench.domain.model.ScreenPolicy
import com.thermalbench.domain.model.SpecId
import com.thermalbench.domain.port.DeviceConditionSource
import com.thermalbench.domain.port.ResultCodec
import com.thermalbench.domain.usecase.AwaitPreconditionsUseCase
import com.thermalbench.domain.usecase.RunContext
import com.thermalbench.domain.usecase.RunSustainedBenchmarkUseCase
import com.thermalbench.domain.usecase.SpecValidation
import com.thermalbench.domain.usecase.ValidateSpecUseCase
import com.thermalbench.persistence.RunJournal
import com.thermalbench.persistence.artifact.ArtifactStore
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * The headless execution path (GUIDE.md §4.1): no UI process, no rendering
 * load, scriptable across a fleet, and reproducible by anyone who reads the
 * paper. Everything the app can do to a measurement — compose, recompose,
 * draw — is simply absent here.
 */
class HeadlessRunner @Inject constructor(
    private val validateSpec: ValidateSpecUseCase,
    private val awaitPreconditions: AwaitPreconditionsUseCase,
    private val runBenchmark: RunSustainedBenchmarkUseCase,
    private val conditions: DeviceConditionSource,
    private val codec: ResultCodec,
    private val artifacts: ArtifactStore,
    private val journal: RunJournal,
) {

    data class Outcome(
        val result: BenchmarkResult,
        val documentFile: File,
        val csvFile: File,
    )

    suspend fun run(context: Context, arguments: HarnessArguments): Outcome {
        // Checked before the spec is even read: a results directory the harness
        // cannot share would otherwise surface only after a forty-minute run.
        val directories = HarnessDirectories.prepare(context)
        check(directories.foreign.isEmpty()) { HarnessDirectories.explain(directories) }

        val spec = readSpec(context, arguments)
        when (val validation = validateSpec(spec)) {
            is SpecValidation.Valid -> Unit
            is SpecValidation.Invalid -> error(
                "Spec rejected before any device time was spent:\n" +
                    validation.problems.joinToString("\n") { "  ${it.field}: ${it.message}" },
            )
        }

        // `am instrument` does not keep the CPU awake. Unplugged with the screen
        // off, a Honor Magic V3 slept through most of a 45-minute gate wait: the
        // wait took 99 minutes of wall time and the harness, polling only in
        // maintenance windows, never started the run. Held from here to the end
        // of the run, like the app's service does.
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            }
        try {
            return runAwake(spec, arguments, wakeLockHeld = wakeLock.isHeld)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private suspend fun runAwake(spec: BenchmarkSpec, arguments: HarnessArguments, wakeLockHeld: Boolean): Outcome {
        val gate = awaitGate(spec, arguments)
        val runContext = RunContext(
            preconditionsSatisfied = gate.satisfied,
            unmetAtStart = gate.unmet,
            conditionsAtStart = gate.conditions,
            wakeLockHeld = wakeLockHeld,
            screenPolicy = spec.preconditions.screenPolicy,
            executionPath = EXECUTION_PATH,
            liveChartEnabled = false,
        )

        journal.reset()
        var finished: BenchmarkResult? = null
        runBenchmark(spec, runContext).collect { progress ->
            when (progress) {
                is RunProgress.IterationCompleted -> {
                    journal.record(RunJournal.IN_FLIGHT, progress.sample)
                    Log.i(
                        TAG,
                        "iteration ${progress.sample.index + 1}/${progress.total} " +
                            "phase=${progress.sample.phase} " +
                            "decode=${"%.2f".format(progress.sample.decodeTokensPerSecond)} tok/s " +
                            "thermal=${progress.sample.thermalStatusAtEnd}",
                    )
                }
                is RunProgress.ThermalChanged ->
                    Log.i(TAG, "thermal ${progress.transition.from} -> ${progress.transition.to}")
                is RunProgress.Finished -> finished = progress.result
                else -> Unit
            }
        }

        val result = requireNotNull(finished) { "Benchmark produced no result" }
        journal.flush(RunJournal.IN_FLIGHT)
        val outcome = writeOutputs(result, arguments)
        // The document is written and authoritative; the journal's crash-recovery
        // copy has done its job.
        journal.discardPersisted()
        return outcome
    }

    private fun readSpec(context: Context, arguments: HarnessArguments): BenchmarkSpec {
        val file = File(requireNotNull(arguments.specPath) { "Missing -e specPath <path to spec json>" })
        if (!file.isFile) {
            // "Not found" on its own sends people hunting for a file that is
            // demonstrably on the device. Say where the process actually looked
            // and what it can actually see from in here.
            error(buildString {
                appendLine("Spec file not found: ${arguments.specPath}")
                appendLine("  exists=${file.exists()} canRead=${file.canRead()} isDir=${file.isDirectory}")
                appendLine("  canonical=${runCatching { file.canonicalPath }.getOrElse { "unreadable" }}")
                appendLine("  parent exists=${file.parentFile?.exists()} readable=${file.parentFile?.canRead()}")
                appendLine("  parent contents=${file.parentFile?.list()?.joinToString() ?: "unlistable"}")
                val external = context.getExternalFilesDir(null)
                appendLine("  getExternalFilesDir=$external exists=${external?.exists()}")
                appendLine("  external contents=${external?.list()?.joinToString() ?: "unlistable"}")
                append("  filesDir=${context.filesDir}")
            })
        }
        val declared = codec.decodeSpec(file.readText())
        val spec = arguments.iterations?.let { override ->
            declared.copy(protocol = declared.protocol.copy(measuredIterations = override))
        } ?: declared
        // The id is recomputed from what will actually run. The file's own id
        // goes stale when the spec is edited by hand or overridden here, and a
        // stale id files a GPU run, say, beside the CPU runs it was copied from.
        val derived = spec.copy(specId = SpecId.fromDigest(codec.specDigest(spec)))
        if (derived.specId != declared.specId) {
            Log.w(TAG, "spec id ${declared.specId} does not describe this run; recording it as ${derived.specId}")
        }
        return derived
    }

    private data class Gate(
        val satisfied: Boolean,
        val unmet: List<String>,
        val conditions: com.thermalbench.domain.model.DeviceConditions?,
    )

    private suspend fun awaitGate(spec: BenchmarkSpec, arguments: HarnessArguments): Gate {
        val policy = arguments.preconditionTimeoutSeconds
            ?.let { spec.preconditions.copy(maxWaitForPreconditions = it.seconds) }
            ?: spec.preconditions
        // Screen state is a precondition, and under `am instrument` nothing here
        // turns the screen on or off; the operator or tools/cli sets it first.
        check(policy.screenPolicy in ScreenPolicy.entries) { "unreachable" }

        var last: PreconditionState? = null
        awaitPreconditions(policy).collect { state ->
            last = state
            if (state is PreconditionState.Waiting && state.unmet.isNotEmpty()) {
                Log.i(TAG, "waiting for preconditions: ${state.unmet.joinToString { it.description }}")
            }
        }

        return when (val terminal = last) {
            is PreconditionState.Satisfied -> Gate(true, emptyList(), terminal.at)
            is PreconditionState.TimedOut -> {
                val unmet = terminal.unmet.map { it.description }
                if (!arguments.ignorePreconditions) {
                    error(
                        "Preconditions not met within ${policy.maxWaitForPreconditions}: " +
                            "${unmet.joinToString()}. Pass -e ignorePreconditions true to run anyway; " +
                            "the result will be marked PRECONDITIONS_UNMET.",
                    )
                }
                Log.w(TAG, "starting with unmet preconditions: ${unmet.joinToString()}")
                Gate(false, unmet, terminal.latest)
            }
            else -> Gate(false, listOf("Precondition gate produced no terminal state"), conditions.snapshot())
        }
    }

    private suspend fun writeOutputs(result: BenchmarkResult, arguments: HarnessArguments): Outcome {
        val documentName = artifacts.fileNameFor(result)
        val csvName = artifacts.csvNameFor(result.resultId)
        val document = codec.encodeResult(result)
        val csv = codec.resultCsv(result)

        val documentFile: File
        val csvFile: File
        if (arguments.outputDir != null) {
            val dir = File(arguments.outputDir).also { it.mkdirs() }
            documentFile = File(dir, documentName).also { it.writeText(document) }
            csvFile = File(dir, csvName).also { it.writeText(csv) }
        } else {
            documentFile = artifacts.write(documentName, document)
            csvFile = artifacts.writeSidecar(csvName, csv)
        }
        Log.i(TAG, "wrote ${documentFile.absolutePath}")
        return Outcome(result, documentFile, csvFile)
    }

    private companion object {
        const val TAG = "ThermalBench"
        const val WAKE_LOCK_TAG = "ThermalBench::harness"

        // A backstop for a process that dies without reaching `finally`: past
        // the longest gate wait plus the longest run a spec allows.
        const val WAKE_LOCK_TIMEOUT_MILLIS = 4L * 60L * 60L * 1000L
        const val EXECUTION_PATH = "headless"
    }
}
