package com.thermalbench.ui.screen.composer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.thermalbench.backend.api.DiscoveredModel
import com.thermalbench.backend.api.ModelFiles
import com.thermalbench.ui.ModelImport
import com.thermalbench.domain.model.BackendRef
import com.thermalbench.domain.model.BenchmarkSpec
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.RunProtocol
import com.thermalbench.ui.BackendOption
import com.thermalbench.ui.BenchIntent
import com.thermalbench.ui.BenchUiState
import com.thermalbench.ui.Comparison
import com.thermalbench.ui.planRuns
import com.thermalbench.ui.withGpuApiFor
import com.thermalbench.ui.SpecForm
import com.thermalbench.ui.common.KeyValueRow
import com.thermalbench.ui.common.SelectionChip
import com.thermalbench.ui.theme.MeasurementSmallTextStyle
import com.thermalbench.ui.theme.ThermalColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pick model, backend, delegate and protocol, and validate before committing.
 * Validation happens here rather than at run time because the alternative is
 * discovering an incomparable configuration an hour into a device-hour run.
 */
@Composable
fun SpecComposerScreen(
    state: BenchUiState.Composing,
    onIntent: (BenchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The document picker is an activity result, so it is launched from the
    // composable. Everything it produces goes back through an intent, which
    // keeps the ViewModel free of Android UI types beyond the URI itself.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onIntent(BenchIntent.ImportModel(uri))
    }
    // Model files have no registered MIME type, so the picker cannot filter by
    // one. The extension check happens on the way in instead.
    val onPickFile: () -> Unit = { picker.launch(arrayOf("*/*")) }

    // The run is meant to proceed with the screen off, so its notification is
    // the only sign it is waiting or running, and the only Cancel outside the
    // app. Android 13+ hides it until this permission is granted. Asked for at
    // Start, where the reason is obvious; the run starts whatever the answer.
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onIntent(BenchIntent.Start) }
    val onStart: () -> Unit = {
        val needsAsking = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsAsking) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onIntent(BenchIntent.Start)
        }
    }

    SpecComposerContent(state, onIntent, onPickFile, onStart, modifier)
}

