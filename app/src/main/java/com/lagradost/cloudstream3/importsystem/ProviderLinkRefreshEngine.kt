package com.lagradost.cloudstream3.importsystem

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Resolves fresh playable links for existing database content from exactly one provider. */
class ProviderLinkRefreshEngine(
    private val provider: MainAPI,
    private val targets: List<ProviderLinkRefreshTarget>,
    private val requestDelayMs: Long = 400L,
    private val linkTimeoutMs: Long = 45_000L,
    private val emit: (Progress) -> Unit = {},
) {
    data class Progress(
        val completedTargets: Int = 0,
        val totalTargets: Int = 0,
        val currentTitle: String? = null,
        val linksResolved: Int = 0,
        val targetsWithLinks: Int = 0,
        val targetsWithoutLinks: Int = 0,
        val failedTargets: Int = 0,
        val message: String? = null,
    )

    data class Result(
        val contents: List<ProviderLinkRefreshContent>,
        val progress: Progress,
        val failures: List<String>,
    )

    private val failures = mutableListOf<String>()
    private var progress = Progress(totalTargets = targets.size)

    suspend fun run(): Result {
        val refreshed = mutableListOf<ProviderLinkRefreshContent>()
        targets.forEachIndexed { index, target ->
            progress = progress.copy(
                currentTitle = target.title,
                message = "Refreshing ${index + 1}/${targets.size}: ${target.title}",
            )
            emit(progress)

            try {
                delay(requestDelayMs)
                val loaded = provider.load(target.detailUrl)
                    ?: throw IllegalStateException("provider returned no detail page")
                val expectedTitle = target.normalizedTitle.ifBlank {
                    TitleNormalizer.normalize(target.title)
                }
                if (TitleNormalizer.normalize(loaded.name) != expectedTitle) {
                    throw IllegalStateException(
                        "loaded title did not match the saved database title; old links were kept",
                    )
                }
                if (ImportEngine.contentTypeName(loaded.type) != target.contentType) {
                    throw IllegalStateException(
                        "loaded content type did not match the saved database record; old links were kept",
                    )
                }
                if (target.releaseYear != null && loaded.year != null &&
                    target.releaseYear != loaded.year
                ) {
                    throw IllegalStateException(
                        "loaded release year did not match the saved database record; old links were kept",
                    )
                }

                val content = resolveContent(target, loaded)
                if (content != null) {
                    refreshed += content
                    val count = content.sources.size + content.episodes.sumOf { it.sources.size }
                    progress = progress.copy(
                        linksResolved = progress.linksResolved + count,
                        targetsWithLinks = progress.targetsWithLinks + 1,
                    )
                } else {
                    progress = progress.copy(
                        targetsWithoutLinks = progress.targetsWithoutLinks + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val message = t.message?.take(300) ?: t::class.simpleName ?: "unknown error"
                failures += "${target.title}: $message"
                progress = progress.copy(failedTargets = progress.failedTargets + 1)
            }

            progress = progress.copy(
                completedTargets = index + 1,
                currentTitle = null,
            )
            emit(progress)
        }

        progress = progress.copy(message = "Resolved ${progress.linksResolved} playable links")
        emit(progress)
        return Result(refreshed, progress, failures.toList())
    }

    private suspend fun resolveContent(
        target: ProviderLinkRefreshTarget,
        loaded: LoadResponse,
    ): ProviderLinkRefreshContent? {
        return when (loaded) {
            is MovieLoadResponse -> {
                val sources = loaded.dataUrl.takeIf { it.isNotBlank() }
                    ?.let { resolve(it) }
                    ?.sources.orEmpty()
                sources.takeIf { it.isNotEmpty() }?.let {
                    ProviderLinkRefreshContent(contentId = target.contentId, sources = it)
                }
            }

            is LiveStreamLoadResponse -> {
                val sources = loaded.dataUrl.takeIf { it.isNotBlank() }
                    ?.let { resolve(it) }
                    ?.sources.orEmpty()
                sources.takeIf { it.isNotEmpty() }?.let {
                    ProviderLinkRefreshContent(contentId = target.contentId, sources = it)
                }
            }

            is TvSeriesLoadResponse -> resolveEpisodes(target, loaded.episodes)

            is AnimeLoadResponse -> resolveEpisodes(
                target,
                loaded.episodes.values.flatten(),
            )

            else -> null
        }
    }

    private suspend fun resolveEpisodes(
        target: ProviderLinkRefreshTarget,
        episodes: List<Episode>,
    ): ProviderLinkRefreshContent? {
        data class Group(
            val seasonNumber: Int?,
            val episodeNumber: Int?,
            val title: String?,
            val linkData: String,
            val sources: MutableList<ImportEpisodeSource> = mutableListOf(),
        )

        val groups = LinkedHashMap<String, Group>()
        for (episode in episodes) {
            if (episode.data.isBlank()) continue
            val key = if (episode.episode != null) {
                "${episode.season ?: 0}:${episode.episode}"
            } else {
                "unknown:${episode.season}:${episode.data}"
            }
            val group = groups.getOrPut(key) {
                Group(
                    seasonNumber = episode.season,
                    episodeNumber = episode.episode,
                    title = episode.name,
                    linkData = episode.data,
                )
            }
            val result = resolve(episode.data)
            group.sources += result.sources
            result.error?.let { failures += "${target.title} / ${episode.name ?: key}: $it" }
        }

        val refreshedEpisodes = groups.values
            .filter { it.sources.isNotEmpty() }
            .map { group ->
                ProviderLinkRefreshEpisode(
                    seasonNumber = group.seasonNumber,
                    episodeNumber = group.episodeNumber,
                    title = group.title,
                    linkData = group.linkData,
                    sources = group.sources.distinctBy { ContentMerger.sourceKey(it) },
                )
            }
        return refreshedEpisodes.takeIf { it.isNotEmpty() }?.let {
            ProviderLinkRefreshContent(contentId = target.contentId, episodes = it)
        }
    }

    private data class LinkResolution(
        val sources: List<ImportEpisodeSource>,
        val error: String? = null,
    )

    private suspend fun resolve(data: String): LinkResolution {
        val links = mutableListOf<ResolvedLink>()
        var error: String? = null
        try {
            delay(requestDelayMs)
            withTimeout(linkTimeoutMs) {
                provider.loadLinks(
                    data = data,
                    isCasting = false,
                    subtitleCallback = { _: SubtitleFile -> },
                    callback = { link -> links += link.toResolved() },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            error = t.message?.take(300) ?: t::class.simpleName ?: "link resolution failed"
        }

        return LinkResolution(
            sources = links.map { it.toSource(provider.name, System.currentTimeMillis()) },
            error = error,
        )
    }

    private data class ResolvedLink(
        val extractorName: String,
        val serverName: String,
        val url: String,
        val quality: Int,
        val type: String,
        val referer: String,
        val headers: Map<String, String>,
    )

    private fun ExtractorLink.toResolved() = ResolvedLink(
        extractorName = source,
        serverName = name,
        url = url,
        quality = quality,
        type = type.name,
        referer = referer,
        headers = headers,
    )

    private fun ResolvedLink.toSource(providerId: String, resolvedAt: Long) = ImportEpisodeSource(
        providerId = providerId,
        extractorName = extractorName,
        serverName = serverName,
        mediaUrl = url,
        quality = quality.takeIf { it > 0 }?.toString(),
        linkType = type,
        isResolved = true,
        resolvedAt = resolvedAt,
        status = "resolved",
        headers = requestHeaders(),
    )

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
}
