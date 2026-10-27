package com.thermalbench.domain.usecase

import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.DegradationProfile
import com.thermalbench.domain.port.DegradationAnalyzer

/**
 * Samples → [DegradationProfile]. Thin: the math lives behind the
 * [DegradationAnalyzer] port (core/analysis) so the exact same code runs
 * on-device and offline.
 */
class AnalyzeDegradationUseCase(private val analyzer: DegradationAnalyzer) {

    operator fun invoke(result: BenchmarkResult): DegradationProfile = analyzer.analyze(
        iterations = result.iterations,
        timeline = result.thermalTimeline,
        terminated = result.terminated,
        preconditionsSatisfied = result.environment.preconditionsSatisfied,
        minMeasuredIterations = result.spec.protocol.measuredIterations,
    )

    /** Re-analyzes a stored result, e.g. after an analysis algorithm update. */
    fun reanalyze(result: BenchmarkResult): BenchmarkResult = result.copy(profile = invoke(result))
}
