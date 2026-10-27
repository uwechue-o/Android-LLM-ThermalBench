package com.thermalbench.leaderboard

import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.LeaderboardEntry
import com.thermalbench.domain.model.LeaderboardFilter
import com.thermalbench.domain.model.SubmissionConsent
import com.thermalbench.domain.model.SubmissionReceipt
import com.thermalbench.domain.port.LeaderboardClient
import com.thermalbench.domain.port.ResultCodec
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opt-in submission client. Every call goes through [toRequest], which is the
 * only place a result is turned into something transmissible, so the set of
 * fields that can leave the device is readable in one screen.
 */
@Singleton
class KtorLeaderboardClient @Inject constructor(
    private val http: HttpClient,
    private val codec: ResultCodec,
    private val config: LeaderboardConfig,
) : LeaderboardClient {

    override suspend fun submit(result: BenchmarkResult, consent: SubmissionConsent): SubmissionReceipt {
        require(consent.consentGiven) { "submit() called without consent" }
        val response: SubmissionResponse = http.post("${config.baseUrl}/v1/submissions") {
            contentType(ContentType.Application.Json)
            setBody(toRequest(result, consent))
        }.body()
        return SubmissionReceipt(
            submissionId = response.submissionId,
            acceptedEpochMillis = response.acceptedEpochMillis,
            publicUrl = response.publicUrl,
        )
    }

    override suspend fun fetch(filter: LeaderboardFilter): List<LeaderboardEntry> {
        val page: LeaderboardPage = http.get("${config.baseUrl}/v1/leaderboard") {
            filter.modelName?.let { parameter("model", it) }
            filter.backendId?.let { parameter("backend", it.value) }
            filter.delegate?.let { parameter("delegate", it.name) }
            filter.deviceModel?.let { parameter("device", it) }
            parameter("minIterations", filter.minMeasuredIterations)
            parameter("limit", filter.limit)
        }.body()
        return page.entries.map { it.toDomain() }
    }

    private fun toRequest(result: BenchmarkResult, consent: SubmissionConsent): SubmissionRequest {
        val profile = requireNotNull(result.profile) { "Result has no analysis profile" }
        return SubmissionRequest(
            schemaVersion = result.schemaVersion,
            toolVersion = result.toolVersion.toString(),
            specId = result.spec.specId.value,
            specDigest = codec.specDigest(result.spec),
            modelName = result.spec.model.name,
            modelQuantization = result.spec.model.quantization,
            backendId = result.spec.backend.id.value,
            delegate = result.spec.backend.delegate.name,
            backendVersion = result.environment.backendVersion,
            deviceModel = if (consent.includeDeviceModel) {
                "${result.device.manufacturer} ${result.device.model}"
            } else {
                null
            },
            socManufacturer = result.device.socManufacturer,
            socModel = result.device.socModel,
            androidRelease = result.device.androidRelease,
            sdkInt = result.device.sdkInt,
            anonymousDeviceId = result.device.anonymousDeviceId,
            measuredIterations = profile.measuredIterationCount,
            peakDecodeTokensPerSecond = profile.peakDecodeTokensPerSecond,
            steadyStateTokensPerSecond = profile.steadyStateTokensPerSecond,
            retentionRatio = profile.retentionRatio,
            iterationsToFirstThrottle = profile.iterationsToFirstThrottle,
            timeToFirstThrottleMillis = profile.timeToFirstThrottle?.inWholeMilliseconds,
            ttftDriftMicrosPerIteration = profile.ttftDriftMicrosPerIteration,
            terminated = profile.terminated.name,
            validity = profile.validity.name,
            executionPath = result.environment.executionPath,
            liveChartEnabled = result.environment.liveChartEnabled,
            preconditionsSatisfied = result.environment.preconditionsSatisfied,
            iterations = if (consent.includeRawIterations) {
                result.measuredIterations.map {
                    SubmittedIteration(
                        index = it.index,
                        decodeTokensPerSecond = it.decodeTokensPerSecond,
                        timeToFirstTokenMicros = it.timeToFirstTokenMicros,
                        thermalStatusAtEnd = it.thermalStatusAtEnd.name,
                        thermalHeadroomAtStart = it.thermalHeadroomAtStart,
                    )
                }
            } else {
                null
            },
            consentEpochMillis = consent.consentEpochMillis,
        )
    }

    private fun LeaderboardEntryDto.toDomain() = LeaderboardEntry(
        submissionId = submissionId,
        deviceModel = deviceModel,
        socModel = socModel,
        modelName = modelName,
        backendId = BackendId(backendId),
        delegate = Delegate.valueOf(delegate),
        peakDecodeTokensPerSecond = peakDecodeTokensPerSecond,
        steadyStateTokensPerSecond = steadyStateTokensPerSecond,
        retentionRatio = retentionRatio,
        iterationsToFirstThrottle = iterationsToFirstThrottle,
        measuredIterations = measuredIterations,
        submittedEpochMillis = submittedEpochMillis,
    )
}

/** Where submissions go. Overridable so a fork can host its own leaderboard. */
data class LeaderboardConfig(val baseUrl: String) {
    companion object {
        const val DEFAULT_BASE_URL = "https://leaderboard.thermalbench.dev"
    }
}
