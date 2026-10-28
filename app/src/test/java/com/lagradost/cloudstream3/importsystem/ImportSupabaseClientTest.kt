package com.lagradost.cloudstream3.importsystem

import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Supabase integration rules: unconfigured builds must fail loudly instead of pretending,
 * the payload must survive JSON round trips, and no secret may ever be part of the client.
 */
class ImportSupabaseClientTest {

    private fun payload() = ImportUploadPayload(
        job = ImportJobRecord(
            jobId = "11111111-2222-3333-4444-555555555555",
            providerIds = listOf("ProviderA"),
            filters = mapOf("categories" to "ANIME", "max_items" to "10"),
            status = "completed",
            startedAt = 1_000L,
            finishedAt = 2_000L,
            counters = mapOf("loaded" to 3),
            errorSummary = null,
        ),
        providers = listOf(ProviderRef("ProviderA", "Provider A", "en", "https://a.example")),
        contents = listOf(
            CanonicalContent(
                canonicalKey = "series|naruto|2002",
                title = "Naruto",
                normalizedTitle = "naruto",
                contentType = "series",
                tvType = "Anime",
                releaseYear = 2002,
                genres = listOf("Action"),
                providerContents = listOf(
                    ProviderContentRef("ProviderA", "https://a.example/naruto", 1_000L),
                ),
                seasons = listOf(
                    ImportSeason(
                        seasonNumber = 1,
                        sortOrder = 0,
                        episodes = listOf(
                            ImportEpisode(
                                seasonNumber = 1,
                                episodeNumber = 1,
                                title = "Episode 1",
                                detailUrl = "https://a.example/ep/1",
                                linkData = "data-1",
                                sortOrder = 0,
                                sources = listOf(
                                    ImportEpisodeSource(
                                        providerId = "ProviderA",
                                        extractorName = "FakeExtractor",
                                        mediaUrl = "https://cdn.example/1.m3u8",
                                        quality = "1080",
                                        linkType = "M3U8",
                                        isResolved = true,
                                        resolvedAt = 3_000L,
                                        status = "resolved",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                updatedAt = 1_000L,
            ),
        ),
        itemErrors = listOf(
            ImportFailure(
                provider = "ProviderA",
                stage = FailureStage.LOAD,
                url = "https://a.example/broken",
                message = "load() returned null",
                at = 1_500L,
            ),
        ),
    )

    @Test
    fun `unconfigured client reports the missing public value`() {
        val client = SupabaseImportClient(
            functionUrl = "",
            publishableKey = "",
            callerKey = "",
        )
        val config = client.configuration()
        assertFalse(config.configured)
        assertNotNull(config.reason)
        // The reason must name the environment variable, never a value.
        assertTrue(config.reason!!.contains("SUPABASE"))
    }

    @Test
    fun `upload on an unconfigured build throws instead of faking success`() {
        val client = SupabaseImportClient(
            functionUrl = "",
            publishableKey = "",
            callerKey = "",
        )
        val thrown = runBlocking {
            runCatching { client.upload(payload()) }.exceptionOrNull()
        }
        assertTrue(thrown is ImportUploadException)
        assertTrue(thrown!!.message!!.contains("SUPABASE"))
    }

    @Test
    fun `non https function url is rejected`() {
        val client = SupabaseImportClient(
            functionUrl = "http://insecure.example/functions/v1/import-sync",
            publishableKey = "sb_publishable_x",
            callerKey = "",
        )
        assertFalse(client.configuration().configured)
    }

    @Test
    fun `payload survives a json round trip`() {
        val original = payload()
        val json = original.toJson()
        val parsed = tryParseJson<ImportUploadPayload>(json)

        assertNotNull(parsed)
        assertEquals(original.job.jobId, parsed!!.job.jobId)
        assertEquals(original.contents.size, parsed.contents.size)

        val content = parsed.contents.single()
        assertEquals(original.contents.single().canonicalKey, content.canonicalKey)
        assertEquals("series", content.contentType)
        assertEquals(listOf("Action"), content.genres)

        val episode = content.seasons.single().episodes.single()
        assertEquals("data-1", episode.linkData)
        val source = episode.sources.single()
        assertEquals("https://cdn.example/1.m3u8", source.mediaUrl)
        assertTrue(source.isResolved)
        assertEquals("resolved", source.status)
        assertEquals(3_000L, source.resolvedAt)

        assertEquals(FailureStage.LOAD, parsed.itemErrors.single().stage)
    }

    @Test
    fun `an unresolved source never carries a media url`() {
        val source = ImportEpisodeSource(
            providerId = "ProviderA",
            isResolved = false,
            status = "no_links",
        )
        val json = source.toJson()
        // No url of any kind may appear for something that was never resolved.
        assertFalse(json.contains("http"))
        val parsed = tryParseJson<ImportEpisodeSource>(json)!!
        assertNull(parsed.mediaUrl)
        assertFalse(parsed.isResolved)
        assertEquals("no_links", parsed.status)
    }

    @Test
    fun `no service role field exists anywhere in the payload model`() {
        val json = payload().toJson()
        assertFalse(json.contains("service_role"))
        assertFalse(json.contains("serviceRole"))
        assertFalse(json.contains("SUPABASE"))
    }
}
