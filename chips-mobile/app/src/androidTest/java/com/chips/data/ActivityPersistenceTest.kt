package com.chips.data

import androidx.test.platform.app.InstrumentationRegistry
import com.chips.dashboard.ActivityType
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.UUID

class ActivityPersistenceTest {
    @Test fun rhythmUncheckPersistsTombstonesForOnlyTheSelectedActivityAndDay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "rhythm-${UUID.randomUUID()}.db"
        var store = ActivityStore(context, name)
        val now = Instant.parse("2026-10-20T12:00:00Z")
        val zone = ZoneId.of("Asia/Jerusalem")
        try {
            val yesterday = store.log(ActivityType.WORKOUT, now.minusSeconds(86400), zone)
            val drawing = store.log(ActivityType.DRAWING, now, zone)
            val first = store.toggleToday(ActivityType.WORKOUT, now, zone)!!
            val second = store.log(ActivityType.WORKOUT, now, zone)
            assertNull(store.toggleToday(ActivityType.WORKOUT, now, zone))
            store.close()
            store = ActivityStore(context, name)
            val rows = store.load()
            for (id in listOf(first.id, second.id)) {
                val removed = rows.first { it.id == id }
                assertTrue(removed.deleted)
                assertEquals(2L, removed.revision)
                assertEquals("PENDING", removed.syncStatus)
            }
            assertFalse(rows.first { it.id == yesterday.id }.deleted)
            assertFalse(rows.first { it.id == drawing.id }.deleted)
            val next = store.toggleToday(ActivityType.WORKOUT, now, zone)!!
            assertNotEquals(first.id, next.id)
            assertEquals(setOf(ActivityType.WORKOUT, ActivityType.DRAWING), ActivityRules.completed(store.load(), LocalDate.parse("2026-10-20")))
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun offlineWorkoutSurvivesReopenEditUndoAndStaleAcknowledgement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "persistence-${UUID.randomUUID()}.db"
        var store = ActivityStore(context, name)
        try {
            val first = store.log(ActivityType.WORKOUT)
            store.close()
            store = ActivityStore(context, name)
            assertEquals(first, store.load().single())
            assertEquals(setOf(ActivityType.WORKOUT), ActivityRules.completed(store.load(), LocalDate.now()))
            store.edit(first.id, ActivityType.DRAWING, "Sketch", "2026-10-19")
            store.merge(first.copy(syncStatus = "SYNCED"))
            assertEquals("Sketch", store.load().single().note)
            assertEquals(2L, store.load().single().revision)
            store.delete(first.id)
            store.merge(first.copy(revision = 2, syncStatus = "SYNCED"))
            store.close()
            store = ActivityStore(context, name)
            assertTrue(store.load().single().deleted)
            assertEquals("PENDING", store.load().single().syncStatus)
            assertTrue(ActivityRules.completed(store.load(), LocalDate.now()).isEmpty())
        } finally { store.close(); context.deleteDatabase(name) }
    }
    @Test fun allActivitiesSharePersistenceAndUndoOnlyRemovesOneSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "activities-${UUID.randomUUID()}.db"
        val store = ActivityStore(context, name)
        try {
            ActivityType.entries.forEach { store.log(it) }
            val extra = store.log(ActivityType.WORKOUT)
            store.delete(extra.id)
            assertEquals(ActivityType.entries.toSet(), ActivityRules.completed(store.load(), LocalDate.now()))
            assertEquals(1, ActivityRules.streak(store.load(), ActivityType.WORKOUT, LocalDate.now()))
            store.bind("owner-one/device-one")
            assertTrue(runCatching { store.bind("owner-two/device-two") }.isFailure)
            store.merge(store.load().first().copy(deleted = true, syncStatus = "SYNCED"))
            assertEquals(5, store.load().size)
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
