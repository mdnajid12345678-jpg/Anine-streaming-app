package com.lagradost.cloudstream3.importsystem

import com.lagradost.cloudstream3.TvType
import kotlinx.serialization.Serializable

/**
 * Content categories offered in the import dashboard.
 *
 * Every entry maps onto a real [TvType] that CloudStream providers actually emit in
 * [com.lagradost.cloudstream3.SearchResponse.type] / [com.lagradost.cloudstream3.LoadResponse.type],
 * so filtering happens against real provider data instead of a hard-coded list.
 */
@Serializable
enum class ImportCategory(val tvType: TvType, val label: String) {
    ANIME(TvType.Anime, "Anime"),
    ANIME_MOVIE(TvType.AnimeMovie, "Anime Movie"),
    OVA(TvType.OVA, "OVA"),
    MOVIE(TvType.Movie, "Movie"),
    TV_SERIES(TvType.TvSeries, "Series"),
    CARTOON(TvType.Cartoon, "Cartoon"),
    ASIAN_DRAMA(TvType.AsianDrama, "Asian Drama"),
    DOCUMENTARY(TvType.Documentary, "Documentary"),
    LIVE(TvType.Live, "TV / Live"),
    OTHERS(TvType.Others, "Other");

    companion object {
        val DEFAULT: Set<ImportCategory> =
            setOf(ANIME, ANIME_MOVIE, MOVIE, TV_SERIES, LIVE, OTHERS)

        fun fromTvType(type: TvType?): ImportCategory? =
            entries.firstOrNull { it.tvType == type }
    }
}

/** Reasons an item was not imported, kept verbatim for the final report. */
@Serializable
enum class FailureStage {
    DISCOVERY,
    LOAD,
    FILTER,
    LINK_RESOLUTION,
    SERIALIZATION,
    UPLOAD,
    PROVIDER,
}

/** A single real failure recorded during a run. Never synthesised. */
@Serializable
data class ImportFailure(
    val provider: String?,
    val stage: FailureStage,
    /** Provider detail/page url the failure happened on, when known. */
    val url: String? = null,
    val message: String,
    val at: Long,
)

/** Immutable scan/import configuration chosen by the user. */
@Serializable
data class ImportConfig(
    /** Names as reported by [com.lagradost.cloudstream3.MainAPI.name] of selected providers. */
    val providerNames: Set<String> = emptySet(),
    val categories: Set<ImportCategory> = ImportCategory.DEFAULT,
    /** Inclusive release-year window, null means "any". */
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    /** Exact provider catalog-addition date (yyyy-MM-dd), null means no date filter. */
    val addedOnDate: String? = null,
    /** Hard cap on new, unique titles added for each selected provider in a run. */
    val maxItems: Int = 60,
    /** Hard cap on catalog pages requested per main-page request. */
    val maxPages: Int = 3,
    /** Next page per provider/search/catalog entry point, persisted after a successful save. */
    val pageOffsets: Map<String, Int> = emptyMap(),
    /** Exhausted entry points stay completed for a resumable one-pass scan instead of wrapping. */
    val stopAtEndOfEntryPoint: Boolean = false,
    /** Optional keyword seeds, scanned through MainAPI.search() when the provider supports it. */
    val searchQueries: List<String> = emptyList(),
    /** When false only metadata is imported, loadLinks() is never called. */
    val resolveLinks: Boolean = false,
    /** Upper bound on episodes resolved per series to keep runs bounded. */
    val maxEpisodesPerSeries: Int = 12,
    /** Delay between provider requests, to respect provider rate limits. */
    val requestDelayMs: Long = 400L,
    val requestTimeoutMs: Long = 30_000L,
    val linkTimeoutMs: Long = 45_000L,
    val maxRetries: Int = 2,
    val concurrency: Int = 3,
    /** Upload the preview to Supabase; local preview always works without it. */
    val uploadToSupabase: Boolean = true,
) {
    /** Returns a human readable problem, or null when the configuration is valid. */
    fun validate(): String? {
        if (providerNames.isEmpty()) return "Select at least one provider"
        if (categories.isEmpty()) return "Select at least one content category"
        val from = yearFrom
        val to = yearTo
        if (from != null && to != null && from > to) {
            return "Start year ($from) must be lower than or equal to end year ($to)"
        }
        if (addedOnDate != null && !ImportDate.isValid(addedOnDate)) {
            return "Added date must be a valid date in yyyy-MM-dd format"
        }
        listOfNotNull(from, to).forEach { year ->
            if (year < 1900 || year > 2100) return "Year $year is outside 1900-2100"
        }
        if (maxItems < 1) return "Scan limit must be at least 1"
        if (maxPages < 1) return "Page limit must be at least 1"
        if (maxEpisodesPerSeries < 1) return "Episode limit must be at least 1"
        if (maxRetries < 0) return "Retries cannot be negative"
        if (concurrency < 1) return "Concurrency must be at least 1"
        if (concurrency > 8) return "Concurrency above 8 risks provider rate limits"
        if (requestDelayMs < 0) return "Request delay cannot be negative"
        return null
    }

    fun yearMatches(year: Int?): Boolean {
        val from = yearFrom
        val to = yearTo
        if (from == null && to == null) return true
        if (year == null) return false
        if (from != null && year < from) return false
        if (to != null && year > to) return false
        return true
    }

    fun addedDateMatches(timestampMs: Long?): Boolean =
        addedOnDate == null || ImportDate.matches(timestampMs, addedOnDate)

    fun categoryAllows(type: TvType?): Boolean {
        // Unknown types are only imported when the user explicitly asked for "Other".
        val category = ImportCategory.fromTvType(type) ?: return ImportCategory.OTHERS in categories
        return category in categories
    }
}