@Composable
private fun SpecComposerContent(
    state: BenchUiState.Composing,
    onIntent: (BenchIntent) -> Unit,
    onPickFile: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    // Open by default only while the device has no models, where downloading
    // one is the obvious next step; once the user toggles it, their choice holds.
    var catalogToggled by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val catalogExpanded = catalogToggled ?: state.models.isEmpty()
    // The verdict appears just above the buttons, below the fold of a long
    // form; scroll to the end so it is seen rather than left off screen.
    val listState = rememberLazyListState()
    val verdictShown = state.validSpecs.isNotEmpty() || state.problems.isNotEmpty()
    LaunchedEffect(state.validSpecs, state.problems) {
        if (verdictShown) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Model", style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = { onIntent(BenchIntent.RefreshModels) },
                    enabled = !state.scanning,
                ) {
                    Text(if (state.scanning) "Scanning…" else "Rescan")
                }
            }
        }

        item { ScanStatus(state) }

        if (state.models.isEmpty()) {
            item { NoModelsCard(state.pushCommand) }
        } else {
            items(state.models, key = { it.path }) { model ->
                ModelRow(
                    model = model,
                    selected = model.path == form.modelPath,
                    onSelect = { onIntent(BenchIntent.SelectModel(model)) },
                )
            }
        }

        if (state.unrecognisedFiles.isNotEmpty()) {
            item { UnrecognisedFilesCard(state.unrecognisedFiles) }
        }

        state.import?.let { progress -> item { ImportProgressCard(progress, onIntent) } }
        state.importError?.let { message -> item { ImportErrorCard(message, onIntent) } }

        item {
            BrowseField(
                path = form.modelPath,
                enabled = state.import == null,
                onPick = onPickFile,
            )
        }

        modelCatalogSection(
            state = state,
            expanded = catalogExpanded,
            onToggle = { catalogToggled = !catalogExpanded },
            onIntent = onIntent,
        )

        item {
            var manual by rememberSaveable { mutableStateOf(false) }
            if (manual) {
                OutlinedTextField(
                    value = form.modelPath,
                    onValueChange = { onIntent(BenchIntent.UpdateForm(form.copy(modelPath = it))) },
                    label = { Text("Path") },
                    supportingText = {
                        Text("Only files this app can open. Scoped storage blocks most of /sdcard.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TextButton(onClick = { manual = true }) { Text("Enter a path manually") }
            }
        }

        item {
            OutlinedTextField(
                value = form.label,
                onValueChange = { onIntent(BenchIntent.UpdateForm(form.copy(label = it))) },
                label = { Text("Run label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item { Text("Backend", style = MaterialTheme.typography.titleMedium) }
        items(state.backends, key = { it.id.value }) { option ->
            BackendRow(
                option = option,
                selected = option.id == form.backendId,
                selectedDelegate = form.delegate,
                selectedGpuApi = form.gpuApi,
                onSelect = { onIntent(BenchIntent.UpdateForm(form.withBackend(option))) },
                onDelegate = { onIntent(BenchIntent.UpdateForm(form.copy(delegate = it))) },
                onGpuApi = { onIntent(BenchIntent.UpdateForm(form.copy(gpuApi = it))) },
            )
        }

        item { Text("Compare", style = MaterialTheme.typography.titleMedium) }
        item { ComparisonCard(state, onIntent) }

        item { Text("Protocol", style = MaterialTheme.typography.titleMedium) }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    NumberField(
                        label = "Measured iterations",
                        value = form.measuredIterations,
                        onValue = { onIntent(BenchIntent.UpdateForm(form.copy(measuredIterations = it))) },
                    )
                    if (form.measuredIterations < RunProtocol.SUSTAINED_LOAD_MIN_ITERATIONS) {
                        Text(
                            "Below ${RunProtocol.SUSTAINED_LOAD_MIN_ITERATIONS}: the result will be " +
                                "marked insufficient for sustained-load analysis.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    NumberField(
                        label = "Warmup iterations (discarded, still recorded)",
                        value = form.warmupIterations,
                        onValue = { onIntent(BenchIntent.UpdateForm(form.copy(warmupIterations = it))) },
                    )
                    NumberField(
                        label = "Output tokens per iteration",
                        value = form.outputTokens,
                        onValue = { onIntent(BenchIntent.UpdateForm(form.copy(outputTokens = it))) },
                    )
                    NumberField(
                        label = "Delay between iterations (seconds)",
                        value = form.interIterationDelaySeconds,
                        onValue = {
                            onIntent(BenchIntent.UpdateForm(form.copy(interIterationDelaySeconds = it)))
                        },
                    )
                    SwitchRow(
                        label = "Abort if thermal status reaches CRITICAL",
                        checked = form.abortOnCriticalThermal,
                        onChange = { onIntent(BenchIntent.UpdateForm(form.copy(abortOnCriticalThermal = it))) },
                    )
                }
            }
        }

        item { Text("Preconditions", style = MaterialTheme.typography.titleMedium) }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    SwitchRow(
                        label = "Require the device to be unplugged",
                        checked = form.requireUnplugged,
                        onChange = { onIntent(BenchIntent.UpdateForm(form.copy(requireUnplugged = it))) },
                    )
                    NumberField(
                        label = "Minimum battery percent",
                        value = form.minBatteryPercent,
                        onValue = { onIntent(BenchIntent.UpdateForm(form.copy(minBatteryPercent = it))) },
                    )
                    Text(
                        "Charging is a heat source and a low battery triggers its own throttling. " +
                            "A run that starts outside these bounds is recorded as invalid, not adjusted.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        verdictAndActions(state, onIntent, onStart)
    }
}

/** The validator's verdict on the form, then Validate and Start. */
private fun LazyListScope.verdictAndActions(
    state: BenchUiState.Composing,
    onIntent: (BenchIntent) -> Unit,
    onStart: () -> Unit,
) {
    if (state.problems.isNotEmpty()) {
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Spec rejected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.problems.forEach { problem ->
                        KeyValueRow(problem.field, problem.message)
                    }
                }
            }
        }
    }

    if (state.validSpecs.isNotEmpty()) item { ValidSpecCard(state.validSpecs) }

    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onIntent(BenchIntent.Validate) }) { Text("Validate") }
            Button(
                onClick = onStart,
                enabled = state.form.modelPath.isNotBlank(),
            ) { Text(if (state.form.comparison == Comparison.SINGLE) "Start run" else "Start comparison") }
        }
    }
}

/**
 * What passed validation. Without it a successful check changed nothing on
 * screen and Validate read as a button that does nothing. It also shows what
 * the check settled: the spec identity and whether the model's bytes are
 * pinned by digest. A comparison lists every run, in the order they will run.
 */
