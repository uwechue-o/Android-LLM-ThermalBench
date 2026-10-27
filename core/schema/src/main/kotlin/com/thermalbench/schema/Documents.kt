package com.thermalbench.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The published result document model. These are the wire types; the domain
 * types in core/domain are mapped to and from them in [Mappers]. Keeping the
 * two apart lets the domain evolve without silently changing the contract.
 *
 * Durations are integer milliseconds; timestamps are monotonic nanoseconds
 * except [ResultDocument.startedAtEpochMillis], the single wall-clock value.
 */
object Schema {
    /**
     * 2: GPU clock and per-sensor temperatures on iterations and thermal
     * samples, readable-sensor list on the device, compute device on the
     * environment. All optional, so version 1 documents still load.
     */
    const val VERSION: Int = 2
    const val MEDIA_TYPE: String = "application/vnd.thermalbench.result+json"
}

@Serializable
data class ModelRefDocument(
    val name: String,
    val format: String,
    val sha256: String,
    val sizeBytes: Long,
    val path: String,
    val quantization: String? = null,
    val parameterCount: Long? = null,
)

@Serializable
data class BackendRefDocument(
    val id: String,
    val delegate: String,
    val options: Map<String, String> = emptyMap(),
)

@Serializable
data class StopConditionDocument(
    val type: String,
    val tokens: Int? = null,
)

@Serializable
data class WorkloadDocument(
    val promptTokens: Int,
    val maxOutputTokens: Int,
    val promptSha256: String,
    val stopCondition: StopConditionDocument,
    val prompt: String,
)

@Serializable
data class RunProtocolDocument(
    val warmupIterations: Int,
    val measuredIterations: Int,
    val interIterationDelayMillis: Long,
    val abortOnCriticalThermal: Boolean,
    val maxRunDurationMillis: Long,
    val telemetryIntervalMillis: Long,
)

@Serializable
data class PreconditionPolicyDocument(
    val requiredThermalStatus: String,
    val minThermalHeadroom: Float? = null,
    val requireUnplugged: Boolean,
    val minBatteryPercent: Int,
    val screenPolicy: String,
    val stabilizationWindowMillis: Long,
    val maxWaitForPreconditionsMillis: Long,
)

@Serializable
data class SpecDocument(
    val specId: String,
    val label: String = "",
    val model: ModelRefDocument,
    val backend: BackendRefDocument,
    val workload: WorkloadDocument,
    val protocol: RunProtocolDocument,
    val preconditions: PreconditionPolicyDocument,
    val seed: Long,
    val toolVersion: String,
)

@Serializable
data class IterationSampleDocument(
    val index: Int,
    val phase: String,
    val startElapsedNanos: Long,
    val endElapsedNanos: Long,
    val timeToFirstTokenMicros: Long,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
    val promptTokenCount: Int,
    val outputTokenCount: Int,
    val thermalStatusAtStart: String,
    val thermalStatusAtEnd: String,
    val thermalHeadroomAtStart: Float? = null,
    val batteryTemperatureDeciC: Int? = null,
    val batteryPercent: Int,
    val cpuFrequenciesKhz: List<Long>? = null,
    val peakRssBytes: Long,
    val throttleObserved: Boolean,
    val gpuFrequencyKhz: Long? = null,
    val sensorTemperatures: List<SensorTemperatureDocument>? = null,
)

@Serializable
data class SensorTemperatureDocument(
    val name: String,
    val celsius: Float,
)

@Serializable
data class ThermalSampleDocument(
    val elapsedNanos: Long,
    val status: String,
    val headroom: Float? = null,
    val batteryTemperatureDeciC: Int? = null,
    val cpuFrequenciesKhz: List<Long>? = null,
    val gpuFrequencyKhz: Long? = null,
    val sensorTemperatures: List<SensorTemperatureDocument>? = null,
)

@Serializable
data class ThermalTransitionDocument(
    val elapsedNanos: Long,
    val from: String,
    val to: String,
)

