package com.thermalbench.persistence.db

import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.IterationSample
import com.thermalbench.domain.model.Phase
import com.thermalbench.domain.model.PowerSample
import com.thermalbench.domain.model.ResultId
import com.thermalbench.domain.model.ResultSummary
import com.thermalbench.domain.model.SpecId
import com.thermalbench.domain.model.TerminationReason
import com.thermalbench.domain.model.ThermalSample
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.ThermalTransition
import com.thermalbench.domain.model.ValidityVerdict

/** Domain ⇄ Room mapping. Frequency lists are pipe-separated; null stays null. */
internal object EntityMappers {

    private const val SEPARATOR = "|"

    fun encodeFrequencies(values: List<Long>?): String? = values?.joinToString(SEPARATOR)

    fun decodeFrequencies(value: String?): List<Long>? =
        value?.takeIf { it.isNotBlank() }?.split(SEPARATOR)?.mapNotNull { it.toLongOrNull() }

    fun IterationSample.toEntity(resultId: String) = IterationSampleEntity(
        resultId = resultId,
        index = index,
        phase = phase.name,
        startElapsedNanos = startElapsedNanos,
        endElapsedNanos = endElapsedNanos,
        timeToFirstTokenMicros = timeToFirstTokenMicros,
        prefillTokensPerSecond = prefillTokensPerSecond,
        decodeTokensPerSecond = decodeTokensPerSecond,
        promptTokenCount = promptTokenCount,
        outputTokenCount = outputTokenCount,
        thermalStatusAtStart = thermalStatusAtStart.name,
        thermalStatusAtEnd = thermalStatusAtEnd.name,
        thermalHeadroomAtStart = thermalHeadroomAtStart,
        batteryTemperatureDeciC = batteryTemperatureDeciC,
        batteryPercent = batteryPercent,
        cpuFrequenciesKhz = encodeFrequencies(cpuFrequenciesKhz),
        peakRssBytes = peakRssBytes,
        throttleObserved = throttleObserved,
    )

    fun IterationSampleEntity.toDomain() = IterationSample(
        index = index,
        phase = Phase.valueOf(phase),
        startElapsedNanos = startElapsedNanos,
        endElapsedNanos = endElapsedNanos,
        timeToFirstTokenMicros = timeToFirstTokenMicros,
        prefillTokensPerSecond = prefillTokensPerSecond,
        decodeTokensPerSecond = decodeTokensPerSecond,
        promptTokenCount = promptTokenCount,
        outputTokenCount = outputTokenCount,
        thermalStatusAtStart = ThermalStatus.valueOf(thermalStatusAtStart),
        thermalStatusAtEnd = ThermalStatus.valueOf(thermalStatusAtEnd),
        thermalHeadroomAtStart = thermalHeadroomAtStart,
        batteryTemperatureDeciC = batteryTemperatureDeciC,
        batteryPercent = batteryPercent,
        cpuFrequenciesKhz = decodeFrequencies(cpuFrequenciesKhz),
        peakRssBytes = peakRssBytes,
        throttleObserved = throttleObserved,
    )

    fun ThermalSample.toEntity(resultId: String) = ThermalSampleEntity(
        resultId = resultId,
        elapsedNanos = elapsedNanos,
        status = status.name,
        headroom = headroom,
        batteryTemperatureDeciC = batteryTemperatureDeciC,
        cpuFrequenciesKhz = encodeFrequencies(cpuFrequenciesKhz),
    )

    fun ThermalSampleEntity.toDomain() = ThermalSample(
        elapsedNanos = elapsedNanos,
        status = ThermalStatus.valueOf(status),
        headroom = headroom,
        batteryTemperatureDeciC = batteryTemperatureDeciC,
        cpuFrequenciesKhz = decodeFrequencies(cpuFrequenciesKhz),
    )

    fun ThermalTransition.toEntity(resultId: String) = ThermalTransitionEntity(
        resultId = resultId,
        elapsedNanos = elapsedNanos,
        fromStatus = from.name,
        toStatus = to.name,
    )

    fun ThermalTransitionEntity.toDomain() = ThermalTransition(
        elapsedNanos = elapsedNanos,
        from = ThermalStatus.valueOf(fromStatus),
        to = ThermalStatus.valueOf(toStatus),
    )

    fun PowerSample.toEntity(resultId: String) = PowerSampleEntity(
        resultId = resultId,
        elapsedNanos = elapsedNanos,
        batteryPercent = batteryPercent,
        currentNowMicroAmps = currentNowMicroAmps,
        chargeCounterMicroAh = chargeCounterMicroAh,
        voltageMillivolts = voltageMillivolts,
        plugged = plugged,
    )

    fun PowerSampleEntity.toDomain() = PowerSample(
        elapsedNanos = elapsedNanos,
        batteryPercent = batteryPercent,
        currentNowMicroAmps = currentNowMicroAmps,
        chargeCounterMicroAh = chargeCounterMicroAh,
        voltageMillivolts = voltageMillivolts,
        plugged = plugged,
    )

    fun BenchmarkResult.toRunEntity(documentFileName: String?, complete: Boolean) = RunEntity(
        resultId = resultId.value,
        specId = spec.specId.value,
        label = spec.label,
        deviceModel = "${device.manufacturer} ${device.model}",
        backendId = spec.backend.id.value,
        delegate = spec.backend.delegate.name,
        modelName = spec.model.name,
        startedAtEpochMillis = startedAtEpochMillis,
        measuredIterations = measuredIterations.size,
        retentionRatio = profile?.retentionRatio,
        validity = profile?.validity?.name,
        terminated = terminated.name,
        documentFileName = documentFileName,
        complete = complete,
    )

    fun RunEntity.toSummary() = ResultSummary(
        resultId = ResultId(resultId),
        specId = SpecId(specId),
        label = label,
        deviceModel = deviceModel,
        backendId = BackendId(backendId),
        delegate = Delegate.valueOf(delegate),
        modelName = modelName,
        startedAtEpochMillis = startedAtEpochMillis,
        measuredIterations = measuredIterations,
        retentionRatio = retentionRatio,
        validity = validity?.let { ValidityVerdict.valueOf(it) },
        terminated = TerminationReason.valueOf(terminated),
    )
}