@Serializable
enum class ImportPhase {
    IDLE,
    DISCOVERING,
    LOADING,
    RESOLVING,
    WRITING,
    PREVIEW_READY,
    DONE,
    PAUSED,
    CANCELLED,
    FAILED,
}

/** Live counters. Every number is incremented by an actual operation, never estimated. */
@Serializable
data class ImportProgress(
    val phase: ImportPhase = ImportPhase.IDLE,
    val providerName: String? = null,
    val catalogPagesScanned: Int = 0,
    val itemsDiscovered: Int = 0,
    val itemsLoaded: Int = 0,
    val itemsFilteredOut: Int = 0,
    val episodesProcessed: Int = 0,
    val linksResolved: Int = 0,
    val duplicatesSkipped: Int = 0,
    val failed: Int = 0,
    val uploadedContents: Int = 0,
    val currentItem: String? = null,
    val message: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)

@Serializable
enum class ImportSessionStatus {
    RUNNING,
    PAUSED,
    COMPLETED,
}

/** Persisted scan definition, progress and per-entrypoint cursors, restored after process death. */
@Serializable
data class ImportScanSession(
    val config: ImportConfig,
    val progress: ImportProgress,
    val nextPageOffsets: Map<String, Int> = emptyMap(),
    val status: ImportSessionStatus = ImportSessionStatus.RUNNING,
    val report: ImportReport? = null,
    val updatedAt: Long,
)

/** Final report shown after a run, with expandable failures/warnings. */
@Serializable
data class ImportReport(
    val progress: ImportProgress,
    val failures: List<ImportFailure> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** Canonical titles that matched more than one candidate and were not merged. */
    val reviewFlags: List<String> = emptyList(),
    val durationMs: Long = 0L,
    val uploaded: Boolean = false,
    val uploadError: String? = null,
    val jobId: String? = null,
)

// ---------------------------------------------------------------------------------------------
// Payload models sent to the Supabase Edge Function. All JSON field names match the SQL schema.
// ---------------------------------------------------------------------------------------------

@Serializable
data class ProviderRef(
    val providerId: String,
    val displayName: String,
    val language: String? = null,
    val mainUrl: String? = null,
)

@Serializable
data class ProviderContentRef(
    val providerId: String,
    val detailUrl: String,
    val lastSeenAt: Long,
    val providerMetadata: Map<String, String> = emptyMap(),
)