@Serializable
data class ThermalTimelineDocument(
    val samples: List<ThermalSampleDocument>,
    val transitions: List<ThermalTransitionDocument>,
)

@Serializable
data class PowerSampleDocument(
    val elapsedNanos: Long,
    val batteryPercent: Int,
    val currentNowMicroAmps: Long? = null,
    val chargeCounterMicroAh: Long? = null,
    val voltageMillivolts: Int? = null,
    val plugged: Boolean,
)

@Serializable
data class DeviceDescriptorDocument(
    val manufacturer: String,
    val model: String,
    val device: String,
    val board: String,
    val hardware: String,
    val socManufacturer: String? = null,
    val socModel: String? = null,
    val androidRelease: String,
    val sdkInt: Int,
    val securityPatch: String? = null,
    val abis: List<String>,
    val cpuCoreCount: Int,
    val totalRamBytes: Long,
    val cpuMaxFrequenciesKhz: List<Long>? = null,
    val cpuFrequencyReadable: Boolean,
    val thermalHeadroomSupported: Boolean,
    val anonymousDeviceId: String,
    val gpuFrequencyReadable: Boolean = false,
    val thermalSensorNames: List<String> = emptyList(),
)

@Serializable
data class DeviceConditionsDocument(
    val elapsedNanos: Long,
    val thermalStatus: String,
    val thermalHeadroom: Float? = null,
    val batteryPercent: Int,
    val plugged: Boolean,
    val screenOn: Boolean,
    val batteryTemperatureDeciC: Int? = null,
)

@Serializable
data class RunEnvironmentDocument(
    val preconditionsSatisfied: Boolean,
    val unmetAtStart: List<String>,
    val conditionsAtStart: DeviceConditionsDocument? = null,
    val conditionsAtEnd: DeviceConditionsDocument? = null,
    val wakeLockHeld: Boolean,
    val screenPolicy: String,
    val executionPath: String,
    val liveChartEnabled: Boolean,
    val backendVersion: String? = null,
    val computeDevice: String? = null,
)

@Serializable
data class DecayFitDocument(
    val asymptote: Double,
    val amplitude: Double,
    val timeConstantSeconds: Double,
    val rSquared: Double,
)

@Serializable
data class ThrottleEventDocument(
    val iterationIndex: Int? = null,
    val elapsedNanos: Long,
    val from: String,
    val to: String,
)

@Serializable
data class DegradationProfileDocument(
    val peakDecodeTokensPerSecond: Double,
    val steadyStateTokensPerSecond: Double? = null,
    val retentionRatio: Double? = null,
    val timeToFirstThrottleMillis: Long? = null,
    val iterationsToFirstThrottle: Int? = null,
    val decayModel: DecayFitDocument? = null,
    val ttftDriftMicrosPerIteration: Double,
    val throttleEvents: List<ThrottleEventDocument>,
    val terminated: String,
    val validity: String,
    val measuredIterationCount: Int,
    val meanDecodeTokensPerSecond: Double,
    val minDecodeTokensPerSecond: Double,
    val batteryDrainPercent: Int? = null,
    val batteryTemperatureRiseDeciC: Int? = null,
)

@Serializable
data class ResultDocument(
    @SerialName("\$schema") val schema: String = SCHEMA_URI,
    val schemaVersion: Int = Schema.VERSION,
    val resultId: String,
    val spec: SpecDocument,
    val device: DeviceDescriptorDocument,
    val environment: RunEnvironmentDocument,
    val startedAtEpochMillis: Long,
    val iterations: List<IterationSampleDocument>,
    val thermalTimeline: ThermalTimelineDocument,
    val powerSamples: List<PowerSampleDocument>,
    val terminated: String,
    val errorMessage: String? = null,
    val profile: DegradationProfileDocument? = null,
    val toolVersion: String,
) {
    companion object {
        const val SCHEMA_URI = "https://thermalbench.dev/schema/result-v2.json"
    }
}
