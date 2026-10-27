package com.thermalbench.analysis

import kotlin.math.sqrt

/** Small, dependency-free statistics used by both the live chart and the offline report. */
object Statistics {

    fun mean(xs: DoubleArray): Double = if (xs.isEmpty()) Double.NaN else xs.sum() / xs.size

    fun median(xs: DoubleArray): Double {
        if (xs.isEmpty()) return Double.NaN
        val sorted = xs.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    /** Linear-interpolated percentile, p in 0..100. */
    fun percentile(xs: DoubleArray, p: Double): Double {
        if (xs.isEmpty()) return Double.NaN
        require(p in 0.0..100.0) { "p must be 0..100" }
        val sorted = xs.sortedArray()
        val rank = p / 100.0 * (sorted.size - 1)
        val lo = rank.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = rank - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    fun standardDeviation(xs: DoubleArray): Double {
        if (xs.size < 2) return 0.0
        val m = mean(xs)
        var acc = 0.0
        for (x in xs) acc += (x - m) * (x - m)
        return sqrt(acc / (xs.size - 1))
    }

    /** Coefficient of variation; NaN when the mean is zero. */
    fun coefficientOfVariation(xs: DoubleArray): Double {
        val m = mean(xs)
        return if (m == 0.0) Double.NaN else standardDeviation(xs) / m
    }

    data class Regression(val slope: Double, val intercept: Double, val rSquared: Double)

    /** Ordinary least squares y = slope * x + intercept. */
    fun linearRegression(xs: DoubleArray, ys: DoubleArray): Regression {
        require(xs.size == ys.size) { "xs and ys must have the same length" }
        val n = xs.size
        if (n < 2) return Regression(0.0, ys.firstOrNull() ?: Double.NaN, 0.0)
        val mx = mean(xs)
        val my = mean(ys)
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        if (sxx == 0.0) return Regression(0.0, my, 0.0)
        val slope = sxy / sxx
        val intercept = my - slope * mx
        val r2 = if (syy == 0.0) 1.0 else (sxy * sxy) / (sxx * syy)
        return Regression(slope, intercept, r2)
    }

    /** Median of every contiguous window of [size]; empty if the series is shorter than the window. */
    fun rollingMedian(xs: DoubleArray, size: Int): DoubleArray {
        require(size > 0) { "window size must be positive" }
        if (xs.size < size) return DoubleArray(0)
        return DoubleArray(xs.size - size + 1) { i -> median(xs.copyOfRange(i, i + size)) }
    }
}
