package com.thermalbench.tools.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

/**
 * Fleet orchestrator. Pushes a spec to every attached device, launches the
 * headless harness on each, and pulls the result documents back.
 *
 * The whole point of driving the harness rather than the app is that a run
 * started this way has no UI process at all, so the numbers from a phone on
 * someone else's desk are produced the same way as the ones in the paper.
 */
fun main(args: Array<String>) = ThermalBenchCli()
    .subcommands(DevicesCommand(), PushModelCommand(), RunCommand(), PullCommand(), SpecCommand())
    .main(args)

class ThermalBenchCli : CliktCommand(name = "thermalbench") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Drive the ThermalBench headless harness across a fleet of devices."

    override fun run() = Unit
}