@Serializable
data class ImportEpisodeSource(
    val providerId: String,
    val extractorName: String? = null,
    val serverName: String? = null,
    /** Only set when it came from the real loadLinks()/extractor callback. */
    val mediaUrl: String? = null,
    val quality: String? = null,
    val linkType: String? = null,
    val isResolved: Boolean = false,
    val resolvedAt: Long? = null,
    val status: String = "unresolved",
    val error: String? = null,
    /**
     * Request headers the extractor itself used (referer, user-agent, ...), copied verbatim
     * from the real ExtractorLink so a player can repeat the exact request instead of
     * guessing and being blocked by the CDN's hotlink protection. Empty when the link was
     * never resolved.
     */
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class ImportEpisode(
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val posterUrl: String? = null,
    /** Provider detail/page url for the episode, never a media url. */
    val detailUrl: String? = null,
    /**
     * The provider's own episode payload, i.e. the `data` string that is passed back to
     * MainAPI.loadLinks(). It is a provider identifier, never a media url.
     */
    val linkData: String? = null,
    /** Non secret provider facts such as dub status (`sub`/`dub`). */
    val attributes: Map<String, String> = emptyMap(),
    val sortOrder: Int,
    val sources: List<ImportEpisodeSource> = emptyList(),
)

@Serializable
data class ImportSeason(
    val seasonNumber: Int? = null,
    val name: String? = null,
    val sortOrder: Int,
    val episodes: List<ImportEpisode> = emptyList(),
)

/** One canonical work with all provider sources merged in. */
@Serializable
data class CanonicalContent(
    /** Stable identity: normalized title + year + kind, see [TitleNormalizer.canonicalKey]. */
    val canonicalKey: String,
    val title: String,
    val normalizedTitle: String,
    val originalTitle: String? = null,
    /** "movie", "anime_movie", "series", "anime_series", "other". */
    val contentType: String,
    val tvType: String,
    val releaseYear: Int? = null,
    val posterUrl: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val rating: Double? = null,
    val externalIds: Map<String, String> = emptyMap(),
    val providerContents: List<ProviderContentRef> = emptyList(),
    val seasons: List<ImportSeason> = emptyList(),
    /** Media links resolved at content level (movies, live streams). */
    val sources: List<ImportEpisodeSource> = emptyList(),
    val updatedAt: Long,
)

@Serializable
data class DuplicateCandidate(
    val canonicalKey: String,
    val title: String,
    val releaseYear: Int?,
    val tvType: String,
    /** Competing normalized titles that were close but not equal. */
    val conflictingTitles: List<String>,
    val reason: String,
    val detectedAt: Long,
)

@Serializable
data class ImportJobRecord(
    val jobId: String,
    val providerIds: List<String>,
    val filters: Map<String, String> = emptyMap(),
    val status: String = "running",
    val startedAt: Long,
    val finishedAt: Long? = null,
    val counters: Map<String, Int> = emptyMap(),
    val errorSummary: String? = null,
)

@Serializable
data class ImportUploadPayload(
    val job: ImportJobRecord,
    val providers: List<ProviderRef>,
    val contents: List<CanonicalContent>,
    val duplicateCandidates: List<DuplicateCandidate> = emptyList(),
    val itemErrors: List<ImportFailure> = emptyList(),
)

@Serializable
data class ImportUploadResult(
    val job: String? = null,
    val upsertedContents: Int = 0,
    val upsertedEpisodes: Int = 0,
    val upsertedSources: Int = 0,
    val duplicateCandidates: Int = 0,
    val errors: List<String> = emptyList(),
)

@Serializable
data class ExistingProviderTitles(
    val existingProviderTitles: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class ExistingProviderTitlesRequest(
    val action: String,
    val providerIds: List<String>,
)

@Serializable
data class ProviderLinkRefreshTarget(
    val contentId: String,
    val title: String,
    val normalizedTitle: String,
    val releaseYear: Int? = null,
    val contentType: String,
    val detailUrl: String,
)

@Serializable
data class ProviderLinkRefreshTargetsRequest(
    val action: String,
    val providerId: String,
    val offset: Int,
    val limit: Int = 200,
)

@Serializable
data class ProviderLinkRefreshTargetsResponse(
    val targets: List<ProviderLinkRefreshTarget> = emptyList(),
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
)

/** One database episode identity and its newly resolved playable sources. */
@Serializable
data class ProviderLinkRefreshEpisode(
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val title: String? = null,
    val linkData: String? = null,
    val sources: List<ImportEpisodeSource>,
)

/** Only source data is sent; this operation never changes content metadata or creates titles. */
@Serializable
data class ProviderLinkRefreshContent(
    val contentId: String,
    val sources: List<ImportEpisodeSource> = emptyList(),
    val episodes: List<ProviderLinkRefreshEpisode> = emptyList(),
)

@Serializable
data class ReplaceProviderLinksRequest(
    val action: String,
    val providerId: String,
    val contents: List<ProviderLinkRefreshContent>,
)

@Serializable
data class ReplaceProviderLinksResult(
    val replacedContents: Int = 0,
    val replacedEpisodes: Int = 0,
    val replacedSources: Int = 0,
    val unmatchedEpisodes: Int = 0,
)
