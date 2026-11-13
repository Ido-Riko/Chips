package com.chips.heartrate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HrStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun equalValuesCompressLosslesslyAndGapsSplitRuns() {
        val name = "hr-${UUID.randomUUID()}.db"
        try {
            HrStore(context, name).use { store ->
                val id = store.begin("band", 1000)
                val readings = listOf(HrReading(70, 2000, 100), HrReading(70, 3031, 1131), HrReading(70, 2500, 2100))
                store.samples(id, readings.take(2))
                store.samples(id, readings.drop(2)) // merges across write batches
                assertEquals(readings, store.readings(id))
                store.readableDatabase.rawQuery("SELECT COUNT(*), SUM(sample_count) FROM sample_runs", null).use {
                    it.moveToFirst(); assertEquals(1, it.getInt(0)); assertEquals(3, it.getInt(1))
                }
                store.event(id, "disconnected", 3000)
                store.sample(id, 70, 4000, 3100)
                store.sample(id, 70, 24000, 23100) // >15-second gap cannot merge
                store.sample(id, 100, 25000, 24100)
                val session = store.sessions().single()
                assertEquals(6, session.samples)
                assertEquals(75.0, session.average!!, 0.0)
                store.readableDatabase.rawQuery("SELECT COUNT(*) FROM sample_runs", null).use { it.moveToFirst(); assertEquals(4, it.getInt(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun runSizeIsBoundedAndRecoveryKeepsIdentityWithoutJoiningAcrossInterruption() {
        val name = "hr-${UUID.randomUUID()}.db"
        try {
            HrStore(context, name).use { store ->
                val id = store.begin("band", 1000)
                val readings = (0..256).map { HrReading(70, 2000L + it, 100L + it) }
                store.samples(id, readings)
                store.readableDatabase.rawQuery("SELECT sample_count FROM sample_runs ORDER BY id", null).use {
                    it.moveToFirst(); assertEquals(256, it.getInt(0)); it.moveToNext(); assertEquals(1, it.getInt(0))
                }
                store.recoverInterrupted()
                assertEquals(id, store.resume(id, "band", 3000))
                store.sample(id, 70, 3100, 1100)
                assertEquals(readings + HrReading(70, 3100, 1100), store.readings(id))
                store.readableDatabase.rawQuery("SELECT COUNT(*) FROM sample_runs", null).use { it.moveToFirst(); assertEquals(3, it.getInt(0)) }
                assertNull(store.sessions().single().ended)
                store.end(id)
                assertNotEquals(id, store.resume(id, "band")) // explicit Stop cannot be reopened
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migratesRealVersionOneWithoutLosingSamplesEventsOrEndedSessions() {
        val name = "hr-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,started INTEGER NOT NULL,ended INTEGER,status TEXT NOT NULL,device TEXT NOT NULL)")
                db.execSQL("CREATE TABLE samples(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,bpm INTEGER NOT NULL,received INTEGER NOT NULL,elapsed INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,at INTEGER NOT NULL,state TEXT NOT NULL)")
                db.execSQL("INSERT INTO sessions VALUES('old',1000,5000,'stopped','band')")
                db.execSQL("INSERT INTO samples(session_id,bpm,received,elapsed) VALUES('old',70,2000,100),('old',70,3000,1100),('old',90,2500,2100)")
                db.execSQL("INSERT INTO events(session_id,at,state) VALUES('old',2300,'disconnected')")
                db.version = 1
            }
            repeat(2) {
                HrStore(context, name).use { store ->
                    assertEquals(listOf(HrReading(70,2000,100), HrReading(70,3000,1100), HrReading(90,2500,2100)), store.readings("old"))
                    val session = store.sessions().single()
                    assertEquals(3, session.samples)
                    assertEquals(76.666666, session.average!!, 0.0001)
                    assertEquals(2500L, session.lastSample)
                    assertEquals(5000L, session.ended)
                    store.readableDatabase.rawQuery("SELECT state FROM events", null).use { it.moveToFirst(); assertEquals("disconnected", it.getString(0)) }
                    assertEquals(3, store.readableDatabase.version)
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun restartIntentPersistsActiveCaptureAndLatestOutputsButExplicitStopClearsIt() {
        val prefs = context.getSharedPreferences("hr_capture_recovery", Context.MODE_PRIVATE)
        val previous = prefs.all
        try {
            val settings = HrRecoverySettings(context)
            settings.clear()
            assertNull(settings.restart(context))
            settings.save("session-id", "00:11:22:33:44:55", HrOutputConfig())
            settings.outputs(HrOutputConfig(relay = true, osc = true, host = "127.0.0.1", port = 9100))
            val recreated = HrRecoverySettings(context)
            val intent = recreated.restart(context)!!
            assertEquals("session-id", recreated.session())
            assertEquals("00:11:22:33:44:55", intent.getStringExtra("device"))
            assertTrue(intent.getBooleanExtra("relay", false))
            assertTrue(intent.getBooleanExtra("osc", false))
            assertEquals("127.0.0.1", intent.getStringExtra("host"))
            assertEquals(9100, intent.getIntExtra("port", 0))
            recreated.clear()
            recreated.outputs(HrOutputConfig(osc = true)) // late output update cannot rearm capture
            assertNull(HrRecoverySettings(context).restart(context))
        } finally {
            prefs.edit().clear().apply {
                previous.forEach { (key, value) -> when(value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                } }
            }.commit()
        }
    }

    @Test fun retainsStableSamplesAndGapsThenDeletesOwnedCapture() {
        val name = "hr-${UUID.randomUUID()}.db"
        try {
            val id = HrStore(context, name).use { store ->
                val id = store.begin("00:11:22:33:44:55", 1000)
                store.sample(id, 80, 1100, 10)
                store.sample(id, 80, 2100, 1010)
                store.event(id, "disconnected", 2200)
                store.sample(id, 100, 9100, 8010)
                store.delete(id) // active session cannot be deleted
                assertEquals(1, store.sessions().size)
                store.end(id, now = 10000)
                store.sample(id, 200, 11000, 9910) // late callback cannot add data
                id
            }
            HrStore(context, name).use { store ->
                val session = store.sessions().single()
                assertEquals(3, session.samples)
                assertEquals(86.666666, session.average!!, 0.0001)
                assertEquals(100, session.peak)
                assertEquals(9100L, session.lastSample)
                store.readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
                store.delete(id)
                assertTrue(store.sessions().isEmpty())
                for(table in listOf("events", "sample_runs")) store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun processDeathMarksInterruptionAtLastReceivedSample() {
        val name = "hr-${UUID.randomUUID()}.db"
        try {
            HrStore(context, name).use { store ->
                val id = store.begin("00:11:22:33:44:55", 1000)
                store.sample(id, 70, 2000, 100)
            }
            HrStore(context, name).use { store ->
                store.recoverInterrupted(); store.recoverInterrupted()
                assertEquals("interrupted", store.sessions().single().status)
                assertEquals(2000L, store.sessions().single().ended)
            }
        } finally { context.deleteDatabase(name) }
    }
}
