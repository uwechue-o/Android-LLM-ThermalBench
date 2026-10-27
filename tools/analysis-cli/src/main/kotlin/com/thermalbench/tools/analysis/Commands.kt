package com.thermalbench.tools.analysis

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import com.thermalbench.analysis.DefaultDegradationAnalyzer
import com.thermalbench.analysis.chart.ChartPage
import com.thermalbench.domain.model.ValidityVerdict
import com.thermalbench.domain.usecase.AnalyzeDegradationUseCase
import java.io.File

private val analyzer = DefaultDegradationAnalyzer()
private val analyze = AnalyzeDegradationUseCase(analyzer)

private fun Double?.fmt(decimals: Int = 2): String =
    this?.let { String.format("%.${decimals}f", it) } ?: "—"

private fun Double?.pct(): String = this?.let { String.format("%.1f%%", it * 100) } ?: "—"

class SummaryCommand : CliktCommand(name = "summary") {
    override fun help(context: Context) = "Print the headline numbers for each result document."

    private val paths by argument(help = "Result documents or directories").file().multiple()
    private val terminal = Terminal()

    override fun run() {
        val report = Documents.load(paths)
        report.failures.forEach { (file, message) ->
            terminal.println(TextColors.red("could not read ${file.name}: $message"))
        }
        if (report.loaded.isEmpty()) {
            terminal.println(TextColors.yellow("No result documents found."))
            return
        }
        report.loaded.forEach { (file, result) ->
            val profile = result.profile ?: analyze(result)
            terminal.println(TextColors.brightBlue("── ${file.name}"))
            terminal.println("   device      ${result.device.manufacturer} ${result.device.model} (${result.device.socModel ?: "unknown SoC"})")
            terminal.println("   backend     ${result.spec.backend.id.value}/${result.spec.backend.delegate.name} ${result.environment.backendVersion ?: ""}")
            terminal.println("   model       ${result.spec.model.name}")
            terminal.println("   path        ${result.environment.executionPath}${if (result.environment.liveChartEnabled) " (charts on)" else ""}")
            terminal.println("   iterations  ${profile.measuredIterationCount}")
            terminal.println("   peak        ${profile.peakDecodeTokensPerSecond.fmt(1)} tok/s")
            terminal.println("   steady      ${profile.steadyStateTokensPerSecond.fmt(1)} tok/s")
            terminal.println("   retained    ${profile.retentionRatio.pct()}")
            terminal.println("   throttle    ${profile.iterationsToFirstThrottle?.let { "iteration $it" } ?: "never"}")
            terminal.println("   validity    ${verdictColor(profile.validity)}")
        }
    }

    private fun verdictColor(verdict: ValidityVerdict): String = when (verdict) {
        ValidityVerdict.VALID -> TextColors.green(verdict.name)
        else -> TextColors.red(verdict.name)
    }
}

class CompareCommand : CliktCommand(name = "compare") {
    override fun help(context: Context) = "Cross-device comparison table for one spec."

    private val paths by argument(help = "Result documents or directories").file().multiple()
    private val includeInvalid by option(
        "--include-invalid",
        help = "Include runs whose preconditions were unmet or whose data is insufficient",
    ).flag()
    private val csv by option("--csv", help = "Write the table to this CSV file").file()
    private val terminal = Terminal()

