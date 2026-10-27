package com.thermalbench.domain.model

/** What the device is. Collected once per run, before the first iteration. */
data class DeviceDescriptor(
    val manufacturer: String,
    val model: String,
    val device: String,
    val board: String,
    val hardware: String,
    val socManufacturer: String?,
    val socModel: String?,
    val androidRelease: String,
    val sdkInt: Int,
    val securityPatch: String?,
    val abis: List<String>,
    val cpuCoreCount: Int,
    val totalRamBytes: Long,
    /** Present only when the tool could read it; see [cpuFrequencyReadable]. */
    val cpuMaxFrequenciesKhz: List<Long>?,
    val cpuFrequencyReadable: Boolean,
    val thermalHeadroomSupported: Boolean,
    /** A stable, non-identifying pseudonym for leaderboard grouping. */
    val anonymousDeviceId: String,
    /** True when the GPU clock could be read on this device. */
    val gpuFrequencyReadable: Boolean = false,
    /** Temperature sensors this tool could read here; empty when none were readable. */
    val thermalSensorNames: List<String> = emptyList(),
)

/** The conditions the run actually executed under — recorded, not assumed. */
data class RunEnvironment(
    val preconditionsSatisfied: Boolean,
    val unmetAtStart: List<String>,
    val conditionsAtStart: DeviceConditions?,
    val conditionsAtEnd: DeviceConditions?,
    val wakeLockHeld: Boolean,
    val screenPolicy: ScreenPolicy,
    /** `headless` for the harness path, `app` for the foreground-service path. */
    val executionPath: String,
    /** True when the live chart was enabled; a known observer-effect contributor. */
    val liveChartEnabled: Boolean,
    val backendVersion: String?,
    /** The device the model ran on, as the runtime names it (e.g. "Mali-G78 via Vulkan"). */
    val computeDevice: String? = null,
)

/** Everything one run produced. This is what gets serialized, exported and submitted. */
data class BenchmarkResult(
    val resultId: ResultId,
    val spec: BenchmarkSpec,
    val device: DeviceDescriptor,
    val environment: RunEnvironment,
    /** The single wall-clock value in the document, for labelling only. Epoch millis UTC. */
    val startedAtEpochMillis: Long,
    val iterations: List<IterationSample>,
    val thermalTimeline: ThermalTimeline,
    val powerSamples: List<PowerSample>,
    val terminated: TerminationReason,
    val errorMessage: String?,
    val profile: DegradationProfile?,
    val toolVersion: SemVer,
    val schemaVersion: Int,
) {
    val measuredIterations: List<IterationSample>
        get() = iterations.filter { it.phase == Phase.MEASURED }
}

data class ResultSummary(
    val resultId: ResultId,
    val specId: SpecId,
    val label: String,
    val deviceModel: String,
    val backendId: BackendId,
    val delegate: Delegate,
    val modelName: String,
    val startedAtEpochMillis: Long,
    val measuredIterations: Int,
    val retentionRatio: Double?,
    val validity: ValidityVerdict?,
    val terminated: TerminationReason,
)

/** Progress events emitted by the sustained-load engine. */
sealed interface RunProgress {
    data class LoadingModel(val backend: BackendId) : RunProgress
    data class ModelLoaded(val loadNanos: Long) : RunProgress
    data class IterationStarted(val index: Int, val phase: Phase, val total: Int) : RunProgress
    data class IterationCompleted(val sample: IterationSample, val total: Int) : RunProgress
    data class Thermal(val sample: ThermalSample) : RunProgress
    data class ThermalChanged(val transition: ThermalTransition) : RunProgress
    data class Power(val sample: PowerSample) : RunProgress
    data class Finished(val result: BenchmarkResult) : RunProgress
}
