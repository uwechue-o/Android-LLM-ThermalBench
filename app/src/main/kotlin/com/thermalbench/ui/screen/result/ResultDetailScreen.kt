package com.thermalbench.ui.screen.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.thermalbench.report.DocumentKind
import com.thermalbench.ui.BenchIntent
import com.thermalbench.ui.BenchUiState
import com.thermalbench.ui.PerformanceSeries
import com.thermalbench.ui.chart.ChartPlaceholder
import com.thermalbench.ui.chart.DecayChart
import com.thermalbench.ui.chart.DecayLabels
import com.thermalbench.ui.chart.TemperatureChart
import com.thermalbench.ui.chart.ThermalStrip
import com.thermalbench.ui.TelemetrySummary
import com.thermalbench.ui.common.KeyValueRow
import com.thermalbench.ui.common.MeasurementTile
import com.thermalbench.ui.common.ValidityBanner
import com.thermalbench.ui.common.formatOrDash
import com.thermalbench.ui.common.formatPercentOrDash

/**
 * The result. Built around the retention ratio, which is the number the whole
 * project is about: what fraction of its peak throughput a device can actually
 * hold under sustained load.
 */
@Composable
fun ResultDetailScreen(
    state: BenchUiState.Complete,
    onIntent: (BenchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile
    val telemetry = state.telemetry
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ValidityBanner(profile.validity)
        // The banner says what the data supports; this says why there is so
        // little of it. Without it, a run whose runtime hung read as merely short.
        state.errorMessage?.let { RunErrorCard(it, profile.measuredIterationCount) }

        MeasurementTile(
            label = "Sustained throughput retained",
            value = profile.retentionRatio.formatPercentOrDash(),
            caption = "Steady state divided by peak. The headline number.",
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MeasurementTile(
                label = "Peak",
                value = profile.peakDecodeTokensPerSecond.formatOrDash(1),
                unit = "tok/s",
                caption = "Best sustained window",
                modifier = Modifier.weight(1f),
            )
            MeasurementTile(
                label = "Steady state",
                value = profile.steadyStateTokensPerSecond.formatOrDash(1),
                unit = "tok/s",
                caption = if (profile.steadyStateTokensPerSecond == null) "Never stabilized" else "Floor",
                modifier = Modifier.weight(1f),
            )
        }

        state.performance?.let { PerformanceCard(it) }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Throttling", style = MaterialTheme.typography.titleSmall)
                KeyValueRow(
                    "Iterations to first throttle",
                    profile.iterationsToFirstThrottle?.toString() ?: "never throttled",
                )
                KeyValueRow(
                    "Time to first throttle",
                    profile.timeToFirstThrottle?.toString() ?: "—",
                )
                KeyValueRow("Throttle events", profile.throttleEvents.size.toString())
                profile.throttleEvents.take(MAX_LISTED_EVENTS).forEach { event ->
                    KeyValueRow(
                        "  iteration ${event.iterationIndex ?: "—"}",
                        "${event.from} → ${event.to}",
                    )
                }
            }
        }

        telemetry?.let { TelemetryCard(it) }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Decay model", style = MaterialTheme.typography.titleSmall)
                val fit = profile.decayModel
                if (fit == null) {
                    Text(
                        "No exponential decay could be fitted. Reported as absent rather " +
                            "than approximated.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    KeyValueRow("Asymptote", fit.asymptote.formatOrDash(2))
                    KeyValueRow("Amplitude", fit.amplitude.formatOrDash(2))
                    KeyValueRow("Time constant", "${fit.timeConstantSeconds.formatOrDash(1)} s")
                    KeyValueRow("R²", fit.rSquared.formatOrDash(3))
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Run", style = MaterialTheme.typography.titleSmall)
                KeyValueRow("Measured iterations", profile.measuredIterationCount.toString())
                KeyValueRow("Mean decode", "${profile.meanDecodeTokensPerSecond.formatOrDash(1)} tok/s")
                KeyValueRow("Minimum decode", "${profile.minDecodeTokensPerSecond.formatOrDash(1)} tok/s")
                KeyValueRow(
                    "Time-to-first-token drift",
                    "${profile.ttftDriftMicrosPerIteration.formatOrDash(1)} µs/iteration",
                )
                KeyValueRow("Terminated", profile.terminated.name)
                KeyValueRow("Battery drain", profile.batteryDrainPercent?.let { "$it%" } ?: "—")
                KeyValueRow(
                    "Battery temperature rise",
                    profile.batteryTemperatureRiseDeciC?.let { "${it / 10.0} °C" } ?: "—",
                )
            }
        }

        SaveButtons(state, onIntent)
        OutlinedButton(onClick = { onIntent(BenchIntent.Dismiss) }) { Text("Done") }
    }
}

/**
 * Decode throughput over the whole session, warm-up shaded and throttle onsets
 * marked, with the platform thermal status underneath on the same time axis.
 */
