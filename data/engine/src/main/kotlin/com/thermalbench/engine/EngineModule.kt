package com.thermalbench.engine

import com.thermalbench.analysis.DefaultDegradationAnalyzer
import com.thermalbench.domain.model.ResultId
import com.thermalbench.domain.port.BackendRegistry
import com.thermalbench.domain.port.DegradationAnalyzer
import com.thermalbench.domain.port.DeviceConditionSource
import com.thermalbench.domain.port.DeviceProfiler
import com.thermalbench.domain.port.FrequencySource
import com.thermalbench.domain.port.MemorySource
import com.thermalbench.domain.port.MonotonicClock
import com.thermalbench.domain.port.PowerTelemetrySource
import com.thermalbench.domain.port.ThermalTelemetrySource
import com.thermalbench.domain.port.WallClock
import com.thermalbench.domain.usecase.AnalyzeDegradationUseCase
import com.thermalbench.domain.usecase.AwaitPreconditionsUseCase
import com.thermalbench.domain.usecase.RunSustainedBenchmarkUseCase
import com.thermalbench.domain.usecase.ValidateSpecUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import java.util.UUID
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class TelemetryDispatcher

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class InferenceDispatcher

/**
 * Builds the domain use cases from the ports.
 *
 * The use cases are constructed here rather than annotated with `@Inject`
 * because core/domain is pure Kotlin and must not take a dependency on a DI
 * framework, not even on the annotations (GUIDE.md §2).
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides
    @Singleton
    fun analyzer(): DegradationAnalyzer = DefaultDegradationAnalyzer()

    @Provides
    fun validateSpec(registry: BackendRegistry): ValidateSpecUseCase = ValidateSpecUseCase(registry)

    @Provides
    fun awaitPreconditions(
        conditions: DeviceConditionSource,
        clock: MonotonicClock,
    ): AwaitPreconditionsUseCase = AwaitPreconditionsUseCase(conditions, clock)

    @Provides
    fun analyzeDegradation(analyzer: DegradationAnalyzer): AnalyzeDegradationUseCase =
        AnalyzeDegradationUseCase(analyzer)

    /**
     * Telemetry sampling and inference get their own dispatchers so a long
     * generation call can never starve the thermal time series (GUIDE.md §4.3).
     */
    @Provides
    @Singleton
    fun runSustainedBenchmark(
        backends: BackendRegistry,
        thermal: ThermalTelemetrySource,
        power: PowerTelemetrySource,
        frequency: FrequencySource,
        memory: MemorySource,
        conditions: DeviceConditionSource,
        profiler: DeviceProfiler,
        analyzer: DegradationAnalyzer,
        clock: MonotonicClock,
        wallClock: WallClock,
        @TelemetryDispatcher telemetryDispatcher: CoroutineDispatcher,
        @InferenceDispatcher inferenceDispatcher: CoroutineDispatcher,
    ): RunSustainedBenchmarkUseCase = RunSustainedBenchmarkUseCase(
        backends = backends,
        thermal = thermal,
        power = power,
        frequency = frequency,
        memory = memory,
        conditions = conditions,
        profiler = profiler,
        analyzer = analyzer,
        clock = clock,
        wallClock = wallClock,
        newResultId = { ResultId(UUID.randomUUID().toString().replace("-", "").take(RESULT_ID_LENGTH)) },
        telemetryDispatcher = telemetryDispatcher,
        inferenceDispatcher = inferenceDispatcher,
    )

    private const val RESULT_ID_LENGTH = 24
}
