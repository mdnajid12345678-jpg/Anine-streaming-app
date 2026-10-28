package com.lagradost.cloudstream3.importsystem

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.isEpisodeBased
import com.lagradost.cloudstream3.isMovieType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

/**
 * The real import scanner.
 *
 * Every number this class reports comes from an actual CloudStream call:
 *  - discovery uses [MainAPI.getMainPage] and/or [MainAPI.search] with real pagination
 *  - metadata comes from [MainAPI.load] and the concrete [LoadResponse] subtypes
 *  - playable urls only ever come from [MainAPI.loadLinks] through the extractor callback
 *
 * Nothing here fabricates a url, a count or a success: when a provider cannot resolve a link
 * the item is recorded with an accurate error instead.
 */
class ImportEngine(
    private val config: ImportConfig,
    private val providers: List<MainAPI>,
    private val emit: (ImportProgress) -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    data class Result(
        val contents: Map<String, CanonicalContent>,
        val failures: List<ImportFailure>,
        val warnings: List<String>,
        val reviewFlags: List<String>,
        val progress: ImportProgress,
    )

    private val lock = Any()
    private val failures = mutableListOf<ImportFailure>()
    private val warnings = mutableListOf<String>()
    private val merged = LinkedHashMap<String, CanonicalContent>()

    private var progress = ImportProgress(
        phase = ImportPhase.DISCOVERING,
        startedAt = now(),
    )

    /** Progress and merged results are touched from concurrent tasks, so they are guarded. */
    private fun update(transform: (ImportProgress) -> ImportProgress) {
        synchronized(lock) {
            progress = transform(progress)
            emit(progress)
        }
    }

    private fun fail(provider: String?, stage: FailureStage, url: String?, message: String) {
        synchronized(lock) {
            failures += ImportFailure(
                provider = provider,
                stage = stage,
                url = url,
                message = message,
                at = now(),
            )
        }
        update { it.copy(failed = it.failed + 1) }
    }

    private fun warn(message: String) {
        synchronized(lock) { warnings += message }
    }

    private fun putContent(key: String, content: CanonicalContent) {
        synchronized(lock) { merged[key] = content }
    }

    private fun getContent(key: String): CanonicalContent? = synchronized(lock) { merged[key] }

    suspend fun run(): Result {
        val started = now()
        try {
            for (provider in providers) {
                coroutineContext.ensureActive()
                scanProvider(provider)
            }
        } catch (e: CancellationException) {
            update {
                it.copy(
                    phase = ImportPhase.CANCELLED,
                    finishedAt = now(),
                    message = "Cancelled after ${it.itemsLoaded} items",
                )
            }
            throw e
        } catch (t: Throwable) {
            update {
                it.copy(phase = ImportPhase.FAILED, finishedAt = now(), message = t.message)
            }
            throw t
        }

        update {
            it.copy(
                phase = ImportPhase.LOADING,
                message = "Building review list",
            )
        }

        val review = buildReviewFlags()
        val snapshot = synchronized(lock) { merged.toMap() }
        update {
            it.copy(
                phase = ImportPhase.PREVIEW_READY,
                finishedAt = now(),
                message = "Preview ready: ${snapshot.size} canonical titles",
            )
        }

        return Result(
            contents = snapshot,
            failures = synchronized(lock) { failures.toList() },
            warnings = synchronized(lock) { warnings.toList() },
            reviewFlags = review,
            progress = progress,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------------------------

    private suspend fun scanProvider(provider: MainAPI) {
        update { it.copy(providerName = provider.name, phase = ImportPhase.DISCOVERING) }

        val candidates = LinkedHashMap<String, SearchResponse>()
        var anyEntryPointWorked = false

        // 1. keyword seeds through the real search API
        for (query in config.searchQueries) {
            if (candidates.size >= config.maxItems) break
            var page = 1
            while (page <= config.maxPages && candidates.size < config.maxItems) {
                coroutineContext.ensureActive()
                val response = try {
                    retrying {
                        delay(config.requestDelayMs)
                        provider.search(query, page)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    fail(
                        provider.name,
                        FailureStage.DISCOVERY,
                        null,
                        "search(\"$query\", $page) failed: ${describe(t)}",
                    )
                    break
                }
                if (response == null) break
                anyEntryPointWorked = true
                addResponses(provider, response, candidates)
                if (!response.hasNext) break
                page++
            }
        }

        // 2. catalog pages through the real homepage API
        if (provider.hasMainPage && candidates.size < config.maxItems) {
            for (request in provider.mainPage) {
                if (candidates.size >= config.maxItems) break
                if (request.data.isBlank()) continue
                var page = 1
                while (page <= config.maxPages && candidates.size < config.maxItems) {
                    coroutineContext.ensureActive()
                    val mainPageRequest = MainPageRequest(
                        name = request.name,
                        data = request.data,
                        horizontalImages = request.horizontalImages,
                    )
                    val response = try {
                        retrying {
                            delay(config.requestDelayMs)
                            provider.getMainPage(page, mainPageRequest)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        fail(
                            provider.name,
                            FailureStage.DISCOVERY,
                            null,
                            "getMainPage(page=$page, \"${request.name}\") failed: ${describe(t)}",
                        )
                        break
                    }
                    if (response == null) break
                    anyEntryPointWorked = true
                    addHomeResponse(provider, response, candidates)
                    if (!response.hasNext) break
                    page++
                }
            }
        }

        if (!anyEntryPointWorked && candidates.isEmpty()) {
            fail(
                provider.name,
                FailureStage.PROVIDER,
                provider.mainUrl,
                "Provider exposes neither a usable getMainPage() nor search() result, " +
                    "so it cannot be scanned",
            )
            return
        }

        if (candidates.isEmpty()) {
            warn("${provider.name}: catalog returned no items for the selected filters")
            return
        }

        update {
            it.copy(
                phase = ImportPhase.LOADING,
                itemsDiscovered = it.itemsDiscovered + candidates.size,
                message = "Loading ${candidates.size} detail pages from ${provider.name}",
            )
        }

        processCandidates(provider, candidates.values.toList())
    }

    private fun addResponses(
        provider: MainAPI,
        response: SearchResponseList,
        into: MutableMap<String, SearchResponse>,
    ) {
        var added = 0
        for (item in response.items) {
            if (item.url.isBlank()) continue
            if (into.containsKey(item.url)) continue
            if (!config.categoryAllows(item.type)) {
                update { it.copy(itemsFilteredOut = it.itemsFilteredOut + 1) }
                continue
            }
            into[item.url] = item
            added++
        }
        if (added > 0) update { it.copy(itemsDiscovered = it.itemsDiscovered + added) }
    }

    private fun addHomeResponse(
        provider: MainAPI,
        response: HomePageResponse,
        into: MutableMap<String, SearchResponse>,
    ) {
        update { it.copy(catalogPagesScanned = it.catalogPagesScanned + 1) }
        for (row in response.items) {
            for (item in row.list) {
                if (item.url.isBlank()) continue
                if (into.containsKey(item.url)) continue
                if (!config.categoryAllows(item.type)) {
                    update { it.copy(itemsFilteredOut = it.itemsFilteredOut + 1) }
                    continue
                }
                into[item.url] = item
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Metadata
    // -----------------------------------------------------------------------------------------

    private suspend fun processCandidates(provider: MainAPI, candidates: List<SearchResponse>) {
        val limit = minOf(config.maxItems, candidates.size)
        val semaphore = Semaphore(config.concurrency)

        kotlinx.coroutines.coroutineScope {
            // Bounded concurrency: at most config.concurrency detail pages are in flight, and
            // the whole block is a child of the caller so cancellation stops it immediately.
            candidates.take(limit).forEach { candidate ->
                launch {
                    semaphore.withPermit {
                        coroutineContext.ensureActive()
                        processOne(provider, candidate)
                    }
                }
            }
        }
    }

    private suspend fun processOne(provider: MainAPI, candidate: SearchResponse) {
        update { it.copy(currentItem = candidate.name) }

        val loaded: LoadResponse = try {
            retrying {
                delay(config.requestDelayMs)
                provider.load(candidate.url)
            } ?: run {
                fail(
                    provider.name,
                    FailureStage.LOAD,
                    candidate.url,
                    "load() returned null for \"${candidate.name}\"",
                )
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            fail(
                provider.name,
                FailureStage.LOAD,
                candidate.url,
                "load(\"${candidate.url}\") failed: ${describe(t)}",
            )
            return
        }

        if (!config.categoryAllows(loaded.type)) {
            update { it.copy(itemsFilteredOut = it.itemsFilteredOut + 1) }
            return
        }
        if (!config.yearMatches(loaded.year)) {
            update { it.copy(itemsFilteredOut = it.itemsFilteredOut + 1) }
            return
        }

        val nowMs = now()
        val content = buildContent(provider, loaded, candidate, nowMs)
        val key = content.canonicalKey

        synchronized(lock) {
            val existing = merged[key]
            if (existing == null) {
                merged[key] = content
                update { it.copy(itemsLoaded = it.itemsLoaded + 1) }
            } else {
                val outcome = ContentMerger.merge(existing, content)
                merged[key] = outcome.content
                update {
                    it.copy(
                        itemsLoaded = it.itemsLoaded + 1,
                        duplicatesSkipped = it.duplicatesSkipped + 1,
                    )
                }
            }
        }

        if (config.resolveLinks) {
            resolveLinks(provider, loaded, key)
        }
    }

    private fun buildContent(
        provider: MainAPI,
        loaded: LoadResponse,
        candidate: SearchResponse,
        nowMs: Long,
    ): CanonicalContent {
        val episodic = isEpisodic(loaded)
        val kind = when {
            loaded.type.isMovieType() -> "movie"
            episodic -> "series"
            else -> "other"
        }
        val year = loaded.year
        val key = TitleNormalizer.canonicalKey(loaded.name, year, kind)

        val episodes = if (episodic) collectEpisodes(loaded) else emptyList()
        val contentSources = if (episodic) emptyList() else collectMovieSources(loaded, nowMs)

        val providerContents = listOf(
            ProviderContentRef(
                providerId = provider.name,
                detailUrl = loaded.url.ifBlank { candidate.url },
                lastSeenAt = nowMs,
                providerMetadata = buildMap {
                    put("api_name", loaded.apiName)
                    put("poster_url", loaded.posterUrl ?: "")
                    if (loaded.plot != null) put("has_description", "true")
                },
            ),
        )

        val status = (loaded as? TvSeriesLoadResponse)?.showStatus?.name
            ?: (loaded as? AnimeLoadResponse)?.showStatus?.name

        return CanonicalContent(
            canonicalKey = key,
            title = loaded.name,
            normalizedTitle = TitleNormalizer.normalize(loaded.name),
            originalTitle = (loaded as? AnimeLoadResponse)?.japName,
            contentType = contentTypeName(loaded.type),
            tvType = loaded.type.name,
            releaseYear = year,
            posterUrl = loaded.posterUrl,
            description = loaded.plot,
            genres = loaded.tags.orEmpty().filter { it.isNotBlank() },
            status = status,
            rating = loaded.score?.toDouble(10),
            externalIds = emptyMap(),
            providerContents = providerContents,
            seasons = episodes,
            sources = contentSources,
            updatedAt = nowMs,
        )
    }

    private fun isEpisodic(loaded: LoadResponse): Boolean =
        loaded is TvSeriesLoadResponse ||
            loaded is AnimeLoadResponse ||
            loaded.type.isEpisodeBased()

    private fun collectEpisodes(loaded: LoadResponse): List<ImportSeason> {
        val raw: List<Pair<String, Episode>> = when (loaded) {
            is TvSeriesLoadResponse -> loaded.episodes.map { "sub" to it }
            is AnimeLoadResponse -> loaded.episodes.entries.flatMap { (status, list) ->
                list.map { status.name.lowercase() to it }
            }
            else -> emptyList()
        }

        if (raw.isEmpty()) return emptyList()

        val bySeason = LinkedHashMap<Int?, MutableList<Pair<String, Episode>>>()
        raw.forEach { (variant, episode) ->
            bySeason.getOrPut(episode.season) { mutableListOf() }.add(variant to episode)
        }

        var order = 0
        return bySeason.entries.sortedBy { it.key ?: Int.MIN_VALUE }.map { (season, list) ->
            val episodes = list.mapIndexed { index, (variant, episode) ->
                ImportEpisode(
                    seasonNumber = episode.season,
                    episodeNumber = episode.episode,
                    title = episode.name,
                    description = episode.description,
                    posterUrl = episode.posterUrl,
                    detailUrl = null,
                    linkData = episode.data,
                    attributes = mapOf("variant" to variant),
                    sortOrder = index,
                )
            }.sortedWith(
                compareBy(
                    { it.episodeNumber ?: Int.MAX_VALUE },
                    { it.sortOrder },
                ),
            )
            ImportSeason(
                seasonNumber = season,
                name = null,
                sortOrder = order++,
                episodes = episodes,
            )
        }
    }

    private fun collectMovieSources(loaded: LoadResponse, nowMs: Long): List<ImportEpisodeSource> {
        val data = (loaded as? MovieLoadResponse)?.dataUrl
        if (data.isNullOrBlank()) return emptyList()
        // dataUrl is the provider's payload for loadLinks(), NOT a playable url.
        return listOf(
            ImportEpisodeSource(
                providerId = loaded.apiName,
                isResolved = false,
                status = "unresolved",
                linkType = "provider_payload",
                resolvedAt = null,
            ),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Link resolution through the real loadLinks()/extractor path
    // -----------------------------------------------------------------------------------------

    private suspend fun resolveLinks(
        provider: MainAPI,
        loaded: LoadResponse,
        key: String,
    ) {
        val targets: List<Pair<String, String>> = when {
            loaded is TvSeriesLoadResponse -> loaded.episodes
                .take(config.maxEpisodesPerSeries)
                .map { it.data to "s${it.season ?: 0}e${it.episode ?: 0}" }

            loaded is AnimeLoadResponse -> loaded.episodes.values
                .flatten()
                .take(config.maxEpisodesPerSeries)
                .map { it.data to "s${it.season ?: 0}e${it.episode ?: 0}" }

            loaded is MovieLoadResponse -> listOfNotNull(
                loaded.dataUrl.takeIf { it.isNotBlank() }?.let { it to "movie" },
            )

            else -> emptyList()
        }

        if (targets.isEmpty()) {
            warn(
                "${provider.name} \"${loaded.name}\": no loadLinks() payload available " +
                    "(provider did not expose a data url), link resolution skipped",
            )
            return
        }

        update { it.copy(phase = ImportPhase.RESOLVING) }

        for ((data, label) in targets) {
            coroutineContext.ensureActive()
            val resolvedAt = now()
            val found = mutableListOf<ResolvedLink>()
            var error: String? = null

            try {
                withLinkTimeout {
                    provider.loadLinks(
                        data = data,
                        isCasting = false,
                        subtitleCallback = { /* subtitles are not part of the import payload */ },
                        callback = { link -> found += link.toResolved() },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                error = describe(t)
            }

            if (error != null) {
                fail(
                    provider.name,
                    FailureStage.LINK_RESOLUTION,
                    loaded.url,
                    "loadLinks($label) failed: $error",
                )
            }

            val sources = if (found.isEmpty()) {
                listOf(
                    ImportEpisodeSource(
                        providerId = provider.name,
                        isResolved = false,
                        status = if (error == null) "no_links" else "error",
                        error = error,
                    ),
                )
            } else {
                found.map { it.toSource(provider.name, resolvedAt) }
            }

            update {
                it.copy(
                    linksResolved = it.linksResolved + found.size,
                    episodesProcessed = it.episodesProcessed + 1,
                )
            }

            attachSources(key, label, sources, provider.name)
        }
    }

    private fun attachSources(
        key: String,
        label: String,
        sources: List<ImportEpisodeSource>,
        @Suppress("UNUSED_PARAMETER") providerId: String,
    ) {
        synchronized(lock) {
            val content = merged[key] ?: return

            if (label == "movie") {
                merged[key] = content.copy(
                    sources = (content.sources + sources)
                        .distinctBy { ContentMerger.sourceKey(it) },
                )
                return
            }

            val seasonNumber = label.substringBefore('e').removePrefix("s").toIntOrNull()
            val episodeNumber = label.substringAfter('e').toIntOrNull()

            val seasons = content.seasons.toMutableList()
            val seasonIndex = seasons.indexOfFirst { it.seasonNumber == seasonNumber }
            if (seasonIndex < 0) return

            val season = seasons[seasonIndex]
            val episodeIndex = season.episodes.indexOfFirst {
                it.episodeNumber == episodeNumber &&
                    (seasonNumber == null || it.seasonNumber == seasonNumber)
            }
            if (episodeIndex < 0) return

            val episode = season.episodes[episodeIndex]
            val mergedEpisode = episode.copy(
                sources = (episode.sources + sources).distinctBy { ContentMerger.sourceKey(it) },
            )
            val episodes = season.episodes.toMutableList()
            episodes[episodeIndex] = mergedEpisode
            seasons[seasonIndex] = season.copy(episodes = episodes)

            merged[key] = content.copy(seasons = seasons)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Review flags: ambiguous titles are reported, never merged silently
    // -----------------------------------------------------------------------------------------

    private fun buildReviewFlags(): List<String> {
        val list = synchronized(lock) { merged.values.toList() }
        val flags = linkedSetOf<String>()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i]
                val b = list[j]
                if (a.canonicalKey == b.canonicalKey) continue
                if (TitleNormalizer.isAmbiguousPair(
                        a.title, a.releaseYear, a.contentType,
                        b.title, b.releaseYear, b.contentType,
                    )
                ) {
                    flags += "Ambiguous match: \"${a.title}\" (${a.releaseYear}) vs " +
                        "\"${b.title}\" (${b.releaseYear}) — kept separate for review"
                }
            }
        }
        return flags.toList()
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var last: Throwable? = null
        var attempt = 0
        while (attempt <= config.maxRetries) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                last = t
                if (attempt == config.maxRetries) break
                // Exponential backoff with a hard ceiling so a run always terminates.
                delay(minOf(config.requestDelayMs * (1L shl attempt), 8_000L))
                attempt++
            }
        }
        throw last ?: IllegalStateException("retry loop exited without result")
    }

    private suspend fun <T> withLinkTimeout(block: suspend () -> T): T =
        kotlinx.coroutines.withTimeout(config.linkTimeoutMs) { block() }

    private fun describe(t: Throwable): String {
        val message = t.message?.trim().orEmpty()
        return if (message.isEmpty()) t::class.simpleName ?: "unknown error" else message
    }

    private data class ResolvedLink(
        val source: String,
        val name: String,
        val url: String,
        val quality: Int,
        val type: String,
    )

    private fun com.lagradost.cloudstream3.utils.ExtractorLink.toResolved() = ResolvedLink(
        source = source,
        name = name,
        url = url,
        quality = quality,
        type = type.name,
    )

    private fun ResolvedLink.toSource(providerId: String, resolvedAt: Long) = ImportEpisodeSource(
        providerId = providerId,
        extractorName = source,
        serverName = name,
        mediaUrl = url,
        quality = quality.takeIf { it > 0 }?.toString(),
        linkType = type,
        isResolved = true,
        resolvedAt = resolvedAt,
        status = "resolved",
    )

    companion object {
        fun contentTypeName(type: TvType): String = when (type) {
            TvType.AnimeMovie -> "anime_movie"
            TvType.TvSeries -> "series"
            TvType.Anime -> "anime_series"
            TvType.OVA -> "ova"
            TvType.Cartoon -> "cartoon"
            TvType.Movie -> "movie"
            TvType.AsianDrama -> "asian_drama"
            TvType.Documentary -> "documentary"
            TvType.Live -> "live"
            else -> "other"
        }

        /**
         * Providers currently enabled in this running app. This is the app's real registry,
         * not a hard-coded list: built-in providers and installed plugins both register here.
         */
        fun installedProviders(): List<MainAPI> {
            val enabled = APIHolder.apis.withLock { APIHolder.apis.toList() }
            return enabled.ifEmpty {
                APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
            }
        }
    }
}
