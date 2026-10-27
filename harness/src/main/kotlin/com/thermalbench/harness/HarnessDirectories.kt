package com.thermalbench.harness

import android.content.Context
import android.os.Process
import android.system.Os
import java.io.File

/**
 * The harness's `specs/`, `models/` and `results/` directories, which the
 * harness must create itself.
 *
 * Inside `Android/data/<package>/`, a directory belongs to whoever made it.
 * One made by the harness is owned by the harness and shared with adb's group,
 * so adb can push into it and pull results out of it. One made by
 * `adb shell mkdir` is owned by shell and closed to the harness: the spec
 * pushed into it cannot be read, and results written into it cannot be pulled.
 * Opening it with chmod does not help, because chmod also drops the
 * group-inheritance bit, so results written there still cannot be pulled.
 *
 * Ownership is therefore the test, not readability: a directory the harness
 * does not own is reported before any device time is spent.
 */
internal object HarnessDirectories {

    val NAMES = listOf("specs", "models", "results")

    data class Prepared(
        val root: File,
        /** Directories that exist but were created by someone else, typically adb. */
        val foreign: List<File>,
    )

    fun prepare(context: Context, bundles: List<String> = emptyList()): Prepared {
        val root = requireNotNull(context.getExternalFilesDir(null)) {
            "This device has no app-specific external storage for the harness."
        }
        // Bundle folders sit under models/ and follow the same ownership rule.
        val dirs = NAMES.map { File(root, it) } + bundles.map { File(File(root, "models"), it) }
        val foreign = dirs.filterNot { dir ->
            dir.mkdirs()
            ownedByHarness(dir)
        }
        return Prepared(root, foreign)
    }

    /** What to tell an operator whose directories were made by adb. */
    fun explain(prepared: Prepared): String = buildString {
        appendLine("These harness directories were not created by the harness, so it cannot use them:")
        prepared.foreign.forEach { appendLine("  ${it.absolutePath}") }
        appendLine("They were most likely made with `adb shell mkdir`. Move anything you need out")
        appendLine("of them, remove them, and let the harness create them:")
        appendLine("  adb shell rm -r ${prepared.foreign.joinToString(" ") { it.absolutePath }}")
        append(
            "  adb shell am instrument -w -e ${HarnessArguments.ARG_PREPARE} true " +
                "com.thermalbench.harness/androidx.test.runner.AndroidJUnitRunner",
        )
    }

    private fun ownedByHarness(dir: File): Boolean =
        runCatching { Os.stat(dir.path).st_uid == Process.myUid() }.getOrDefault(false)
}
