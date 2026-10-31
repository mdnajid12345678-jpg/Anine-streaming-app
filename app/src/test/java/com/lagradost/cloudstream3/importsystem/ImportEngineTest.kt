package com.lagradost.cloudstream3.importsystem

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
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
import java.time.LocalDate
import java.time.ZoneId

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
        var searchResponseByPage: Map<Int, List<SearchResponse>>? = null
        var pagesWithNext: Set<Int> = emptySet()
        val searchStarted = CompletableDeferred<Unit>()

        override suspend fun search(query: String, page: Int): SearchResponseList? {
            searchCalls++
            searchStarted.complete(Unit)
            if (suspendForever) awaitCancellation()
            if (searchFailuresRemaining > 0) {
                searchFailuresRemaining--
                throw IllegalStateException("temporary upstream error")
            }
            searchResponseByPage?.let { pages ->
                return newSearchResponseList(pages[page].orEmpty(), page in pagesWithNext)
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
            val title = searchResponseByPage?.values.orEmpty()
                .flatten()
                .firstOrNull { it.url == url }
                ?.name ?: TITLE
            return newMovieLoadResponse(
                name = title,
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

    @Test
    fun `known provider title is skipped and pagination continues until the new-item limit is met`() =
        runBlocking {
            val provider = FakeProvider().apply {
                searchResponseByPage = mapOf(
                    1 to listOf(searchItem(TITLE, "/anime/known")),
                    2 to listOf(searchItem(NEW_TITLE, "/anime/new")),
                )
                pagesWithNext = setOf(1)
            }
            val config = config().copy(maxItems = 1, maxPages = 2)
            val knownTitles = mapOf("FakeProvider" to setOf(TitleNormalizer.normalize(TITLE)))

            val result = ImportEngine(
                config = config,
                providers = listOf(provider),
                existingProviderTitles = knownTitles,
            ).run()

            assertEquals(2, provider.searchCalls)
            assertEquals(1, provider.loadCalls)
            assertEquals(listOf(NEW_TITLE), result.contents.values.map { it.title })
            assertEquals(1, result.progress.duplicatesSkipped)
        }

    @Test
    fun `known titles on the same page do not spend the limit for new titles`() = runBlocking {
        val provider = FakeProvider().apply {
            searchResponseByPage = mapOf(
                1 to listOf(
                    searchItem(TITLE, "/anime/known-1"),
                    searchItem(" ${TITLE.uppercase()}! ", "/anime/known-2"),
                    searchItem(NEW_TITLE, "/anime/new"),
                ),
            )
        }
        val result = ImportEngine(
            config = config().copy(maxItems = 1),
            providers = listOf(provider),
            existingProviderTitles = mapOf(
                "FakeProvider" to setOf(TitleNormalizer.normalize(TITLE)),
            ),
        ).run()

        assertEquals(listOf(NEW_TITLE), result.contents.values.map { it.title })
        assertEquals(1, provider.loadCalls)
        assertEquals(2, result.progress.duplicatesSkipped)
    }

    @Test
    fun `same title from another provider merges provider references without removing the first`() =
        runBlocking {
            val first = FakeProvider().apply {
                name = "ProviderA"
                searchResponseByPage = mapOf(1 to listOf(searchItem(TITLE, "/a/frieren")))
            }
            val second = FakeProvider().apply {
                name = "ProviderB"
                searchResponseByPage = mapOf(1 to listOf(searchItem(TITLE, "/b/frieren")))
            }

            val result = ImportEngine(
                config = config().copy(providerNames = setOf("ProviderA", "ProviderB")),
                providers = listOf(first, second),
            ).run()

            assertEquals(1, result.contents.size)
            assertEquals(
                setOf("ProviderA", "ProviderB"),
                result.contents.values.single().providerContents.map { it.providerId }.toSet(),
            )
        }

    @Test
    fun `selected added date includes only entries with an addedAt timestamp on that local day`() =
        runBlocking {
            val zone = ZoneId.systemDefault()
            val selectedDate = LocalDate.of(2026, 10, 31)
            val selectedTimestamp = selectedDate.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            val previousTimestamp = selectedDate.minusDays(1).atTime(12, 0)
                .atZone(zone).toInstant().toEpochMilli()
            val provider = FakeProvider().apply {
                searchResponseByPage = mapOf(
                    1 to listOf(
                        searchItem("Older Entry", "/anime/older", previousTimestamp),
                        searchItem(NEW_TITLE, "/anime/today", selectedTimestamp),
                    ),
                )
            }

            val result = ImportEngine(
                config = config().copy(addedOnDate = selectedDate.toString()),
                providers = listOf(provider),
            ).run()

            assertEquals(listOf(NEW_TITLE), result.contents.values.map { it.title })
            assertEquals(1, result.progress.itemsFilteredOut)
        }

    @Test
    fun `selected added date skips providers without timestamp metadata and reports why`() =
        runBlocking {
            val provider = FakeProvider().apply {
                searchResponseByPage = mapOf(1 to listOf(searchItem(NEW_TITLE, "/anime/unknown-date")))
            }

            val result = ImportEngine(
                config = config().copy(addedOnDate = "2026-10-31"),
                providers = listOf(provider),
            ).run()

            assertTrue(result.contents.isEmpty())
            assertTrue(result.warnings.any { it.contains("did not expose an added date") })
        }

    private fun searchItem(title: String, url: String, addedAt: Long? = null): SearchResponse =
        FakeProvider().newMovieSearchResponse(
            name = title,
            url = url,
            type = TvType.AnimeMovie,
            fix = false,
        ).apply { this.addedAt = addedAt }

    private companion object {
        const val TITLE = "Frieren: Beyond Journey's End"
        const val NEW_TITLE = "New anime title"
    }
}
