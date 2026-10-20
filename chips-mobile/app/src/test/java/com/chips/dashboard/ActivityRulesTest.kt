package com.chips.dashboard

import com.chips.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ActivityRulesTest {
    private val today = LocalDate.parse("2026-10-20")
    private fun record(day: String, type: ActivityType = ActivityType.WORKOUT) = ActivityRecord(day, type, 0, "Asia/Jerusalem", day)
    @Test fun repeatedSessionsCountOnceAndDeletedSessionsDoNotCount() {
        val records = listOf(record("2026-10-20"), record("2026-10-20"), record("2026-10-19"), record("2026-10-18").copy(deleted = true))
        assertEquals(setOf(ActivityType.WORKOUT), ActivityRules.completed(records, today))
        assertEquals(2, ActivityRules.streak(records, ActivityType.WORKOUT, today))
        assertEquals(0, ActivityRules.streak(records, ActivityType.DRAWING, today))
    }
    @Test fun yesterdayKeepsStreakAliveUntilMidnightAndGapsBreakIt() {
        val records = listOf(record("2026-10-19"), record("2026-10-18"), record("2026-10-16"))
        assertEquals(2, ActivityRules.streak(records, ActivityType.WORKOUT, today))
        assertEquals(0, ActivityRules.streak(records, ActivityType.WORKOUT, today.plusDays(1)))
        assertTrue(ActivityRules.completed(records, today).isEmpty())
    }
    @Test fun localMidnightAndDstUseCalendarDatesRatherThan24HourWindows() {
        val zone = ZoneId.of("Asia/Jerusalem")
        assertEquals("2026-10-19", ActivityRules.day(Instant.parse("2026-10-19T20:59:59Z"), zone))
        assertEquals("2026-10-20", ActivityRules.day(Instant.parse("2026-10-19T21:00:00Z"), zone))
        val ny = ZoneId.of("America/New_York")
        assertEquals("2026-11-01", ActivityRules.day(Instant.parse("2026-11-01T05:30:00Z"), ny))
        assertEquals("2026-11-01", ActivityRules.day(Instant.parse("2026-11-01T06:30:00Z"), ny))
        assertEquals(1, ActivityRules.streak(listOf(record("2026-10-20"), record("2026-10-22")), ActivityType.WORKOUT, today))
    }
}
