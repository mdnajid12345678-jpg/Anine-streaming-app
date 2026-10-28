package com.lagradost.cloudstream3.importsystem

/**
 * Pure, side-effect free merging of two canonical records that share a canonical key.
 *
 * Merging rules (deliberately conservative):
 *  - provider/content associations are unioned and de-duplicated by `providerId + detailUrl`
 *  - genres/status/rating are unioned or kept from whichever side has a value
 *  - seasons are matched by season number, episodes by (season, episode) when both are known,
 *    otherwise by detail url; episodes that cannot be matched safely are kept separate and
 *    reported as a review flag by the caller rather than silently collapsed
 *  - media sources are unioned and de-duplicated by `providerId + extractor + media url`
 *  - nothing is ever deleted: a provider that temporarily omits an episode keeps its record
 */
object ContentMerger {

    data class MergeOutcome(
        val content: CanonicalContent,
        /** Human readable notes for the review list. */
        val notes: List<String> = emptyList(),
    )

    fun merge(existing: CanonicalContent, incoming: CanonicalContent): MergeOutcome {
        require(existing.canonicalKey == incoming.canonicalKey) {
            "Refusing to merge different canonical keys: ${existing.canonicalKey} vs ${incoming.canonicalKey}"
        }
        val notes = mutableListOf<String>()

        val providerContents = (existing.providerContents + incoming.providerContents)
            .distinctBy { it.providerId to normalizeUrl(it.detailUrl) }
            .sortedBy { it.providerId }

        val genres = (existing.genres + incoming.genres)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .sorted()

        val externalIds = existing.externalIds + incoming.externalIds.filterKeys { it !in existing.externalIds }

        val seasons = mergeSeasons(existing.seasons, incoming.seasons, notes)

        val sources = (existing.sources + incoming.sources).distinctBy { sourceKey(it) }

        val merged = existing.copy(
            title = existing.title.ifBlank { incoming.title },
            originalTitle = existing.originalTitle ?: incoming.originalTitle,
            releaseYear = existing.releaseYear ?: incoming.releaseYear,
            posterUrl = existing.posterUrl ?: incoming.posterUrl,
            description = existing.description ?: incoming.description,
            status = existing.status ?: incoming.status,
            rating = existing.rating ?: incoming.rating,
            genres = genres,
            externalIds = externalIds,
            providerContents = providerContents,
            seasons = seasons,
            sources = sources,
            updatedAt = maxOf(existing.updatedAt, incoming.updatedAt),
        )
        return MergeOutcome(merged, notes)
    }

    private fun mergeSeasons(
        existing: List<ImportSeason>,
        incoming: List<ImportSeason>,
        notes: MutableList<String>,
    ): List<ImportSeason> {
        if (existing.isEmpty()) return incoming
        if (incoming.isEmpty()) return existing

        val byKey = LinkedHashMap<String, ImportSeason>()
        existing.forEach { season -> byKey[seasonKey(season)] = season }

        incoming.forEach { inc ->
            val key = seasonKey(inc)
            val current = byKey[key]
            if (current == null) {
                byKey[key] = inc
            } else {
                byKey[key] = mergeSeason(current, inc, notes)
            }
        }

        return byKey.values
            .sortedWith(compareBy({ it.seasonNumber ?: Int.MAX_VALUE }, { it.sortOrder }))
            .map { season ->
                season.copy(episodes = sortEpisodes(season.episodes))
            }
    }

    private fun mergeSeason(
        existing: ImportSeason,
        incoming: ImportSeason,
        notes: MutableList<String>,
    ): ImportSeason {
        val episodes = LinkedHashMap<String, ImportEpisode>()

        existing.episodes.forEach { episodes[episodeKey(it)] = it }
        incoming.episodes.forEach { inc ->
            val key = episodeKey(inc)
            val current = episodes[key]
            episodes[key] = if (current == null) {
                inc
            } else {
                val mergedSources = (current.sources + inc.sources).distinctBy { sourceKey(it) }
                if (current.detailUrl != null && inc.detailUrl != null &&
                    current.detailUrl != inc.detailUrl
                ) {
                    notes += "Episode ${current.episodeNumber ?: "?"} matched by season/episode " +
                        "number but different provider urls; kept both source sets"
                }
                current.copy(
                    title = current.title ?: inc.title,
                    description = current.description ?: inc.description,
                    posterUrl = current.posterUrl ?: inc.posterUrl,
                    detailUrl = current.detailUrl ?: inc.detailUrl,
                    sources = mergedSources,
                )
            }
        }

        return existing.copy(
            name = existing.name ?: incoming.name,
            sortOrder = minOf(existing.sortOrder, incoming.sortOrder),
            episodes = episodes.values.toList(),
        )
    }

    private fun sortEpisodes(episodes: List<ImportEpisode>): List<ImportEpisode> =
        episodes.sortedWith(
            compareBy(
                { it.episodeNumber ?: Int.MAX_VALUE },
                { it.seasonNumber ?: Int.MAX_VALUE },
                { it.sortOrder },
            ),
        )

    fun seasonKey(season: ImportSeason): String = "s:${season.seasonNumber ?: "u"}"

    fun episodeKey(episode: ImportEpisode): String {
        val number = episode.episodeNumber
        if (number != null) return "n:${episode.seasonNumber ?: "u"}:$number"
        val url = episode.detailUrl
        if (!url.isNullOrBlank()) return "u:${normalizeUrl(url)}"
        return "t:${TitleNormalizer.normalize(episode.title ?: "").hashCode()}"
    }

    fun sourceKey(source: ImportEpisodeSource): String =
        listOf(
            source.providerId,
            source.extractorName ?: "",
            source.serverName ?: "",
            normalizeUrl(source.mediaUrl),
        ).joinToString("|")

    /** Media urls frequently carry per-session query noise; it is not a different source. */
    fun normalizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        return withoutQuery.trimEnd('/')
    }
}