    override fun run() {
        val report = Documents.load(paths)
        report.failures.forEach { (file, message) ->
            terminal.println(TextColors.red("could not read ${file.name}: $message"))
        }
        val rows = report.loaded
            .map { it.result to (it.result.profile ?: analyze(it.result)) }
            .filter { (_, profile) -> includeInvalid || profile.validity == ValidityVerdict.VALID }
            .sortedByDescending { (_, profile) -> profile.retentionRatio ?: -1.0 }

        if (rows.isEmpty()) {
            terminal.println(TextColors.yellow("Nothing to compare. Pass --include-invalid to see rejected runs."))
            return
        }

        // Runs of different specs are not comparable; say so rather than
        // silently tabulating them side by side.
        val specIds = rows.map { it.first.spec.specId.value }.toSet()
        if (specIds.size > 1) {
            terminal.println(
                TextColors.yellow(
                    "Warning: ${specIds.size} different specs in this table. " +
                        "Only runs of the same spec are comparable.",
                ),
            )
        }

        terminal.println(
            table {
                header { row("device", "soc", "backend", "peak", "steady", "retained", "to throttle", "n", "validity") }
                body {
                    rows.forEach { (result, profile) ->
                        row(
                            "${result.device.manufacturer} ${result.device.model}",
                            result.device.socModel ?: "—",
                            "${result.spec.backend.id.value}/${result.spec.backend.delegate.name}",
                            profile.peakDecodeTokensPerSecond.fmt(1),
                            profile.steadyStateTokensPerSecond.fmt(1),
                            profile.retentionRatio.pct(),
                            profile.iterationsToFirstThrottle?.toString() ?: "none",
                            profile.measuredIterationCount.toString(),
                            profile.validity.name,
                        )
                    }
                }
            },
        )

        csv?.let { target ->
            val header = "device,soc,backend,delegate,peak_tps,steady_tps,retention,iterations_to_throttle,n,validity"
            val lines = rows.joinToString("\n") { (result, profile) ->
                listOf(
                    "${result.device.manufacturer} ${result.device.model}",
                    result.device.socModel ?: "",
                    result.spec.backend.id.value,
                    result.spec.backend.delegate.name,
                    profile.peakDecodeTokensPerSecond.fmt(3),
                    profile.steadyStateTokensPerSecond.fmt(3),
                    profile.retentionRatio.fmt(4),
                    profile.iterationsToFirstThrottle?.toString() ?: "",
                    profile.measuredIterationCount.toString(),
                    profile.validity.name,
                ).joinToString(",") { field -> if (field.contains(',')) "\"$field\"" else field }
            }
            target.writeText("$header\n$lines\n")
            terminal.println(TextColors.green("wrote ${target.path}"))
        }
    }
}

class ReanalyzeCommand : CliktCommand(name = "reanalyze") {
    override fun help(context: Context) =
        "Recompute the profile from the recorded samples and report any disagreement."

    private val paths by argument(help = "Result documents or directories").file().multiple()
    private val write by option("--write", help = "Rewrite the documents with the new profile").flag()
    private val terminal = Terminal()

    override fun run() {
        val report = Documents.load(paths)
        report.loaded.forEach { (file, result) ->
            val recomputed = analyze(result)
            val stored = result.profile
            val changed = stored != null && stored.retentionRatio != recomputed.retentionRatio
            terminal.println(
                buildString {
                    append(file.name).append(": ")
                    append("retained ").append(recomputed.retentionRatio.pct())
                    if (stored == null) {
                        append(TextColors.yellow(" (no stored profile)"))
                    } else if (changed) {
                        // Worth surfacing loudly: the analysis code changed since
                        // the run, so published numbers may need a correction.
                        append(TextColors.yellow(" (was ${stored.retentionRatio.pct()})"))
                    }
                },
            )
            if (write) {
                file.writeText(Documents.encode(result.copy(profile = recomputed)))
            }
        }
        report.failures.forEach { (file, message) ->
            terminal.println(TextColors.red("could not read ${file.name}: $message"))
        }
    }
}

class PlotCommand : CliktCommand(name = "plot") {
    override fun help(context: Context) =
        "Draw decode speed and battery temperature over time to a PNG, each run labelled where its line ends. " +
            "The app's \"Share chart\" button draws the same image."

    private val paths by argument(help = "Result documents or directories").file().multiple()
    private val output by option("--out").file().default(File("decay.png"))
    private val width by option("--width", help = "Image width in pixels").int().default(DEFAULT_WIDTH)
    private val height by option("--height", help = "Image height in pixels").int().default(DEFAULT_HEIGHT)
    private val terminal = Terminal()

    override fun run() {
        val report = Documents.load(paths)
        report.failures.forEach { (file, message) ->
            terminal.println(TextColors.red("could not read ${file.name}: $message"))
        }
        if (report.loaded.isEmpty()) {
            terminal.println(TextColors.yellow("No result documents found."))
            return
        }
        // In the order they ran, so colours follow the session rather than file names.
        val results = report.loaded.map { it.result }.sortedBy { it.startedAtEpochMillis }
        if (results.size > ChartPage.MAX_SERIES) {
            terminal.println(
                TextColors.yellow("Drawing the first ${ChartPage.MAX_SERIES} of ${results.size} runs; more would not stay distinguishable."),
            )
        }
        AwtChartCanvas.writePng(ChartPage.of(results), output, width, height)
        terminal.println(TextColors.green("wrote ${output.path}"))
    }

    private companion object {
        const val DEFAULT_WIDTH = 1600
        const val DEFAULT_HEIGHT = 1000
    }
}
