package com.thermalbench.ui.screen.gate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thermalbench.domain.model.formatThermalMargin
import com.thermalbench.ui.BenchIntent
import com.thermalbench.ui.BenchUiState
import com.thermalbench.ui.common.KeyValueRow
import com.thermalbench.ui.common.ThermalBadge

/**
 * A live view of what is blocking the run and how close the device is to ready.
 *
 * This screen exists because preconditions are part of the measurement. Telling
 * an operator "unplug the phone and wait for it to cool" is the difference
 * between a comparable run and an hour of device time spent on a number nobody
 * can use.
 */
@Composable
fun PreconditionGateScreen(
    state: BenchUiState.Preconditioning,
    onIntent: (BenchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Waiting for a stable device", style = MaterialTheme.typography.titleLarge)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ThermalBadge(state.currentThermal)
                // The margin, formatted as the blocking message, the notification
                // and the spoken readout all format it (rounded down), so none of
                // them disagree about one reading. The platform's raw value runs
                // the other way (1.0 = throttling).
                KeyValueRow("Thermal headroom margin", state.headroom?.let { formatThermalMargin(1f - it) } ?: "—")
                KeyValueRow("Battery", "${state.batteryPercent}%")
            }
        }

        if (state.unmet.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Conditions holding", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Stable for ${state.elapsedInWindow}. " +
                            (state.estimatedReady?.let { "Starting in about $it." } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Blocking the run",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.unmet.forEach { condition ->
                        Text("• ${condition.description}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "The stabilization window restarts whenever a condition fails again.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        // Turning the screen on to check restarts the window, so say up front
        // that there is no need to.
        Text(
            "You can turn the screen off and listen instead. While the phone cools, it reads out the " +
                "headroom margin every 20 seconds, says when it is halfway and when it is cool enough, " +
                "and announces the end of the run. It stays quiet while measuring. Media volume " +
                "controls it.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedButton(onClick = { onIntent(BenchIntent.Abort) }) { Text("Cancel") }
    }
}
