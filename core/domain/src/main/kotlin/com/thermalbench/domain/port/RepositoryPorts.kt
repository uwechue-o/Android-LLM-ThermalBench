package com.thermalbench.domain.port

import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.BenchmarkSpec
import com.thermalbench.domain.model.DegradationProfile
import com.thermalbench.domain.model.IterationSample
import com.thermalbench.domain.model.LeaderboardEntry
import com.thermalbench.domain.model.LeaderboardFilter
import com.thermalbench.domain.model.ResultId
import com.thermalbench.domain.model.ResultSummary
import com.thermalbench.domain.model.SpecId
import com.thermalbench.domain.model.SubmissionConsent
import com.thermalbench.domain.model.SubmissionReceipt
import com.thermalbench.domain.model.TerminationReason
import com.thermalbench.domain.model.ThermalTimeline
import kotlinx.coroutines.flow.Flow

interface ResultRepository {
    suspend fun save(result: BenchmarkResult): ResultId

    fun observeAll(): Flow<List<ResultSummary>>

    suspend fun load(id: ResultId): BenchmarkResult?

    suspend fun loadBySpec(specId: SpecId): List<BenchmarkResult>

    /** Ingests a canonical result document (someone else's, or an older run). */
    suspend fun import(document: String): ResultId

    suspend fun delete(id: ResultId)
}

interface SpecRepository {
    suspend fun save(spec: BenchmarkSpec)

    suspend fun load(id: SpecId): BenchmarkSpec?

    fun observeAll(): Flow<List<BenchmarkSpec>>
}

/**
 * Serializes and parses result and spec documents. Implemented in core/schema
 * with a canonical encoder so that equal results produce byte-identical text.
 */
interface ResultCodec {
    fun encodeResult(result: BenchmarkResult): String

    fun decodeResult(document: String): BenchmarkResult

    fun encodeSpec(spec: BenchmarkSpec): String

    fun decodeSpec(document: String): BenchmarkSpec

    /** Canonical SHA-256 of the spec, which is what [SpecId] is derived from. */
    fun specDigest(spec: BenchmarkSpec): String

    fun resultCsv(result: BenchmarkResult): String
}

/** Samples -> [DegradationProfile]. Implemented in core/analysis, pure Kotlin. */
interface DegradationAnalyzer {
    fun analyze(
        iterations: List<IterationSample>,
        timeline: ThermalTimeline,
        terminated: TerminationReason,
        preconditionsSatisfied: Boolean,
        minMeasuredIterations: Int,
    ): DegradationProfile
}

interface LeaderboardClient {
    suspend fun submit(result: BenchmarkResult, consent: SubmissionConsent): SubmissionReceipt

    suspend fun fetch(filter: LeaderboardFilter): List<LeaderboardEntry>
}
