package com.thermalbench.domain.usecase

import com.thermalbench.domain.model.BackendComparison
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.ComparisonRow
import com.thermalbench.domain.model.DeviceComparison
import com.thermalbench.domain.model.SpecId
import com.thermalbench.domain.model.ValidityVerdict
import com.thermalbench.domain.port.ResultRepository

private fun BenchmarkResult.toRow(): ComparisonRow? {
    val p = profile ?: return null
    return ComparisonRow(
        resultId = resultId,
        label = spec.label.ifBlank { spec.model.name },
        deviceModel = "${device.manufacturer} ${device.model}",
        backendId = spec.backend.id,
        delegate = spec.backend.delegate,
        peakDecodeTokensPerSecond = p.peakDecodeTokensPerSecond,
        steadyStateTokensPerSecond = p.steadyStateTokensPerSecond,
        retentionRatio = p.retentionRatio,
        iterationsToFirstThrottle = p.iterationsToFirstThrottle,
        ttftDriftMicrosPerIteration = p.ttftDriftMicrosPerIteration,
        validity = p.validity,
    )
}

/** Multiple results on one device → backend comparison. */
class CompareBackendsUseCase(private val results: ResultRepository) {

    suspend operator fun invoke(specId: SpecId, deviceModel: String, includeInvalid: Boolean = false): BackendComparison {
        val rows = results.loadBySpec(specId)
            .filter { "${it.device.manufacturer} ${it.device.model}" == deviceModel }
            .mapNotNull { it.toRow() }
            .filter { includeInvalid || it.validity == ValidityVerdict.VALID }
            .sortedByDescending { it.retentionRatio ?: -1.0 }
        return BackendComparison(specId, deviceModel, rows)
    }

    fun fromResults(specId: SpecId, deviceModel: String, results: List<BenchmarkResult>): BackendComparison =
        BackendComparison(specId, deviceModel, results.mapNotNull { it.toRow() })
}

/** Same spec across devices → the cross-device table. */
class CompareDevicesUseCase(private val results: ResultRepository) {

    suspend operator fun invoke(specId: SpecId, includeInvalid: Boolean = false): DeviceComparison {
        val rows = results.loadBySpec(specId)
            .mapNotNull { it.toRow() }
            .filter { includeInvalid || it.validity == ValidityVerdict.VALID }
            .sortedByDescending { it.retentionRatio ?: -1.0 }
        return DeviceComparison(specId, rows)
    }

    fun fromResults(specId: SpecId, results: List<BenchmarkResult>): DeviceComparison =
        DeviceComparison(specId, results.filter { it.spec.specId == specId }.mapNotNull { it.toRow() })
}
