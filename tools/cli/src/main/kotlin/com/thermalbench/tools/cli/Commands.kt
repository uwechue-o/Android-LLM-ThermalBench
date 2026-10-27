package com.thermalbench.tools.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.Delegate
import java.io.File

private const val HARNESS_PACKAGE = "com.thermalbench.harness"
private const val RUNNER = "androidx.test.runner.AndroidJUnitRunner"
// The harness targets a scoped-storage SDK, so it cannot open an arbitrary
// path under /sdcard no matter what permission it declares. Its own external
// files directory needs no permission and is still reachable from adb, which
// makes it the only location that works for both sides.
private const val HARNESS_FILES_DIR = "/sdcard/Android/data/$HARNESS_PACKAGE/files"
private const val REMOTE_SPEC_DIR = "$HARNESS_FILES_DIR/specs"
private const val REMOTE_MODEL_DIR = "$HARNESS_FILES_DIR/models"
private const val REMOTE_OUTPUT_DIR = "$HARNESS_FILES_DIR/results"

/**
 * Has the harness create its own specs/, models/ and results/ directories.
 *
 * They must not be made with `adb shell mkdir`: a directory adb creates there
 * belongs to shell, the harness cannot read a spec pushed into it, and adb
 * cannot pull results the harness writes into it. `am instrument` exits 0
 * even when the test inside fails, so success is read from the runner's output.
 */
private fun prepareHarness(adb: Adb, serial: String, terminal: Terminal): Boolean {
    val result = adb.shell(
        serial,
        "am instrument -w -e ${HarnessArgs.PREPARE} true $HARNESS_PACKAGE/$RUNNER",
    )
    val ok = result.ok && "OK (1 test)" in result.stdout
    if (!ok) {
        terminal.println(TextColors.red("  harness could not prepare its directories:"))
        result.stdout.lineSequence()
            .filterNot { it.trimStart().startsWith("at ") }
            .forEach { terminal.println("    $it") }
    }
    return ok
}

class DevicesCommand : CliktCommand(name = "devices") {
    override fun help(context: Context) = "List attached devices and whether the harness is installed."

    private val terminal = Terminal()

    override fun run() {
        val adb = Adb()
        val devices = adb.devices()
        if (devices.isEmpty()) {
            terminal.println(TextColors.yellow("No devices attached."))
            return
        }
        terminal.println(
            table {
                header { row("serial", "state", "model", "harness") }
                body {
                    devices.forEach { device ->
                        val installed = device.ready && adb
                            .shell(device.serial, "pm list packages $HARNESS_PACKAGE")
                            .stdout.contains(HARNESS_PACKAGE)
                        row(
                            device.serial,
                            device.state,
                            device.model ?: "—",
                            if (installed) "installed" else "missing",
                        )
                    }
                }
            },
        )
    }
}

class RunCommand : CliktCommand(name = "run") {
    override fun help(context: Context) =
        "Push a spec and run the headless harness on every ready device, then pull the results."

    private val spec by option("--spec", help = "Local spec JSON document").file(mustExist = true).required()
    private val output by option("--out", help = "Local directory for pulled results")
        .file().default(File("results"))
    private val serial by option("--serial", help = "Restrict to one device serial")
    private val iterations by option("--iterations", help = "Override the spec's measured iterations").int()
    private val ignorePreconditions by option(
        "--ignore-preconditions",
        help = "Run even if the gate is unsatisfied; the result is marked PRECONDITIONS_UNMET",
    ).flag()
    private val screenOff by option(
        "--screen-off",
        help = "Turn the screen off before starting, matching the default precondition policy",
    ).flag()

    private val terminal = Terminal()

    override fun run() {
        val adb = Adb()
        val targets = adb.devices().filter { it.ready && (serial == null || it.serial == serial) }
        if (targets.isEmpty()) {
            terminal.println(TextColors.red("No ready devices."))
            return
        }
        output.mkdirs()

        targets.forEach { device ->
            terminal.println(TextColors.brightBlue("── ${device.serial} (${device.model ?: "unknown"})"))
            if (!prepareHarness(adb, device.serial, terminal)) return@forEach
            val remoteSpec = "$REMOTE_SPEC_DIR/${spec.name}"
            val pushed = adb.push(device.serial, spec, remoteSpec)
            if (!pushed.ok) {
                terminal.println(TextColors.red("  push failed: ${pushed.stderr.trim()}"))
                return@forEach
            }

            if (screenOff) {
                // The screen is a precondition, not an incidental. Setting it
                // here means every device in the fleet runs under the same one.
                adb.shell(device.serial, "input keyevent KEYCODE_SLEEP")
            }

            val arguments = buildString {
                append("-e ${HarnessArgs.SPEC_PATH} $remoteSpec ")
                append("-e ${HarnessArgs.OUTPUT_DIR} $REMOTE_OUTPUT_DIR ")
                iterations?.let { append("-e ${HarnessArgs.ITERATIONS} $it ") }
                if (ignorePreconditions) append("-e ${HarnessArgs.IGNORE_PRECONDITIONS} true ")
            }
            val command = "am instrument -w $arguments$HARNESS_PACKAGE/$RUNNER"
            terminal.println("  $command")

            val result = adb.shell(device.serial, command, timeout = RUN_TIMEOUT_MINUTES)
            terminal.println(summarize(result.stdout))
            if (!result.ok) terminal.println(TextColors.red("  ${result.stderr.trim()}"))

            val destination = File(output, device.serial).also { it.mkdirs() }
            adb.pull(device.serial, REMOTE_OUTPUT_DIR, destination)
            terminal.println(TextColors.green("  pulled into ${destination.path}"))
        }
    }

