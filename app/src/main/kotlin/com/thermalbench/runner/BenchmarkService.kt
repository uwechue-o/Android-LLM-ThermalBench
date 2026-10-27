package com.thermalbench.runner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.thermalbench.MainActivity
import com.thermalbench.R
import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.BenchmarkSpec
import com.thermalbench.domain.model.DeviceConditions
import com.thermalbench.domain.model.PreconditionState
import com.thermalbench.domain.model.RunProgress
import com.thermalbench.domain.model.ScreenPolicy
import com.thermalbench.domain.model.SpecId
import com.thermalbench.domain.port.DeviceConditionSource
import com.thermalbench.domain.port.ResultRepository
import com.thermalbench.domain.port.SpecRepository
import com.thermalbench.domain.usecase.AwaitPreconditionsUseCase
import com.thermalbench.domain.usecase.RunContext
import com.thermalbench.domain.usecase.RunSustainedBenchmarkUseCase
import com.thermalbench.persistence.RunJournal
import com.thermalbench.persistence.settings.SettingsStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import javax.inject.Inject

/**
 * The in-app runner (GUIDE.md §4.2).
 *
 * A foreground service, not a coroutine in a ViewModel and not WorkManager.
 * Runs last tens of minutes and must survive Doze and screen-off; WorkManager
 * may defer or reschedule work based on system conditions, which is precisely
 * the confound this tool exists to measure.
 *
 * The service is handed only [SpecId]s and re-resolves everything else from
 * persistence, so restarting it is idempotent.
 */
@AndroidEntryPoint
class BenchmarkService : Service() {

