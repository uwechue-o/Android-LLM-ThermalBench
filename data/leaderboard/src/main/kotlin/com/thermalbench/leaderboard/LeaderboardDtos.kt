package com.thermalbench.leaderboard

import kotlinx.serialization.Serializable

/**
 * What actually leaves the device. This type is the privacy contract: if a
 * field is not here, it is not transmitted, and every optional field maps to a
 * switch the operator set before the upload.
 */
@Serializable
data class SubmissionRequest(
    val schemaVersion: Int,
    val toolVersion: String,
    val specId: String,
    val specDigest: String,
    val modelName: String,
    val modelQuantization: String?,
    val backendId: String,
    val delegate: String,
    val backendVersion: String?,
    /** Present only with consent; otherwise the entry is SoC-level only. */
    val deviceModel: String?,
    val socManufacturer: String?,
    val socModel: String?,
    val androidRelease: String,
    val sdkInt: Int,
    val anonymousDeviceId: String,
    val measuredIterations: Int,
    val peakDecodeTokensPerSecond: Double,
    val steadyStateTokensPerSecond: Double?,
    val retentionRatio: Double?,
    val iterationsToFirstThrottle: Int?,
    val timeToFirstThrottleMillis: Long?,
    val ttftDriftMicrosPerIteration: Double,
    val terminated: String,
    val validity: String,
    val executionPath: String,
    val liveChartEnabled: Boolean,
    val preconditionsSatisfied: Boolean,
    /** Present only when the operator opted to include raw per-iteration data. */
    val iterations: List<SubmittedIteration>?,
    val consentEpochMillis: Long,
)

@Serializable
data class SubmittedIteration(
    val index: Int,
    val decodeTokensPerSecond: Double,
    val timeToFirstTokenMicros: Long,
    val thermalStatusAtEnd: String,
    val thermalHeadroomAtStart: Float?,
)

@Serializable
data class SubmissionResponse(
    val submissionId: String,
    val acceptedEpochMillis: Long,
    val publicUrl: String? = null,
)

@Serializable
data class LeaderboardEntryDto(
    val submissionId: String,
    val deviceModel: String,
    val socModel: String? = null,
    val modelName: String,
    val backendId: String,
    val delegate: String,
    val peakDecodeTokensPerSecond: Double,
    val steadyStateTokensPerSecond: Double? = null,
    val retentionRatio: Double? = null,
    val iterationsToFirstThrottle: Int? = null,
    val measuredIterations: Int,
    val submittedEpochMillis: Long,
)

@Serializable
data class LeaderboardPage(val entries: List<LeaderboardEntryDto>)
