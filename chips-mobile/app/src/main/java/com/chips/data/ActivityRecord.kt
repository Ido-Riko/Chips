package com.chips.data

import com.chips.dashboard.ActivityType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The calendar date/zone at capture is retained when travelling or changing phone timezone. */
data class ActivityRecord(
    val id: String,
    val type: ActivityType,
    val occurredAt: Long,
    val zoneId: String,
    val localDay: String,
    val note: String = "",
    val revision: Long = 1,
    val deleted: Boolean = false,
    val syncStatus: String = "PENDING",
    val error: String = ""
)

object ActivityRules {
    fun day(instant: Instant, zone: ZoneId): String = instant.atZone(zone).toLocalDate().toString()
    fun completed(records: List<ActivityRecord>, today: LocalDate): Set<ActivityType> = records
        .filter { !it.deleted && it.localDay == today.toString() }.map { it.type }.toSet()

    /** A streak stays alive through today if the last completion was yesterday. */
    fun streak(records: List<ActivityRecord>, type: ActivityType, today: LocalDate): Int {
        val days = records.filter { !it.deleted && it.type == type }.map { LocalDate.parse(it.localDay) }.toSet()
        var day = if (today in days) today else today.minusDays(1)
        var count = 0
        while (day in days) { count++; day = day.minusDays(1) }
        return count
    }
}
