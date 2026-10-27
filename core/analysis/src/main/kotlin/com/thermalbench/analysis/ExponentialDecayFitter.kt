package com.thermalbench.analysis

import com.thermalbench.domain.model.DecayFit
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer
import org.apache.commons.math3.fitting.leastsquares.MultivariateJacobianFunction
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import org.apache.commons.math3.util.Pair
import kotlin.math.exp

/**
 * Fits `y(t) = asymptote + amplitude * exp(-t / tau)` with Levenberg-Marquardt.
 *
 * Returns null when the series is too short, degenerate, or the optimizer does
 * not converge — a missing fit is recorded honestly rather than replaced by a
 * straight line dressed up as a decay.
 */
object ExponentialDecayFitter {

    private const val MIN_POINTS = 8
    private const val MAX_EVALUATIONS = 2_000
    private const val MAX_ITERATIONS = 1_000

    fun fit(timesSeconds: DoubleArray, values: DoubleArray): DecayFit? {
        require(timesSeconds.size == values.size) { "times and values must have the same length" }
        val n = values.size
        if (n < MIN_POINTS) return null
        val span = timesSeconds.last() - timesSeconds.first()
        if (span <= 0.0) return null

        // Initial guess: asymptote from the tail, amplitude from head minus tail,
        // time constant a third of the span.
        val tail = Statistics.median(values.copyOfRange(n - n / 4, n))
        val head = Statistics.median(values.copyOfRange(0, maxOf(2, n / 4)))
        val amplitude0 = head - tail
        // No decay to model.
        if (amplitude0 <= 0.0) return flatOrNull(values)
        val start = doubleArrayOf(tail, amplitude0, span / 3.0)

        val model = MultivariateJacobianFunction { point ->
            val a = point.getEntry(0)
            val b = point.getEntry(1)
            val tau = point.getEntry(2).coerceAtLeast(1e-6)
            val out = ArrayRealVector(n)
            val jac = Array2DRowRealMatrix(n, 3)
            for (i in 0 until n) {
                val t = timesSeconds[i] - timesSeconds.first()
                val e = exp(-t / tau)
                out.setEntry(i, a + b * e)
                jac.setEntry(i, 0, 1.0)
                jac.setEntry(i, 1, e)
                jac.setEntry(i, 2, b * e * t / (tau * tau))
            }
            Pair(out, jac)
        }

        val problem = LeastSquaresBuilder()
            .start(start)
            .model(model)
            .target(values)
            .lazyEvaluation(false)
            .maxEvaluations(MAX_EVALUATIONS)
            .maxIterations(MAX_ITERATIONS)
            .build()

        val optimum = try {
            LevenbergMarquardtOptimizer().optimize(problem)
        } catch (_: Exception) {
            return null
        }

        val p = optimum.point
        val asymptote = p.getEntry(0)
        val amplitude = p.getEntry(1)
        val tau = p.getEntry(2)
        if (!asymptote.isFinite() || !amplitude.isFinite() || !tau.isFinite() || tau <= 0.0) return null
        // A converged optimum can still be meaningless. On a nearly flat series
        // the optimizer can trade a time constant of centuries against
        // asymptote and amplitude in the millions that cancel: over the data it
        // is a straight line, which is exactly what this fitter must not
        // present as a decay. A run of 50 iterations once came back with
        // tau = 7.2e9 s and an asymptote of -2.2e6 tok/s. A negative asymptote
        // is physically impossible on its own.
        if (tau > MAX_TAU_SPANS * span || asymptote < 0.0) return flatOrNull(values)

        val predicted = DoubleArray(n) { i -> asymptote + amplitude * exp(-(timesSeconds[i] - timesSeconds.first()) / tau) }
        return DecayFit(asymptote, amplitude, tau, rSquared(values, predicted))
    }

    /** A flat fit if the series is genuinely flat; otherwise no fit at all. */
    private fun flatOrNull(values: DoubleArray): DecayFit? {
        val cv = Statistics.coefficientOfVariation(values)
        return if (!cv.isNaN() && cv < FLAT_CV) {
            DecayFit(asymptote = Statistics.mean(values), amplitude = 0.0, timeConstantSeconds = 0.0, rSquared = 1.0)
        } else {
            null
        }
    }

    private fun rSquared(actual: DoubleArray, predicted: DoubleArray): Double {
        val mean = Statistics.mean(actual)
        var ssRes = 0.0
        var ssTot = 0.0
        for (i in actual.indices) {
            ssRes += (actual[i] - predicted[i]) * (actual[i] - predicted[i])
            ssTot += (actual[i] - mean) * (actual[i] - mean)
        }
        return if (ssTot == 0.0) 1.0 else (1.0 - ssRes / ssTot).coerceIn(0.0, 1.0)
    }

    private const val FLAT_CV = 0.03

    /**
     * A time constant this many times the observed span is indistinguishable
     * from a straight line over the data; the decay it claims is extrapolation.
     */
    private const val MAX_TAU_SPANS = 10.0
}
