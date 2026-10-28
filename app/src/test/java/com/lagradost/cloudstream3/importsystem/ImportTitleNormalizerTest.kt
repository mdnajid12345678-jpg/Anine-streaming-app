package com.lagradost.cloudstream3.importsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canonical identity must be stable and duplicate matching must stay conservative:
 * equal normalized titles merge, different works never do.
 */
class ImportTitleNormalizerTest {

    @Test
    fun `normalization is case punctuation and whitespace insensitive`() {
        val a = TitleNormalizer.normalize("  Naruto: Shippuden!! ")
        val b = TitleNormalizer.normalize("naruto shippuden")
        assertEquals(b, a)
    }

    @Test
    fun `normalization strips catalog noise but keeps the work name`() {
        assertEquals(
            "one piece",
            TitleNormalizer.normalize("One Piece (2025) (Dubbed) [1080p]"),
        )
        assertEquals(
            "one piece",
            TitleNormalizer.normalize("One Piece Season 1"),
        )
        assertEquals(
            "one piece",
            TitleNormalizer.normalize("The One Piece"),
        )
    }

    @Test
    fun `different works with similar words are not equal`() {
        assertNotEquals(
            TitleNormalizer.normalize("Attack on Titan"),
            TitleNormalizer.normalize("Attack on Titan Junior High"),
        )
    }

    @Test
    fun `canonical key contains kind and year`() {
        val key = TitleNormalizer.canonicalKey("Naruto", 2002, "series")
        assertEquals("series|naruto|2002", key)
        // Unknown year must not invent one, but stays stable for later fills.
        assertEquals(
            "movie|spirited away|",
            TitleNormalizer.canonicalKey("Spirited Away", null, "movie"),
        )
    }

    @Test
    fun `merge only when titles match and years agree`() {
        assertTrue(TitleNormalizer.canMerge("Naruto", 2002, "NARUTO", 2002))
        // A year on only one side stays conservative: title equality is still required.
        assertTrue(TitleNormalizer.canMerge("Naruto", null, "Naruto", 2002))
        // Same title, different years: different works, do not merge.
        assertFalse(TitleNormalizer.canMerge("Naruto", 2002, "Naruto", 2012))
        // Different titles: never merge, even if similar.
        assertFalse(TitleNormalizer.canMerge("Naruto", 2002, "Naruto Shippuden", 2002))
    }

    @Test
    fun `blank titles never merge`() {
        assertFalse(TitleNormalizer.canMerge("", 2020, "", 2020))
        assertFalse(TitleNormalizer.canMerge("   ", 2020, "   ", 2020))
    }

    @Test
    fun `similarity is a bounded score`() {
        val same = TitleNormalizer.similarity("Naruto", "Naruto")
        val related = TitleNormalizer.similarity("Naruto", "Naruto Shippuden")
        val unrelated = TitleNormalizer.similarity("Naruto", "Breaking Bad")
        assertEquals(1.0, same, 0.0001)
        assertTrue(related in 0.0..1.0 && related < 1.0)
        assertTrue(unrelated < related)
    }

    @Test
    fun `ambiguity is flagged only for same kind and year`() {
        assertTrue(
            TitleNormalizer.isAmbiguousPair(
                "Attack on Titan", 2013, "anime_series",
                "Attack on Titan Junior High", 2013, "anime_series",
                threshold = 0.5,
            ),
        )
        // Different year: not an ambiguity pair, they are simply different records.
        assertFalse(
            TitleNormalizer.isAmbiguousPair(
                "Attack on Titan", 2013, "anime_series",
                "Attack on Titan Junior High", 2015, "anime_series",
            ),
        )
        // Different kind: never merged or flagged together.
        assertFalse(
            TitleNormalizer.isAmbiguousPair(
                "Naruto", 2002, "series",
                "Naruto", 2002, "movie",
            ),
        )
        // Identical normalized titles are an exact match, not an ambiguity.
        assertFalse(
            TitleNormalizer.isAmbiguousPair(
                "Naruto", 2002, "series",
                "NARUTO", 2002, "series",
            ),
        )
    }
}
