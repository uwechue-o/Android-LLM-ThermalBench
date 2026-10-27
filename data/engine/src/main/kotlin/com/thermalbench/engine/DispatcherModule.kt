package com.thermalbench.engine

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    /**
     * A single dedicated thread for telemetry. Sharing `Dispatchers.Default`
     * with inference would let a long generation call delay a thermal sample
     * and put a gap in exactly the series being measured.
     */
    @Provides
    @Singleton
    @TelemetryDispatcher
    fun telemetryDispatcher(): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "thermalbench-telemetry").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    /**
     * Inference gets its own single thread too, so the workload under
     * measurement is not competing with the pool that runs everything else.
     */
    @Provides
    @Singleton
    @InferenceDispatcher
    fun inferenceDispatcher(): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "thermalbench-inference").apply { isDaemon = true }
        }.asCoroutineDispatcher()
}
