package com.lagradost.cloudstream3.importsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-provider merging: one canonical record per work, sources unioned, nothing deleted,
 * repeated merges idempotent, ambiguous keys refused.
 */
class ImportContentMergerTest {

    private fun content(
        key: String = "series|naruto|2002",
        title: String = "Naruto",
        year: Int? = 2002,
        providerId: String = "ProviderA",
        detailUrl: String = "https://a.example/naruto",
        posterUrl: String? = "https://a.example/poster.jpg",
        genres: List<String> = listOf("Action"),
        seasons: List<ImportSeason> = emptyList(),
        sources: List<ImportEpisodeSource> = emptyList(),
        updatedAt: Long = 1_000L,
    ) = CanonicalContent(
        canonicalKey = key,
        title = title,
        normalizedTitle = TitleNormalizer.normalize(title),
        contentType = "series",
        tvType = "Anime",
        releaseYear = year,
        posterUrl = posterUrl,
        description = null,
        genres = genres,
        providerContents = listOf(
            ProviderContentRef(
                providerId = providerId,
                detailUrl = detailUrl,
                lastSeenAt = updatedAt,
            ),
        ),
        seasons = seasons,
        sources = sources,
        updatedAt = updatedAt,
    )

    private fun season(
        seasonNumber: Int?,
        episodes: List<ImportEpisode>,
        sortOrder: Int = 0,
    ) = ImportSeason(
        seasonNumber = seasonNumber,
        name = null,
        sortOrder = sortOrder,
        episodes = episodes,
    )

    private fun episode(
        number: Int?,
    ) = ImportEpisode(
        seasonNumber = 1,
        episodeNumber = number,
        title = "Episode $number",
        detailUrl = number?.let { "https://a.example/ep/$it" },
        sortOrder = number ?: 0,
    )

    @Test
    fun `a second provider adds an association instead of a second title`() {
        val existing = content()
        val incoming = content(
            providerId = "ProviderB",
            detailUrl = "https://b.example/naruto",
            posterUrl = null,
            genres = listOf("Adventure"),
        )

        val merged = ContentMerger.merge(existing, incoming).content

        assertEquals(
            listOf("ProviderA", "ProviderB"),
            merged.providerContents.map { it.providerId }.sorted(),
        )
        // Poster from the first provider is kept when the second has none.
        assertEquals("https://a.example/poster.jpg", merged.posterUrl)
        assertEquals(listOf("Action", "Adventure"), merged.genres)
        assertEquals(existing.canonicalKey, merged.canonicalKey)
    }

    @Test
    fun `merging the same record twice is idempotent`() {
        val first = content()
        val once = ContentMerger.merge(first, content()).content
        val twice = ContentMerger.merge(once, content()).content

        assertEquals(1, twice.providerContents.size)
        assertEquals(once, twice)
    }

    @Test
    fun `duplicate provider pages collapse into one association`() {
        val merged = ContentMerger.merge(
            content(providerId = "A", detailUrl = "https://a.example/naruto"),
            content(providerId = "A", detailUrl = "https://a.example/naruto"),
        ).content

        assertEquals(1, merged.providerContents.size)
    }

    @Test
    fun `episodes are merged by season and episode number and stay ordered`() {
        val existing = content(
            seasons = listOf(
                season(1, listOf(episode(2), episode(1))),
            ),
        )
        val incoming = content(
            providerId = "B",
            detailUrl = "https://b.example/naruto",
            seasons = listOf(
                season(1, listOf(episode(1), episode(3))),
            ),
        )

        val merged = ContentMerger.merge(existing, incoming).content
        val episodes = merged.seasons.single().episodes

        assertEquals(listOf(1, 2, 3), episodes.map { it.episodeNumber })
        // Two providers contributed episode 1: it is one episode with both source sets kept.
        assertEquals(3, episodes.size)
    }

    @Test
    fun `episodes missing from the new provider are never deleted`() {
        val existing = content(
            seasons = listOf(season(1, listOf(episode(1), episode(2), episode(3)))),
        )
        val incoming = content(
            providerId = "B",
            detailUrl = "https://b.example/naruto",
            seasons = listOf(season(1, listOf(episode(1)))),
        )

        val merged = ContentMerger.merge(existing, incoming).content
        assertEquals(listOf(1, 2, 3), merged.seasons.single().episodes.map { it.episodeNumber })
    }

    @Test
    fun `seasons are keyed by season number and keep ordering`() {
        val existing = content(
            seasons = listOf(season(2, listOf(episode(1)), sortOrder = 1)),
        )
        val incoming = content(
            providerId = "B",
            detailUrl = "https://b.example/naruto",
            seasons = listOf(
                season(1, listOf(episode(1)), sortOrder = 0),
                season(2, listOf(episode(2)), sortOrder = 1),
            ),
        )

        val merged = ContentMerger.merge(existing, incoming).content
        assertEquals(listOf(1, 2), merged.seasons.map { it.seasonNumber })
        // Season 2 exists on both sides: both providers' episodes are kept, ordered.
        assertEquals(listOf(1, 2), merged.seasons[1].episodes.map { it.episodeNumber })
    }

    @Test
    fun `sources deduplicate by provider extractor and normalized url`() {
        val resolved = ImportEpisodeSource(
            providerId = "A",
            extractorName = "FakeExtractor",
            serverName = "Server 1",
            mediaUrl = "https://cdn.example/video.m3u8?token=abc",
            quality = "1080",
            linkType = "M3U8",
            isResolved = true,
            resolvedAt = 5_000L,
            status = "resolved",
        )
        val sameUrlOtherQuery = resolved.copy(mediaUrl = "https://cdn.example/video.m3u8?token=zzz")
        val otherExtractor = resolved.copy(extractorName = "OtherExtractor")

        val merged = ContentMerger.merge(
            content(sources = listOf(resolved)),
            content(sources = listOf(sameUrlOtherQuery, otherExtractor)),
        ).content

        assertEquals(2, merged.sources.size)
        assertEquals(
            setOf("FakeExtractor", "OtherExtractor"),
            merged.sources.map { it.extractorName }.toSet(),
        )
    }

    @Test
    fun `unresolved source rows never gain a media url`() {
        val unresolved = ImportEpisodeSource(
            providerId = "A",
            isResolved = false,
            status = "error",
            error = "extractor timeout",
        )

        val merged = ContentMerger.merge(
            content(sources = listOf(unresolved)),
            content(sources = listOf(unresolved)),
        ).content

        assertEquals(1, merged.sources.size)
        assertNull(merged.sources.single().mediaUrl)
        assertEquals("error", merged.sources.single().status)
    }

    @Test
    fun `merging different canonical keys is refused`() {
        val left = content(key = "series|naruto|2002")
        val right = content(key = "series|bleach|2004")
        val thrown = runCatching { ContentMerger.merge(left, right) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }
}
