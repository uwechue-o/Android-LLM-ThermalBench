package com.thermalbench.ui.screen.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thermalbench.domain.model.ValidityVerdict
import com.thermalbench.ui.BenchIntent
import com.thermalbench.ui.SeriesRun
import com.thermalbench.ui.chart.ComparisonChart
import com.thermalbench.ui.chart.SeriesPalette
import com.thermalbench.ui.chart.TemperatureChart
import com.thermalbench.ui.common.KeyValueRow
import com.thermalbench.ui.common.formatOrDash
import com.thermalbench.ui.common.formatPercentOrDash
import java.util.Locale

/**
 * A finished comparison: every run's decay curve on one chart, then each
 * run's numbers. Runs are listed in the order they ran. A run that is not
 * valid keeps its place and says so, because "this configuration could not
 * produce a comparable run" is itself a result.
 */
@Composable
fun SeriesResultScreen(
    runs: List<SeriesRun>,
    onIntent: (BenchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = runs.indices.map { SeriesPalette[it % SeriesPalette.size] }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Decode throughput over each run",
            style = MaterialTheme.typography.titleMedium,
        )
        ComparisonChart(
            series = colors.zip(runs.map { it.decodeSeries }),
            endLabels = runs.map { it.endLabel() },
            endValues = runs.map { run -> (run.closingTokensPerSecond ?: run.decodeSeries.lastOrNull()?.y?.toDouble() ?: 0.0).toFloat() },
        )
        Text(
            "Each run started from the same cooled state. Minutes count from each run's first measured iteration. " +
                "Lines end labelled with the mean of their last ${runs.maxOf { it.closingWindow }} iterations " +
                "and its share of the run's peak.",
            style = MaterialTheme.typography.bodySmall,
        )
        TemperatureChart(
            series = colors.zip(runs.map { it.batterySeries }),
            minX = 0f,
            maxX = runs.maxOf { run -> run.decodeSeries.lastOrNull()?.x ?: 0f },
        )
        Button(
            onClick = { onIntent(BenchIntent.ShareChart(runs.map { it.resultId })) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Share chart image") }

        runs.zip(colors).forEach { (run, color) -> RunCard(run, color) }

        OutlinedButton(onClick = { onIntent(BenchIntent.Dismiss) }) { Text("Done") }
    }
}

/** "14.2 tok/s, 83%": where the run ended up and what share of its peak that is. */
private fun SeriesRun.endLabel(): String {
    val mean = closingTokensPerSecond ?: return ""
    val share = closingShareOfPeak?.let { String.format(Locale.US, ", %.0f%%", it * 100) }.orEmpty()
    return String.format(Locale.US, "%.1f tok/s", mean) + share
}

@Composable
private fun RunCard(run: SeriesRun, color: Color) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(SWATCH_SIZE.dp)
                        .background(color, RoundedCornerShape(2.dp)),
                )
                Text(
                    run.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            KeyValueRow("Retained", run.retentionRatio.formatPercentOrDash())
            KeyValueRow("Peak", "${run.peakDecodeTokensPerSecond.formatOrDash(1)} tok/s")
            KeyValueRow("Steady state", "${run.steadyStateTokensPerSecond.formatOrDash(1)} tok/s")
            KeyValueRow(
                "Last ${run.closingWindow} iterations",
                "${run.closingTokensPerSecond.formatOrDash(1)} tok/s (${run.closingShareOfPeak.formatPercentOrDash()} of peak)",
            )
            run.batterySeries.takeIf { it.size >= 2 }?.let { battery ->
                KeyValueRow("Battery temperature rise", String.format(Locale.US, "%+.1f °C", battery.last().y - battery.first().y))
            }
            KeyValueRow("Iterations to first throttle", run.iterationsToFirstThrottle?.toString() ?: "never")
            run.computeDevice?.let { KeyValueRow("Ran on", it) }
            Text(
                run.validity?.name ?: "NO RESULT",
                style = MaterialTheme.typography.labelLarge,
                color = if (run.validity == ValidityVerdict.VALID) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private const val SWATCH_SIZE = 12
