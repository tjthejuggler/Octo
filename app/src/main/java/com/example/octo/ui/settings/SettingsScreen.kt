package com.example.octo.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.octo.BuildConfig
import com.example.octo.R
import com.example.octo.data.settings.SettingsRepository
import com.example.octo.ui.components.OctoHeader

/**
 * Settings (Phase 5, picker reworked Phase 15): LLM endpoint form (base URL /
 * API key / model), Save + inline "Test LLM" round-trip, and a compact Tail
 * habit row that opens a searchable habit-picker dialog fed by
 * [TailIntegrationClient][com.example.octo.ipc.TailIntegrationClient]'s provider
 * query. Replaces the Phase 2 placeholder per task spec §5.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val testState by viewModel.testLlm.collectAsState()
    val tailState by viewModel.tailHabits.collectAsState()
    val savedHabit by viewModel.savedHabitName.collectAsState()
    val showPassword by viewModel.showPassword.collectAsState()
    val baseUrl by viewModel.baseUrl.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val model by viewModel.model.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()
    var saveFlash by remember { mutableStateOf(false) }
    var showHabitPicker by rememberSaveable { mutableStateOf(false) }

    val selectedHabit = savedHabit.ifBlank { SettingsRepository.DEFAULT_TAIL_HABIT_NAME }

    val jsonMime = stringResource(R.string.settings_backup_mime)

    // Phase 8: SAF CreateDocument / OpenDocument — export writes the URL JSON, import
    // restores STREAM_ONLY rows. Launchers are remembered at composition level.
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) viewModel.exportLibrary(uri)
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importLibrary(uri)
    }

    if (showHabitPicker) {
        TailHabitPickerDialog(
            state = tailState,
            selected = selectedHabit,
            onSelect = { habit ->
                viewModel.selectHabit(habit)
                showHabitPicker = false
            },
            onRetry = { viewModel.loadTailHabits() },
            onDismiss = { showHabitPicker = false },
        )
    }

    // contentWindowInsets=0: the root app scaffold already applies the system-bar
    // insets — a second scaffold here would double-pad and push the header down.
    Scaffold(modifier = modifier, containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // Header geometry must match Body/Stats: 8dp top (owned by
                // OctoHeader itself), 12dp horizontal like the dashboard grid.
                .padding(top = 0.dp, start = 12.dp, end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Shared mascot header — identical to Body/Stats/Library.
            OctoHeader(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.settings_subtitle),
            )
            // ── LLM section ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_llm_section),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { viewModel.baseUrl.value = it },
                        label = { Text(stringResource(R.string.settings_llm_base_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { viewModel.apiKey.value = it },
                        label = { Text(stringResource(R.string.settings_llm_api_key)) },
                        singleLine = true,
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { viewModel.showPassword.value = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (showPassword) {
                                        stringResource(R.string.settings_hide)
                                    } else {
                                        stringResource(R.string.settings_show)
                                    },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = model,
                        onValueChange = { viewModel.model.value = it },
                        label = { Text(stringResource(R.string.settings_llm_model)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { viewModel.save { saveFlash = true } }) {
                            Text(stringResource(R.string.settings_save))
                        }
                        OutlinedButton(onClick = { viewModel.testLlm() }) {
                            Text(stringResource(R.string.settings_test_llm))
                        }
                        if (saveFlash) {
                            Text(
                                text = stringResource(R.string.settings_saved),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    TestLlmResult(testState)
                }
            }

            // ── Tail section (Phase 15): compact row → searchable picker dialog ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    TailHabitRow(
                        selected = selectedHabit,
                        onClick = { showHabitPicker = true },
                    )
                }
            }

            // ── Library backup (Phase 8): SAF export/import of URL rows ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_backup_section),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_backup_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            exportLauncher.launch("octo_library.json")
                        }) {
                            Text(stringResource(R.string.settings_backup_export))
                        }
                        OutlinedButton(onClick = {
                            importLauncher.launch(arrayOf(jsonMime))
                        }) {
                            Text(stringResource(R.string.settings_backup_import))
                        }
                    }
                    when (val status = backupStatus) {
                        SettingsViewModel.STATUS_EXPORTED -> Text(
                            text = stringResource(R.string.settings_backup_exported),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        null -> Unit
                        else -> if (status.startsWith("imported:")) {
                            val parts = status.split(":")
                            Text(
                                text = stringResource(
                                    R.string.settings_backup_imported,
                                    parts.getOrElse(1) { "0" }.toIntOrNull() ?: 0,
                                    parts.getOrElse(2) { "0" }.toIntOrNull() ?: 0,
                                ),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.settings_backup_failed, status),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            // ── Dev-only seed (debug builds): tiny footer action, clearly labeled ──
            if (BuildConfig.DEBUG) {
                var seeded by remember { mutableStateOf<String?>(null) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = seeded ?: "dev: seed stretch data (debug)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        viewModel.devSeedStretchData { n -> seeded = "dev: seeded $n aggregate rows ✓" }
                    }) {
                        Text("seed")
                    }
                }
            }
        }
    }
}

/** Compact settings row: label + currently-selected habit + chevron. */
@Composable
private fun TailHabitRow(
    selected: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_tail_row_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = selected,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f, fill = false),
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.settings_tail_pick),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Searchable habit picker dialog (Phase 15): a search TextField on top and a
 * lazy list of habits below, filtered live with a case-insensitive contains
 * match. Tapping a habit selects + persists it and dismisses the dialog.
 * States: loading / no-match / Tail-not-installed (with retry).
 */
@Composable
private fun TailHabitPickerDialog(
    state: SettingsViewModel.TailHabitsState,
    selected: String,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_tail_pick)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.settings_tail_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )

                when (state) {
                    is SettingsViewModel.TailHabitsState.Loading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.settings_tail_loading))
                        }
                    }

                    is SettingsViewModel.TailHabitsState.Unavailable -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_tail_not_installed),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedButton(onClick = onRetry) {
                                Text(stringResource(R.string.settings_tail_retry))
                            }
                        }
                    }

                    is SettingsViewModel.TailHabitsState.Found -> {
                        val filtered = remember(state.habits, query) {
                            if (query.isBlank()) {
                                state.habits
                            } else {
                                state.habits.filter {
                                    it.contains(query.trim(), ignoreCase = true)
                                }
                            }
                        }
                        when {
                            filtered.isEmpty() -> Text(
                                text = stringResource(R.string.settings_tail_no_match),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                            )

                            else -> {
                                val maxListHeight =
                                    (LocalConfiguration.current.screenHeightDp * 0.5f).dp
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = maxListHeight),
                                ) {
                                    items(filtered, key = { it }) { habit ->
                                        val is_selected = habit == selected
                                        ListItem(
                                            headlineContent = { Text(habit) },
                                            leadingContent = if (is_selected) {
                                                {
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                            colors = ListItemDefaults.colors(
                                                containerColor = Color.Transparent,
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onSelect(habit) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

@Composable
private fun TestLlmResult(state: SettingsViewModel.TestLlmState) {
    when (state) {
        is SettingsViewModel.TestLlmState.Idle -> Unit

        is SettingsViewModel.TestLlmState.Running -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_test_running))
            }
        }

        is SettingsViewModel.TestLlmState.Success -> Text(
            text = stringResource(R.string.settings_test_ok, state.reply),
            color = MaterialTheme.colorScheme.primary,
        )

        is SettingsViewModel.TestLlmState.Failure -> Text(
            text = stringResource(R.string.settings_test_failed, state.message),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
