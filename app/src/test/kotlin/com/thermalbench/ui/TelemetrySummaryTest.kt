package com.thermalbench.ui

import com.google.common.truth.Truth.assertThat
import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.DeviceDescriptor
import com.thermalbench.domain.model.IterationSample
import com.thermalbench.domain.model.Phase
import com.thermalbench.domain.model.ResultId
import com.thermalbench.domain.model.RunEnvironment
import com.thermalbench.domain.model.ScreenPolicy
import com.thermalbench.domain.model.SensorTemperature
import com.thermalbench.domain.model.TerminationReason
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.ThermalTimeline
import com.thermalbench.schema.Fixtures
import com.thermalbench.schema.Schema
import org.junit.Test

class TelemetrySummaryTest {

    private fun sample(
        index: Int,
        phase: Phase = Phase.MEASURED,
        gpuKhz: Long? = null,
        cpuKhz: List<Long>? = null,
        sensors: List<SensorTemperature>? = null,
    ) = IterationSample(
        index, phase, index * 1_000L, index * 1_000L + 900L, 1, 1.0, 10.0, 1, 1,
        ThermalStatus.NONE, ThermalStatus.NONE, null, null, 80, cpuKhz, 1L, false, gpuKhz, sensors,
    )

    private fun result(iterations: List<IterationSample>, sensorNames: List<String> = emptyList()) = BenchmarkResult(
        resultId = ResultId("r"),
        spec = Fixtures.spec(Fixtures.placeholderModel(), BackendId.LLAMA_CPP, Delegate.GPU),
        device = DeviceDescriptor(
            "Google", "Pixel 6", "oriole", "oriole", "gs101", null, null, "17", 37, null,
            listOf("arm64-v8a"), 8, 1L, null, true, true, "anon", thermalSensorNames = sensorNames,
        ),
        environment = RunEnvironment(
            true, emptyList(), null, null, true, ScreenPolicy.OFF, "app", false, null,
            computeDevice = "Mali-G78 via Vulkan",
        ),
        startedAtEpochMillis = 0L,
        iterations = iterations,
        thermalTimeline = ThermalTimeline.EMPTY,
        powerSamples = emptyList(),
        terminated = TerminationReason.COMPLETED,
        errorMessage = null,
        profile = null,
        toolVersion = Fixtures.TOOL_VERSION,
        schemaVersion = Schema.VERSION,
    )

    @Test
    fun `sensors are ranked by how much they rose over the measured iterations`() {
        val summary = result(
            listOf(
                // Warmup readings are not the start of the measurement.
                sample(0, Phase.WARMUP, sensors = listOf(SensorTemperature("cpu", 20f), SensorTemperature("gpu", 20f))),
                sample(1, sensors = listOf(SensorTemperature("cpu", 40f), SensorTemperature("gpu", 35f))),
                sample(2, sensors = listOf(SensorTemperature("cpu", 52f), SensorTemperature("gpu", 37f))),
                sample(3, sensors = listOf(SensorTemperature("cpu", 50f), SensorTemperature("gpu", 47f))),
            ),
        ).telemetrySummary()

        assertThat(summary.sensors.map { it.name }).containsExactly("gpu", "cpu").inOrder()
        val cpu = summary.sensors.single { it.name == "cpu" }
        assertThat(cpu.startCelsius).isEqualTo(40f)
        assertThat(cpu.endCelsius).isEqualTo(50f)
        assertThat(cpu.peakCelsius).isEqualTo(52f)
        assertThat(summary.computeDevice).isEqualTo("Mali-G78 via Vulkan")
    }

    @Test
    fun `clocks report start, end and the lowest point, from the fastest core`() {
        val summary = result(
            listOf(
                sample(0, gpuKhz = 848_000L, cpuKhz = listOf(1_800_000L, 2_800_000L)),
                sample(1, gpuKhz = 400_000L, cpuKhz = listOf(1_800_000L, 1_200_000L)),
                sample(2, gpuKhz = 600_000L, cpuKhz = listOf(1_800_000L, 2_000_000L)),
            ),
        ).telemetrySummary()

        assertThat(summary.clocks).containsExactly(
            ClockChange("GPU", 848_000L, 600_000L, 400_000L),
            ClockChange("Fastest CPU core", 2_800_000L, 2_000_000L, 1_800_000L),
        ).inOrder()
    }

    @Test
    fun `a device that withholds sensors and clocks says so rather than reporting zeros`() {
        val summary = result(listOf(sample(0), sample(1))).telemetrySummary()
        assertThat(summary.sensors).isEmpty()
        assertThat(summary.clocks).isEmpty()
        assertThat(summary.sensorsReadable).isFalse()
        assertThat(summary.gpuClockReadable).isFalse()
    }
}
