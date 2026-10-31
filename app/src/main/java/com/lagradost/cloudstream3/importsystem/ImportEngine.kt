package com.lagradost.cloudstream3.importsystem

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.ProviderAddedAt
import com.lagradost.cloudstream3.SearchResponse
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
    existingProviderTitles: Map<String, Set<String>> = emptyMap(),
    initialWarnings: List<String> = emptyList(),
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
    private val warnings = initialWarnings.toMutableList()
    private val merged = LinkedHashMap<String, CanonicalContent>()
    private val providerTitlesSeen = LinkedHashMap<String, MutableSet<String>>().apply {
        existingProviderTitles.forEach { (provider, titles) -> put(provider, titles.toMutableSet()) }
    }

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

        val seenUrls = LinkedHashSet<String>()
        var anyEntryPointWorked = false
        var newlyAdded = 0
        val missingAddedDates = java.util.concurrent.atomic.AtomicInteger()

        suspend fun processPageItems(items: List<SearchResponse>) {
            val candidates = items.filter { item ->
                if (item.url.isBlank() || !seenUrls.add(item.url)) {
                    false
                } else if (!config.categoryAllows(item.type)) {
                    update { it.copy(itemsFilteredOut = it.itemsFilteredOut + 1) }
                    false
                } else if (isKnownProviderTitle(provider.name, item.name)) {
                    update { it.copy(duplicatesSkipped = it.duplicatesSkipped + 1) }
                    false
                } else {
                    true
                }
            }
            if (candidates.isEmpty()) return

            update {
                it.copy(
                    phase = ImportPhase.LOADING,
                    message = "Loading new titles from ${provider.name}",
                    itemsDiscovered = it.itemsDiscovered + candidates.size,
                )
            }
            var index = 0
            while (index < candidates.size && newlyAdded < config.maxItems) {
                // Never start more potential additions than the remaining quota, so concurrent
                // loads cannot exceed the configured per-provider limit.
                val batchSize = minOf(config.concurrency, config.maxItems - newlyAdded)
                val end = minOf(index + batchSize, candidates.size)
                newlyAdded += processCandidates(
                    provider,
                    candidates.subList(index, end),
                    missingAddedDates,
                )
                index = end
            }
        }

        // 1. keyword seeds through the real search API
        for (query in config.searchQueries) {
            if (newlyAdded >= config.maxItems) break
            var page = 1
            while (page <= config.maxPages && newlyAdded < config.maxItems) {
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
                processPageItems(response.items)
                if (!response.hasNext) break
                page++
            }
        }

        // 2. catalog pages through the real homepage API
        if (provider.hasMainPage && newlyAdded < config.maxItems) {
            for (request in provider.mainPage) {
                if (newlyAdded >= config.maxItems) break
                if (request.data.isBlank()) continue
                var page = 1
                while (page <= config.maxPages && newlyAdded < config.maxItems) {
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
                    update { it.copy(catalogPagesScanned = it.catalogPagesScanned + 1) }
                    processPageItems(response.items.flatMap { row -> row.list })
                    if (!response.hasNext) break
                    page++
                }
            }
        }

        if (!anyEntryPointWorked) {
            fail(
                provider.name,
                FailureStage.PROVIDER,
                provider.mainUrl,
                "Provider exposes neither a usable getMainPage() nor search() result, " +
                    "so it cannot be scanned",
            )
            return
        }

        if (newlyAdded == 0) {
            warn("${provider.name}: no new titles matched the selected filters; previously saved titles were skipped")
        }
        if (missingAddedDates.get() > 0) {
            warn(
                "${provider.name}: skipped ${missingAddedDates.get()} item(s) because the provider " +
                    "did not expose an added date; exact-date scanning requires addedAt metadata",
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Metadata
    // -----------------------------------------------------------------------------------------

    private fun isKnownProviderTitle(providerName: String, title: String): Boolean {
        val normalized = TitleNormalizer.normalize(title)
        if (normalized.isEmpty()) return false
        return synchronized(lock) { normalized in providerTitlesSeen[providerName].orEmpty() }
    }

    private suspend fun processCandidates(
        provider: MainAPI,
        candidates: List<SearchResponse>,
        missingAddedDates: java.util.concurrent.atomic.AtomicInteger,
    ): Int {
        val semaphore = Semaphore(config.concurrency)
        val newlyAdded = java.util.concurrent.atomic.AtomicInteger()

        kotlinx.coroutines.coroutineScope {
            // Bounded concurrency: at most config.concurrency detail pages are in flight, and
            // the whole block is a child of the caller so cancellation stops it immediately.
            candidates.forEach { candidate ->
                launch {
                    semaphore.withPermit {
                        coroutineContext.ensureActive()
                        if (processOne(provider, candidate, missingAddedDates)) {
                            newlyAdded.incrementAndGet()
                        }
                    }
                }
            }
        }
        return newlyAdded.get()
    }

    private suspend fun processOne(
        provider: MainAPI,
        candidate: SearchResponse,
        missingAddedDates: java.util.concurrent.atomic.AtomicInteger,
    ): Boolean {
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
                return false
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
            return false
        }

        if (!config.categoryAllows(loaded.type)) {
            update { it.copy(itemsFilteredOut = it.itemsFilteredOut + 1) }
            return false
        }
        if (!config.yearMatches(loaded.year)) {
            update { it.copy(itemsFilteredOut = it.itemsFilteredOut + 1) }
            return false
        }

        val providerAddedAt = (candidate as? ProviderAddedAt)?.addedAt
        if (!config.addedDateMatches(providerAddedAt)) {
            if (providerAddedAt == null) missingAddedDates.incrementAndGet()
            update { it.copy(itemsFilteredOut = it.itemsFilteredOut + 1) }
            return false
        }

        val nowMs = now()
        val content = buildContent(provider, loaded, candidate, providerAddedAt, nowMs)
        val key = content.canonicalKey

        val (accepted, targetKey) = synchronized(lock) {
            val seenTitles = providerTitlesSeen.getOrPut(provider.name) { linkedSetOf() }
            if (!seenTitles.add(content.normalizedTitle)) {
                false to key
            } else {
                val matching = merged.entries.firstOrNull { (_, existing) ->
                    existing.contentType == content.contentType &&
                        existing.normalizedTitle == content.normalizedTitle &&
                        TitleNormalizer.canMerge(
                            existing.title,
                            existing.releaseYear,
                            content.title,
                            content.releaseYear,
                        )
                }
                val mergeKey = matching?.key ?: key
                val existing = matching?.value ?: merged[mergeKey]
                val incoming = if (existing != null && content.canonicalKey != mergeKey) {
                    content.copy(canonicalKey = mergeKey)
                } else {
                    content
                }
                merged[mergeKey] = if (existing == null) incoming else ContentMerger.merge(existing, incoming).content
                true to mergeKey
            }
        }
        update {
            it.copy(
                itemsLoaded = it.itemsLoaded + 1,
                duplicatesSkipped = it.duplicatesSkipped + if (accepted) 0 else 1,
            )
        }
        if (!accepted) return false

        if (config.resolveLinks) {
            resolveLinks(provider, loaded, targetKey)
        }
        return true
    }

    private fun buildContent(
        provider: MainAPI,
        loaded: LoadResponse,
        candidate: SearchResponse,
        providerAddedAt: Long?,
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
                    providerAddedAt?.let { put("added_at", it.toString()) }
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
        val referer: String,
        val headers: Map<String, String>,
    )

    private fun com.lagradost.cloudstream3.utils.ExtractorLink.toResolved() = ResolvedLink(
        source = source,
        name = name,
        url = url,
        quality = quality,
        type = type.name,
        referer = referer,
        headers = headers,
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
        headers = requestHeaders(),
    )

    /**
     * The exact request the extractor made, so a downstream player does not have to guess:
     * the extractor's own headers win, and its referer is added when it did not set one.
     */
    private fun ResolvedLink.requestHeaders(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) out[key] = value
        }
        if (referer.isNotBlank() && out.keys.none { it.equals("referer", ignoreCase = true) }) {
            out["Referer"] = referer
        }
        return out
    }

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
