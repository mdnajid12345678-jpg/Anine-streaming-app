package com.lagradost.cloudstream3.importsystem

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine must only report what actually happened:
 *  - transient provider errors are retried and then either succeed or are reported as failures,
 *  - cancellation stops the run and is visible in the progress phase,
 *  - MainAPI.loadLinks() is only ever called when the user opted into link resolution, and an
 *    empty extractor result never turns into a "playable" url.
 *
 * The provider below is a unit-test double of the real MainAPI interface: it exercises the exact
 * search()/load()/loadLinks() call path the production providers go through, and it exists only
 * in this test source set.
 */
class ImportEngineTest {

    private class FakeProvider : MainAPI() {
        override var name = "FakeProvider"
        override var mainUrl = "https://fake.example"

        var searchFailuresRemaining = 0
        var suspendForever = false
        var searchCalls = 0
        var loadCalls = 0
        var loadLinksCalls = 0
        val searchStarted = CompletableDeferred<Unit>()

        override suspend fun search(query: String, page: Int): SearchResponseList? {
            searchCalls++
            searchStarted.complete(Unit)
            if (suspendForever) awaitCancellation()
            if (searchFailuresRemaining > 0) {
                searchFailuresRemaining--
                throw IllegalStateException("temporary upstream error")
            }
            return newSearchResponseList(
                listOf(
                    newMovieSearchResponse(
                        name = TITLE,
                        url = "/anime/frieren",
                        type = TvType.AnimeMovie,
                        fix = false,
                    ),
                ),
                false,
            )
        }

        override suspend fun load(url: String): LoadResponse? {
            loadCalls++
            return newMovieLoadResponse(
                name = TITLE,
                url = url,
                type = TvType.AnimeMovie,
                dataUrl = url,
            )
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            loadLinksCalls++
            // The provider exposes no resolvable stream here: nothing is passed to callback.
            return false
        }
    }

    private fun config(resolveLinks: Boolean = false, maxRetries: Int = 2) = ImportConfig(
        providerNames = setOf("FakeProvider"),
        categories = ImportCategory.DEFAULT,
        searchQueries = listOf("frieren"),
        resolveLinks = resolveLinks,
        maxItems = 5,
        maxPages = 1,
        requestDelayMs = 0L,
        maxRetries = maxRetries,
        concurrency = 2,
    )

    @Test
    fun `transient search failures are retried until the scan succeeds`() = runBlocking {
        val provider = FakeProvider().apply { searchFailuresRemaining = 2 }

        val result = ImportEngine(config(maxRetries = 2), listOf(provider)).run()

        assertEquals("two failures then one success", 3, provider.searchCalls)
        assertEquals(1, provider.loadCalls)
        assertEquals(1, result.contents.size)
        assertEquals(1, result.progress.itemsLoaded)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `a permanently failing provider is reported as an error, never as a success`() =
        runBlocking {
            val provider = FakeProvider().apply { searchFailuresRemaining = 100 }

            val result = ImportEngine(config(maxRetries = 1), listOf(provider)).run()

            assertEquals("initial attempt plus one retry", 2, provider.searchCalls)
            assertTrue("no content may be invented", result.contents.isEmpty())
            // The transport error itself plus the engine's "cannot be scanned" summary.
            assertEquals(2, result.failures.size)
            assertTrue(
                result.failures.any {
                    it.stage == FailureStage.DISCOVERY &&
                        it.message.contains("temporary upstream error")
                },
            )
            assertTrue(result.failures.any { it.stage == FailureStage.PROVIDER })
            assertTrue(result.progress.failed >= 2)
            assertEquals(0, provider.loadCalls)
        }

    @Test
    fun `cancelling a run stops it and marks the phase cancelled`() = runBlocking {
        val provider = FakeProvider().apply { suspendForever = true }
        val phases = mutableListOf<ImportPhase>()
        val engine = ImportEngine(
            config = config(),
            providers = listOf(provider),
            emit = { progress ->
                synchronized(phases) { phases += progress.phase }
            },
        )

        val job = launch { engine.run() }
        withTimeout(5_000) { provider.searchStarted.await() }
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(
            "expected a CANCELLED progress phase, saw $phases",
            synchronized(phases) { ImportPhase.CANCELLED in phases },
        )
        assertEquals(0, provider.loadCalls)
    }

    @Test
    fun `loadLinks is never called when link resolution is disabled`() = runBlocking {
        val provider = FakeProvider()

        val result = ImportEngine(config(resolveLinks = false), listOf(provider)).run()

        assertEquals(0, provider.loadLinksCalls)
        assertEquals(1, result.contents.size)
        val sources = result.contents.values.first().sources
        assertTrue(sources.isNotEmpty())
        sources.forEach { source ->
            assertFalse("metadata-only rows are never resolved", source.isResolved)
            assertTrue("a media url requires a real loadLinks() callback", source.mediaUrl == null)
        }
        assertEquals(0, result.progress.linksResolved)
    }

    @Test
    fun `loadLinks runs when enabled and an empty extractor result stays unresolved`() =
        runBlocking {
            val provider = FakeProvider()

            val result = ImportEngine(config(resolveLinks = true), listOf(provider)).run()

            assertEquals(1, provider.loadLinksCalls)
            assertEquals(1, result.contents.size)
            val sources = result.contents.values.first().sources
            assertTrue(sources.isNotEmpty())
            sources.forEach { source ->
                assertTrue(source.mediaUrl == null)
                assertFalse(source.isResolved)
            }
            assertEquals(0, result.progress.linksResolved)
        }

    private companion object {
        const val TITLE = "Frieren: Beyond Journey's End"
    }
}
