package com.thermalbench.tools.analysis

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

/**
 * Offline re-analysis and cross-device comparison.
 *
 * It calls the same `core/analysis` code the device runs. Two implementations
 * of the same statistics is how a published discrepancy happens, so there is
 * exactly one, and this tool proves it by re-deriving on a workstation the
 * numbers a phone reported.
 */
fun main(args: Array<String>) = AnalysisCli()
    .subcommands(SummaryCommand(), CompareCommand(), ReanalyzeCommand(), PlotCommand())
    .main(args)

class AnalysisCli : CliktCommand(name = "thermalbench-analyze") {
    override fun help(context: Context) =
        "Re-analyze ThermalBench result documents and compare them across devices."

    override fun run() = Unit
}
