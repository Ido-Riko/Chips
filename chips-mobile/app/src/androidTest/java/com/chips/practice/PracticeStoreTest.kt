package com.chips.practice

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import com.chips.data.ActivityStore
import com.chips.dashboard.ActivityType
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.UUID

class PracticeStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun additiveMigrationPreservesRealVersionOneData() {
        val name = "upgrade-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name,android.content.Context.MODE_PRIVATE,null).use { db ->
                db.execSQL("CREATE TABLE activities(id TEXT PRIMARY KEY,type TEXT NOT NULL,occurred_at INTEGER NOT NULL,zone_id TEXT NOT NULL,local_day TEXT NOT NULL,note TEXT NOT NULL,revision INTEGER NOT NULL,deleted INTEGER NOT NULL,sync_status TEXT NOT NULL,error TEXT NOT NULL)")
                db.execSQL("CREATE INDEX activity_day ON activities(local_day,deleted)")
                db.execSQL("CREATE TABLE sync_identity(slot INTEGER PRIMARY KEY,identity TEXT NOT NULL)")
                db.execSQL("CREATE TABLE voice_history(id TEXT PRIMARY KEY,occurred_at INTEGER NOT NULL,transcript TEXT NOT NULL,answer TEXT NOT NULL,sync_status TEXT NOT NULL)")
                db.execSQL("INSERT INTO activities VALUES('old','WORKOUT',1000,'UTC','2026-11-13','',1,0,'PENDING','')")
                db.execSQL("INSERT INTO voice_history VALUES('prior',1234,'spoken','answer','LOCAL')")
                db.version=1
            }
            ActivityStore(context,name).use { db ->
                assertEquals(3,db.readableDatabase.version)
                assertEquals(1,db.load().size)
                assertEquals("spoken",db.loadVoiceHistory().single().transcript)
                assertTrue(PracticeStore(db).reviews().isEmpty())
                assertTrue(PracticeStore(db).drawings().isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun reviewsSurviveReopenLimitNewCardsAndLogOnlyOneDailyCompletion() {
        val name = "practice-${UUID.randomUUID()}.db"
        var activity = ActivityStore(context, name)
        var store = PracticeStore(activity)
        val now = Instant.parse("2026-11-13T10:00:00Z")
        val zone = ZoneId.of("Asia/Jerusalem")
        try {
            val deck = store.deck(context)
            assertEquals(30,deck.size)
            assertEquals(3,store.queue(deck,now,zone).size)
            for (card in store.queue(deck,now,zone)) store.rate(card.kanji,Recall.EASY,now,zone)
            assertTrue(store.queue(deck,now,zone).isEmpty())
            assertEquals(1, activity.load().count { it.type == ActivityType.JAPANESE })
            activity.close();activity=ActivityStore(context,name);store=PracticeStore(activity)
            assertEquals(3,store.reviews().size)
            assertTrue(store.queue(deck,now,zone).isEmpty())
            assertEquals(3,store.queue(deck,now.plusSeconds(86400),zone).size)
            store.resetReviews()
            assertEquals(0,store.reviews().size)
            assertEquals(1,activity.load().size)
        } finally { activity.close();context.deleteDatabase(name) }
    }
    @Test fun drawingsPersistNotesQueueDeletionAndDistinctActivityIds() {
        val name="drawings-${UUID.randomUUID()}.db"
        val activity=ActivityStore(context,name)
        val store=PracticeStore(activity)
        try {
            val id=UUID.randomUUID().toString()
            store.addDrawing(id,"Study shapes")
            store.updateDrawing(id,ContentValues().apply { put("notes","Look at the shadow");put("uploaded",1);put("target","test-target");put("feedback","Try a sphere") })
            assertEquals("Look at the shadow",store.drawings().single().notes)
            store.deleteDrawing(id)
            assertTrue(store.drawings().isEmpty())
            val tombstone=store.drawings(true).single()
            assertTrue(tombstone.queued && tombstone.deleted)
            assertEquals("test-target",tombstone.target)
            assertEquals("",tombstone.feedback)
            assertFalse(store.updateActiveDrawing(id,ContentValues().apply { put("feedback","late response");put("queued",0) }))
            assertTrue(store.drawings(true).single().queued)
            assertEquals(1,activity.load().count { it.type==ActivityType.DRAWING })
            store.finishDelete(id)
            assertTrue(store.drawings(true).isEmpty())
        } finally { activity.close();context.deleteDatabase(name) }
    }
}
