package com.thermalbench.domain.usecase

import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.ResultId
import com.thermalbench.domain.model.SubmissionConsent
import com.thermalbench.domain.model.SubmissionReceipt
import com.thermalbench.domain.port.LeaderboardClient
import com.thermalbench.domain.port.ResultCodec
import com.thermalbench.domain.port.ResultRepository

enum class ExportFormat { JSON, CSV }

data class ExportedDocument(val format: ExportFormat, val fileName: String, val content: String)

/** Canonical JSON + CSV. */
class ExportResultUseCase(private val codec: ResultCodec) {

    operator fun invoke(result: BenchmarkResult, format: ExportFormat): ExportedDocument {
        val base = "thermalbench-${result.spec.specId}-${result.resultId}"
        return when (format) {
            ExportFormat.JSON -> ExportedDocument(format, "$base.json", codec.encodeResult(result))
            ExportFormat.CSV -> ExportedDocument(format, "$base.csv", codec.resultCsv(result))
        }
    }
}

/** Ingest someone else's result document. */
class ImportResultUseCase(private val results: ResultRepository) {

    suspend operator fun invoke(document: String): ResultId = results.import(document)
}

sealed interface SubmissionOutcome {
    data class Submitted(val receipt: SubmissionReceipt) : SubmissionOutcome
    data class Refused(val reason: String) : SubmissionOutcome
}

/** Consent-gated, anonymized submission. Nothing leaves the device without [SubmissionConsent.consentGiven]. */
class SubmitToLeaderboardUseCase(private val client: LeaderboardClient) {

    suspend operator fun invoke(result: BenchmarkResult, consent: SubmissionConsent): SubmissionOutcome {
        if (!consent.consentGiven) return SubmissionOutcome.Refused("Consent not given")
        val profile = result.profile ?: return SubmissionOutcome.Refused("Result has no analysis profile")
        if (profile.validity != com.thermalbench.domain.model.ValidityVerdict.VALID) {
            return SubmissionOutcome.Refused("Only VALID results may be submitted (was ${profile.validity})")
        }
        return SubmissionOutcome.Submitted(client.submit(result, consent))
    }
}