    @Inject lateinit var specs: SpecRepository
    @Inject lateinit var results: ResultRepository
    @Inject lateinit var awaitPreconditions: AwaitPreconditionsUseCase
    @Inject lateinit var runBenchmark: RunSustainedBenchmarkUseCase
    @Inject lateinit var conditions: DeviceConditionSource
    @Inject lateinit var journal: RunJournal
    @Inject lateinit var settings: SettingsStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val cues by lazy { SpokenCues(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ABORT -> {
                runJob?.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val specIds = intent.getStringArrayExtra(EXTRA_SPEC_IDS)?.filter { it.isNotBlank() }.orEmpty()
                if (specIds.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWith(getString(R.string.run_notification_preparing))
                if (runJob?.isActive != true) runJob = scope.launch { execute(specIds.map(::SpecId)) }
            }
            else -> stopSelf()
        }
        // NOT_STICKY: a run the system killed is a run whose thermal history has
        // a hole in it. Restarting it silently would produce a document that
        // claims to be one continuous measurement when it is two.
        return START_NOT_STICKY
    }

    /**
     * Runs [specIds] in order. Each run waits at its own precondition gate, so
     * the second run of a comparison starts only once the device has cooled
     * back to the state the first one started from. Without that, whichever
     * ran second would inherit the first one's heat and lose by default.
     */
    private suspend fun execute(specIds: List<SpecId>) {
        // Reported with any early end, so the screen claims saved iterations
        // only when there are some.
        var completedIterations = 0
        val finished = ArrayList<BenchmarkResult>(specIds.size)
        try {
            val liveCharts = settings.settings.first().liveChartsEnabled
            acquireWakeLock()
            for ((index, specId) in specIds.withIndex()) {
                val position = if (specIds.size > 1) SeriesPosition(index, specIds.size) else null
                RunState.series.value = position
                completedIterations = 0
                finished += runOne(specId, position, liveCharts) { completedIterations += 1 }
                if (position != null && index < specIds.lastIndex) {
                    // Between runs, so it cannot disturb either measurement.
                    cues.sayAndWait("ThermalBench: ${position.label.lowercase()} finished. Cooling down for the next.")
                }
            }
            if (specIds.size > 1) {
                RunState.phase.value = RunPhase.SeriesFinished(finished.toList())
                cues.sayAndWait("ThermalBench: comparison finished, ${finished.size} runs.")
            } else {
                RunState.phase.value = RunPhase.Finished(finished.single())
                // After the last iteration, so it cannot disturb the measurement.
                cues.sayAndWait(finishedAnnouncement(finished.single()))
            }
        } catch (e: CancellationException) {
            // Cancel is an instruction, not a failure. Caught before the general
            // handler below, which would otherwise report it as an ERROR.
            Log.i(TAG, "run cancelled after $completedIterations iteration(s)")
            RunState.phase.value = RunPhase.Cancelled(completedIterations)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "run failed", e)
            RunState.phase.value = RunPhase.Failed(e.message ?: e::class.java.simpleName, completedIterations)
            cues.sayAndWait("ThermalBench: the run stopped with an error.")
        } finally {
            RunState.series.value = null
            RunState.screenPolicy.value = null
            cues.release()
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** One spec: its precondition gate, then the run. Returns the saved result. */
    private suspend fun runOne(
        specId: SpecId,
        position: SeriesPosition?,
        liveCharts: Boolean,
        onIteration: () -> Unit,
    ): BenchmarkResult {
        val spec = specs.load(specId) ?: error("Spec $specId not found")
        // Before the gate, which itself waits for the screen to be as the spec says.
        RunState.screenPolicy.value = spec.preconditions.screenPolicy
        // "Run 2 of 3 · " ahead of the notification text during a comparison.
        val prefix = position?.let { "${it.label} · " }.orEmpty()
        val gate = awaitGate(spec, prefix)

        journal.reset()
        val context = RunContext(
            preconditionsSatisfied = gate.satisfied,
            unmetAtStart = gate.unmet,
            conditionsAtStart = gate.startConditions,
            wakeLockHeld = wakeLock?.isHeld == true,
            screenPolicy = spec.preconditions.screenPolicy,
            executionPath = EXECUTION_PATH,
            liveChartEnabled = liveCharts,
        )

        var result: BenchmarkResult? = null
        runBenchmark(spec, context).collect { progress ->
            events.emit(progress)
            when (progress) {
                is RunProgress.IterationCompleted -> {
                    journal.record(RunJournal.IN_FLIGHT, progress.sample)
                    onIteration()
                    val text = getString(R.string.run_notification_running, progress.sample.index + 1, progress.total)
                    updateNotification(prefix + text)
                    RunState.phase.value = RunPhase.Running(progress)
                }
                is RunProgress.Finished -> {
                    journal.flush(RunJournal.IN_FLIGHT)
                    results.save(progress.result)
                    journal.discardPersisted()
                    result = progress.result
                }
                else -> RunState.phase.value = RunPhase.Running(progress)
            }
        }
        return requireNotNull(result) { "Run of $specId produced no result" }
    }

    private class Gate(val satisfied: Boolean, val unmet: List<String>, val startConditions: DeviceConditions)

    /** Waits at the precondition gate, narrating the cool-down; the speech engine is shut down after. */
    private suspend fun awaitGate(spec: BenchmarkSpec, prefix: String): Gate {
        var satisfied = false
        var unmet = emptyList<String>()
        var startConditions = conditions.snapshot()
        val cooldown = CooldownTracker(spec.preconditions.minThermalHeadroom)

        awaitPreconditions(spec.preconditions).collect { state ->
            RunState.phase.value = RunPhase.AwaitingPreconditions(state)
            when (state) {
                is PreconditionState.Satisfied -> {
                    satisfied = true
                    startConditions = state.at
                }
                is PreconditionState.TimedOut -> {
                    satisfied = false
                    unmet = state.unmet.map { it.description }
                    startConditions = state.latest
                }
                is PreconditionState.Waiting -> {
                    val update = cooldown.onWaiting(state)
                    updateNotification(prefix + update.text, update.percent)
                    update.announcement?.let(cues::say)
                }
            }
        }
        // Silenced and shut down before the first iteration: nothing of the
        // speech engine should be running while the device is measured.
        cues.release()

        if (!satisfied) {
            // Recorded, never silently proceeded past: the result document
            // will carry PRECONDITIONS_UNMET.
            Log.w(TAG, "starting with unmet preconditions: ${unmet.joinToString()}")
        }
        return Gate(satisfied, unmet, startConditions)
    }

    /**
     * Held for the whole run and released in `finally`. It affects power draw,
     * so [RunContext.wakeLockHeld] records that it was held and the result
     * document reports it.
     */
    private fun acquireWakeLock() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startForegroundWith(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    /** What the operator hears when the run is over; the headline number if there is one. */
    private fun finishedAnnouncement(result: BenchmarkResult): String = buildString {
        append("ThermalBench: run finished.")
        result.profile?.retentionRatio?.let { append(" Retained ${(it * PERCENT).roundToInt()} percent.") }
    }

    /** [percent], when known, draws the cool-down as a progress bar. */
    private fun updateNotification(text: String, percent: Int? = null) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, percent))
    }

    private fun buildNotification(text: String, percent: Int? = null): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val abort = PendingIntent.getService(
            this,
            1,
            Intent(this, BenchmarkService::class.java).setAction(ACTION_ABORT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.run_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            // Nothing private in it, and the lock screen is where it gets read.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply { if (percent != null) setProgress(PERCENT, percent, false) }
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.abort), abort)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.run_notification_channel),
            // LOW: a benchmark that buzzes the phone every iteration is a
            // benchmark that changes what it measures.
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        val events = MutableSharedFlow<RunProgress>(replay = 0, extraBufferCapacity = 256)

        const val ACTION_START = "com.thermalbench.action.START"
        const val ACTION_ABORT = "com.thermalbench.action.ABORT"
        const val EXTRA_SPEC_IDS = "specIds"
        private const val PERCENT = 100

        private const val TAG = "ThermalBench"
        private const val CHANNEL_ID = "thermalbench_run"
        private const val NOTIFICATION_ID = 1001
        private const val EXECUTION_PATH = "app"
        private const val WAKE_LOCK_TAG = "ThermalBench::run"
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 4L * 60L * 60L * 1000L

        fun start(context: Context, specIds: List<SpecId>) {
            val intent = Intent(context, BenchmarkService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SPEC_IDS, specIds.map { it.value }.toTypedArray())
            context.startForegroundService(intent)
        }

        fun abort(context: Context) {
            val intent = Intent(context, BenchmarkService::class.java).setAction(ACTION_ABORT)
            context.startService(intent)
        }
    }
}
