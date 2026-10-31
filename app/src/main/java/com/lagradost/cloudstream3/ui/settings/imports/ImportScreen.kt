package com.lagradost.cloudstream3.ui.settings.imports

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.importsystem.ImportCategory
import com.lagradost.cloudstream3.importsystem.ImportEngine
import com.lagradost.cloudstream3.importsystem.ImportScheduler
import com.lagradost.cloudstream3.importsystem.ImportManager
import com.lagradost.cloudstream3.importsystem.ImportPhase
import com.lagradost.cloudstream3.importsystem.ImportProgress
import com.lagradost.cloudstream3.importsystem.ImportReport
import com.lagradost.cloudstream3.importsystem.ImportConfig
import com.lagradost.cloudstream3.importsystem.ProviderLinkRefreshManager
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.rememberAppSettings
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import kotlinx.collections.immutable.persistentListOf
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Settings -> Import -> Import dashboard.
 *
 * Every control here drives real work: provider selection comes from the running app's
 * provider registry, the scan runs [com.lagradost.cloudstream3.importsystem.ImportEngine]
 * against the real MainAPI calls, and the report shows the counters and errors those calls
 * actually produced.
 */
object ImportScreen : SearchableSettings {

    @Composable
    override fun getTitleRes(): String = stringResource(R.string.category_import)

    @Composable
    override fun getPreferences(): List<Preference> {
        val settings = rememberAppSettings()
        val state by ImportManager.state.collectAsState()
        val linkRefreshState by ProviderLinkRefreshManager.state.collectAsState()
        val context = LocalContext.current

        val providers = ImportEngine.installedProviders()
        val providerEntries: Map<String, String> = providers
            .associate { it.name to "${it.name} · ${it.lang}" }
            .toSortedMap()

        val categoryEntries: Map<String, String> =
            ImportCategory.entries.associate { it.name to it.label }

        return listOf(
            // ---------------------------------------------------------------------------------
            // A. Provider selection from the real registry
            // ---------------------------------------------------------------------------------
            Preference.PreferenceGroup(
                title = stringResource(R.string.import_provider_group),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(
                            R.string.import_provider_count,
                            providerEntries.size,
                        ),
                    ),
                    Preference.PreferenceItem.MultiSelectListPreference(
                        title = stringResource(R.string.import_select_providers),
                        icon = painterResource(R.drawable.plugin_lang),
                        entries = providerEntries,
                        preference = settings.importer.providers,
                        subtitle = "%s",
                        subtitleProvider = { selected, entries ->
                            val names = selected.mapNotNull { entries[it] }
                            when {
                                names.isEmpty() ->
                                    stringResource(R.string.import_no_provider_selected)

                                else -> names.joinToString(", ")
                            }
                        },
                    ),
                ),
            ),

