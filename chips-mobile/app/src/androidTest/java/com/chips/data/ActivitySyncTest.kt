package com.chips.data

import androidx.test.platform.app.InstrumentationRegistry
import com.chips.dashboard.ActivityType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class ActivitySyncTest {
    @Test fun lostAcknowledgementRetryAndConcurrentLocalEditKeepDurableChanges() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sync-${UUID.randomUUID()}.db"
        var store = ActivityStore(context, name)
        val server = mutableMapOf<String, ActivityRecord>()
        var loseResponse = true
        var duringSend: (() -> Unit)? = null
        val remote = object : ActivityRemote {
            override suspend fun identity() = "test-service|owner|device"
            override suspend fun put(record: ActivityRecord): ActivityRecord {
                val old = server[record.id]
                if (old == null || (!old.deleted && record.revision > old.revision)) server[record.id] = record.copy(syncStatus = "SYNCED")
                duringSend?.also { duringSend = null; it() }
                if (loseResponse) { loseResponse = false; throw IOException("Response lost after commit") }
                return server.getValue(record.id)
            }
            override suspend fun list() = server.values.toList()
        }
        try {
            val workout = store.log(ActivityType.WORKOUT)
            assertTrue(runCatching { ActivitySynchronizer(store, remote).sync() }.isFailure)
            assertEquals("FAILED", store.load().single().syncStatus)
            store.close()
            store = ActivityStore(context, name)
            repeat(3) { ActivitySynchronizer(store, remote).sync() }
            assertEquals(1, server.size)
            assertEquals("SYNCED", store.load().single().syncStatus)
            store.edit(workout.id, ActivityType.WORKOUT, "First edit", workout.localDay)
            duringSend = { store.edit(workout.id, ActivityType.WORKOUT, "Latest edit", workout.localDay) }
            ActivitySynchronizer(store, remote).sync()
            assertEquals("Latest edit", store.load().single().note)
            assertEquals("PENDING", store.load().single().syncStatus)
            ActivitySynchronizer(store, remote).sync()
            assertEquals("Latest edit", server.getValue(workout.id).note)
            store.delete(workout.id)
            repeat(3) { ActivitySynchronizer(store, remote).sync() }
            assertTrue(server.getValue(workout.id).deleted)
            assertTrue(store.load().single().deleted)
            assertEquals("SYNCED", store.load().single().syncStatus)
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
