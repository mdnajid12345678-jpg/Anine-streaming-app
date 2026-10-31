package com.lagradost.cloudstream3.importsystem

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Date helpers for matching provider-supplied epoch timestamps to the user's local date. */
object ImportDate {
    fun isValid(value: String): Boolean = try {
        LocalDate.parse(value).toString() == value
    } catch (_: Exception) {
        false
    }

    fun matches(timestampMs: Long?, date: String?): Boolean {
        if (timestampMs == null || date == null || !isValid(date)) return false
        return Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString() == date
    }
}
