package com.thermalbench.runner

import android.content.Context
import com.thermalbench.domain.model.RunProgress
import com.thermalbench.domain.model.SpecId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** [RunController] backed by [BenchmarkService]. */
@Singleton
class ServiceRunController @Inject constructor(
    @ApplicationContext private val context: Context,
) : RunController {

    override val phase: StateFlow<RunPhase> = RunState.phase

    override val progress: SharedFlow<RunProgress> = BenchmarkService.events

    override val series: StateFlow<SeriesPosition?> = RunState.series

    override fun startSeries(specIds: List<SpecId>) = BenchmarkService.start(context, specIds)

    override fun abort() = BenchmarkService.abort(context)
}
