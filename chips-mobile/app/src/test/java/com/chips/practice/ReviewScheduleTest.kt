package com.chips.practice

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ReviewScheduleTest {
    @Test fun againUsesMinutesAndGoodUsesLocalMidnightAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026,3,7,23,30,0,0,zone).toInstant()
        assertEquals(now.plusSeconds(600).toEpochMilli(), ReviewSchedule.next(null, Recall.AGAIN, now, zone).second)
        val next = ReviewSchedule.next(null, Recall.GOOD, now, zone)
        assertEquals(0, next.first)
        assertEquals(LocalDate.of(2026,3,8).atStartOfDay(zone).toInstant().toEpochMilli(), next.second)
        assertEquals(1, ReviewSchedule.next(null, Recall.EASY, now, zone).first)
    }
}
