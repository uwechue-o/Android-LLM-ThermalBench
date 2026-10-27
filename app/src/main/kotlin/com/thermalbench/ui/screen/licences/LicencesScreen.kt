package com.thermalbench.ui.screen.licences

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.android.rememberLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.thermalbench.R
import com.thermalbench.ui.BenchIntent

/**
 * The open-source licences of everything this build ships.
 *
 * The list is generated at build time from the dependencies actually
 * packaged, plus llama.cpp and the Khronos headers it is compiled with, so it
 * cannot drift from the build. ThermalBench's own licence leads, and
 * Qualcomm's third-party notices follow when this build includes the Qualcomm
 * AI Runtime; a build without it carries neither the libraries nor the notice.
 */
@Composable
fun LicencesScreen(onIntent: (BenchIntent) -> Unit, modifier: Modifier = Modifier) {
    BackHandler { onIntent(BenchIntent.Dismiss) }
    val context = LocalContext.current
    val libraries by rememberLibraries(R.raw.aboutlibraries)
    val ownLicence = remember { context.assetText(PROJECT_LICENCE_ASSET) }
    // Split into components once: shown whole, the 129 KB file swamped the list.
    val qualcomm = remember { context.assetText(QAIRT_NOTICE_ASSET)?.let { parseComponentNotices(it).withSharedApacheLicence() } }
    var qualcommOpen by rememberSaveable { mutableStateOf(false) }
    var openComponent by rememberSaveable { mutableStateOf<String?>(null) }

    LibrariesContainer(
        libraries = libraries,
        modifier = modifier.fillMaxSize(),
        header = {
            ownLicence?.let { text ->
                item { ExpandableNotice("ThermalBench", "All rights reserved", text) }
            }
            qualcomm?.let { notices ->
                item {
                    NoticeHeader(
                        title = "Qualcomm AI Runtime (QAIRT)",
                        subtitle = "Used under Qualcomm's licence · notices for ${notices.components.size} " +
                            "components it includes",
                        open = qualcommOpen,
                        onToggle = { qualcommOpen = !qualcommOpen },
                        text = "",
                    )
                }
                if (qualcommOpen) {
                    // Qualcomm's own preamble is one more collapsed row, not text
                    // that opens with the card.
                    val rows = listOf(ComponentNotice(PREAMBLE_TITLE, notices.preamble)) +
                        notices.sharedLicences + notices.components
                    items(rows, key = { "qairt-${it.title}" }) { component ->
                        ComponentRow(
                            notice = component,
                            open = openComponent == component.title,
                            onToggle = {
                                openComponent = if (openComponent == component.title) null else component.title
                            },
                        )
                    }
                }
            }
        },
    )
}

/** A notice that shows its title until tapped, then its full text. */
@Composable
private fun ExpandableNotice(title: String, subtitle: String, text: String) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    NoticeHeader(title, subtitle, expanded, { expanded = !expanded }, text)
}

/** A card with a title and subtitle that reveals [text] while [open]. */
@Composable
private fun NoticeHeader(title: String, subtitle: String, open: Boolean, onToggle: () -> Unit, text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            if (open && text.isNotBlank()) {
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** One component of a combined notice: a single line until tapped. */
@Composable
private fun ComponentRow(notice: ComponentNotice, open: Boolean, onToggle: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 32.dp, vertical = 10.dp),
    ) {
        Text(
            (if (open) "▾ " else "▸ ") + notice.title,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (open) {
            Text(notice.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** An asset's text, or null when this build does not carry it. */
private fun Context.assetText(path: String): String? =
    runCatching { assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()

private const val PROJECT_LICENCE_ASSET = "licences/LICENSE.txt"
private const val QAIRT_NOTICE_ASSET = "qairt/NOTICE.txt"
private const val PREAMBLE_TITLE = "About these notices (Qualcomm)"
