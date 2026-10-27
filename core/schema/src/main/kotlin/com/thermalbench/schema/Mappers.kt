package com.thermalbench.schema

import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.BackendRef
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.BenchmarkSpec
import com.thermalbench.domain.model.DecayFit
import com.thermalbench.domain.model.DegradationProfile
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.DeviceConditions
import com.thermalbench.domain.model.DeviceDescriptor
import com.thermalbench.domain.model.IterationSample
import com.thermalbench.domain.model.ModelFormat
import com.thermalbench.domain.model.ModelRef
import com.thermalbench.domain.model.Phase
import com.thermalbench.domain.model.PowerSample
import com.thermalbench.domain.model.PreconditionPolicy
import com.thermalbench.domain.model.ResultId
import com.thermalbench.domain.model.RunEnvironment
import com.thermalbench.domain.model.RunProtocol
import com.thermalbench.domain.model.ScreenPolicy
import com.thermalbench.domain.model.SemVer
import com.thermalbench.domain.model.SensorTemperature
import com.thermalbench.domain.model.Sha256
import com.thermalbench.domain.model.SpecId
import com.thermalbench.domain.model.StopCondition
import com.thermalbench.domain.model.TerminationReason
import com.thermalbench.domain.model.ThermalSample
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.ThermalTimeline
import com.thermalbench.domain.model.ThermalTransition
import com.thermalbench.domain.model.ThrottleEvent
import com.thermalbench.domain.model.ValidityVerdict
import com.thermalbench.domain.model.Workload
import kotlin.time.Duration.Companion.milliseconds

/** Domain ⇄ document mapping. Deliberately boring; every field is spelled out. */
object Mappers {

    // ---- spec ---------------------------------------------------------------

    fun BenchmarkSpec.toDocument(): SpecDocument = SpecDocument(
        specId = specId.value,
        label = label,
        model = ModelRefDocument(
            name = model.name,
            format = model.format.name,
            sha256 = model.sha256.hex,
            sizeBytes = model.sizeBytes,
            path = model.path,
            quantization = model.quantization,
            parameterCount = model.parameterCount,
        ),
        backend = BackendRefDocument(backend.id.value, backend.delegate.name, backend.options.toSortedMap()),
        workload = WorkloadDocument(
            promptTokens = workload.promptTokens,
            maxOutputTokens = workload.maxOutputTokens,
            promptSha256 = workload.promptSha256.hex,
            stopCondition = when (val s = workload.stopCondition) {
                is StopCondition.TokenCount -> StopConditionDocument("TOKEN_COUNT", s.tokens)
            },
            prompt = workload.prompt,
        ),
        protocol = RunProtocolDocument(
            warmupIterations = protocol.warmupIterations,
            measuredIterations = protocol.measuredIterations,
            interIterationDelayMillis = protocol.interIterationDelay.inWholeMilliseconds,
            abortOnCriticalThermal = protocol.abortOnCriticalThermal,
            maxRunDurationMillis = protocol.maxRunDuration.inWholeMilliseconds,
            telemetryIntervalMillis = protocol.telemetryInterval.inWholeMilliseconds,
        ),
        preconditions = PreconditionPolicyDocument(
            requiredThermalStatus = preconditions.requiredThermalStatus.name,
            minThermalHeadroom = preconditions.minThermalHeadroom,
            requireUnplugged = preconditions.requireUnplugged,
            minBatteryPercent = preconditions.minBatteryPercent,
            screenPolicy = preconditions.screenPolicy.name,
            stabilizationWindowMillis = preconditions.stabilizationWindow.inWholeMilliseconds,
            maxWaitForPreconditionsMillis = preconditions.maxWaitForPreconditions.inWholeMilliseconds,
        ),
        seed = seed,
        toolVersion = toolVersion.toString(),
    )

    fun SpecDocument.toDomain(): BenchmarkSpec = BenchmarkSpec(
        specId = SpecId(specId),
        label = label,
        model = ModelRef(
            name = model.name,
            format = ModelFormat.valueOf(model.format),
            sha256 = Sha256(model.sha256),
            sizeBytes = model.sizeBytes,
            path = model.path,
            quantization = model.quantization,
            parameterCount = model.parameterCount,
        ),
        backend = BackendRef(BackendId(backend.id), Delegate.valueOf(backend.delegate), backend.options),
        workload = Workload(
            promptTokens = workload.promptTokens,
            maxOutputTokens = workload.maxOutputTokens,
            promptSha256 = Sha256(workload.promptSha256),
            stopCondition = when (workload.stopCondition.type) {
                "TOKEN_COUNT" -> StopCondition.TokenCount(
                    requireNotNull(workload.stopCondition.tokens) { "TOKEN_COUNT stop condition needs tokens" },
                )
                else -> throw IllegalArgumentException("Unknown stop condition '${workload.stopCondition.type}'")
            },
            prompt = workload.prompt,
        ),
        protocol = RunProtocol(
            warmupIterations = protocol.warmupIterations,
            measuredIterations = protocol.measuredIterations,
            interIterationDelay = protocol.interIterationDelayMillis.milliseconds,
            abortOnCriticalThermal = protocol.abortOnCriticalThermal,
            maxRunDuration = protocol.maxRunDurationMillis.milliseconds,
            telemetryInterval = protocol.telemetryIntervalMillis.milliseconds,
        ),
        preconditions = PreconditionPolicy(
            requiredThermalStatus = ThermalStatus.valueOf(preconditions.requiredThermalStatus),
            minThermalHeadroom = preconditions.minThermalHeadroom,
            requireUnplugged = preconditions.requireUnplugged,
            minBatteryPercent = preconditions.minBatteryPercent,
            screenPolicy = ScreenPolicy.valueOf(preconditions.screenPolicy),
            stabilizationWindow = preconditions.stabilizationWindowMillis.milliseconds,
            maxWaitForPreconditions = preconditions.maxWaitForPreconditionsMillis.milliseconds,
        ),
        seed = seed,
        toolVersion = SemVer.parse(toolVersion),
    )

