package com.thermalbench.harness

import android.os.Bundle

/**
 * Instrumentation arguments for the headless runner.
 *
 * Before anything is pushed to a device, let the harness create its own
 * directories (see [HarnessDirectories]):
 *
 * ```
 * adb shell am instrument -w -e prepare true \
 *   com.thermalbench.harness/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * A model that is a folder (a Qualcomm Genie bundle) needs its folder made the
 * same way, by the harness, before its files are pushed:
 *
 * ```
 * adb shell am instrument -w -e prepare true -e bundles tinyllama-cpu  *   com.thermalbench.harness/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * Then, with a spec and model pushed into them:
 *
 * ```
 * adb shell am instrument -w \
 *   -e specPath /sdcard/Android/data/com.thermalbench.harness/files/specs/gemma3n-q4-gpu.json \
 *   -e outputDir /sdcard/Android/data/com.thermalbench.harness/files/results \
 *   com.thermalbench.harness/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
data class HarnessArguments(
    /** Path to a canonical spec document on the device. Absent only when [prepare] is set. */
    val specPath: String?,
    /** Where result documents are written. Defaults to the app's external files dir. */
    val outputDir: String?,
    /** Overrides the spec's measured iteration count; for smoke tests only. */
    val iterations: Int?,
    /**
     * Runs even when the precondition gate is not satisfied. The result is then
     * marked PRECONDITIONS_UNMET, never silently presented as valid.
     */
    val ignorePreconditions: Boolean,
    /** Overrides the spec's maximum wait for preconditions, in seconds. */
    val preconditionTimeoutSeconds: Long?,
    /** Only create the harness's directories and report where they are; run nothing. */
    val prepare: Boolean = false,
    /** Model bundle folders to create under models/ while preparing, by name. */
    val bundles: List<String> = emptyList(),
) {
    companion object {
        const val ARG_SPEC_PATH = "specPath"
        const val ARG_OUTPUT_DIR = "outputDir"
        const val ARG_ITERATIONS = "iterations"
        const val ARG_IGNORE_PRECONDITIONS = "ignorePreconditions"
        const val ARG_PRECONDITION_TIMEOUT = "preconditionTimeoutSeconds"
        const val ARG_PREPARE = "prepare"
        const val ARG_BUNDLES = "bundles"
        private val BUNDLE_NAME = Regex("[A-Za-z0-9._-]+")

        fun from(bundle: Bundle): HarnessArguments {
            val prepare = bundle.getString(ARG_PREPARE)?.toBooleanStrictOrNull() ?: false
            val specPath = bundle.getString(ARG_SPEC_PATH)
            require(prepare || specPath != null) {
                "Missing -e $ARG_SPEC_PATH <path to spec json> (or -e $ARG_PREPARE true to set up directories)"
            }
            return HarnessArguments(
                specPath = specPath,
                outputDir = bundle.getString(ARG_OUTPUT_DIR),
                iterations = bundle.getString(ARG_ITERATIONS)?.toIntOrNull(),
                ignorePreconditions = bundle.getString(ARG_IGNORE_PRECONDITIONS)?.toBooleanStrictOrNull() ?: false,
                preconditionTimeoutSeconds = bundle.getString(ARG_PRECONDITION_TIMEOUT)?.toLongOrNull(),
                prepare = prepare,
                bundles = bundle.getString(ARG_BUNDLES).orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .onEach { require(BUNDLE_NAME.matches(it)) { "Bundle name '$it' must be a plain folder name" } },
            )
        }
    }
}
