package com.lagradost.cloudstream3.importsystem

import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Scan configuration validation and filtering rules. */
class ImportConfigTest {

    private fun config(
        providers: Set<String> = setOf("ProviderA"),
        categories: Set<ImportCategory> = setOf(ImportCategory.ANIME),
        yearFrom: Int? = null,
        yearTo: Int? = null,
        addedOnDate: String? = null,
        maxItems: Int = 60,
        maxPages: Int = 3,
        maxEpisodes: Int = 12,
        concurrency: Int = 3,
    ) = ImportConfig(
        providerNames = providers,
        categories = categories,
        yearFrom = yearFrom,
        yearTo = yearTo,
        addedOnDate = addedOnDate,
        maxItems = maxItems,
        maxPages = maxPages,
        maxEpisodesPerSeries = maxEpisodes,
        concurrency = concurrency,
    )

    @Test
    fun `valid configuration passes validation`() {
        assertNull(config(yearFrom = 2025, yearTo = 2026).validate())
    }

    @Test
    fun `at least one provider and category is required`() {
        assertNotNull(config(providers = emptySet()).validate())
        assertNotNull(config(categories = emptySet()).validate())
    }

    @Test
    fun `start year must not be after end year`() {
        val error = config(yearFrom = 2026, yearTo = 2025).validate()
        assertNotNull(error)
        assertTrue(error!!.contains("2026"))
        assertTrue(error.contains("2025"))
    }

    @Test
    fun `years outside a sane range are rejected`() {
        assertNotNull(config(yearFrom = 1800).validate())
        assertNotNull(config(yearTo = 2200).validate())
    }

    @Test
    fun `one sided year bounds are allowed`() {
        assertNull(config(yearFrom = 2025).validate())
        assertNull(config(yearTo = 2026).validate())
    }

    @Test
    fun `selected added date must be a valid ISO local date`() {
        assertNull(config(addedOnDate = "2026-10-31").validate())
        assertNotNull(config(addedOnDate = "2026-02-31").validate())
        assertNotNull(config(addedOnDate = "31-10-2026").validate())
    }

    @Test
    fun `selected added date matches provider timestamp local calendar day only`() {
        val day = LocalDate.of(2026, 10, 31)
        val zone = ZoneId.systemDefault()
        val inDay = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val nextDay = day.plusDays(1).atTime(0, 0).atZone(zone).toInstant().toEpochMilli()
        val filtered = config(addedOnDate = day.toString())

        assertTrue(filtered.addedDateMatches(inDay))
        assertFalse(filtered.addedDateMatches(nextDay))
        assertFalse(filtered.addedDateMatches(null))
        assertTrue(config().addedDateMatches(null))
    }

    @Test
    fun `limits must be positive and concurrency bounded`() {
        assertNotNull(config(maxItems = 0).validate())
        assertNotNull(config(maxPages = 0).validate())
        assertNotNull(config(maxEpisodes = 0).validate())
        assertNotNull(config(concurrency = 0).validate())
        assertNotNull(config(concurrency = 9).validate())
    }

    @Test
    fun `year filter rejects unknown years only when a bound is set`() {
        val unrestricted = config()
        assertTrue(unrestricted.yearMatches(null))
        assertTrue(unrestricted.yearMatches(1990))

        val bounded = config(yearFrom = 2025, yearTo = 2026)
        assertFalse(bounded.yearMatches(null))
        assertTrue(bounded.yearMatches(2025))
        assertTrue(bounded.yearMatches(2026))
        assertFalse(bounded.yearMatches(2024))
        assertFalse(bounded.yearMatches(2027))
    }

    @Test
    fun `category filter maps real provider tv types`() {
        val animeOnly = config(categories = setOf(ImportCategory.ANIME))
        assertTrue(animeOnly.categoryAllows(TvType.Anime))
        assertFalse(animeOnly.categoryAllows(TvType.Movie))

        val animeAndMovies = config(
            categories = setOf(ImportCategory.ANIME, ImportCategory.ANIME_MOVIE),
        )
        assertTrue(animeAndMovies.categoryAllows(TvType.Anime))
        assertTrue(animeAndMovies.categoryAllows(TvType.AnimeMovie))
        assertFalse(animeAndMovies.categoryAllows(TvType.TvSeries))
    }

    @Test
    fun `anime movie and movie stay distinguishable`() {
        assertEquals(ImportCategory.ANIME_MOVIE, ImportCategory.fromTvType(TvType.AnimeMovie))
        assertEquals(ImportCategory.MOVIE, ImportCategory.fromTvType(TvType.Movie))
        assertNotEquals(
            ImportEngine.contentTypeName(TvType.AnimeMovie),
            ImportEngine.contentTypeName(TvType.Movie),
        )
        assertEquals("series", ImportEngine.contentTypeName(TvType.TvSeries))
        assertEquals("anime_series", ImportEngine.contentTypeName(TvType.Anime))
    }
}
