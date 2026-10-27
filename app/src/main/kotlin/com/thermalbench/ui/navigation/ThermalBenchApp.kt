package com.thermalbench.ui.navigation

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermalbench.domain.model.ResultSummary
import com.thermalbench.domain.model.ScreenPolicy
import com.thermalbench.ui.BenchIntent
import com.thermalbench.ui.BenchUiState
import com.thermalbench.ui.BenchViewModel
import com.thermalbench.ui.common.KeyValueRow
import com.thermalbench.ui.common.formatPercentOrDash
import com.thermalbench.ui.screen.compare.SeriesResultScreen
import com.thermalbench.ui.screen.licences.LicencesScreen
import com.thermalbench.ui.screen.composer.SpecComposerScreen
import com.thermalbench.ui.screen.gate.PreconditionGateScreen
import com.thermalbench.ui.screen.result.ResultDetailScreen
import com.thermalbench.ui.screen.run.LiveRunScreen
import com.thermalbench.ui.screen.run.WarmupScreen

/**
 * The console is state-driven rather than route-driven: which screen is shown
 * follows from what the run is doing, so the UI cannot drift out of step with
 * the service that owns the measurement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThermalBenchApp(viewModel: BenchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val series by viewModel.series.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.shares.collect { chooser -> context.startActivity(chooser) }
    }
    val screenPolicy by viewModel.screenPolicy.collectAsStateWithLifecycle()
    if (screenPolicy == ScreenPolicy.ON_DIMMED) KeepScreenOnDimmed()
    // "Run 2 of 3" in the title while a comparison is under way.
    val title = listOfNotNull(titleFor(state), series?.label.takeIf { state.isRunPhase() }).joinToString(" · ")

    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                BenchUiState.Idle -> HomeScreen(
                    history = history,
                    onIntent = viewModel::onIntent,
                )
                BenchUiState.Licences -> LicencesScreen(viewModel::onIntent)
                is BenchUiState.Composing -> SpecComposerScreen(current, viewModel::onIntent)
                is BenchUiState.Preconditioning -> PreconditionGateScreen(
                    current,
                    viewModel::onIntent,
                    screenOn = screenPolicy == ScreenPolicy.ON_DIMMED,
                )
                is BenchUiState.Warmup -> WarmupScreen(current)
                is BenchUiState.Running -> LiveRunScreen(current, viewModel::onIntent)
                is BenchUiState.Complete -> ResultDetailScreen(current, viewModel::onIntent)
                is BenchUiState.SeriesComplete -> SeriesResultScreen(current.runs, viewModel::onIntent)
                is BenchUiState.Aborted -> AbortedScreen(current, viewModel::onIntent)
            }
        }
    }
}

private fun titleFor(state: BenchUiState): String = when (state) {
    BenchUiState.Idle -> "ThermalBench"
    BenchUiState.Licences -> "Open-source licences"
    is BenchUiState.Composing -> "New run"
    is BenchUiState.Preconditioning -> "Preconditions"
    is BenchUiState.Warmup -> "Warmup"
    is BenchUiState.Running -> "Running"
    is BenchUiState.Complete -> "Result"
    is BenchUiState.SeriesComplete -> "Comparison"
    is BenchUiState.Aborted -> if (state.cancelled) "Run cancelled" else "Run aborted"
}

/**
 * Holds the screen on at minimum brightness for as long as it is composed: a
 * screen-on run must not time out into a screen-off one halfway through, and
 * a bright display would add heat the run is not meant to measure.
 */
@Composable
private fun KeepScreenOnDimmed() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        view.keepScreenOn = true
        window?.attributes = window?.attributes?.apply { screenBrightness = MIN_BRIGHTNESS }
        onDispose {
            view.keepScreenOn = false
            window?.attributes = window?.attributes?.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
}

// Not 0: some devices treat zero as "screen off".
private const val MIN_BRIGHTNESS = 0.01f

private fun BenchUiState.isRunPhase(): Boolean =
    this is BenchUiState.Preconditioning || this is BenchUiState.Warmup || this is BenchUiState.Running

@Composable
private fun HomeScreen(
    history: List<ResultSummary>,
    onIntent: (BenchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Measures how much inference throughput a device keeps under sustained load, " +
                "not how fast its first iteration is.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onIntent(BenchIntent.Compose) }) { Text("New run") }
            TextButton(onClick = { onIntent(BenchIntent.ShowLicences) }) { Text("Open-source licences") }
        }

        Text("History", style = MaterialTheme.typography.titleMedium)
        if (history.isEmpty()) {
            Text("No runs yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history) { summary ->
                    HistoryCard(summary, onOpen = { onIntent(BenchIntent.OpenResult(summary.resultId)) })
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(summary: ResultSummary, onOpen: () -> Unit) {
    // Opens the full result, with its graph and its report.
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                summary.label.ifBlank { summary.modelName },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "${summary.deviceModel} · ${summary.backendId.value} · ${summary.delegate.name}",
                style = MaterialTheme.typography.bodySmall,
            )
            KeyValueRow("Retained", summary.retentionRatio.formatPercentOrDash())
            KeyValueRow("Iterations", summary.measuredIterations.toString())
            KeyValueRow("Outcome", summary.validity?.name ?: summary.terminated.name)
        }
    }
}

@Composable
private fun AbortedScreen(
    state: BenchUiState.Aborted,
    onIntent: (BenchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (state.cancelled) "You cancelled the run" else "Run did not complete",
            style = MaterialTheme.typography.titleLarge,
        )
        if (!state.cancelled) {
            Text("Reason: ${state.reason.name}", style = MaterialTheme.typography.bodyMedium)
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        // Only claim saved iterations when there are some: a run cancelled at
        // the precondition gate measured nothing.
        val saved = state.completedIterations
        Text(
            when {
                saved == null ->
                    "Iterations completed before this point were written to the database and are not lost."
                saved == 0 -> "It ended before any iteration ran, so nothing was measured."
                else -> "The $saved iteration(s) completed before this point were written to the database " +
                    "and are not lost."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { onIntent(BenchIntent.Dismiss) }) { Text("Back") }
    }
}
