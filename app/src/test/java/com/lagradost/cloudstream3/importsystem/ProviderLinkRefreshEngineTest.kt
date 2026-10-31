package com.lagradost.cloudstream3.importsystem

import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(Prerelease::class)
class ProviderLinkRefreshEngineTest {
    private class FakeProvider : MainAPI() {
        override var name = "ProviderA"
        override var mainUrl = "https://provider.example"
        lateinit var loaded: LoadResponse
        var loadedUrl: String? = null
        val requestedData = mutableListOf<String>()

        override suspend fun search(query: String, page: Int): SearchResponseList? = null

        override suspend fun load(url: String): LoadResponse? {
            loadedUrl = url
            return loaded
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            requestedData += data
            callback(
                newExtractorLink(
                    source = "Fake extractor",
                    name = "Fresh source",
                    url = "https://cdn.example/$data.m3u8",
                ) {
                    referer = "https://provider.example/watch"
                    headers = mapOf("User-Agent" to "Test agent")
                },
            )
            return true
        }
    }

    @Test
    fun `movie refresh resolves new source and sends only source replacement data`() = runBlocking {
        val provider = FakeProvider()
        provider.loaded = provider.newMovieLoadResponse(
            name = "Naruto",
            url = "https://provider.example/naruto",
            type = TvType.Movie,
            dataUrl = "naruto-payload",
        )
        val target = target("Naruto", "https://provider.example/naruto", contentType = "movie")

        val result = ProviderLinkRefreshEngine(
            provider = provider,
            targets = listOf(target),
            requestDelayMs = 0,
        ).run()

        assertEquals("https://provider.example/naruto", provider.loadedUrl)
        assertEquals(listOf("naruto-payload"), provider.requestedData)
        assertEquals(1, result.contents.size)
        val refreshed = result.contents.single()
        assertEquals(target.contentId, refreshed.contentId)
        assertTrue(refreshed.episodes.isEmpty())
        assertEquals("https://cdn.example/naruto-payload.m3u8", refreshed.sources.single().mediaUrl)
        assertTrue(refreshed.sources.single().isResolved)
        assertEquals("https://provider.example/watch", refreshed.sources.single().headers["Referer"])
        assertEquals(1, result.progress.targetsWithLinks)
    }

    @Test
    fun `series refresh keeps episode identity for the database replacement`() = runBlocking {
        val provider = FakeProvider()
        val episode = provider.newEpisode(
            url = "episode-payload",
            initializer = {
                season = 2
                episode = 4
                name = "The new link"
            },
            fix = false,
        )
        provider.loaded = provider.newTvSeriesLoadResponse(
            name = "Naruto",
            url = "https://provider.example/naruto",
            type = TvType.Anime,
            episodes = listOf(episode),
        )
        val result = ProviderLinkRefreshEngine(
            provider = provider,
            targets = listOf(target("Naruto", "https://provider.example/naruto")),
            requestDelayMs = 0,
        ).run()

        val refreshedEpisode = result.contents.single().episodes.single()
        assertEquals(2, refreshedEpisode.seasonNumber)
        assertEquals(4, refreshedEpisode.episodeNumber)
        assertEquals("episode-payload", refreshedEpisode.linkData)
        assertEquals("https://cdn.example/episode-payload.m3u8", refreshedEpisode.sources.single().mediaUrl)
    }

    @Test
    fun `title mismatch does not produce replacement data and leaves old source to backend`() = runBlocking {
        val provider = FakeProvider()
        provider.loaded = provider.newMovieLoadResponse(
            name = "A different title",
            url = "https://provider.example/changed-route",
            type = TvType.Movie,
            dataUrl = "wrong-payload",
        )

        val result = ProviderLinkRefreshEngine(
            provider = provider,
            targets = listOf(
                target("Naruto", "https://provider.example/changed-route", contentType = "movie"),
            ),
            requestDelayMs = 0,
        ).run()

        assertTrue(result.contents.isEmpty())
        assertFalse(provider.requestedData.contains("wrong-payload"))
        assertEquals(1, result.progress.failedTargets)
        assertTrue(result.failures.single().contains("old links were kept"))
    }

    private fun target(
        title: String,
        detailUrl: String,
        contentType: String = "anime_series",
    ) = ProviderLinkRefreshTarget(
        contentId = "11111111-2222-3333-4444-555555555555",
        title = title,
        normalizedTitle = TitleNormalizer.normalize(title),
        releaseYear = 2002,
        contentType = contentType,
        detailUrl = detailUrl,
    )
}