            // ---------------------------------------------------------------------------------
            // B. Scan configuration
            // ---------------------------------------------------------------------------------
            Preference.PreferenceGroup(
                title = stringResource(R.string.import_scan_group),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.MultiSelectListPreference(
                        title = stringResource(R.string.import_categories),
                        icon = painterResource(R.drawable.movie_edit_24px),
                        entries = categoryEntries,
                        preference = settings.importer.categories,
                    ),
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(R.string.import_unlimited_scan_summary),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        title = stringResource(R.string.import_year_from),
                        preference = settings.importer.yearFrom,
                        subtitle = "%s",
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        title = stringResource(R.string.import_year_to),
                        preference = settings.importer.yearTo,
                        subtitle = "%s",
                    ),
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(R.string.import_added_date_title),
                        content = { AddedOnDateFilter(settings) },
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        title = stringResource(R.string.import_search_queries),
                        preference = settings.importer.searchQueries,
                        subtitle = "%s",
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        title = stringResource(R.string.import_resolve_links),
                        icon = painterResource(R.drawable.ic_baseline_ondemand_video_24),
                        subtitle = stringResource(R.string.import_resolve_links_summary),
                        preference = settings.importer.resolveLinks,
                    ),
                ),
            ),

            // ---------------------------------------------------------------------------------
            // C. Run: real progress, cancel, report
            // ---------------------------------------------------------------------------------
            Preference.PreferenceGroup(
                title = stringResource(R.string.import_run_group),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(R.string.import_run_group),
                        content = { RunPanel(state, linkRefreshState, settings, providers, context) },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        title = stringResource(R.string.import_upload_enabled),
                        icon = painterResource(R.drawable.cloud_2_solid),
                        subtitle = stringResource(R.string.import_upload_enabled_summary),
                        preference = settings.importer.uploadEnabled,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        title = stringResource(R.string.import_daily_enabled),
                        icon = painterResource(R.drawable.baseline_sync_24),
                        subtitle = stringResource(R.string.import_daily_enabled_summary),
                        preference = settings.importer.dailyImportEnabled,
                        onValueChanged = { enabled ->
                            ImportScheduler.setDailyEnabled(context, enabled)
                            true
                        },
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        title = stringResource(R.string.import_edge_key),
                        subtitle = stringResource(R.string.import_edge_key_summary),
                        preference = settings.importer.edgeFunctionKey,
                    ),
                ),
            ),

            // ---------------------------------------------------------------------------------
            // D. Preview before any write
            // ---------------------------------------------------------------------------------
            Preference.PreferenceGroup(
                title = stringResource(R.string.import_preview_group),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(R.string.import_preview_group),
                        content = { PreviewPanel(state, settings) },
                    ),
                ),
            ),
        )
    }

    @Composable
    private fun AddedOnDateFilter(settings: AppSettings) {
        val context = LocalContext.current
        var selectedDate by remember { mutableStateOf(settings.importer.addedOnDate.get()) }

        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val calendar = Calendar.getInstance()
                        selectedDate.takeIf { it.isNotBlank() }?.let { value ->
                            runCatching {
                                SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                                    isLenient = false
                                }.parse(value)
                            }.getOrNull()?.let(calendar::setTime)
                        }
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                val date = String.format(
                                    Locale.US,
                                    "%04d-%02d-%02d",
                                    year,
                                    month + 1,
                                    day,
                                )
                                settings.importer.addedOnDate.set(date)
                                selectedDate = date
                            },
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH),
                            calendar.get(Calendar.DAY_OF_MONTH),
                        ).show()
                    },
                ) {
                    Text(
                        if (selectedDate.isBlank()) stringResource(R.string.import_added_date_select)
                        else selectedDate,
                    )
                }
                if (selectedDate.isNotBlank()) {
                    TextButton(
                        onClick = {
                            settings.importer.addedOnDate.set("")
                            selectedDate = ""
                        },
                    ) {
                        Text(stringResource(R.string.import_added_date_clear))
                    }
                }
            }
            Text(
                text = stringResource(R.string.import_added_date_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun RunPanel(
        state: ImportManager.State,
        linkState: ProviderLinkRefreshManager.State,
        settings: AppSettings,
        providers: List<com.lagradost.cloudstream3.MainAPI>,
        context: android.content.Context,
    ) {
        LaunchedEffect(context) { ImportManager.restore(context.applicationContext) }
        var showProviderPicker by remember { mutableStateOf(false) }
        var selectedProvider by remember { mutableStateOf(providers.firstOrNull()?.name) }

        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = progressLabel(state.progress),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = progressCounters(state.progress),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.progress.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !state.running && !state.uploading && !linkState.running,
                    onClick = {
                            val (config, error) = ImportManager.configFromSettings(settings)
                            if (error != null) {
                                ImportManager.reportProblem(error)
                            } else {
                                ImportManager.startPreview(config, settings)
                            }
                        },
                ) {
                    Text(
                        stringResource(
                            if (state.sessionStatus == com.lagradost.cloudstream3.importsystem.ImportSessionStatus.PAUSED) {
                                R.string.import_resume_scan
                            } else R.string.import_start_scan,
                        ),
                    )
                }
                OutlinedButton(
                    enabled = !state.running && !state.uploading && !linkState.running,
                    onClick = {
                        selectedProvider = providers.firstOrNull()?.name
                        showProviderPicker = true
                    },
                ) {
                    Text(stringResource(R.string.import_new_link))
                }
                OutlinedButton(
                    enabled = state.running,
                    onClick = { ImportManager.pause() },
                ) {
                    Text(stringResource(R.string.import_pause_scan))
                }
                if (linkState.running) {
                    OutlinedButton(onClick = { ProviderLinkRefreshManager.cancel() }) {
                        Text(stringResource(R.string.import_new_link_cancel))
                    }
                }
            }

            if (linkState.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (linkState.totalTargets > 0) {
                Text(
                    text = stringResource(
                        R.string.import_new_link_progress,
                        linkState.completedTargets,
                        linkState.totalTargets,
                        linkState.linksResolved,
                        linkState.replacedContents,
                        linkState.replacedEpisodes,
                        linkState.failedTargets + linkState.unmatchedEpisodes,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            linkState.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val supabaseReady = ImportManager.supabaseConfigured()
            Text(
                text = stringResource(
                    if (supabaseReady) R.string.import_supabase_ready
                    else R.string.import_supabase_missing,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showProviderPicker) {
            AlertDialog(
                onDismissRequest = { showProviderPicker = false },
                title = { Text(stringResource(R.string.import_new_link_title)) },
                text = {
                    Column {
                        Text(
                            text = stringResource(R.string.import_new_link_description),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (providers.isEmpty()) {
                            Text(
                                text = stringResource(R.string.import_no_provider_installed),
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                                items(providers, key = { it.name }) { provider ->
                                    val selected = provider.name == selectedProvider
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = selected,
                                                role = Role.RadioButton,
                                                onClick = { selectedProvider = provider.name },
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = selected, onClick = null)
                                        Text("${provider.name} · ${provider.lang}")
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = selectedProvider != null && providers.isNotEmpty(),
                        onClick = {
                            selectedProvider?.let { ProviderLinkRefreshManager.start(it, settings) }
                            showProviderPicker = false
                        },
                    ) {
                        Text(stringResource(R.string.import_new_link_refresh))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showProviderPicker = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }

    @Composable
    private fun PreviewPanel(state: ImportManager.State, settings: AppSettings) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                state.preview.isEmpty() && state.report == null -> {
                    Text(
                        text = stringResource(R.string.import_preview_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                else -> {
                    val report = state.report
                    Text(
                        text = stringResource(
                            R.string.import_preview_summary,
                            state.preview.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    state.preview.take(PREVIEW_LIMIT).forEach { content ->
                        val detail = buildString {
                            append(content.title)
                            content.releaseYear?.let { append(" ($it)") }
                            append(" · ")
                            append(content.contentType)
                            append(" · ")
                            append(content.providerContents.joinToString { it.providerId })
                            val episodes = content.seasons.sumOf { it.episodes.size }
                            if (episodes > 0) append(" · $episodes episodes")
                        }
                        Text(
                            text = "• $detail",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.preview.size > PREVIEW_LIMIT) {
                        Text(
                            text = stringResource(
                                R.string.import_preview_more,
                                state.preview.size - PREVIEW_LIMIT,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    report?.let { ReportDetails(it, state.uploadMessage) }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !state.running && !state.uploading && state.preview.isNotEmpty(),
                            onClick = { ImportManager.uploadPreview(settings) },
                        ) {
                            Text(
                                stringResource(
                                    if (state.uploading) R.string.import_uploading
                                    else R.string.import_confirm,
                                ),
                            )
                        }
                        if (state.report != null) {
                            TextButton(onClick = { ImportManager.clearReport() }) {
                                Text(stringResource(R.string.import_dismiss))
                            }
                        }
                    }

                    state.uploadMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ReportDetails(report: ImportReport, uploadMessage: String?) {
        var expanded by remember(report) { mutableStateOf(false) }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(
                    R.string.import_report_counts,
                    report.progress.itemsLoaded,
                    report.failures.size,
                    report.warnings.size,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(
                        if (expanded) R.string.import_report_hide
                        else R.string.import_report_show,
                    ),
                )
            }
            if (expanded) {
                if (report.failures.isEmpty() && report.warnings.isEmpty() &&
                    report.reviewFlags.isEmpty()
                ) {
                    Text(
                        text = stringResource(R.string.import_report_clean),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                report.failures.forEach { failure ->
                    Text(
                        text = "✗ [${failure.stage}] ${failure.provider ?: "-"} — ${failure.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                report.warnings.forEach { warning ->
                    Text(
                        text = "! $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                report.reviewFlags.forEach { flag ->
                    Text(
                        text = "? $flag",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            report.uploadError?.let { error ->
                Text(
                    text = stringResource(R.string.import_upload_failed, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (report.jobId != null) {
                Text(
                    text = stringResource(R.string.import_job_id, report.jobId),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    @Composable
    private fun progressLabel(progress: ImportProgress): String = when (progress.phase) {
        ImportPhase.IDLE -> stringResource(R.string.import_phase_idle)
        ImportPhase.DISCOVERING -> stringResource(R.string.import_phase_discovering)
        ImportPhase.LOADING -> stringResource(R.string.import_phase_loading)
        ImportPhase.RESOLVING -> stringResource(R.string.import_phase_resolving)
        ImportPhase.WRITING -> stringResource(R.string.import_phase_writing)
        ImportPhase.PREVIEW_READY -> stringResource(R.string.import_phase_preview)
        ImportPhase.DONE -> stringResource(R.string.import_phase_done)
        ImportPhase.PAUSED -> stringResource(R.string.import_phase_paused)
        ImportPhase.CANCELLED -> stringResource(R.string.import_phase_cancelled)
        ImportPhase.FAILED -> stringResource(R.string.import_phase_failed)
    }

    private fun progressCounters(p: ImportProgress): String =
        "pages ${p.catalogPagesScanned} · discovered ${p.itemsDiscovered} · " +
            "loaded ${p.itemsLoaded} · filtered ${p.itemsFilteredOut} · " +
            "episodes ${p.episodesProcessed} · links ${p.linksResolved} · " +
            "duplicates ${p.duplicatesSkipped} · errors ${p.failed}"

    private const val PREVIEW_LIMIT = 12
}
