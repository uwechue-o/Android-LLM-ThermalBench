package com.thermalbench.ui.screen.run

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thermalbench.ui.BenchIntent
import com.thermalbench.ui.BenchUiState
import com.thermalbench.ui.chart.ChartPlaceholder
import com.thermalbench.ui.chart.DecayChart
import com.thermalbench.ui.chart.ThermalStrip
import com.thermalbench.ui.common.MeasurementTile
import com.thermalbench.ui.common.ThermalBadge

/**
 * The live run. Charts are the largest observer effect in the app path
 * (GUIDE.md §5.3), so they can be switched off from this screen while a run is
 * in progress, and the result document records which mode produced the numbers.
 */
@Composable
fun LiveRunScreen(
    state: BenchUiState.Running,
    onIntent: (BenchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Iteration ${state.iteration} of ${state.total}",
                style = MaterialTheme.typography.titleMedium,
            )
            ThermalBadge(state.currentThermal)
        }

        LinearProgressIndicator(
            progress = { state.iteration.toFloat() / state.total.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MeasurementTile(
                label = "Decode",
                value = String.format("%.1f", state.liveDecodeTps),
                unit = "tok/s",
                modifier = Modifier.weight(1f),
            )
            MeasurementTile(
                label = "Battery",
                value = state.batteryPercent.toString(),
                unit = "%",
                modifier = Modifier.weight(1f),
            )
        }

        if (state.throttled) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Throttling: the device is now limiting performance. " +
                        "This is the effect being measured, not a fault.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Text("Throughput over time", style = MaterialTheme.typography.titleSmall)
        if (state.chartsEnabled) {
            DecayChart(series = state.decaySeries)
            ThermalStrip(series = state.thermalSeries)
        } else {
            ChartPlaceholder("Charts off — minimal UI reduces this app's own thermal load")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Live charts", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.chartsEnabled,
                onCheckedChange = { onIntent(BenchIntent.SetLiveCharts(it)) },
            )
        }

        OutlinedButton(onClick = { onIntent(BenchIntent.Abort) }) { Text("Abort run") }
    }
}

@Composable
fun WarmupScreen(state: BenchUiState.Warmup, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Warmup", style = MaterialTheme.typography.titleLarge)
        Text(
            "Iteration ${state.iteration} of ${state.total}. Warmup iterations are " +
                "discarded from the analysis and still recorded in the document.",
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}
