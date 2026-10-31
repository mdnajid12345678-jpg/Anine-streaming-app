package com.lagradost.cloudstream3.importsystem

import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream4.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Coordinates the separate New Link action; it never creates or updates content metadata. */
object ProviderLinkRefreshManager {
    data class State(
        val running: Boolean = false,
        val providerName: String? = null,
        val totalTargets: Int = 0,
        val completedTargets: Int = 0,
        val linksResolved: Int = 0,
        val targetsWithLinks: Int = 0,
        val targetsWithoutLinks: Int = 0,
        val failedTargets: Int = 0,
        val replacedContents: Int = 0,
        val replacedEpisodes: Int = 0,
        val replacedSources: Int = 0,
        val unmatchedEpisodes: Int = 0,
        val message: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    fun start(providerName: String, settings: AppSettings) {
        if (job?.isActive == true || ImportManager.state.value.running ||
            ImportManager.state.value.uploading
        ) return

        val provider = ImportEngine.installedProviders().firstOrNull { it.name == providerName }
        if (provider == null) {
            _state.update { it.copy(message = "Provider $providerName is not currently installed") }
            return
        }
        val client = client(settings)
        val configError = client.configuration().reason
        if (configError != null) {
            _state.update { it.copy(message = configError) }
            return
        }
        if (settings.importer.edgeFunctionKey.get().trim().isEmpty()) {
            _state.update {
                it.copy(message = "Set the Import Edge Function caller key before refreshing links")
            }
            return
        }

        _state.value = State(
            running = true,
            providerName = providerName,
            message = "Loading saved $providerName content from the database",
        )
        job = scope.launch {
            try {
                val targets = loadTargets(client, providerName)
                if (targets.isEmpty()) {
                    _state.update {
                        it.copy(
                            running = false,
                            message = "No saved content was found for $providerName",
                        )
                    }
                    return@launch
                }

                _state.update {
                    it.copy(
                        totalTargets = targets.size,
                        message = "Found ${targets.size} saved items; resolving new playable links",
                    )
                }
                val result = ProviderLinkRefreshEngine(
                    provider = provider,
                    targets = targets,
                    emit = { progress ->
                        _state.update {
                            it.copy(
                                completedTargets = progress.completedTargets,
                                totalTargets = progress.totalTargets,
                                linksResolved = progress.linksResolved,
                                targetsWithLinks = progress.targetsWithLinks,
                                targetsWithoutLinks = progress.targetsWithoutLinks,
                                failedTargets = progress.failedTargets,
                                message = progress.message ?: it.message,
                            )
                        }
                    },
                ).run()

                val replacedTitleIds = linkedSetOf<String>()
                var replacedEpisodes = 0
                var replacedSources = 0
                var unmatchedEpisodes = 0
                val batches = replacementBatches(result.contents)
                batches.forEachIndexed { index, batch ->
                    val saved = client.replaceProviderLinks(providerName, batch)
                    if (saved.replacedContents > 0) batch.forEach { replacedTitleIds += it.contentId }
                    replacedEpisodes += saved.replacedEpisodes
                    replacedSources += saved.replacedSources
                    unmatchedEpisodes += saved.unmatchedEpisodes
                    _state.update {
                        it.copy(
                            replacedContents = replacedTitleIds.size,
                            replacedEpisodes = replacedEpisodes,
                            replacedSources = replacedSources,
                            unmatchedEpisodes = unmatchedEpisodes,
                            message = "Saved link refresh batch ${index + 1}/${batches.size}",
                        )
                    }
                }

                val summary = buildString {
                    append("Refreshed ")
                    append(replacedSources)
                    append(" playable links across ")
                    append(replacedTitleIds.size)
                    append(" titles / ")
                    append(replacedEpisodes)
                    append(" episodes")
                    if (result.progress.targetsWithoutLinks > 0) {
                        append("; ")
                        append(result.progress.targetsWithoutLinks)
                        append(" had no new link, so their saved links were kept")
                    }
                    if (result.failures.isNotEmpty()) {
                        append("; ")
                        append(result.failures.size)
                        append(" link/page failure(s)")
                    }
                    if (unmatchedEpisodes > 0) {
                        append("; ")
                        append(unmatchedEpisodes)
                        append(" episode(s) could not be matched safely")
                    }
                }
                _state.update {
                    it.copy(
                        running = false,
                        failedTargets = result.failures.size,
                        replacedContents = replacedTitleIds.size,
                        message = summary,
                    )
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(running = false, message = "New Link refresh cancelled; untouched old links were kept")
                }
                throw e
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        running = false,
                        failedTargets = it.failedTargets + 1,
                        message = "New Link refresh failed: ${t.message ?: t::class.simpleName}",
                    )
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun loadTargets(
        client: SupabaseImportClient,
        providerName: String,
    ): List<ProviderLinkRefreshTarget> {
        val byContentId = LinkedHashMap<String, ProviderLinkRefreshTarget>()
        var offset = 0
        while (true) {
            val page = client.providerLinkRefreshTargets(
                providerId = providerName,
                offset = offset,
            )
            page.targets.forEach { target -> byContentId.putIfAbsent(target.contentId, target) }
            if (!page.hasMore) break
            if (page.nextOffset <= offset) {
                throw ImportUploadException("Provider link lookup did not advance its page offset")
            }
            offset = page.nextOffset
            _state.update {
                it.copy(message = "Loading saved $providerName content: ${byContentId.size} found")
            }
        }
        return byContentId.values.toList()
    }

    /** Keep each request bounded, including unusually long-running series. */
    private fun replacementBatches(
        contents: List<ProviderLinkRefreshContent>,
    ): List<List<ProviderLinkRefreshContent>> = buildList {
        contents.forEach { content ->
            if (content.episodes.isEmpty()) {
                add(listOf(content))
            } else {
                content.episodes.chunked(EPISODES_PER_BATCH).forEachIndexed { index, episodes ->
                    add(
                        listOf(
                            content.copy(
                                sources = if (index == 0) content.sources else emptyList(),
                                episodes = episodes,
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun client(settings: AppSettings) = SupabaseImportClient(
        functionUrl = BuildConfig.SUPABASE_IMPORT_FUNCTION_URL,
        publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        callerKey = settings.importer.edgeFunctionKey.get().trim(),
    )

    private const val EPISODES_PER_BATCH = 25
}
