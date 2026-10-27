package com.thermalbench.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thermalbench.domain.model.ThermalStatus
import com.thermalbench.domain.model.ValidityVerdict
import com.thermalbench.ui.theme.MeasurementSmallTextStyle
import com.thermalbench.ui.theme.MeasurementTextStyle
import com.thermalbench.ui.theme.ThermalColors

fun ThermalStatus.color(): Color = when (this) {
    ThermalStatus.NONE -> ThermalColors.none
    ThermalStatus.LIGHT -> ThermalColors.light
    ThermalStatus.MODERATE -> ThermalColors.moderate
    ThermalStatus.SEVERE -> ThermalColors.severe
    ThermalStatus.CRITICAL -> ThermalColors.critical
    ThermalStatus.EMERGENCY -> ThermalColors.emergency
    ThermalStatus.SHUTDOWN -> ThermalColors.shutdown
    ThermalStatus.UNKNOWN -> ThermalColors.unknown
}

@Composable
fun ThermalBadge(status: ThermalStatus, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(status.color()),
        ) {}
        Text(
            text = status.name,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * A single measurement. Values are monospaced so a figure that changes every
 * second does not reflow the row it sits in.
 */
@Composable
fun MeasurementTile(
    label: String,
    value: String,
    unit: String? = null,
    caption: String? = null,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MeasurementTextStyle)
                if (unit != null) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
            }
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * The validity verdict is deliberately prominent. A number produced under
 * unmet preconditions is not a slightly worse number, it is a different
 * measurement, and the UI should never let it be read as comparable.
 */
@Composable
fun ValidityBanner(verdict: ValidityVerdict, modifier: Modifier = Modifier) {
    val (text, color) = when (verdict) {
        ValidityVerdict.VALID ->
            "Valid run" to ThermalColors.none
        ValidityVerdict.PRECONDITIONS_UNMET ->
            "Invalid: preconditions were not met — not comparable with other runs" to ThermalColors.critical
        ValidityVerdict.INSUFFICIENT_DATA ->
            "Insufficient data: too few measured iterations for sustained-load analysis" to ThermalColors.moderate
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color),
            ) {}
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MeasurementSmallTextStyle, textAlign = TextAlign.End)
    }
}

/** Formats a nullable double for display; an unavailable value says so. */
fun Double?.formatOrDash(decimals: Int = 2): String =
    this?.let { String.format("%.${decimals}f", it) } ?: "—"

fun Double?.formatPercentOrDash(): String =
    this?.let { String.format("%.1f%%", it * 100) } ?: "—"
