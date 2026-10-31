package com.lagradost.cloudstream3.importsystem

import android.content.Context
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream4.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App scoped coordinator for the import feature.
 *
 * It owns exactly one scan at a time, exposes real counters to the UI through [state] and
 * performs the upload through the Supabase Edge Function. The scan itself is always the real
 * [ImportEngine]; there is no simulated progress anywhere in this class.
 */
object ImportManager {

    data class State(
        val running: Boolean = false,
        val uploading: Boolean = false,
        val progress: ImportProgress = ImportProgress(),
        /** Result of the last completed scan, shown as a preview before any write. */
        val preview: List<CanonicalContent> = emptyList(),
        val report: ImportReport? = null,
        val uploadMessage: String? = null,
        val jobId: String? = null,
        /** Cursor produced by the preview; committed only once its data is safely stored. */
        val nextPageOffsets: Map<String, Int> = emptyMap(),
        val sessionConfig: ImportConfig? = null,
        val sessionStatus: ImportSessionStatus? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var sessionStore: ImportSessionStore? = null
    private val pauseRequested = AtomicBoolean(false)

    /** Initialize once per process and restore a saved scan before the user resumes it. */
    fun restore(context: Context) {
        if (sessionStore != null) return
        val store = ImportSessionStore(context.applicationContext)
        sessionStore = store
        val saved = store.loadSession() ?: return
        val pending = store.loadPendingContents().sortedBy { it.title.lowercase() }
        _state.update {
            it.copy(
                running = false,
                progress = saved.progress,
                preview = pending,
                report = saved.report,
                nextPageOffsets = saved.nextPageOffsets,
                sessionConfig = saved.config,
                sessionStatus = saved.status,
                uploadMessage = if (saved.status == ImportSessionStatus.PAUSED) {
                    "Saved scan restored. Resume continues from the last committed page."
                } else null,
            )
        }
    }

    /**
     * Builds an [ImportConfig] from persisted user preferences. Validation problems are
     * returned instead of being silently ignored.
     */
    fun configFromSettings(settings: AppSettings): Pair<ImportConfig, String?> {
        val prefs = settings.importer
        val categories = prefs.categories.get()
            .mapNotNull { name -> ImportCategory.entries.firstOrNull { it.name == name } }
            .toSet()
            .ifEmpty { ImportCategory.DEFAULT }

        val from = prefs.yearFrom.get().trim().toIntOrNull()
        val to = prefs.yearTo.get().trim().toIntOrNull()
        val addedOnDate = prefs.addedOnDate.get().trim().takeIf { it.isNotEmpty() }

        val config = ImportConfig(
            providerNames = prefs.providers.get(),
            categories = categories,
            yearFrom = from,
            yearTo = to,
            addedOnDate = addedOnDate,
            maxItems = prefs.maxItems.get().coerceAtLeast(1),
            maxPages = prefs.maxPages.get().coerceAtLeast(1),
            pageOffsets = readPageOffsets(prefs.scanPageOffsets.get()),
            searchQueries = prefs.searchQueries.get()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            resolveLinks = prefs.resolveLinks.get(),
            maxEpisodesPerSeries = prefs.maxEpisodesPerSeries.get().coerceAtLeast(1),
            uploadToSupabase = prefs.uploadEnabled.get(),
        )
        return config to config.validate()
    }

    /** True when this build carries the public Supabase configuration. */
    fun supabaseConfigured(): Boolean = BuildConfig.SUPABASE_CONFIGURED

    private fun readPageOffsets(raw: String): Map<String, Int> = runCatching {
        Json.decodeFromString<Map<String, Int>>(raw).filterValues { it >= 1 }
    }.getOrDefault(emptyMap())

    /** Compatibility cursor for existing daily/incremental scans. */
    fun persistScanPageOffsets(settings: AppSettings, offsets: Map<String, Int>) {
        settings.importer.scanPageOffsets.set(Json.encodeToString(offsets))
    }

    fun startPreview(config: ImportConfig, settings: AppSettings) {
        if (_state.value.sessionStatus == ImportSessionStatus.PAUSED) {
            resume(settings)
            return
        }
        if (job?.isActive == true || _state.value.uploading ||
            ProviderLinkRefreshManager.state.value.running
        ) return

        val store = sessionStore
        if (store == null) {
            reportProblem("Import session storage is not initialized yet; reopen this screen and try again")
            return
        }
        val previous = _state.value
        if (previous.preview.isNotEmpty() && previous.report?.uploaded != true) {
            _state.update { it.copy(uploadMessage = "Import or keep the saved batch before starting a new scan") }
            return
        }

        val scanConfig = config.copy(
            maxItems = Int.MAX_VALUE,
            maxPages = Int.MAX_VALUE,
            maxEpisodesPerSeries = Int.MAX_VALUE,
            stopAtEndOfEntryPoint = true,
        )
        val validationError = scanConfig.validate()
        if (validationError != null) {
            _state.update { it.copy(uploadMessage = validationError) }
            return
        }
        store.clearAll()
        val initialProgress = ImportProgress(
            phase = ImportPhase.DISCOVERING,
            startedAt = nowMs(),
            message = "Checking previously imported titles",
        )
        runScan(scanConfig, settings, emptyMap(), initialProgress, null)
    }

    fun resume(settings: AppSettings) {
        if (job?.isActive == true || _state.value.uploading ||
            ProviderLinkRefreshManager.state.value.running
        ) return
        val store = sessionStore
        val saved = store?.loadSession()
        if (store == null || saved == null) {
            reportProblem("No saved scan checkpoint was found")
            return
        }
        if (saved.status == ImportSessionStatus.COMPLETED) {
            reportProblem("This scan is already complete; import its saved batch or start a new scan")
            return
        }
        val config = saved.config.copy(pageOffsets = saved.nextPageOffsets)
        val pending = store.loadPendingContents().associateBy { it.canonicalKey }
        runScan(
            config,
            settings,
            pending,
            saved.progress,
            saved.report?.copy(uploaded = false, jobId = null),
        )
    }

    /** Stops at the end of the current provider page, after saving the cursor and scanned batch. */
    fun pause() {
        if (!_state.value.running) return
        pauseRequested.set(true)
        _state.update {
            it.copy(progress = it.progress.copy(message = "Pausing after the current page…"))
        }
    }

    private fun runScan(
        config: ImportConfig,
        settings: AppSettings,
        initialContents: Map<String, CanonicalContent>,
        initialProgress: ImportProgress,
        previousReport: ImportReport?,
    ) {
        val store = sessionStore ?: return
        val providers = ImportEngine.installedProviders().filter { it.name in config.providerNames }
        if (providers.isEmpty()) {
            _state.update { it.copy(uploadMessage = "None of the selected providers are currently installed") }
            return
        }
        pauseRequested.set(false)
        val runningProgress = initialProgress.copy(
            phase = ImportPhase.DISCOVERING,
            finishedAt = null,
            message = if (initialContents.isEmpty()) "Checking previously imported titles" else "Resuming saved scan",
        )
        store.saveSession(
            ImportScanSession(
                config = config.copy(pageOffsets = config.pageOffsets),
                progress = runningProgress,
                nextPageOffsets = config.pageOffsets,
                status = ImportSessionStatus.RUNNING,
                report = previousReport,
                updatedAt = nowMs(),
            ),
        )
        _state.update {
            it.copy(
                running = true,
                progress = runningProgress,
                preview = initialContents.values.sortedBy { content -> content.title.lowercase() },
                report = previousReport,
                nextPageOffsets = config.pageOffsets,
                sessionConfig = config,
                sessionStatus = ImportSessionStatus.RUNNING,
                uploadMessage = null,
            )
        }

        job = scope.launch {
            val startedAt = initialProgress.startedAt ?: nowMs()
            try {
                val callerKey = settings.importer.edgeFunctionKey.get().trim()
                val existingTitles = SupabaseImportClient(
                    functionUrl = BuildConfig.SUPABASE_IMPORT_FUNCTION_URL,
                    publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                    callerKey = callerKey,
                ).existingProviderTitles(config.providerNames)

                val engine = ImportEngine(
                    config = config,
                    providers = providers,
                    existingProviderTitles = existingTitles,
                    initialContents = initialContents,
                    initialWarnings = previousReport?.warnings.orEmpty(),
                    initialProgress = initialProgress,
                    emit = { progress -> _state.update { it.copy(progress = progress) } },
                    pauseRequested = { pauseRequested.get() },
                    onContentChanged = { content ->
                        store.savePendingContent(content)
                        _state.update { current -> current.withPendingContent(content) }
                    },
                    onCheckpoint = { checkpoint ->
                        val status = if (pauseRequested.get() ||
                            checkpoint.progress.phase == ImportPhase.PAUSED
                        ) ImportSessionStatus.PAUSED else ImportSessionStatus.RUNNING
                        val savedConfig = config.copy(pageOffsets = checkpoint.nextPageOffsets)
                        val report = _state.value.report
                        store.saveSession(
                            ImportScanSession(
                                config = savedConfig,
                                progress = checkpoint.progress,
                                nextPageOffsets = checkpoint.nextPageOffsets,
                                status = status,
                                report = report,
                                updatedAt = nowMs(),
                            ),
                        )
                        _state.update { current ->
                            current.copy(
                                progress = checkpoint.progress,
                                preview = checkpoint.contents.values.sortedBy { it.title.lowercase() },
                                nextPageOffsets = checkpoint.nextPageOffsets,
                                sessionConfig = savedConfig,
                                sessionStatus = status,
                            )
                        }
                    },
                )
                val result = withContext(Dispatchers.IO) { engine.run() }
                val sessionStatus = if (result.progress.phase == ImportPhase.PAUSED) {
                    ImportSessionStatus.PAUSED
                } else {
                    ImportSessionStatus.COMPLETED
                }
                val report = ImportReport(
                    progress = result.progress,
                    failures = previousReport?.failures.orEmpty() + result.failures,
                    warnings = result.warnings,
                    reviewFlags = result.reviewFlags,
                    durationMs = nowMs() - startedAt,
                )
                val savedConfig = config.copy(pageOffsets = result.nextPageOffsets)
                store.saveSession(
                    ImportScanSession(
                        config = savedConfig,
                        progress = result.progress,
                        nextPageOffsets = result.nextPageOffsets,
                        status = sessionStatus,
                        report = report,
                        updatedAt = nowMs(),
                    ),
                )
                val pending = store.loadPendingContents()
                _state.update {
                    it.copy(
                        running = false,
                        progress = result.progress,
                        preview = pending.sortedBy { content -> content.title.lowercase() },
                        report = report,
                        nextPageOffsets = result.nextPageOffsets,
                        sessionConfig = savedConfig,
                        sessionStatus = sessionStatus,
                        uploadMessage = null,
                    )
                }

                val completed = sessionStatus == ImportSessionStatus.COMPLETED
                if (completed && config.uploadToSupabase && BuildConfig.SUPABASE_CONFIGURED && pending.isNotEmpty()) {
                    uploadPreview(settings)
                } else if (completed && pending.isEmpty() && result.failures.isEmpty()) {
                    persistScanPageOffsets(settings, result.nextPageOffsets)
                }
            } catch (t: Throwable) {
                val cancelled = t is CancellationException || pauseRequested.get()
                val current = _state.value
                val progress = current.progress.copy(
                    phase = ImportPhase.PAUSED,
                    finishedAt = nowMs(),
                    message = if (cancelled) "Scan saved and paused at the last checkpoint" else t.message,
                )
                val failure = if (cancelled) null else ImportFailure(
                    provider = current.progress.providerName,
                    stage = FailureStage.PROVIDER,
                    message = t.message ?: t::class.simpleName ?: "unknown error",
                    at = nowMs(),
                )
                val report = ImportReport(
                    progress = progress,
                    failures = current.report?.failures.orEmpty() + listOfNotNull(failure),
                    warnings = current.report?.warnings.orEmpty(),
                    reviewFlags = current.report?.reviewFlags.orEmpty(),
                    durationMs = nowMs() - startedAt,
                    uploadError = t.message,
                )
                val savedConfig = config.copy(pageOffsets = current.nextPageOffsets)
                store.saveSession(
                    ImportScanSession(
                        config = savedConfig,
                        progress = progress,
                        nextPageOffsets = current.nextPageOffsets,
                        status = ImportSessionStatus.PAUSED,
                        report = report,
                        updatedAt = nowMs(),
                    ),
                )
                _state.update {
                    it.copy(
                        running = false,
                        progress = progress,
                        preview = store.loadPendingContents().sortedBy { content -> content.title.lowercase() },
                        report = report,
                        sessionConfig = savedConfig,
                        sessionStatus = ImportSessionStatus.PAUSED,
                    )
                }
            }
        }
    }

    private fun State.withPendingContent(content: CanonicalContent): State {
        val current = preview.firstOrNull { it.canonicalKey == content.canonicalKey }
        val merged = current?.let { ContentMerger.merge(it, content).content } ?: content
        return copy(
            preview = (preview.filterNot { it.canonicalKey == content.canonicalKey } + merged)
                .sortedBy { it.title.lowercase() },
        )
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * Writes the preview through the Supabase Edge Function. Returns without claiming success
     * when the build carries no configuration or the function rejects the payload.
     */
    fun uploadPreview(settings: AppSettings) {
        val snapshot = _state.value
        val preview = snapshot.preview
        if (preview.isEmpty() || snapshot.uploading) return

        val callerKey = settings.importer.edgeFunctionKey.get().trim()
        if (callerKey.isEmpty()) {
            // Honest state: nothing was written, and exactly what is missing is said.
            _state.update {
                it.copy(
                    uploadMessage = "Scan kept on screen, but nothing was saved yet: set the " +
                        "edge function caller key, then tap \"Import to database\"",
                )
            }
            return
        }

        _state.update {
            it.copy(
                uploading = true,
                uploadMessage = null,
                progress = it.progress.copy(
                    phase = ImportPhase.WRITING,
                    message = "Saving ${preview.size} titles to Supabase",
                ),
            )
        }

        scope.launch {
            val configResult = configFromSettings(settings)
            val config = snapshot.sessionConfig ?: configResult.first
            val startedAt = snapshot.progress.startedAt ?: nowMs()
            val jobId = java.util.UUID.randomUUID().toString()

            val payload = ImportUploadPayload(
                job = ImportJobRecord(
                    jobId = jobId,
                    providerIds = preview.flatMap { c -> c.providerContents.map { it.providerId } }
                        .distinct()
                        .sorted(),
                    filters = buildMap {
                        put("categories", config.categories.joinToString(",") { it.name })
                        put("scan_mode", "unbounded_until_paused_or_catalog_end")
                        put("year_from", config.yearFrom?.toString() ?: "")
                        put("year_to", config.yearTo?.toString() ?: "")
                        put("added_on_date", config.addedOnDate.orEmpty())
                        put("resolve_links", config.resolveLinks.toString())
                        put("source", "android_native_import")
                    },
                    status = "completed",
                    startedAt = startedAt,
                    finishedAt = nowMs(),
                    counters = mapOf(
                        "pages" to snapshot.progress.catalogPagesScanned,
                        "discovered" to snapshot.progress.itemsDiscovered,
                        "loaded" to snapshot.progress.itemsLoaded,
                        "filtered" to snapshot.progress.itemsFilteredOut,
                        "episodes" to snapshot.progress.episodesProcessed,
                        "links_resolved" to snapshot.progress.linksResolved,
                        "duplicates" to snapshot.progress.duplicatesSkipped,
                        "failed" to snapshot.progress.failed,
                    ),
                    errorSummary = snapshot.report?.failures?.takeIf { it.isNotEmpty() }
                        ?.joinToString("; ") { "${it.stage}: ${it.message}" }
                        ?.take(500),
                ),
                providers = preview.flatMap { it.providerContents }
                    .distinctBy { it.providerId }
                    .map { ref ->
                        ProviderRef(
                            providerId = ref.providerId,
                            displayName = ref.providerId,
                            language = null,
                            mainUrl = null,
                        )
                    },
                contents = preview,
                duplicateCandidates = snapshot.report?.reviewFlags.orEmpty().mapIndexed { i, note ->
                    DuplicateCandidate(
                        canonicalKey = "review|$i",
                        title = note,
                        releaseYear = null,
                        tvType = "other",
                        conflictingTitles = emptyList(),
                        reason = "ambiguous_title",
                        detectedAt = nowMs(),
                    )
                },
                itemErrors = snapshot.report?.failures.orEmpty(),
            )

            val client = SupabaseImportClient(
                functionUrl = BuildConfig.SUPABASE_IMPORT_FUNCTION_URL,
                publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                callerKey = callerKey,
            )

            try {
                val result = client.upload(payload)
                if (result.errors.isNotEmpty()) {
                    throw IllegalStateException(result.errors.joinToString("; ").take(500))
                }
                val stillPaused = snapshot.sessionStatus == ImportSessionStatus.PAUSED
                val phase = if (stillPaused) ImportPhase.PAUSED else ImportPhase.DONE
                val progress = snapshot.progress.copy(
                    phase = phase,
                    message = if (stillPaused) "Batch imported; scan remains paused at its saved checkpoint" else null,
                )
                val report = snapshot.report?.copy(
                    progress = progress,
                    uploaded = true,
                    jobId = jobId,
                    uploadError = null,
                )
                sessionStore?.clearPendingContents()
                snapshot.sessionConfig?.let { savedConfig ->
                    sessionStore?.saveSession(
                        ImportScanSession(
                            config = savedConfig.copy(pageOffsets = snapshot.nextPageOffsets),
                            progress = progress,
                            nextPageOffsets = snapshot.nextPageOffsets,
                            status = if (stillPaused) ImportSessionStatus.PAUSED else ImportSessionStatus.COMPLETED,
                            report = report,
                            updatedAt = nowMs(),
                        ),
                    )
                }
                if (!stillPaused) persistScanPageOffsets(settings, snapshot.nextPageOffsets)
                settings.importer.lastRunAt.set(nowMs())
                _state.update {
                    it.copy(
                        uploading = false,
                        preview = emptyList(),
                        jobId = jobId,
                        uploadMessage = buildString {
                            append("Uploaded ")
                            append(result.upsertedContents)
                            append(" titles, ")
                            append(result.upsertedEpisodes)
                            append(" episodes, ")
                            append(result.upsertedSources)
                            append(" sources")
                        },
                        report = report,
                        progress = progress,
                        sessionStatus = if (stillPaused) ImportSessionStatus.PAUSED else ImportSessionStatus.COMPLETED,
                    )
                }
            } catch (t: Throwable) {
                val stillPaused = snapshot.sessionStatus == ImportSessionStatus.PAUSED
                val progress = snapshot.progress.copy(
                    phase = if (stillPaused) ImportPhase.PAUSED else ImportPhase.PREVIEW_READY,
                    message = null,
                )
                val report = snapshot.report?.copy(
                    uploaded = false,
                    jobId = jobId,
                    uploadError = t.message,
                    progress = progress,
                )
                snapshot.sessionConfig?.let { savedConfig ->
                    sessionStore?.saveSession(
                        ImportScanSession(
                            config = savedConfig.copy(pageOffsets = snapshot.nextPageOffsets),
                            progress = progress,
                            nextPageOffsets = snapshot.nextPageOffsets,
                            status = if (stillPaused) ImportSessionStatus.PAUSED else ImportSessionStatus.COMPLETED,
                            report = report,
                            updatedAt = nowMs(),
                        ),
                    )
                }
                _state.update {
                    it.copy(
                        uploading = false,
                        uploadMessage = t.message ?: "Upload failed",
                        report = report,
                        // Back to the preview so the manual button can retry after a failure.
                        progress = progress,
                    )
                }
            }
        }
    }

    /** Clears the finished report when the user dismisses it. */
    fun clearReport() {
        _state.update {
            it.copy(report = null, uploadMessage = null, jobId = null)
        }
    }

    /** Surfaces a configuration/validation problem in the UI without starting any scan. */
    fun reportProblem(message: String) {
        _state.update { it.copy(uploadMessage = message) }
    }

    private fun nowMs(): Long = System.currentTimeMillis()
}
