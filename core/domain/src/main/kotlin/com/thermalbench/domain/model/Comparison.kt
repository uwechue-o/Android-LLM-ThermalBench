package com.thermalbench.domain.model

/** One row of a comparison table; the same shape serves backends and devices. */
data class ComparisonRow(
    val resultId: ResultId,
    val label: String,
    val deviceModel: String,
    val backendId: BackendId,
    val delegate: Delegate,
    val peakDecodeTokensPerSecond: Double,
    val steadyStateTokensPerSecond: Double?,
    val retentionRatio: Double?,
    val iterationsToFirstThrottle: Int?,
    val ttftDriftMicrosPerIteration: Double,
    val validity: ValidityVerdict,
)

/** Several results for one spec on one device, one row per backend/delegate. */
data class BackendComparison(
    val specId: SpecId,
    val deviceModel: String,
    val rows: List<ComparisonRow>,
)

/** The same spec across devices — the cross-device table the paper is built on. */
data class DeviceComparison(
    val specId: SpecId,
    val rows: List<ComparisonRow>,
)