    // ---- samples ------------------------------------------------------------

    fun IterationSample.toDocument() = IterationSampleDocument(
        index, phase.name, startElapsedNanos, endElapsedNanos, timeToFirstTokenMicros,
        prefillTokensPerSecond, decodeTokensPerSecond, promptTokenCount, outputTokenCount,
        thermalStatusAtStart.name, thermalStatusAtEnd.name, thermalHeadroomAtStart,
        batteryTemperatureDeciC, batteryPercent, cpuFrequenciesKhz, peakRssBytes, throttleObserved,
        gpuFrequencyKhz, sensorTemperatures?.map { it.toDocument() },
    )

    fun IterationSampleDocument.toDomain() = IterationSample(
        index, Phase.valueOf(phase), startElapsedNanos, endElapsedNanos, timeToFirstTokenMicros,
        prefillTokensPerSecond, decodeTokensPerSecond, promptTokenCount, outputTokenCount,
        ThermalStatus.valueOf(thermalStatusAtStart), ThermalStatus.valueOf(thermalStatusAtEnd), thermalHeadroomAtStart,
        batteryTemperatureDeciC, batteryPercent, cpuFrequenciesKhz, peakRssBytes, throttleObserved,
        gpuFrequencyKhz, sensorTemperatures?.map { it.toDomain() },
    )

    fun SensorTemperature.toDocument() = SensorTemperatureDocument(name, celsius)

    fun SensorTemperatureDocument.toDomain() = SensorTemperature(name, celsius)

    fun ThermalTimeline.toDocument() = ThermalTimelineDocument(
        samples = samples.map {
            ThermalSampleDocument(
                it.elapsedNanos, it.status.name, it.headroom, it.batteryTemperatureDeciC, it.cpuFrequenciesKhz,
                it.gpuFrequencyKhz, it.sensorTemperatures?.map { sensor -> sensor.toDocument() },
            )
        },
        transitions = transitions.map { ThermalTransitionDocument(it.elapsedNanos, it.from.name, it.to.name) },
    )

    fun ThermalTimelineDocument.toDomain() = ThermalTimeline(
        samples = samples.map {
            ThermalSample(
                it.elapsedNanos, ThermalStatus.valueOf(it.status), it.headroom, it.batteryTemperatureDeciC,
                it.cpuFrequenciesKhz, it.gpuFrequencyKhz, it.sensorTemperatures?.map { sensor -> sensor.toDomain() },
            )
        },
        transitions = transitions.map { ThermalTransition(it.elapsedNanos, ThermalStatus.valueOf(it.from), ThermalStatus.valueOf(it.to)) },
    )

    fun PowerSample.toDocument() =
        PowerSampleDocument(elapsedNanos, batteryPercent, currentNowMicroAmps, chargeCounterMicroAh, voltageMillivolts, plugged)

    fun PowerSampleDocument.toDomain() =
        PowerSample(elapsedNanos, batteryPercent, currentNowMicroAmps, chargeCounterMicroAh, voltageMillivolts, plugged)

    // ---- device / environment ----------------------------------------------

    fun DeviceDescriptor.toDocument() = DeviceDescriptorDocument(
        manufacturer, model, device, board, hardware, socManufacturer, socModel, androidRelease, sdkInt,
        securityPatch, abis, cpuCoreCount, totalRamBytes, cpuMaxFrequenciesKhz, cpuFrequencyReadable,
        thermalHeadroomSupported, anonymousDeviceId, gpuFrequencyReadable, thermalSensorNames,
    )

    fun DeviceDescriptorDocument.toDomain() = DeviceDescriptor(
        manufacturer, model, device, board, hardware, socManufacturer, socModel, androidRelease, sdkInt,
        securityPatch, abis, cpuCoreCount, totalRamBytes, cpuMaxFrequenciesKhz, cpuFrequencyReadable,
        thermalHeadroomSupported, anonymousDeviceId, gpuFrequencyReadable, thermalSensorNames,
    )

