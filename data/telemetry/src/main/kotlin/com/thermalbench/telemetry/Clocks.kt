package com.thermalbench.telemetry

import android.os.SystemClock
import com.thermalbench.domain.port.MonotonicClock
import com.thermalbench.domain.port.WallClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `elapsedRealtimeNanos` keeps counting through deep sleep, unlike
 * `System.nanoTime()` on some kernels, so it is the right base for runs that
 * last tens of minutes with the screen off.
 */
@Singleton
class ElapsedRealtimeClock @Inject constructor() : MonotonicClock {
    override fun nanos(): Long = SystemClock.elapsedRealtimeNanos()
}

/** Read exactly once per run, for the document's label timestamp. */
@Singleton
class SystemWallClock @Inject constructor() : WallClock {
    override fun epochMillis(): Long = System.currentTimeMillis()
}
