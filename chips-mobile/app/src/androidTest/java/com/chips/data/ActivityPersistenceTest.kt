package com.chips.data

import androidx.test.platform.app.InstrumentationRegistry
import com.chips.dashboard.ActivityType
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.UUID

class ActivityPersistenceTest {
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
