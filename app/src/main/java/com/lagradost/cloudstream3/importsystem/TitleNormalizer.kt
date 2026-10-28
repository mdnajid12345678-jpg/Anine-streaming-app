package com.lagradost.cloudstream3.importsystem

/**
 * Conservative, deterministic title normalization used for canonical identity and for
 * deciding whether two provider records describe the same work.
 *
 * The rules are intentionally boring: lowercase, strip punctuation, drop common suffix
 * noise (season markers, audio markers, brackets) and collapse whitespace. Nothing here
 * guesses at translations or fuzzy-matches unrelated works together: if two titles are not
 * equal after normalization they are NOT merged unless the caller explicitly opts into the
 * stricter "similar" helper, which still requires a matching year and kind.
 */
object TitleNormalizer {

    private val punctuation = Regex("""[\p{P}\p{S}]+""")
    private val whitespace = Regex("""\s+""")
    private val bracketed = Regex("""[\(\[\{][^)\]\}]*[\)\]\}]""")

    /** Noise appended by catalog sites that does not change the work's identity. */
    private val trailingNoise = Regex(
        """\s+(full (series|movie|show)|episode[s]? \d+.*|season \d+|dubbed|subbed|""" +
            """hindi|english|Dual Audio|Multi Audio|4k|1080p|720p|480p|hd|hq|mobile[^ ]*|""" +
            """watch online.*|free download.*)$""",
        RegexOption.IGNORE_CASE,
    )

    private val articles = Regex("""^(the|a|an)\s+""", RegexOption.IGNORE_CASE)

    /**
     * Stable canonical form. Used for exact identity grouping.
     * Same input always yields the same output; no locale/device dependence.
     */
    fun normalize(title: String): String {
        var t = title.trim().lowercase()
        t = bracketed.replace(t, " ")
        t = t.replace('’', '\'')
        t = punctuation.replace(t, " ")
        t = whitespace.replace(t, " ").trim()
        // Remove noise repeatedly so "Foo Season 1 (Dubbed)" collapses to "foo".
        var previous: String
        do {
            previous = t
            t = trailingNoise.replace(t, "").trim()
        } while (t != previous)
        t = articles.replace(t, "").trim()
        return whitespace.replace(t, " ").trim()
    }

    /**
     * Stable database identity for a canonical record:
     * `kind|normalized_title|year` (year omitted when unknown so later discovery can fill it).
     */
    fun canonicalKey(title: String, year: Int?, kind: String): String {
        val normalized = normalize(title)
        val yearPart = year?.toString() ?: ""
        return "$kind|$normalized|$yearPart"
    }

    /**
     * Conservative duplicate check for cross-provider merging.
     *
     * Two records may be merged only when their normalized titles are equal, and, when both
     * years are known, the years agree. A missing year on either side keeps the merge
     * conservative by requiring equality of titles only, and the caller is expected to keep
     * the ambiguous pair in the review list instead of overwriting either record.
     */
    fun canMerge(titleA: String, yearA: Int?, titleB: String, yearB: Int?): Boolean {
        val a = normalize(titleA)
        val b = normalize(titleB)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a != b) return false
        if (yearA != null && yearB != null && yearA != yearB) return false
        return true
    }

    /**
     * Similarity used only to *flag* candidates for manual review, never to merge.
     * Token Jaccard: returns 0.0..1.0.
     */
    fun similarity(titleA: String, titleB: String): Double {
        val a = normalize(titleA).split(' ').filter { it.isNotEmpty() }.toSet()
        val b = normalize(titleB).split(' ').filter { it.isNotEmpty() }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    /**
     * When two *different* normalized titles share a kind and year and are very similar,
     * they are flagged for review rather than silently merged or silently ignored.
     */
    fun isAmbiguousPair(
        titleA: String,
        yearA: Int?,
        kindA: String,
        titleB: String,
        yearB: Int?,
        kindB: String,
        threshold: Double = 0.8,
    ): Boolean {
        if (kindA != kindB) return false
        if (normalize(titleA) == normalize(titleB)) return false
        if (yearA == null || yearB == null || yearA != yearB) return false
        return similarity(titleA, titleB) >= threshold
    }
}
