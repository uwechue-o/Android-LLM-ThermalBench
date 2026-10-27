package com.thermalbench.ui.screen.compare

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thermalbench.domain.model.ComparisonRow
import com.thermalbench.domain.model.ValidityVerdict
import com.thermalbench.ui.common.formatOrDash
import com.thermalbench.ui.common.formatPercentOrDash
import com.thermalbench.ui.theme.MeasurementSmallTextStyle

/**
 * Backends on this device, or this device against imported results. Rows that
 * are not valid are shown with their verdict rather than filtered out, because
 * "this configuration could not produce a comparable run" is itself a result.
 */
@Composable
fun CompareScreen(
    title: String,
    rows: List<ComparisonRow>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (rows.isEmpty()) {
            Text(
                "Nothing to compare yet. Run the same spec on another backend or " +
                    "import a result from another device.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            return
        }

        val scroll = rememberScrollState()
        Column(modifier = Modifier.horizontalScroll(scroll)) {
            HeaderRow()
            HorizontalDivider()
            LazyColumn {
                items(rows) { row ->
                    ComparisonRowView(row)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeaderCell("Device", DEVICE_WIDTH)
        HeaderCell("Backend", BACKEND_WIDTH)
        HeaderCell("Delegate", SMALL_WIDTH)
        HeaderCell("Peak", SMALL_WIDTH)
        HeaderCell("Steady", SMALL_WIDTH)
        HeaderCell("Retained", SMALL_WIDTH)
        HeaderCell("To throttle", SMALL_WIDTH)
        HeaderCell("Validity", BACKEND_WIDTH)
    }
}

@Composable
private fun ComparisonRowView(row: ComparisonRow) {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cell(row.deviceModel, DEVICE_WIDTH)
        Cell(row.backendId.value, BACKEND_WIDTH)
        Cell(row.delegate.name, SMALL_WIDTH)
        Cell(row.peakDecodeTokensPerSecond.formatOrDash(1), SMALL_WIDTH)
        Cell(row.steadyStateTokensPerSecond.formatOrDash(1), SMALL_WIDTH)
        Cell(row.retentionRatio.formatPercentOrDash(), SMALL_WIDTH)
        Cell(row.iterationsToFirstThrottle?.toString() ?: "none", SMALL_WIDTH)
        Cell(
            text = row.validity.name,
            width = BACKEND_WIDTH,
            emphasize = row.validity != ValidityVerdict.VALID,
        )
    }
}

@Composable
private fun HeaderCell(text: String, width: Int) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.width(width.dp),
    )
}

@Composable
private fun Cell(text: String, width: Int, emphasize: Boolean = false) {
    Text(
        text = text,
        style = MeasurementSmallTextStyle,
        color = if (emphasize) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.width(width.dp),
    )
}

private const val DEVICE_WIDTH = 140
private const val BACKEND_WIDTH = 120
private const val SMALL_WIDTH = 76
