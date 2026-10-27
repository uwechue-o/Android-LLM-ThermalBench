package com.thermalbench.tools.analysis

import com.google.common.truth.Truth.assertThat
import com.thermalbench.analysis.chart.ChartPage
import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.DegradationProfile
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.DeviceConditions
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
import com.thermalbench.domain.model.sustainedChart
import com.thermalbench.schema.Fixtures
import com.thermalbench.schema.Schema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.imageio.ImageIO

class ChartPageTest {

    private val minute = 60_000_000_000L

    private fun sample(index: Int, phase: Phase, tps: Double) = IterationSample(
        index, phase, index * minute, index * minute + minute / 2, 1, 30.0, tps, 80, 32,
        ThermalStatus.NONE, ThermalStatus.NONE, null, null, 80, null, 1L, false,
    )

    private val unpluggedDark = DeviceConditions(0L, ThermalStatus.NONE, 0.6f, 80, plugged = false, screenOn = false, batteryTemperatureDeciC = 280)

    private fun result(
        delegate: Delegate,
        speeds: List<Double>,
        peak: Double,
        validity: ValidityVerdict = ValidityVerdict.VALID,
        id: String = "abcdef0123456789abcdef01",
    ) = BenchmarkResult(
        resultId = ResultId(id),
        spec = Fixtures.spec(Fixtures.placeholderModel(), BackendId.MEDIAPIPE, delegate),
        device = DeviceDescriptor(
            "Google", "Pixel 6", "oriole", "oriole", "oriole", "Google", "Tensor", "17", 37, null,
            listOf("arm64-v8a"), 8, 8L shl 30, null, true, true, "anon",
        ),
        environment = RunEnvironment(true, emptyList(), unpluggedDark, null, true, ScreenPolicy.OFF, "headless", false, null),
        startedAtEpochMillis = 1_793_000_000_000L,
        iterations = listOf(sample(0, Phase.WARMUP, 30.0)) +
            speeds.mapIndexed { i, tps -> sample(i + 1, Phase.MEASURED, tps) },
        thermalTimeline = ThermalTimeline(
            samples = listOf(
                ThermalSample(minute / 2, ThermalStatus.NONE, null, 280, null),
                ThermalSample(3 * minute, ThermalStatus.LIGHT, null, 310, null),
                ThermalSample(speeds.size * minute, ThermalStatus.LIGHT, null, 360, null),
            ),
            transitions = listOf(
                // During warm-up: before the chart's time axis begins.
                ThermalTransition(minute / 4, ThermalStatus.NONE, ThermalStatus.MODERATE),
                ThermalTransition(minute / 2, ThermalStatus.MODERATE, ThermalStatus.NONE),
                ThermalTransition(3 * minute, ThermalStatus.NONE, ThermalStatus.LIGHT),
            ),
        ),
        powerSamples = emptyList(),
        terminated = TerminationReason.COMPLETED,
        errorMessage = null,
        profile = DegradationProfile(
            peakDecodeTokensPerSecond = peak,
            steadyStateTokensPerSecond = null,
            retentionRatio = null,
            timeToFirstThrottle = null,
            iterationsToFirstThrottle = null,
            decayModel = null,
            ttftDriftMicrosPerIteration = 0.0,
            throttleEvents = emptyList(),
            terminated = TerminationReason.COMPLETED,
            validity = validity,
            measuredIterationCount = speeds.size,
            meanDecodeTokensPerSecond = speeds.average(),
            minDecodeTokensPerSecond = speeds.min(),
            batteryDrainPercent = null,
            batteryTemperatureRiseDeciC = null,
        ),
        toolVersion = Fixtures.TOOL_VERSION,
        schemaVersion = Schema.VERSION,
    )

    private val cpu = result(Delegate.CPU, List(12) { if (it < 2) 17.0 else 14.0 }, peak = 17.0)

    @Test
    fun `the chart measures from the first measured iteration and ends where the run ended up`() {
        val chart = cpu.sustainedChart(closingWindow = 10)
        assertThat(chart.speed.first().x).isEqualTo(0.0)
        assertThat(chart.speed).hasSize(12)
        assertThat(chart.closingTokensPerSecond).isEqualTo(14.0)
        assertThat(chart.closingShareOfPeak).isWithin(1e-9).of(14.0 / 17.0)
        // A throttle during warm-up is before the axis; the first one on it counts.
        assertThat(chart.firstThrottle?.to).isEqualTo(ThermalStatus.LIGHT)
        assertThat(chart.firstThrottle?.minutes).isWithin(1e-9).of(2.0)
        // The reading taken during warm-up is left out.
        assertThat(chart.batteryCelsius.map { it.y }).containsExactly(31.0, 36.0).inOrder()
        assertThat(chart.batteryRiseCelsius).isWithin(1e-9).of(5.0)
    }

    @Test
    fun `the peak is the profile's, not a single fast iteration`() {
        val spiky = result(Delegate.GPU, listOf(12.0, 12.1, 15.8, 12.0, 12.0), peak = 12.1)
        val chart = spiky.sustainedChart()
        assertThat(chart.peak?.y).isEqualTo(12.1)
        // Labelled where the run ran at about its peak, not at the 15.8 spike.
        assertThat(chart.peak?.x).isWithin(1e-9).of(0.0)
        assertThat(chart.closingShareOfPeak).isWithin(1e-9).of(spiky.measuredIterations.map { it.decodeTokensPerSecond }.average() / 12.1)
    }

    @Test
    fun `one run is named by its runtime and compute unit`() {
        val page = ChartPage.of(listOf(cpu))
        assertThat(page.title).isEqualTo("gemma-3n-e2b-it-q4 on Google Pixel 6")
        assertThat(page.series.single().label).isEqualTo("MediaPipe · CPU")
        assertThat(page.subtitle).isEqualTo(
            "MediaPipe · CPU, ${Fixtures.referenceWorkload().maxOutputTokens}-token answers back to back " +
                "for 11 minutes, unplugged, screen off",
        )
        assertThat(page.footer).isEqualTo("Measured with ThermalBench 0.1.0 · rated VALID")
    }

    @Test
    fun `runs are told apart by only what differs between them`() {
        val gpu = result(Delegate.GPU, List(12) { 12.0 }, peak = 12.0, ValidityVerdict.PRECONDITIONS_UNMET, id = "0123456789abcdef01234567")
        val page = ChartPage.of(listOf(gpu, cpu))
        assertThat(page.series.map { it.label }).containsExactly("GPU", "CPU").inOrder()
        assertThat(page.subtitle).startsWith("MediaPipe, ")
        assertThat(page.footer).endsWith("GPU: PRECONDITIONS_UNMET, CPU: VALID")
    }

    @Test
    fun `the plot command writes an image of the requested size`(@TempDir directory: File) {
        val out = File(directory, "chart.png")
        AwtChartCanvas.writePng(ChartPage.of(listOf(cpu)), out, 800, 500)
        val image = ImageIO.read(out)
        assertThat(image.width).isEqualTo(800)
        assertThat(image.height).isEqualTo(500)
    }
}
