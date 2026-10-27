package com.thermalbench.domain.model

/**
 * Explicit, per-submission consent. Nothing leaves the device without this,
 * and the anonymization level is the user's choice, not the tool's.
 */
data class SubmissionConsent(
    val consentGiven: Boolean,
    val includeDeviceModel: Boolean,
    val includeRawIterations: Boolean,
    val consentEpochMillis: Long,
)

data class SubmissionReceipt(
    val submissionId: String,
    val acceptedEpochMillis: Long,
    val publicUrl: String?,
)

data class LeaderboardFilter(
    val modelName: String? = null,
    val backendId: BackendId? = null,
    val delegate: Delegate? = null,
    val deviceModel: String? = null,
    val minMeasuredIterations: Int = RunProtocol.SUSTAINED_LOAD_MIN_ITERATIONS,
    val limit: Int = 100,
)

data class LeaderboardEntry(
    val submissionId: String,
    val deviceModel: String,
    val socModel: String?,
    val modelName: String,
    val backendId: BackendId,
    val delegate: Delegate,
    val peakDecodeTokensPerSecond: Double,
    val steadyStateTokensPerSecond: Double?,
    val retentionRatio: Double?,
    val iterationsToFirstThrottle: Int?,
    val measuredIterations: Int,
    val submittedEpochMillis: Long,
)
