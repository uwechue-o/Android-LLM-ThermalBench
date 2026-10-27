package com.thermalbench.analysis

import kotlin.math.abs

/**
 * Finds the throughput floor a device settles on.
 *
 * A series is "steady" from index `i` onward when every trailing window of
 * [windowSize] samples starting at or after `i` has a coefficient of variation
 * below [maxCv] and a normalized slope (slope * window / mean) below
 * [maxNormalizedSlope]. The steady-state value is the median of the settled
 * tail. If the last window is not itself steady, the run never stabilized.
 */
class SteadyStateDetector(
    private val windowSize: Int = DEFAULT_WINDOW,
    private val maxCv: Double = DEFAULT_MAX_CV,
    private val maxNormalizedSlope: Double = DEFAULT_MAX_SLOPE,
) {
    data class SteadyState(
        /** Index into the series where the settled tail begins. */
        val startIndex: Int,
        val value: Double,
    )

    fun detect(values: DoubleArray): SteadyState? {
        if (values.size < windowSize) return null
        val lastWindowStart = values.size - windowSize
        if (!isSteady(values, lastWindowStart)) return null

        var start = lastWindowStart
        while (start > 0 && isSteady(values, start - 1)) start--
        val tail = values.copyOfRange(start, values.size)
        return SteadyState(start, Statistics.median(tail))
    }

    private fun isSteady(values: DoubleArray, windowStart: Int): Boolean {
        val window = values.copyOfRange(windowStart, windowStart + windowSize)
        val mean = Statistics.mean(window)
        if (mean <= 0.0) return false
        val cv = Statistics.coefficientOfVariation(window)
        if (cv.isNaN() || cv > maxCv) return false
        val xs = DoubleArray(windowSize) { it.toDouble() }
        val slope = Statistics.linearRegression(xs, window).slope
        return abs(slope * windowSize / mean) <= maxNormalizedSlope
    }

    companion object {
        const val DEFAULT_WINDOW = 10
        const val DEFAULT_MAX_CV = 0.05
        const val DEFAULT_MAX_SLOPE = 0.03
    }
}
