package com.thermalbench.report

import com.google.common.truth.Truth.assertThat
import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.BackendRef
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
import com.thermalbench.domain.model.ThermalSample
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.ThermalTimeline
import com.thermalbench.domain.model.ThermalTransition
import com.thermalbench.domain.model.ValidityVerdict
import com.thermalbench.schema.Fixtures
import com.thermalbench.schema.Schema
import com.thermalbench.ui.performanceSeries
import org.junit.Test
import java.time.ZoneOffset

class ReportContentTest {

    private val minute = 60_000_000_000L

    private fun sample(index: Int, phase: Phase, tps: Double) = IterationSample(
        index, phase, 10L + index * minute, 10L + index * minute + minute / 2, 1, 30.0, tps, 80, 32,
        ThermalStatus.NONE, ThermalStatus.NONE, null, null, 80, null, 1L, false,
    )

    private fun result(profile: DegradationProfile?) = BenchmarkResult(
        resultId = ResultId("abcdef0123456789abcdef01"),
        spec = Fixtures.spec(
            Fixtures.placeholderModel(), BackendId.LLAMA_CPP, Delegate.GPU,
            options = mapOf(BackendRef.OPTION_GPU_API to "OpenCL"),
        ),
        device = DeviceDescriptor(
            "OnePlus", "IN2013", "OnePlus8", "kona", "qcom", "QTI", "SM8250", "13", 33, null,
            listOf("arm64-v8a"), 8, 12L shl 30, null, true, true, "anon",
        ),
        environment = RunEnvironment(
            false, listOf("Device is charging; unplug it"), null, null, true, ScreenPolicy.OFF, "app", false,
            "llama.cpp b11879", computeDevice = "QUALCOMM Adreno(TM) 650 via OpenCL",
        ),
        startedAtEpochMillis = 1_793_000_000_000L,
        iterations = listOf(
            sample(0, Phase.WARMUP, 20.0),
            sample(1, Phase.MEASURED, 18.0),
            sample(2, Phase.MEASURED, 12.0),
            sample(3, Phase.MEASURED, 11.0),
        ),
        thermalTimeline = ThermalTimeline(
            samples = listOf(
                ThermalSample(5L, ThermalStatus.NONE, null, null, null),
                ThermalSample(10L + 2 * minute, ThermalStatus.MODERATE, null, null, null),
            ),
            transitions = listOf(
                ThermalTransition(10L + 2 * minute, ThermalStatus.NONE, ThermalStatus.MODERATE),
                // A fall is recovery, not a throttle onset.
                ThermalTransition(10L + 3 * minute, ThermalStatus.MODERATE, ThermalStatus.LIGHT),
            ),
        ),
        powerSamples = emptyList(),
        terminated = TerminationReason.COMPLETED,
        errorMessage = null,
        profile = profile,
        toolVersion = Fixtures.TOOL_VERSION,
        schemaVersion = Schema.VERSION,
    )

    private val profile = DegradationProfile(
        peakDecodeTokensPerSecond = 18.0,
        steadyStateTokensPerSecond = 11.5,
        retentionRatio = 11.5 / 18.0,
        timeToFirstThrottle = null,
        iterationsToFirstThrottle = 2,
        decayModel = null,
        ttftDriftMicrosPerIteration = 0.5,
        throttleEvents = emptyList(),
        terminated = TerminationReason.COMPLETED,
        validity = ValidityVerdict.PRECONDITIONS_UNMET,
        measuredIterationCount = 3,
        meanDecodeTokensPerSecond = 13.7,
        minDecodeTokensPerSecond = 11.0,
        batteryDrainPercent = 4,
        batteryTemperatureRiseDeciC = null,
    )

    @Test
    fun `the graph covers the whole session in minutes, warm-up marked, throttle onsets only`() {
        val series = result(profile).performanceSeries()
        assertThat(series.decode.map { it.x }).containsExactly(0f, 1f, 2f, 3f).inOrder()
        assertThat(series.decode.first().y).isEqualTo(20f)
        assertThat(series.warmupEndMinutes).isEqualTo(0.5f)
        assertThat(series.throttleMinutes).containsExactly(2f)
        // The sample taken before the first iteration is outside the time axis.
        assertThat(series.thermal.map { it.x }).containsExactly(2f)
    }

    @Test
    fun `the report leads with what ran, where, and whether it counts`() {
        val content = result(profile).reportContent(ZoneOffset.UTC)
        assertThat(content.subtitle).isEqualTo("gemma-3n-e2b-it-q4 · llama.cpp · GPU (OpenCL) · OnePlus IN2013")
        assertThat(content.valid).isFalse()
        assertThat(content.verdict).isEqualTo("Not valid for comparison: PRECONDITIONS_UNMET")
        assertThat(content.headline).containsExactly(
            "Throughput retained" to "63.9%",
            "Peak decode" to "18.0 tok/s",
            "Steady state" to "11.5 tok/s",
            "First throttle" to "iteration 2",
        ).inOrder()
        val conditions = content.sections.single { it.title == "Conditions at start" }.rows.toMap()
        assertThat(conditions["Preconditions"]).isEqualTo("NOT met")
        assertThat(conditions["Unmet"]).isEqualTo("Device is charging; unplug it")
        val run = content.sections.single { it.title == "Run" }.rows.toMap()
        assertThat(run["Ran on"]).isEqualTo("QUALCOMM Adreno(TM) 650 via OpenCL")
        assertThat(content.footer).contains("result abcdef0123456789abcdef01")
    }

    @Test
    fun `a run with no profile says so instead of printing zeros`() {
        val content = result(profile = null).reportContent(ZoneOffset.UTC)
        assertThat(content.verdict).isEqualTo("No result: COMPLETED")
        assertThat(content.headline.map { it.second }).containsExactly("—", "—", "never stable", "never").inOrder()
        val sensors = content.sections.single { it.title == "Temperatures and clocks" }.rows.toMap()
        assertThat(sensors["Sensors"]).isEqualTo("not readable on this device")
    }

    @Test
    fun `the suggested file name is safe and says what ran`() {
        val name = ResultDocuments.suggestedName(result(profile), DocumentKind.PDF)
        assertThat(name).isEqualTo("thermalbench-gemma-3n-e2b-it-q4-gpu-abcdef01.pdf")
    }
}
