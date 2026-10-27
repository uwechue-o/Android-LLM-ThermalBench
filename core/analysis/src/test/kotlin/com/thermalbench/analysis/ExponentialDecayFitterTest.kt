package com.thermalbench.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.exp

class ExponentialDecayFitterTest {

    /**
     * The 50 measured iterations of a real run (Pixel 6, Qwen2.5 0.5B Q4_K_M on
     * llama.cpp CPU) that never throttled: flat at about 8 tok/s, with two
     * outliers at iterations 14 and 15. It once fitted as tau = 7.2e9 s with an
     * asymptote of -2.2e6 tok/s and R^2 = 0.023.
     */
    private val flatRunTimes = doubleArrayOf(
        0.0, 18.4, 36.8, 55.2, 74.1, 92.5, 111.0, 129.4, 147.9, 166.3,
        184.6, 202.9, 221.4, 239.8, 253.5, 268.3, 286.5, 304.7, 323.3, 341.8,
        360.3, 378.4, 396.9, 415.2, 433.6, 451.8, 470.3, 488.7, 507.2, 525.4,
        543.6, 562.0, 580.6, 598.8, 617.6, 636.0, 654.3, 672.8, 691.6, 710.0,
        728.6, 746.8, 765.2, 783.7, 802.2, 820.3, 838.8, 857.2, 875.8, 894.3,
    )
    private val flatRunTps = doubleArrayOf(
        7.992, 7.979, 7.972, 7.762, 7.993, 7.947, 7.989, 7.946, 7.979, 8.041,
        8.059, 7.942, 7.958, 11.399, 9.326, 8.057, 8.096, 7.887, 7.891, 7.976,
        8.110, 7.963, 8.007, 8.024, 8.048, 7.957, 7.986, 7.943, 8.059, 8.086,
        8.029, 7.880, 8.083, 7.807, 7.982, 8.041, 7.939, 7.810, 7.993, 7.898,
        8.100, 7.948, 7.952, 7.943, 8.154, 7.947, 7.982, 7.887, 7.941, 7.833,
    )

    @Test
    fun `a run that never decayed is not dressed up as a decay`() {
        // Not flat enough for a flat fit (the outliers put CV near 6.5%), and no
        // decay in it either, so the honest answer is no fit.
        assertThat(ExponentialDecayFitter.fit(flatRunTimes, flatRunTps)).isNull()
    }

    @Test
    fun `the same run without its outliers is reported as flat`() {
        val keep = flatRunTps.indices.filter { flatRunTps[it] < 9.0 }
        val fit = ExponentialDecayFitter.fit(
            DoubleArray(keep.size) { flatRunTimes[keep[it]] },
            DoubleArray(keep.size) { flatRunTps[keep[it]] },
        )
        requireNotNull(fit)
        assertThat(fit.amplitude).isEqualTo(0.0)
        assertThat(fit.asymptote).isWithin(0.05).of(7.98)
    }

    @Test
    fun `a genuine decay is still fitted`() {
        // tps(t) = 8 + 12 exp(-t / 200 s), sampled like the run above.
        val values = DoubleArray(flatRunTimes.size) { 8.0 + 12.0 * exp(-flatRunTimes[it] / 200.0) }
        val fit = requireNotNull(ExponentialDecayFitter.fit(flatRunTimes, values))
        assertThat(fit.asymptote).isWithin(0.01).of(8.0)
        assertThat(fit.amplitude).isWithin(0.01).of(12.0)
        assertThat(fit.timeConstantSeconds).isWithin(0.5).of(200.0)
        assertThat(fit.rSquared).isGreaterThan(0.99)
    }
}
