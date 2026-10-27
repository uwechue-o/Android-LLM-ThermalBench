package com.thermalbench.ui.screen.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.thermalbench.backend.api.CatalogModel
import com.thermalbench.backend.api.DiscoveredModel
import com.thermalbench.backend.api.ModelLibrary
import com.thermalbench.ui.BackendOption
import com.thermalbench.ui.BenchIntent
import com.thermalbench.ui.BenchUiState
import com.thermalbench.ui.theme.MeasurementSmallTextStyle
import java.util.Locale

/**
 * Published models, grouped by the backend that loads them.
 *
 * Each entry either downloads straight into the models directory, verified
 * against the publisher's digest, or, when the publisher requires accepting a
 * licence first, links to the page where that happens. The second kind then
 * comes in through the file picker above, like any other file.
 */
internal fun LazyListScope.modelCatalogSection(
    state: BenchUiState.Composing,
    expanded: Boolean,
    onToggle: () -> Unit,
    onIntent: (BenchIntent) -> Unit,
) {
    if (state.catalog.isEmpty()) return

    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Download a model", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onToggle) { Text(if (expanded) "Hide" else "Show") }
        }
    }
    if (!expanded) return

    item {
        Text(
            "Downloads go straight into the models directory and are checked against the " +
                "publisher's SHA-256 before they are kept, so a run names the exact file it used. " +
                "These are large; Wi-Fi is the sensible choice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    state.catalog.groupBy { it.format }.forEach { (format, entries) ->
        val backend = state.backends.firstOrNull { format in it.formats }
        item(key = "catalog-header-${format.name}") {
            Text(
                "${backend?.id?.presentation()?.displayName ?: format.name} · ${ModelLibrary.extensionFor(format)}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        items(entries, key = { "catalog-${it.fileName}" }) { entry ->
            CatalogRow(
                entry = entry,
                backend = backend,
                onDevice = state.models.firstOrNull { it.isCopyOf(entry) },
                busy = state.import != null,
                onIntent = onIntent,
            )
        }
    }
}

@Composable
private fun CatalogRow(
    entry: CatalogModel,
    backend: BackendOption?,
    onDevice: DiscoveredModel?,
    busy: Boolean,
    onIntent: (BenchIntent) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(displaySize(entry.sizeBytes), style = MeasurementSmallTextStyle)
            }
            Text(
                "${entry.quantization} · ${entry.license} licence",
                style = MeasurementSmallTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                entry.note,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (backend != null && !backend.available) {
                // Still listed: the file can be fetched now and run on a build
                // that has the backend. Hiding it would hide the gap.
                Text(
                    "${backend.id.presentation().displayName} is not available in this build.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    onDevice != null -> OutlinedButton(onClick = { onIntent(BenchIntent.SelectModel(onDevice)) }) {
                        Text("On device · use it")
                    }
                    entry.downloadable -> Button(
                        onClick = { onIntent(BenchIntent.DownloadModel(entry)) },
                        enabled = !busy,
                    ) { Text("Download") }
                    else -> OutlinedButton(onClick = { uriHandler.openUri(entry.pageUrl) }) {
                        Text("Accept licence online")
                    }
                }
                TextButton(onClick = { uriHandler.openUri(entry.pageUrl) }) { Text("Model page") }
            }
        }
    }
}

/** A file on disk is this entry only if both the name and the exact size agree. */
private fun DiscoveredModel.isCopyOf(entry: CatalogModel): Boolean =
    name == entry.fileName && sizeBytes == entry.sizeBytes

private fun displaySize(bytes: Long): String {
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 1024) {
        String.format(Locale.US, "%.1f GB", megabytes / 1024)
    } else {
        String.format(Locale.US, "%.0f MB", megabytes)
    }
}
