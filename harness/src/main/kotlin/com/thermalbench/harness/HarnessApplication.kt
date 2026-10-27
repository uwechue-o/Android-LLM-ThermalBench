package com.thermalbench.harness

import android.app.Application
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

/**
 * The harness process. It has no activity and no UI: `am instrument` starts it,
 * the benchmark runs, a document is written, the process exits.
 */
@HiltAndroidApp
class HarnessApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // As early as the process allows: OnePlus's freezer has frozen the
        // harness within a second of launch, while JUnit was still scanning
        // for tests. The process exits with the run, taking the service along.
        RunForegroundService.start(this)
    }
}

/**
 * Instrumentation entry points are not constructed by Hilt, so the runner is
 * pulled from the singleton component by hand.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface HarnessEntryPoint {
    fun headlessRunner(): HeadlessRunner
}
