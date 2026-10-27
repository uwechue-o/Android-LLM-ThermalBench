package com.thermalbench.ui.screen.leaderboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thermalbench.domain.model.LeaderboardEntry
import com.thermalbench.ui.common.KeyValueRow
import com.thermalbench.ui.common.formatOrDash
import com.thermalbench.ui.common.formatPercentOrDash

data class ConsentForm(
    val consentGiven: Boolean = false,
    val includeDeviceModel: Boolean = true,
    val includeRawIterations: Boolean = false,
)

/**
 * Community results, with the consent flow ahead of any upload.
 *
 * The consent panel spells out exactly what would be transmitted. Nothing
 * leaves the device until the box is ticked, and each field is a separate
 * choice rather than one blanket agreement.
 */
@Composable
fun LeaderboardScreen(
    entries: List<LeaderboardEntry>,
    consent: ConsentForm,
    canSubmit: Boolean,
    onConsentChange: (ConsentForm) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Community results", style = MaterialTheme.typography.titleLarge)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Share this run", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Submitting sends the analysis summary, the SoC identifier and an " +
                        "anonymous per-install device id. It never sends the prompt, the " +
                        "generated text, or anything that identifies you.",
                    style = MaterialTheme.typography.bodySmall,
                )
                CheckRow(
                    label = "I consent to publishing this result",
                    checked = consent.consentGiven,
                    onChange = { onConsentChange(consent.copy(consentGiven = it)) },
                )
                CheckRow(
                    label = "Include the device model name",
                    checked = consent.includeDeviceModel,
                    onChange = { onConsentChange(consent.copy(includeDeviceModel = it)) },
                )
                CheckRow(
                    label = "Include per-iteration throughput data",
                    checked = consent.includeRawIterations,
                    onChange = { onConsentChange(consent.copy(includeRawIterations = it)) },
                )
                Button(
                    onClick = onSubmit,
                    enabled = consent.consentGiven && canSubmit,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Submit result")
                }
                if (!canSubmit) {
                    Text(
                        "Only runs marked VALID can be submitted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (entries.isEmpty()) {
            Text("No entries yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries) { entry -> EntryCard(entry) }
            }
        }
    }
}

@Composable
private fun EntryCard(entry: LeaderboardEntry) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(entry.deviceModel, style = MaterialTheme.typography.titleSmall)
            Text(
                "${entry.modelName} · ${entry.backendId.value} · ${entry.delegate.name}",
                style = MaterialTheme.typography.bodySmall,
            )
            KeyValueRow("Retained", entry.retentionRatio.formatPercentOrDash())
            KeyValueRow("Peak", "${entry.peakDecodeTokensPerSecond.formatOrDash(1)} tok/s")
            KeyValueRow("Steady state", "${entry.steadyStateTokensPerSecond.formatOrDash(1)} tok/s")
            KeyValueRow("Iterations", entry.measuredIterations.toString())
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
