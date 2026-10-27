package com.thermalbench.schema

import com.google.common.truth.Truth.assertThat
import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.DecayFit
import com.thermalbench.domain.model.DegradationProfile
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.DeviceConditions
import com.thermalbench.domain.model.DeviceDescriptor
import com.thermalbench.domain.model.IterationSample
import com.thermalbench.domain.model.Phase
import com.thermalbench.domain.model.PowerSample
import com.thermalbench.domain.model.ResultId
import com.thermalbench.domain.model.RunEnvironment
import com.thermalbench.domain.model.ScreenPolicy
import com.thermalbench.domain.model.SensorTemperature
import com.thermalbench.domain.model.TerminationReason
import com.thermalbench.domain.model.ThermalSample
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.ThermalTimeline
import com.thermalbench.domain.model.ThermalTransition
import com.thermalbench.domain.model.ThrottleEvent
import com.thermalbench.domain.model.ValidityVerdict
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class JsonResultCodecTest {

    private val codec = JsonResultCodec()

    private fun sampleResult(): BenchmarkResult {
        val spec = Fixtures.spec(Fixtures.placeholderModel(), BackendId.LITERT_LM, Delegate.GPU, label = "unit test")
        val iterations = (0 until 12).map { i ->
            IterationSample(
                index = i,
                phase = if (i < 2) Phase.WARMUP else Phase.MEASURED,
                startElapsedNanos = 1_000L + i * 4_000_000_000L,
                endElapsedNanos = 1_000L + (i + 1) * 4_000_000_000L,
                timeToFirstTokenMicros = 100_000L + i,
                prefillTokensPerSecond = 350.123456789,
                decodeTokensPerSecond = 17.5 - i * 0.3,
                promptTokenCount = 128,
                outputTokenCount = 128,
                thermalStatusAtStart = ThermalStatus.NONE,
                thermalStatusAtEnd = if (i > 8) ThermalStatus.LIGHT else ThermalStatus.NONE,
                thermalHeadroomAtStart = 0.42f,
                batteryTemperatureDeciC = 310 + i,
                batteryPercent = 80 - i,
                cpuFrequenciesKhz = if (i % 2 == 0) listOf(1_800_000L, 2_400_000L) else null,
                peakRssBytes = 2_000_000_000L,
                throttleObserved = i > 8,
                // Schema 2 fields, present on some iterations and absent on others.
                gpuFrequencyKhz = if (i % 3 == 0) 848_000L else null,
                sensorTemperatures = if (i % 2 == 0) {
                    listOf(SensorTemperature("cpu-1-0-usr", 41.5f + i), SensorTemperature("gpu0-usr", 39.25f))
                } else {
                    null
                },
            )
        }
        val profile = DegradationProfile(
            peakDecodeTokensPerSecond = 17.5,
            steadyStateTokensPerSecond = 14.2,
            retentionRatio = 14.2 / 17.5,
            timeToFirstThrottle = 36.seconds,
            iterationsToFirstThrottle = 7,
            decayModel = DecayFit(14.0, 3.5, 40.0, 0.97),
            ttftDriftMicrosPerIteration = 1.0,
            throttleEvents = listOf(ThrottleEvent(9, 37_000_000_000L, ThermalStatus.NONE, ThermalStatus.LIGHT)),
            terminated = TerminationReason.COMPLETED,
            validity = ValidityVerdict.VALID,
            measuredIterationCount = 10,
            meanDecodeTokensPerSecond = 15.0,
            minDecodeTokensPerSecond = 14.2,
            batteryDrainPercent = 11,
            batteryTemperatureRiseDeciC = 11,
        )
        val conditions = DeviceConditions(500L, ThermalStatus.NONE, 0.3f, 80, false, false, 310)
        return BenchmarkResult(
            resultId = ResultId("r1"),
            spec = spec,
            device = DeviceDescriptor(
                "Google", "Pixel 8", "shiba", "shiba", "zuma", "Google", "Tensor G3", "15", 35, "2025-08-05",
                listOf("arm64-v8a"), 9, 12_000_000_000L, listOf(2_900_000L), true, true, "anon-1234",
                gpuFrequencyReadable = true, thermalSensorNames = listOf("cpu-1-0-usr", "gpu0-usr"),
            ),
            environment = RunEnvironment(
                true, emptyList(), conditions, conditions, true, ScreenPolicy.OFF, "headless", false, "0.17.0",
                computeDevice = "QUALCOMM Adreno(TM) 750 via OpenCL",
            ),
            startedAtEpochMillis = 1_757_000_000_000L,
            iterations = iterations,
            thermalTimeline = ThermalTimeline(
                samples = listOf(
                    ThermalSample(1_000L, ThermalStatus.NONE, 0.3f, 310, null),
                    ThermalSample(
                        2_000L, ThermalStatus.NONE, 0.29f, 311, listOf(1_800_000L), 600_000L,
                        listOf(SensorTemperature("skin", 33.0f)),
                    ),
                ),
                transitions = listOf(ThermalTransition(37_000_000_000L, ThermalStatus.NONE, ThermalStatus.LIGHT)),
            ),
            powerSamples = listOf(PowerSample(1_000L, 80, -450_000L, 3_900_000L, 3_950, false)),
            terminated = TerminationReason.COMPLETED,
            errorMessage = null,
            profile = profile,
            toolVersion = Fixtures.TOOL_VERSION,
            schemaVersion = Schema.VERSION,
        )
    }

    @Test
    fun `result round-trips through the canonical document`() {
        val original = sampleResult()
        val text = codec.encodeResult(original)
        val decoded = codec.decodeResult(text)
        assertThat(decoded).isEqualTo(original)
        assertThat(codec.encodeResult(decoded)).isEqualTo(text)
    }

    @Test
    fun `canonical text has sorted keys and no whitespace`() {
        val text = codec.encodeResult(sampleResult())
        assertThat(text).doesNotContain("\n")
        assertThat(text).doesNotContain(": ")
        assertThat(text).startsWith("{\"\$schema\":")
        val keys = Regex("\"([a-zA-Z]+)\":").findAll(text.substringBefore("\"iterations\"")).map { it.groupValues[1] }.toList()
        assertThat(keys.first()).isEqualTo("device")
    }

    @Test
    fun `spec id depends on content but not on label or model path`() {
        val a = Fixtures.spec(Fixtures.placeholderModel(), BackendId.LITERT_LM, Delegate.GPU, label = "one")
        val b = Fixtures.spec(
            Fixtures.placeholderModel().copy(path = "/data/local/tmp/other.litertlm"),
            BackendId.LITERT_LM,
            Delegate.GPU,
            label = "two",
        )
        val c = Fixtures.spec(Fixtures.placeholderModel(), BackendId.LITERT_LM, Delegate.CPU)
        assertThat(a.specId).isEqualTo(b.specId)
        assertThat(a.specId).isNotEqualTo(c.specId)
        assertThat(codec.verifyId(a)).isTrue()
        assertThat(codec.verifyId(a.copy(seed = 7))).isFalse()
    }

    @Test
    fun `spec round-trips`() {
        val spec = Fixtures.spec(Fixtures.placeholderModel(), BackendId.LLAMA_CPP, Delegate.CPU, options = mapOf("threads" to "4"))
        val text = codec.encodeSpec(spec)
        assertThat(codec.decodeSpec(text)).isEqualTo(spec)
    }

    @Test
    fun `double formatting is stable through a decode-encode cycle`() = runBlocking {
        checkAll(2_000, Arb.double(-1e12, 1e12)) { d ->
            if (d.isFinite()) {
                val once = CanonicalJson.formatDouble(d)
                val twice = CanonicalJson.formatDouble(once.toDouble())
                assertThat(twice).isEqualTo(once)
                assertThat(once).doesNotContain("E")
            }
        }
    }

    @Test
    fun `canonical encoder normalizes number spellings without losing precision`() {
        val element = buildJsonObject {
            put("b", 1.0e3)
            put("a", 2)
            // Not 0.3: the encoder must preserve the double it was actually given.
            put("c", 0.1 + 0.2)
        }
        assertThat(CanonicalJson.encode(element))
            .isEqualTo("{\"a\":2,\"b\":1000.0,\"c\":0.30000000000000004}")
        val parsed = Json.parseToJsonElement("{\"x\": 1E2, \"y\": \"q\\\"uote\"}")
        assertThat(CanonicalJson.encode(parsed)).isEqualTo("{\"x\":100.0,\"y\":\"q\\\"uote\"}")
    }

    @Test
    fun `encoder emits the shortest form that still round-trips`() {
        // 17 significant digits are needed here; rounding to 15 silently changes the value.
        val ratio = 14.2 / 17.5
        assertThat(CanonicalJson.formatDouble(ratio)).isEqualTo("0.8114285714285714")
        assertThat(CanonicalJson.formatDouble(ratio).toDouble()).isEqualTo(ratio)
        // …but a value that needs only two digits gets only two.
        assertThat(CanonicalJson.formatDouble(0.25)).isEqualTo("0.25")
        assertThat(CanonicalJson.formatDouble(-0.0)).isEqualTo("-0.0")
        assertThat(CanonicalJson.formatDouble(Double.NaN)).isEqualTo("null")
    }

    @Test
    fun `csv has one row per iteration plus header`() {
        val csv = codec.resultCsv(sampleResult())
        val lines = csv.trimEnd().lines()
        assertThat(lines).hasSize(13)
        assertThat(lines.first()).startsWith("result_id,spec_id,device")
    }

    @Test
    fun `csv carries the gpu clock and every sensor beside the throughput`() {
        val lines = codec.resultCsv(sampleResult()).trimEnd().lines()
        assertThat(lines.first()).endsWith(",gpu_freq_khz,sensor_temps_c")
        // Iteration 0 has both; iteration 1 has neither, and leaves them blank.
        assertThat(lines[1]).endsWith(",848000,cpu-1-0-usr:41.5|gpu0-usr:39.25")
        assertThat(lines[2]).endsWith(",,")
    }

    @Test
    fun `a version 1 document without the new fields still loads`() {
        val text = codec.encodeResult(sampleResult())
        val v1 = text
            .replace(Regex(""","gpuFrequencyKhz":(null|\d+)"""), "")
            .replace(Regex(""","sensorTemperatures":(null|\[[^\]]*\])"""), "")
        val decoded = codec.decodeResult(v1)
        assertThat(decoded.iterations.first().gpuFrequencyKhz).isNull()
        assertThat(decoded.iterations.first().sensorTemperatures).isNull()
    }
}
