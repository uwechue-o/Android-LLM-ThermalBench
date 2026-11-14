package com.thermalbench.tools.analysis

import com.github.ajalt.clikt.core.parse
import com.google.common.truth.Truth.assertThat
import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.DegradationProfile
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.DeviceDescriptor
import com.thermalbench.domain.model.IterationSample
import com.thermalbench.domain.model.Phase
import com.thermalbench.domain.model.ResultId
import com.thermalbench.domain.model.RunEnvironment
import com.thermalbench.domain.model.ScreenPolicy
import com.thermalbench.domain.model.TerminationReason
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.ThermalTimeline
import com.thermalbench.domain.model.ValidityVerdict
import com.thermalbench.schema.Fixtures
import com.thermalbench.schema.Schema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CompareCommandTest {

    private fun result(model: String, retention: Double, throttledBeforeMeasurement: Boolean) = BenchmarkResult(
        resultId = ResultId("abcdef0123456789abcdef0${model.length}"),
        spec = Fixtures.spec(Fixtures.placeholderModel(), BackendId.MEDIAPIPE, Delegate.CPU),
        device = DeviceDescriptor(
            "Maker", model, model, model, model, "Qualcomm", "SM8250", "13", 33, null,
            listOf("arm64-v8a"), 8, 8L shl 30, null, true, true, "anon",
        ),
        environment = RunEnvironment(true, emptyList(), null, null, true, ScreenPolicy.OFF, "headless", false, null),
        startedAtEpochMillis = 1_793_000_000_000L,
        iterations = listOf(
            IterationSample(
                0, Phase.MEASURED, 0L, 1L, 1, 30.0, 20.0, 80, 32,
                ThermalStatus.NONE, ThermalStatus.NONE, null, null, 80, null, 1L, false,
            ),
        ),
        thermalTimeline = ThermalTimeline.EMPTY,
        powerSamples = emptyList(),
        terminated = TerminationReason.COMPLETED,
        errorMessage = null,
        profile = DegradationProfile(
            peakDecodeTokensPerSecond = 20.0,
            steadyStateTokensPerSecond = 20.0 * retention,
            retentionRatio = retention,
            timeToFirstThrottle = null,
            iterationsToFirstThrottle = if (throttledBeforeMeasurement) 0 else null,
            decayModel = null,
            ttftDriftMicrosPerIteration = 0.0,
            throttleEvents = emptyList(),
            terminated = TerminationReason.COMPLETED,
            validity = ValidityVerdict.VALID,
            measuredIterationCount = 150,
            meanDecodeTokensPerSecond = 19.0,
            minDecodeTokensPerSecond = 18.0,
            batteryDrainPercent = null,
            batteryTemperatureRiseDeciC = null,
            throttledBeforeMeasurement = throttledBeforeMeasurement,
        ),
        toolVersion = Fixtures.TOOL_VERSION,
        schemaVersion = Schema.VERSION,
    )

    @Test
    fun `the csv says which runs were already throttling when measurement began`(@TempDir dir: File) {
        File(dir, "tablet.json").writeText(Documents.encode(result("SM-T970", 0.98, throttledBeforeMeasurement = true)))
        File(dir, "phone.json").writeText(Documents.encode(result("FCP-N49", 0.76, throttledBeforeMeasurement = false)))
        val csv = File(dir, "compare.csv")

        CompareCommand().parse(listOf(dir.path, "--csv", csv.path))

        val lines = csv.readLines()
        assertThat(lines.first()).endsWith(",validity,throttled_before_measurement")
        // Sorted by retention, so the overstated run comes first: the reason for the flag.
        assertThat(lines[1]).startsWith("Maker SM-T970,")
        assertThat(lines[1]).endsWith(",VALID,true")
        assertThat(lines[2]).endsWith(",VALID,false")
    }
}