    fun DeviceConditions.toDocument() = DeviceConditionsDocument(
        elapsedNanos, thermalStatus.name, thermalHeadroom, batteryPercent, plugged, screenOn, batteryTemperatureDeciC,
    )

    fun DeviceConditionsDocument.toDomain() = DeviceConditions(
        elapsedNanos, ThermalStatus.valueOf(thermalStatus), thermalHeadroom, batteryPercent, plugged, screenOn, batteryTemperatureDeciC,
    )

    fun RunEnvironment.toDocument() = RunEnvironmentDocument(
        preconditionsSatisfied, unmetAtStart, conditionsAtStart?.toDocument(), conditionsAtEnd?.toDocument(),
        wakeLockHeld, screenPolicy.name, executionPath, liveChartEnabled, backendVersion, computeDevice,
    )

    fun RunEnvironmentDocument.toDomain() = RunEnvironment(
        preconditionsSatisfied, unmetAtStart, conditionsAtStart?.toDomain(), conditionsAtEnd?.toDomain(),
        wakeLockHeld, ScreenPolicy.valueOf(screenPolicy), executionPath, liveChartEnabled, backendVersion,
        computeDevice,
    )

    // ---- profile ------------------------------------------------------------

    fun DegradationProfile.toDocument() = DegradationProfileDocument(
        peakDecodeTokensPerSecond = peakDecodeTokensPerSecond,
        steadyStateTokensPerSecond = steadyStateTokensPerSecond,
        retentionRatio = retentionRatio,
        timeToFirstThrottleMillis = timeToFirstThrottle?.inWholeMilliseconds,
        iterationsToFirstThrottle = iterationsToFirstThrottle,
        decayModel = decayModel?.let { DecayFitDocument(it.asymptote, it.amplitude, it.timeConstantSeconds, it.rSquared) },
        ttftDriftMicrosPerIteration = ttftDriftMicrosPerIteration,
        throttleEvents = throttleEvents.map { ThrottleEventDocument(it.iterationIndex, it.elapsedNanos, it.from.name, it.to.name) },
        terminated = terminated.name,
        validity = validity.name,
        measuredIterationCount = measuredIterationCount,
        meanDecodeTokensPerSecond = meanDecodeTokensPerSecond,
        minDecodeTokensPerSecond = minDecodeTokensPerSecond,
        batteryDrainPercent = batteryDrainPercent,
        batteryTemperatureRiseDeciC = batteryTemperatureRiseDeciC,
    )

    fun DegradationProfileDocument.toDomain() = DegradationProfile(
        peakDecodeTokensPerSecond = peakDecodeTokensPerSecond,
        steadyStateTokensPerSecond = steadyStateTokensPerSecond,
        retentionRatio = retentionRatio,
        timeToFirstThrottle = timeToFirstThrottleMillis?.milliseconds,
        iterationsToFirstThrottle = iterationsToFirstThrottle,
        decayModel = decayModel?.let { DecayFit(it.asymptote, it.amplitude, it.timeConstantSeconds, it.rSquared) },
        ttftDriftMicrosPerIteration = ttftDriftMicrosPerIteration,
        throttleEvents = throttleEvents.map {
            ThrottleEvent(it.iterationIndex, it.elapsedNanos, ThermalStatus.valueOf(it.from), ThermalStatus.valueOf(it.to))
        },
        terminated = TerminationReason.valueOf(terminated),
        validity = ValidityVerdict.valueOf(validity),
        measuredIterationCount = measuredIterationCount,
        meanDecodeTokensPerSecond = meanDecodeTokensPerSecond,
        minDecodeTokensPerSecond = minDecodeTokensPerSecond,
        batteryDrainPercent = batteryDrainPercent,
        batteryTemperatureRiseDeciC = batteryTemperatureRiseDeciC,
    )

    // ---- result -------------------------------------------------------------

    fun BenchmarkResult.toDocument() = ResultDocument(
        schemaVersion = schemaVersion,
        resultId = resultId.value,
        spec = spec.toDocument(),
        device = device.toDocument(),
        environment = environment.toDocument(),
        startedAtEpochMillis = startedAtEpochMillis,
        iterations = iterations.map { it.toDocument() },
        thermalTimeline = thermalTimeline.toDocument(),
        powerSamples = powerSamples.map { it.toDocument() },
        terminated = terminated.name,
        errorMessage = errorMessage,
        profile = profile?.toDocument(),
        toolVersion = toolVersion.toString(),
    )

    fun ResultDocument.toDomain() = BenchmarkResult(
        resultId = ResultId(resultId),
        spec = spec.toDomain(),
        device = device.toDomain(),
        environment = environment.toDomain(),
        startedAtEpochMillis = startedAtEpochMillis,
        iterations = iterations.map { it.toDomain() },
        thermalTimeline = thermalTimeline.toDomain(),
        powerSamples = powerSamples.map { it.toDomain() },
        terminated = TerminationReason.valueOf(terminated),
        errorMessage = errorMessage,
        profile = profile?.toDomain(),
        toolVersion = SemVer.parse(toolVersion),
        schemaVersion = schemaVersion,
    )
}