@Composable
private fun ValidSpecCard(specs: List<BenchmarkSpec>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Spec valid",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = ThermalColors.none,
            )
            Text(
                if (specs.size == 1) {
                    "Ready to start. These are the settings the run will use."
                } else {
                    "Ready to start. ${specs.size} runs, one after another, each after the device has " +
                        "cooled to the same starting point."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            specs.forEachIndexed { index, spec ->
                if (specs.size > 1) {
                    Text(
                        "Run ${index + 1} of ${specs.size}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                SpecSummary(spec)
            }
        }
    }
}

@Composable
private fun SpecSummary(spec: BenchmarkSpec) {
    Column {
        KeyValueRow("Spec ID", spec.specId.value)
        KeyValueRow("Model", spec.model.name)
        val api = spec.backend.options[BackendRef.OPTION_GPU_API]?.let { " ($it)" }.orEmpty()
        KeyValueRow(
            "Backend",
            "${spec.backend.id.presentation().displayName} · ${spec.backend.delegate.name}$api",
        )
        KeyValueRow(
            "Iterations",
            "${spec.protocol.warmupIterations} warm-up + ${spec.protocol.measuredIterations} measured",
        )
        KeyValueRow("Output tokens", spec.workload.maxOutputTokens.toString())
        KeyValueRow(
            "Model digest",
            if (spec.model.sha256.hex == ModelFiles.UNPINNED) {
                "not pinned"
            } else {
                "pinned · ${spec.model.sha256.hex.take(DIGEST_PREFIX)}…"
            },
        )
    }
}

private const val DIGEST_PREFIX = 12

/**
 * Switches backend, and the compute unit with it when the new backend does not
 * offer the current one: llama.cpp without a GPU build offers only CPU, and
 * keeping GPU selected would leave no chip lit and a spec the validator rejects.
 */
private fun SpecForm.withBackend(option: BackendOption): SpecForm = copy(
    backendId = option.id,
    delegate = delegate.takeIf { it in option.delegates } ?: option.delegates.minByOrNull { it.ordinal } ?: delegate,
).withGpuApiFor(option)

/**
 * Whether Start runs one spec or a comparison. A comparison varies one thing
 * and holds the rest, and every run waits for the same cool-down first, so
 * the runs differ only in what is being compared.
 */
@Composable
private fun ComparisonCard(state: BenchUiState.Composing, onIntent: (BenchIntent) -> Unit) {
    val form = state.form
    val option = state.backends.firstOrNull { it.id == form.backendId }
    val update = { next: SpecForm -> onIntent(BenchIntent.UpdateForm(next)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionChip(
                    selected = form.comparison == Comparison.SINGLE,
                    onClick = { update(form.copy(comparison = Comparison.SINGLE)) },
                    label = "Single run",
                )
                SelectionChip(
                    selected = form.comparison == Comparison.COMPUTE_UNITS,
                    onClick = { update(form.copy(comparison = Comparison.COMPUTE_UNITS)) },
                    label = "Compute units",
                )
                SelectionChip(
                    selected = form.comparison == Comparison.OTHER_MODEL,
                    onClick = { update(form.copy(comparison = Comparison.OTHER_MODEL)) },
                    label = "Models",
                )
            }
            val runs = form.planRuns(state.backends, state.models)
            Text(
                when (form.comparison) {
                    Comparison.SINGLE -> "One run with the settings above."
                    Comparison.COMPUTE_UNITS ->
                        "The same model on every compute unit ${option?.id?.presentation()?.displayName ?: "the backend"} " +
                            "offers here: ${runs.joinToString { it.label.substringAfterLast(" · ") }}. The device " +
                            "cools to the same starting point before each run."
                    Comparison.OTHER_MODEL ->
                        "These settings with the model above, then with a second model. The device cools to the " +
                            "same starting point before each run."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (form.comparison == Comparison.OTHER_MODEL) {
                val others = state.models.filter { it.path != form.modelPath }
                if (others.isEmpty()) {
                    Text(
                        "Add a second model file to compare against.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                others.forEach { model ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { update(form.copy(compareModelPath = model.path)) },
                    ) {
                        RadioButton(
                            selected = model.path == form.compareModelPath,
                            onClick = { update(form.copy(compareModelPath = model.path)) },
                        )
                        Text(model.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * A read-only field that opens the document picker when tapped.
 *
 * It is not editable, because a field that both accepts typing and opens a
 * dialog on touch cannot do either predictably. Manual entry is a separate,
 * deliberate choice below it.
 */
@Composable
private fun BrowseField(path: String, enabled: Boolean, onPick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = path.substringAfterLast('/'),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Browse for a model file") },
            placeholder = { Text("Tap to choose a file") },
            supportingText = {
                Text("Copied into this app's storage, because the runtimes need a real file path.")
            },
            trailingIcon = {
                Icon(Icons.Outlined.FolderOpen, contentDescription = "Browse")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // The overlay takes the tap: a read-only text field swallows clicks.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(enabled = enabled, onClick = onPick),
        )
    }
}

/** A copy in flight, with the cancel control it needs. */
@Composable
private fun ImportProgressCard(progress: ModelImport, onIntent: (BenchIntent) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(progress.fileName, style = MaterialTheme.typography.titleSmall)
            val fraction = progress.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }
            Text(
                buildString {
                    append(megabytes(progress.copiedBytes))
                    if (progress.totalBytes > 0) append(" of ").append(megabytes(progress.totalBytes))
                    append(" ").append(progress.verb)
                },
                style = MeasurementSmallTextStyle,
            )
            TextButton(onClick = { onIntent(BenchIntent.CancelImport) }) { Text("Cancel") }
        }
    }
}

@Composable
private fun ImportErrorCard(message: String, onIntent: (BenchIntent) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Could not import that file",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(message, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onIntent(BenchIntent.DismissImportError) }) { Text("Dismiss") }
        }
    }
}

private fun megabytes(bytes: Long): String =
    String.format(Locale.US, "%,d MB", bytes / (1024L * 1024L))

/**
 * What the last scan found, and where it looked.
 *
 * This line is the acknowledgement the Rescan button needs. The count alone is
 * not enough, because two scans of an empty directory produce the same count
 * and nothing on screen would change; the scan time always moves.
 */
@Composable
private fun ScanStatus(state: BenchUiState.Composing) {
    val count = state.models.size
    val summary = when {
        state.scanning -> "Scanning…"
        count == 0 -> "No model files found"
        count == 1 -> "1 model file"
        else -> "$count model files"
    }
    val scannedAt = state.lastScanEpochMillis
        .takeIf { it > 0L && !state.scanning }
        ?.let { " · scanned ${timeFormatter.format(Date(it))}" }
        .orEmpty()

    Column(Modifier.fillMaxWidth()) {
        Text(
            summary + scannedAt,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.modelsDirectory.isNotEmpty()) {
            Text(
                state.modelsDirectory,
                style = MeasurementSmallTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!state.modelsDirectoryAdbAccessible) {
            Text(
                "This device fell back to internal storage, which adb cannot write to. " +
                    "Files pushed to the usual location will never be found here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

/** One model file found on the device. Tapping it sets path, name and format together. */
@Composable
private fun ModelRow(
    model: DiscoveredModel,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.padding(start = 4.dp)) {
                Text(model.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${model.displaySize} · ${model.format.name.lowercase().replace('_', ' ')}",
                    style = MeasurementSmallTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The empty state carries the whole workflow, because there is no other place
 * an operator would learn where model files have to live.
 */
@Composable
private fun NoModelsCard(pushCommand: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Getting a model onto this device", style = MaterialTheme.typography.titleSmall)
            Text(
                "ThermalBench ships no models. Download one from the list below, browse for a " +
                    "file already on this phone, or copy one into the directory above and tap " +
                    "Rescan. That directory needs no permission; most of /sdcard cannot be read " +
                    "at all on this Android version.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Text(pushCommand, style = MeasurementSmallTextStyle)
            Text(
                "Accepted formats: .litertlm, .gguf, .task, .pte",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun UnrecognisedFilesCard(files: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Ignored, unrecognised extension",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            files.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Text(
                "A model's format cannot be guessed from its contents here, so the extension " +
                    "has to name it.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun BackendRow(
    option: BackendOption,
    selected: Boolean,
    selectedDelegate: Delegate,
    selectedGpuApi: String?,
    onSelect: () -> Unit,
    onDelegate: (Delegate) -> Unit,
    onGpuApi: (String) -> Unit,
) {
    val presentation = option.id.presentation()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionChip(
                    selected = selected,
                    onClick = onSelect,
                    enabled = option.available,
                    label = presentation.displayName,
                )
                Text(
                    text = presentation.fileExtension,
                    style = MeasurementSmallTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            if (presentation.description.isNotEmpty()) {
                Text(
                    presentation.description,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (!option.available) {
                // An unavailable backend stays visible with its reason: a gap in
                // coverage is a finding, not something to hide from the list.
                Text(
                    "Not available in this build",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    option.unavailableReason ?: "No reason reported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (selected) {
                Text(
                    "Compute unit",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    option.delegates.sortedBy { it.name }.forEach { delegate ->
                        SelectionChip(
                            selected = delegate == selectedDelegate,
                            onClick = { onDelegate(delegate) },
                            label = delegate.name,
                        )
                    }
                }
                Text(
                    selectedDelegate.explanation(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // Only where there is a choice: llama.cpp on a Snapdragon can
                // drive the same Adreno through OpenCL or through Vulkan.
                if (selectedDelegate == Delegate.GPU && option.gpuApis.size > 1) {
                    Text(
                        "GPU API",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        option.gpuApis.forEach { api ->
                            SelectionChip(
                                selected = api.equals(selectedGpuApi, ignoreCase = true),
                                onClick = { onGpuApi(api) },
                                label = api,
                            )
                        }
                    }
                    Text(
                        "Two routes to the same GPU. OpenCL is llama.cpp's path tuned for Adreno; " +
                            "Vulkan is the portable one.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onValue: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.toIntOrNull()?.let(onValue) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
