package com.lagradost.cloudstream3.importsystem

import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream4.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

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

        val config = ImportConfig(
            providerNames = prefs.providers.get(),
            categories = categories,
            yearFrom = from,
            yearTo = to,
            maxItems = prefs.maxItems.get().coerceAtLeast(1),
            maxPages = prefs.maxPages.get().coerceAtLeast(1),
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

    fun startPreview(config: ImportConfig, settings: AppSettings) {
        if (job?.isActive == true) return

        val validationError = config.validate()
        if (validationError != null) {
            _state.update { it.copy(uploadMessage = validationError) }
            return
        }

        val providers = ImportEngine.installedProviders()
            .filter { it.name in config.providerNames }

        if (providers.isEmpty()) {
            _state.update {
                it.copy(uploadMessage = "None of the selected providers are currently installed")
            }
            return
        }

        _state.update {
            State(
                running = true,
                progress = ImportProgress(phase = ImportPhase.DISCOVERING, startedAt = nowMs()),
            )
        }

        job = scope.launch {
            val started = nowMs()
            try {
                val engine = ImportEngine(
                    config = config,
                    providers = providers,
                    emit = { progress -> _state.update { it.copy(progress = progress) } },
                )
                val result = withContext(Dispatchers.IO) { engine.run() }

                _state.update {
                    it.copy(
                        running = false,
                        progress = result.progress,
                        preview = result.contents.values
                            .sortedWith(compareByDescending<CanonicalContent> { it.releaseYear ?: 0 }
                                .thenBy { it.title.lowercase() }),
                        report = ImportReport(
                            progress = result.progress,
                            failures = result.failures,
                            warnings = result.warnings,
                            reviewFlags = result.reviewFlags,
                            durationMs = nowMs() - started,
                        ),
                        uploadMessage = null,
                    )
                }

                // Auto-save: a scan the user explicitly started is written to Supabase as soon
                // as it finishes, so a long scan is never lost to a missed button press. It
                // still honours the "Upload to Supabase" preference; with it off the preview
                // waits for the manual "Import to database" button exactly as before.
                val completed = result.progress.phase == ImportPhase.PREVIEW_READY ||
                    result.progress.phase == ImportPhase.DONE
                if (config.uploadToSupabase &&
                    BuildConfig.SUPABASE_CONFIGURED &&
                    completed &&
                    result.contents.isNotEmpty()
                ) {
                    uploadPreview(settings)
                }
            } catch (t: Throwable) {
                _state.update { current ->
                    val cancelled = current.progress.phase == ImportPhase.CANCELLED
                    val progress = current.progress.copy(
                        phase = if (cancelled) ImportPhase.CANCELLED else ImportPhase.FAILED,
                        finishedAt = nowMs(),
                        message = t.message,
                    )
                    val failure = ImportFailure(
                        provider = current.progress.providerName,
                        stage = FailureStage.PROVIDER,
                        message = t.message ?: t::class.simpleName ?: "unknown error",
                        at = nowMs(),
                    )
                    current.copy(
                        running = false,
                        progress = progress,
                        report = ImportReport(
                            progress = progress,
                            failures = current.report?.failures.orEmpty() + failure,
                            warnings = current.report?.warnings.orEmpty(),
                            reviewFlags = current.report?.reviewFlags.orEmpty(),
                            durationMs = nowMs() - (current.progress.startedAt ?: nowMs()),
                            uploadError = t.message,
                        ),
                    )
                }
            }
        }
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
            val config = configResult.first
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
                        put("max_items", config.maxItems.toString())
                        put("max_pages", config.maxPages.toString())
                        put("year_from", config.yearFrom?.toString() ?: "")
                        put("year_to", config.yearTo?.toString() ?: "")
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
                settings.importer.lastRunAt.set(nowMs())
                _state.update {
                    it.copy(
                        uploading = false,
                        jobId = jobId,
                        uploadMessage = buildString {
                            append("Uploaded ")
                            append(result.upsertedContents)
                            append(" titles, ")
                            append(result.upsertedEpisodes)
                            append(" episodes, ")
                            append(result.upsertedSources)
                            append(" sources")
                            if (result.errors.isNotEmpty()) {
                                append(" — ")
                                append(result.errors.joinToString("; ").take(300))
                            }
                        },
                        report = it.report?.copy(
                            uploaded = true,
                            jobId = jobId,
                            uploadError = null,
                        ),
                        progress = it.progress.copy(
                            phase = ImportPhase.DONE,
                            message = null,
                        ),
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        uploading = false,
                        uploadMessage = t.message ?: "Upload failed",
                        report = it.report?.copy(
                            uploaded = false,
                            jobId = jobId,
                            uploadError = t.message,
                        ),
                        // Back to the preview so the manual button can retry after a failure.
                        progress = it.progress.copy(
                            phase = ImportPhase.PREVIEW_READY,
                            message = null,
                        ),
                    )
                }
            }
        }
    }

    /** Clears the finished report when the user dismisses it. */
    fun clearReport() {
        _state.update {
            it.copy(report = null, preview = emptyList(), uploadMessage = null, jobId = null)
        }
    }

    /** Surfaces a configuration/validation problem in the UI without starting any scan. */
    fun reportProblem(message: String) {
        _state.update { it.copy(uploadMessage = message) }
    }

    private fun nowMs(): Long = System.currentTimeMillis()
}