    /** Reads the status bundle the harness sends back rather than scraping logcat. */
    private fun summarize(output: String): String {
        val values = output.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("INSTRUMENTATION_STATUS: thermalbench.")) return@mapNotNull null
                val body = trimmed.removePrefix("INSTRUMENTATION_STATUS: thermalbench.")
                val key = body.substringBefore('=')
                val value = body.substringAfter('=', "")
                key to value
            }
            .toMap()
        if (values.isEmpty()) return "  (no result reported)"
        return buildString {
            append("  validity=").append(values["validity"] ?: "?")
            append(" retained=").append(values["retentionRatio"]?.take(6) ?: "?")
            append(" peak=").append(values["peakDecodeTokensPerSecond"]?.take(6) ?: "?")
            append(" iterations=").append(values["measuredIterations"] ?: "?")
        }
    }

    private companion object {
        const val RUN_TIMEOUT_MINUTES = 120L
    }
}

class PullCommand : CliktCommand(name = "pull") {
    override fun help(context: Context) = "Pull result documents from every attached device."

    private val output by option("--out").file().default(File("results"))
    private val terminal = Terminal()

    override fun run() {
        val adb = Adb()
        output.mkdirs()
        adb.devices().filter { it.ready }.forEach { device ->
            val destination = File(output, device.serial).also { it.mkdirs() }
            val result = adb.pull(device.serial, REMOTE_OUTPUT_DIR, destination)
            terminal.println(
                if (result.ok) {
                    TextColors.green("${device.serial}: pulled into ${destination.path}")
                } else {
                    TextColors.red("${device.serial}: ${result.stderr.trim()}")
                },
            )
        }
    }
}

class PushModelCommand : CliktCommand(name = "push-model") {
    override fun help(context: Context) =
        "Copy a model file to every attached device, into the directory the harness can read."

    private val model by option("--model", help = "Local model file")
        .file(mustExist = true).required()
    private val serial by option("--serial", help = "Restrict to one device serial")
    private val terminal = Terminal()

    override fun run() {
        val adb = Adb()
        val targets = adb.devices().filter { it.ready && (serial == null || it.serial == serial) }
        if (targets.isEmpty()) {
            terminal.println(TextColors.red("No ready devices."))
            return
        }
        targets.forEach { device ->
            if (!prepareHarness(adb, device.serial, terminal)) return@forEach
            val remote = "$REMOTE_MODEL_DIR/${model.name}"
            // Multi-gigabyte files, so allow well beyond the default timeout.
            val result = adb.run(
                listOf("-s", device.serial, "push", model.absolutePath, remote),
                timeoutMinutes = PUSH_TIMEOUT_MINUTES,
            )
            terminal.println(
                if (result.ok) {
                    TextColors.green("${device.serial}: $remote")
                } else {
                    TextColors.red("${device.serial}: ${result.stderr.trim()}")
                },
            )
        }
    }

    private companion object {
        const val PUSH_TIMEOUT_MINUTES = 60L
    }
}

class SpecCommand : CliktCommand(name = "spec") {
    override fun help(context: Context) =
        "Print a spec document to standard output, with its identity already derived."

    private val backend by option(
        "--backend",
        help = "Backend id: litert-lm, mediapipe-genai, llamacpp or executorch",
    ).default(BackendId.LITERT_LM.value)

    private val delegate by option("--delegate", help = "CPU, GPU or NPU").default(Delegate.GPU.name)
    private val modelPath by option("--model-path", help = "Absolute path to the model on the device")
    private val modelName by option("--model-name", help = "Name recorded in the result document")
    private val iterations by option("--iterations", help = "Measured iterations").int()
    private val outputTokens by option("--output-tokens", help = "Tokens generated per iteration").int()

    private val terminal = Terminal()

    override fun run() {
        // The format follows from the file extension, the same rule the app
        // uses, so a GGUF path can never be submitted as a LiteRT-LM model.
        val path = modelPath ?: SpecTemplates.defaultPathFor(backend)
        terminal.println(
            SpecTemplates.template(
                modelPath = path,
                modelName = modelName ?: path.substringAfterLast('/').substringBeforeLast('.'),
                backend = BackendId(backend),
                delegate = Delegate.valueOf(delegate.uppercase()),
                format = SpecTemplates.formatForPath(path),
                measuredIterations = iterations,
                outputTokens = outputTokens,
            ),
        )
    }
}

/** Argument names shared with the harness. */
object HarnessArgs {
    const val SPEC_PATH = "specPath"
    const val OUTPUT_DIR = "outputDir"
    const val ITERATIONS = "iterations"
    const val IGNORE_PRECONDITIONS = "ignorePreconditions"
    const val PREPARE = "prepare"
}
