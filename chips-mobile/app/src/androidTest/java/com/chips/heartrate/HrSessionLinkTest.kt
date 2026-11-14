package com.chips.heartrate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chips.dashboard.ActivityType
import com.chips.data.ActivityStore
import com.chips.data.ActivityRecord
import com.chips.data.ActivityRemote
import com.chips.data.ActivitySynchronizer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class HrSessionLinkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture(block: (HrStore, ActivityStore, String, String) -> Unit) {
        val hrName = "hr-links-${UUID.randomUUID()}.db"
        val activityName = "hr-activities-${UUID.randomUUID()}.db"
        try { HrStore(context,hrName).use { hr -> ActivityStore(context,activityName).use { activities -> block(hr,activities,hrName,activityName) } } }
        finally { context.deleteDatabase(hrName); context.deleteDatabase(activityName) }
    }

    @Test fun realVersionTwoHrUpgradePreservesOldDataAndNewCapturesRemainRecoverable() {
        val name="hr-v2-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { db ->
                db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,started INTEGER NOT NULL,ended INTEGER,status TEXT NOT NULL,device TEXT NOT NULL)")
                db.execSQL("CREATE TABLE sample_runs(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,bpm INTEGER NOT NULL,received INTEGER NOT NULL,elapsed INTEGER NOT NULL,last_received INTEGER NOT NULL,last_elapsed INTEGER NOT NULL,sample_count INTEGER NOT NULL,timing BLOB NOT NULL,epoch INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,at INTEGER NOT NULL,state TEXT NOT NULL)")
                db.execSQL("INSERT INTO sessions VALUES('ended',1000,3000,'stopped','band'),('active',4000,NULL,'capturing','band')")
                db.execSQL("INSERT INTO sample_runs(session_id,bpm,received,elapsed,last_received,last_elapsed,sample_count,timing,epoch) VALUES('ended',70,2000,100,2000,100,1,X'',0)")
                db.version=2
            }
            HrStore(context,name).use { hr ->
                assertEquals(3,hr.readableDatabase.version)
                assertEquals(listOf(HrReading(70,2000,100)),hr.readings("ended"))
                assertEquals("none",hr.sessions("ended").single().prompt)
                assertEquals("UTC",hr.sessions("ended").single().zoneId)
                hr.recoverInterrupted()
                assertEquals(listOf("ended"),hr.pendingSync())
                assertEquals("active",hr.resume("active","band",5000))
                val fresh=hr.begin("band",6000)
                hr.recoverInterrupted()
                assertEquals(fresh,hr.resume(fresh,"band",7000))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionTwoActivityUpgradePreservesVoicePracticeIdentityAndOutbox() {
        val name="activities-v2-${UUID.randomUUID()}.db"
        try {
            ActivityStore(context,name).use { store ->
                store.log(ActivityType.DRAWING)
                store.saveVoiceConversation(com.chips.voice.VoiceConversation("kept",1000,"spoken","answer"))
                store.bind("original-owner")
                val db=store.writableDatabase
                db.execSQL("CREATE TABLE activities_v2(id TEXT PRIMARY KEY,type TEXT NOT NULL,occurred_at INTEGER NOT NULL,zone_id TEXT NOT NULL,local_day TEXT NOT NULL,note TEXT NOT NULL,revision INTEGER NOT NULL,deleted INTEGER NOT NULL,sync_status TEXT NOT NULL,error TEXT NOT NULL)")
                db.execSQL("INSERT INTO activities_v2 SELECT id,type,occurred_at,zone_id,local_day,note,revision,deleted,sync_status,error FROM activities")
                db.execSQL("DROP TABLE activities")
                db.execSQL("ALTER TABLE activities_v2 RENAME TO activities")
                db.execSQL("CREATE INDEX activity_day ON activities(local_day,deleted)")
                db.version=2
            }
            ActivityStore(context,name).use { store ->
                assertEquals(3,store.readableDatabase.version)
                assertEquals(ActivityType.DRAWING,store.load().single().type)
                assertNull(store.load().single().hrSessionId)
                assertEquals("PENDING",store.load().single().syncStatus)
                assertEquals("answer",store.loadVoiceHistory().single().answer)
                assertThrows(IllegalStateException::class.java) { store.bind("other-owner") }
                store.logHeartRateSession(UUID.randomUUID().toString(),1000,"UTC")
                assertEquals(2,store.load().size)
                assertEquals(0,com.chips.practice.PracticeStore(store).reviews().size)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun confirmedChoiceLinksExactlyOnceAndKeepsCapturedDateAcrossRestart() = fixture { hr, activities, hrName, activityName ->
        val start = Instant.parse("2026-11-12T22:30:00Z").toEpochMilli()
        val id = hr.begin("band", start, ZoneId.of("Asia/Jerusalem"))
        hr.sample(id,70,start+1000,1000)
        hr.end(id,now=start+60000,offerBeatSaber=true)
        assertEquals(id,HrSessionLinker(hr,activities).pending()!!.id)
        val record = HrSessionLinker(hr,activities).save(id)
        assertEquals(ActivityType.BEAT_SABER,record.type)
        assertEquals(id,record.hrSessionId)
        assertEquals(start,record.occurredAt)
        assertEquals("2026-11-13",record.localDay)
        HrStore(context,hrName).use { reopened -> ActivityStore(context,activityName).use { reopenedActivities ->
            repeat(3) { assertEquals(record,HrSessionLinker(reopened,reopenedActivities).save(id)) }
            assertNull(HrSessionLinker(reopened,reopenedActivities).pending())
            assertEquals(1,reopenedActivities.load().size)
            assertEquals(1,reopened.readings(id).size)
        } }
    }

    @Test fun crashBetweenActivityCommitAndChoiceAcknowledgementDoesNotDuplicateOrResurrect() = fixture { hr,activities,_,_ ->
        val id=hr.begin("band",1000)
        hr.end(id,now=5000,offerBeatSaber=true)
        val record=activities.logHeartRateSession(id,1000,hr.sessions(id).single().zoneId)
        // Simulates process loss at the cross-database boundary before hr.decide().
        assertEquals("pending",hr.sessions(id).single().prompt)
        assertNull(HrSessionLinker(hr,activities).pending())
        assertEquals("saved",hr.sessions(id).single().prompt)
        activities.delete(record.id)
        HrSessionLinker(hr,activities).save(id)
        assertEquals(1,activities.load().size)
        assertTrue(activities.load().single().deleted)
        assertTrue(hr.sessions().isNotEmpty())
    }

    @Test fun declineKeepsHrAndDeletionKeepsTheOtherSave() = fixture { hr,activities,_,_ ->
        val id=hr.begin("band",1000)
        hr.sample(id,70,2000,1000)
        hr.end(id,now=5000,offerBeatSaber=true)
        hr.decide(id,save=false)
        assertNull(HrSessionLinker(hr,activities).pending())
        assertTrue(activities.load().isEmpty())
        assertEquals(1,hr.readings(id).size)
        val record=HrSessionLinker(hr,activities).save(id)
        assertThrows(IllegalArgumentException::class.java) { activities.edit(record.id,ActivityType.WORKOUT,"",record.localDay) }
        hr.delete(id)
        assertTrue(hr.sessions().isEmpty())
        assertTrue(hr.readings(id).isEmpty())
        assertEquals(listOf(id),hr.pendingSync())
        assertFalse(activities.load().single().deleted)
        hr.readableDatabase.rawQuery("SELECT started,ended,device FROM sessions WHERE id=?",arrayOf(id)).use { it.moveToFirst();assertEquals(0L,it.getLong(0));assertEquals(0L,it.getLong(1));assertEquals("",it.getString(2)) }
    }

    @Test fun interruptedActiveCaptureCannotUploadAnImmutableSnapshotBeforeRecovery() = fixture { hr,activities,_,_ ->
        val id=hr.begin("band",1000)
        hr.sample(id,70,2000,100)
        hr.recoverInterrupted()
        assertTrue(hr.pendingSync().isEmpty())
        assertThrows(IllegalStateException::class.java) { hr.manifest(id) }
        assertEquals(id,hr.resume(id,"band",3000))
        assertFalse(hr.seal(id)) // recovery won the race; never link a still-active capture
        assertNull(hr.sessions(id).single().ended)
        hr.end(id,now=4000,offerBeatSaber=true)
        assertEquals(listOf(id),hr.pendingSync())
        assertTrue(activities.load().isEmpty())
    }

    private class Remote : HrRemote {
        var manifest: JSONObject?=null
        val chunks=mutableMapOf<String,JSONArray>()
        var completed=false
        var removed=false
        var loseChunk=true
        var loseComplete=true
        var beforeComplete: (() -> Unit)?=null
        override suspend fun begin(manifest: JSONObject): JSONObject {
            check(!removed)
            if(this.manifest!=null) check(hrCanonical(this.manifest)==hrCanonical(manifest))
            this.manifest=manifest
            return ack()
        }
        fun ack()=JSONObject().put("id",manifest!!.getString("id")).put("manifest",manifest).put("complete",completed).put("deleted",removed)
        override suspend fun chunk(id: String,kind: String,offset: Int,records: JSONArray): JSONObject {
            val key="$kind:$offset"
            chunks[key]?.let { check(hrCanonical(it)==hrCanonical(records)) }
            chunks[key]=records
            if(loseChunk) { loseChunk=false; throw IOException("Chunk committed but response lost") }
            return JSONObject().put("offset",offset).put("records",records)
        }
        override suspend fun complete(id: String): JSONObject {
            completed=true
            beforeComplete?.invoke()
            if(loseComplete) { loseComplete=false; throw IOException("Complete committed but response lost") }
            return ack()
        }
        override suspend fun delete(id: String): JSONObject { removed=true; chunks.clear();return JSONObject().put("id",id).put("deleted",true) }
    }

    @Test fun chunkAndCompleteResponseLossRetryAfterReopenWithoutDroppingTimings() = fixture { hr,_,hrName,_ -> runBlocking {
        val id=hr.begin("band",1000)
        // Different values force more than one upload page; reception times must stay exact.
        val expected=(0..130).map { HrReading(70+it%2,2000L+it*1000,100L+it*1000) }
        hr.samples(id,expected)
        hr.event(id,"disconnected",140000)
        hr.end(id,now=141000)
        val remote=Remote()
        assertTrue(runCatching { HrSynchronizer(hr,remote).sync(id) }.isFailure)
        HrStore(context,hrName).use { reopened ->
            assertTrue(runCatching { HrSynchronizer(reopened,remote).sync(id) }.isFailure)
            repeat(2) { HrSynchronizer(reopened,remote).sync(id) }
            assertTrue(reopened.pendingSync().isEmpty())
            assertEquals("SYNCED",reopened.sessions(id).single().syncStatus)
            assertEquals(expected,reopened.readings(id))
        }
        assertEquals(3,remote.chunks.size) // two run pages and one event page
        assertEquals(128,remote.chunks.getValue("runs:0").length())
        assertEquals(3,remote.chunks.getValue("runs:128").length())
    } }

    @Test fun localDeleteDuringUploadCannotBeClearedByLateAcknowledgement() = fixture { hr,activities,_,_ -> runBlocking {
        val id=hr.begin("band",1000)
        hr.sample(id,70,2000,100)
        hr.end(id,now=3000)
        val linked=HrSessionLinker(hr,activities).save(id)
        val remote=Remote().apply { loseChunk=false;loseComplete=false;beforeComplete={ hr.delete(id) } }
        HrSynchronizer(hr,remote).sync(id)
        assertEquals(listOf(id),hr.pendingSync())
        HrSynchronizer(hr,remote).sync(id)
        assertTrue(remote.removed)
        assertTrue(remote.chunks.isEmpty())
        assertTrue(hr.pendingSync().isEmpty())
        assertFalse(activities.load().first { it.id==linked.id }.deleted)
    } }

    @Test fun incompatibleManifestAcknowledgementAndDifferentEnrollmentStayLocal() = fixture { hr,_,_,_ -> runBlocking {
        val id=hr.begin("band",1000)
        hr.end(id,now=2000)
        hr.bind("service|owner|device")
        assertThrows(IllegalStateException::class.java) { hr.bind("service|other-owner|other-device") }
        val remote=object:HrRemote {
            override suspend fun begin(manifest:JSONObject)=JSONObject().put("id",id).put("deleted",false).put("complete",true).put("manifest",JSONObject(manifest.toString()).put("ended",9999))
            override suspend fun chunk(id:String,kind:String,offset:Int,records:JSONArray):JSONObject=error("unexpected")
            override suspend fun complete(id:String):JSONObject=error("unexpected")
            override suspend fun delete(id:String):JSONObject=error("unexpected")
        }
        assertTrue(runCatching { HrSynchronizer(hr,remote).sync(id) }.isFailure)
        assertEquals(listOf(id),hr.pendingSync())
    } }

    @Test fun hrUploadsBeforeActivityLinkAndActivityResponseLossRetriesOneCompletion() = fixture { hr,activities,_,_ -> runBlocking {
        val id=hr.begin("band",1000)
        hr.sample(id,70,2000,100)
        hr.end(id,now=3000,offerBeatSaber=true)
        val local=HrSessionLinker(hr,activities).save(id)
        val remoteHr=Remote().apply { loseChunk=false;loseComplete=false }
        val saved=mutableMapOf<String,ActivityRecord>()
        var loseActivityReply=true
        val remoteActivity=object:ActivityRemote {
            override suspend fun identity()="service|owner|device"
            override suspend fun put(record:ActivityRecord):ActivityRecord {
                check(remoteHr.completed) { "Activity link must wait for complete HR archive" }
                check(record.hrSessionId==id)
                saved[record.id]=record.copy(syncStatus="SYNCED")
                if(loseActivityReply) { loseActivityReply=false;throw IOException("Activity response lost") }
                return saved.getValue(record.id)
            }
            override suspend fun list()=saved.values.toList()
        }
        fun sync()=ActivitySynchronizer(activities,remoteActivity) { record ->
            record.hrSessionId?.let { HrSynchronizer(hr,remoteHr).sync(it) }
        }
        assertTrue(runCatching { sync().sync() }.isFailure)
        assertEquals("FAILED",activities.load().single().syncStatus)
        repeat(2) { sync().sync() }
        assertEquals(1,saved.size)
        assertEquals(local.id,saved.values.single().id)
        assertEquals("SYNCED",activities.load().single().syncStatus)
        assertEquals("SYNCED",hr.sessions(id).single().syncStatus)
    } }
}