@Composable
private fun PerformanceCard(series: PerformanceSeries) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Performance over time", style = MaterialTheme.typography.titleSmall)
            if (series.isEmpty) {
                ChartPlaceholder("Too few iterations to draw.")
                return@Column
            }
            val maxMinutes = series.decode.maxOf { it.x }
            // DecayChart draws from zero to 10% above the fastest iteration.
            val axisTop = series.decode.maxOf { it.y } * AXIS_HEADROOM
            Text(
                "Decode tok/s, axis 0 to ${"%.0f".format(java.util.Locale.US, axisTop)} · " +
                    "time 0 to ${"%.1f".format(java.util.Locale.US, maxMinutes)} min",
                style = MaterialTheme.typography.bodySmall,
            )
            DecayChart(
                series = series.decode,
                throttleMarkers = series.throttleMinutes,
                labels = series.labels(),
            )
            ThermalStrip(series.thermal)
            Text(
                listOfNotNull(
                    series.warmupEndMinutes?.let { "Warm-up ends at ${"%.1f".format(java.util.Locale.US, it)} min." },
                    "Red lines mark thermal throttling.".takeIf { series.throttleMinutes.isNotEmpty() },
                    "The strip shows the platform's thermal status.",
                ).joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            TemperatureChart(
                series = listOf(MaterialTheme.colorScheme.primary to series.battery),
                minX = series.decode.first().x,
                maxX = series.decode.last().x,
            )
            series.closing?.batteryRiseCelsius?.let { rise ->
                Text(
                    "Battery ${"%+.1f".format(java.util.Locale.US, rise)} °C over the measured iterations.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** The peak, where the run ended up and the first throttle, as words on the chart. */
private fun PerformanceSeries.labels(): DecayLabels {
    val closing = closing
    return DecayLabels(
        peakText = peak?.let { "Peak ${"%.1f".format(java.util.Locale.US, it.y)} tok/s" },
        peak = peak,
        endText = closing?.closingTokensPerSecond?.let { mean ->
            val share = closing.closingShareOfPeak?.let { " (${"%.0f".format(java.util.Locale.US, it * 100)}% of peak)" }.orEmpty()
            "Last ${closing.closingWindow}: ${"%.1f".format(java.util.Locale.US, mean)} tok/s$share"
        },
        throttleText = firstThrottleStatus?.let { "Throttled: ${it.name}" },
    )
}

/**
 * Save as a one-page PDF report, the canonical JSON document or the CSV
 * table. Each opens the system save dialog, so the file can go to Downloads
 * or anywhere else without the app holding a storage permission. The chart
 * image goes to the share sheet instead, since it is usually headed for a
 * post or a message rather than a folder.
 */
@Composable
private fun SaveButtons(state: BenchUiState.Complete, onIntent: (BenchIntent) -> Unit) {
    val launchers = DocumentKind.entries.associateWith { kind ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(kind.mimeType)) { uri ->
            if (uri != null) onIntent(BenchIntent.SaveDocument(state.resultId, kind, uri))
        }
    }
    fun save(kind: DocumentKind) =
        launchers.getValue(kind).launch(state.suggestedNames[kind] ?: "thermalbench.${kind.extension}")

    Button(onClick = { save(DocumentKind.PDF) }, modifier = Modifier.fillMaxWidth()) { Text("Save PDF report") }
    OutlinedButton(
        onClick = { onIntent(BenchIntent.ShareChart(listOf(state.resultId))) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Share chart image") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { save(DocumentKind.JSON) }) { Text("Save JSON") }
        OutlinedButton(onClick = { save(DocumentKind.CSV) }) { Text("Save CSV") }
    }
    state.saveMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/**
 * What the device did while throughput changed: the sensors that heated most
 * and whether the GPU or the CPU was clocked down. A throughput loss with a
 * falling clock is throttling; one without points somewhere else.
 */
@Composable
private fun TelemetryCard(telemetry: TelemetrySummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Temperatures and clocks", style = MaterialTheme.typography.titleSmall)
            telemetry.computeDevice?.let { KeyValueRow("Ran on", it) }
            telemetry.clocks.forEach { clock ->
                KeyValueRow(
                    clock.label,
                    "${megahertz(clock.startKhz)} → ${megahertz(clock.endKhz)} MHz (low ${megahertz(clock.minKhz)})",
                )
            }
            if (!telemetry.gpuClockReadable) KeyValueRow("GPU", "clock not readable on this device")
            if (telemetry.sensors.isEmpty()) {
                Text(
                    if (telemetry.sensorsReadable) {
                        "No sensor readings were recorded during the measured iterations."
                    } else {
                        "This device does not let apps read its temperature sensors."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Text(
                    "Largest rises, start → end of the measured iterations",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                telemetry.sensors.forEach { sensor ->
                    KeyValueRow(
                        sensor.name,
                        "${celsius(sensor.startCelsius)} → ${celsius(sensor.endCelsius)} °C " +
                            "(peak ${celsius(sensor.peakCelsius)})",
                    )
                }
            }
        }
    }
}

private fun megahertz(khz: Long): String = (khz / KHZ_PER_MHZ).toString()

private fun celsius(value: Float): String = String.format(java.util.Locale.US, "%.1f", value)

private const val KHZ_PER_MHZ = 1000L
private const val AXIS_HEADROOM = 1.1f
private const val MAX_LISTED_EVENTS = 5

/** Why the run ended early, in the runtime's or the engine's own words. */
@Composable
private fun RunErrorCard(message: String, measuredIterations: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("The run ended with an error", style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodySmall)
            if (measuredIterations == 0) {
                Text(
                    "No measured iteration finished, so there is nothing to chart.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
