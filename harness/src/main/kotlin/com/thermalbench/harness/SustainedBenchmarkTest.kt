package com.thermalbench.harness

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The headless entry point (GUIDE.md §4.1).
 *
 * It lives in the *main* source set, not androidTest, so a single APK
 * self-instruments and there is no test APK loading a second process into the
 * measurement:
 *
 * ```
 * adb shell am instrument -w \
 *   -e specPath /sdcard/Android/data/com.thermalbench.harness/files/specs/gemma3n-q4-gpu.json \
 *   -e outputDir /sdcard/Android/data/com.thermalbench.harness/files/results \
 *   com.thermalbench.harness/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * A run that reaches the end writes a canonical result document and reports the
 * headline numbers back through the instrumentation status bundle, so
 * tools/cli can read them without parsing logcat.
 */
@RunWith(JUnit4::class)
class SustainedBenchmarkTest {

    @Test
    fun sustainedLoad() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = HarnessArguments.from(InstrumentationRegistry.getArguments())
        if (arguments.prepare) {
            prepareOnly(instrumentation, arguments.bundles)
            return
        }
        val runner = EntryPointAccessors
            .fromApplication(instrumentation.targetContext.applicationContext, HarnessEntryPoint::class.java)
            .headlessRunner()

        val outcome = runBlocking {
            runner.run(instrumentation.targetContext, arguments)
        }

        val profile = outcome.result.profile
        assertNotNull("Benchmark produced no degradation profile", profile)

        val status = Bundle().apply {
            putString(KEY_RESULT_ID, outcome.result.resultId.value)
            putString(KEY_SPEC_ID, outcome.result.spec.specId.value)
            putString(KEY_DOCUMENT, outcome.documentFile.absolutePath)
            putString(KEY_CSV, outcome.csvFile.absolutePath)
            putString(KEY_TERMINATED, outcome.result.terminated.name)
            profile?.let {
                putString(KEY_VALIDITY, it.validity.name)
                putString(KEY_PEAK_TPS, it.peakDecodeTokensPerSecond.toString())
                putString(KEY_STEADY_TPS, it.steadyStateTokensPerSecond?.toString() ?: "")
                putString(KEY_RETENTION, it.retentionRatio?.toString() ?: "")
                putString(KEY_ITERATIONS, it.measuredIterationCount.toString())
                putString(KEY_FIRST_THROTTLE, it.iterationsToFirstThrottle?.toString() ?: "")
            }
        }
        Log.i(TAG, "result ${outcome.result.resultId} validity=${profile?.validity} retention=${profile?.retentionRatio}")
        // -1 (Activity.RESULT_OK) is the status code the AndroidX test
        // result printer treats as a successful intermediate report.
        instrumentation.sendStatus(Activity.RESULT_OK, status)
    }

    /**
     * Creates the harness's directories and reports where they are. This is the
     * setup step that has to happen before anything is pushed: directories made
     * by adb instead belong to shell, and the harness cannot use them.
     */
    private fun prepareOnly(instrumentation: Instrumentation, bundles: List<String>) {
        val prepared = HarnessDirectories.prepare(instrumentation.targetContext, bundles)
        check(prepared.foreign.isEmpty()) { HarnessDirectories.explain(prepared) }
        Log.i(TAG, "prepared ${HarnessDirectories.NAMES.joinToString()} under ${prepared.root}")
        instrumentation.sendStatus(
            Activity.RESULT_OK,
            Bundle().apply { putString(KEY_FILES_DIR, prepared.root.absolutePath) },
        )
    }

    private companion object {
        const val TAG = "ThermalBench"
        const val KEY_FILES_DIR = "thermalbench.filesDir"
        const val KEY_RESULT_ID = "thermalbench.resultId"
        const val KEY_SPEC_ID = "thermalbench.specId"
        const val KEY_DOCUMENT = "thermalbench.document"
        const val KEY_CSV = "thermalbench.csv"
        const val KEY_TERMINATED = "thermalbench.terminated"
        const val KEY_VALIDITY = "thermalbench.validity"
        const val KEY_PEAK_TPS = "thermalbench.peakDecodeTokensPerSecond"
        const val KEY_STEADY_TPS = "thermalbench.steadyStateTokensPerSecond"
        const val KEY_RETENTION = "thermalbench.retentionRatio"
        const val KEY_ITERATIONS = "thermalbench.measuredIterations"
        const val KEY_FIRST_THROTTLE = "thermalbench.iterationsToFirstThrottle"
    }
}
