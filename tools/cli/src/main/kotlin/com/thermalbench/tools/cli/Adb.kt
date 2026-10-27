package com.thermalbench.tools.cli

import java.io.File
import java.util.concurrent.TimeUnit

/** One attached device. */
data class Device(val serial: String, val state: String, val model: String?) {
    val ready: Boolean get() = state == "device"
}

/**
 * A thin wrapper over the `adb` executable.
 *
 * Shelling out rather than speaking the ADB protocol keeps the tool debuggable:
 * every command it runs can be copied from the log and pasted into a terminal,
 * which matters when someone else is reproducing a published measurement.
 */
class Adb(private val executable: String = defaultExecutable()) {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
    }

    fun devices(): List<Device> {
        val output = run(listOf("devices", "-l")).stdout
        return output.lineSequence()
            .drop(1)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val model = parts.firstOrNull { it.startsWith("model:") }?.removePrefix("model:")
                Device(parts[0], parts[1], model)
            }
            .toList()
    }

    fun push(serial: String, local: File, remote: String): Result =
        run(listOf("-s", serial, "push", local.absolutePath, remote))

    fun pull(serial: String, remote: String, local: File): Result =
        run(listOf("-s", serial, "pull", remote, local.absolutePath))

    fun shell(serial: String, command: String, timeout: Long = DEFAULT_TIMEOUT_MINUTES): Result =
        run(listOf("-s", serial, "shell", command), timeout)

    fun install(serial: String, apk: File): Result =
        run(listOf("-s", serial, "install", "-r", "-g", apk.absolutePath), INSTALL_TIMEOUT_MINUTES)

    fun run(arguments: List<String>, timeoutMinutes: Long = DEFAULT_TIMEOUT_MINUTES): Result {
        val process = ProcessBuilder(listOf(executable) + arguments)
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            return Result(TIMEOUT_EXIT_CODE, stdout, "timed out after $timeoutMinutes minutes")
        }
        return Result(process.exitValue(), stdout, stderr)
    }

    companion object {
        const val TIMEOUT_EXIT_CODE = -1
        private const val DEFAULT_TIMEOUT_MINUTES = 5L
        private const val INSTALL_TIMEOUT_MINUTES = 10L

        /** Honours ANDROID_HOME so a machine with several SDKs uses the intended one. */
        fun defaultExecutable(): String {
            val home = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            if (home != null) {
                val name = if (isWindows()) "adb.exe" else "adb"
                val candidate = File(File(home, "platform-tools"), name)
                if (candidate.isFile) return candidate.absolutePath
            }
            return "adb"
        }

        fun isWindows(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("win")
    }
}
