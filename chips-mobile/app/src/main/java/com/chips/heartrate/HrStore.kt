package com.chips.heartrate

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID
import java.time.ZoneId
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HrSession(val id: String, val started: Long, val ended: Long?, val status: String,
    val samples: Int, val average: Double?, val peak: Int?, val lastSample: Long?,
    val zoneId: String = "UTC", val prompt: String = "none", val syncStatus: String = "PENDING", val syncError: String = "")

/** Independent local store. Equal consecutive BPM share a run, retaining all reception times. */
class HrStore(context: Context, name: String = "heart_rate.db") : SQLiteOpenHelper(context, name, null, 3) {
    companion object {
        private val revision = MutableStateFlow(0L)
        val changes = revision.asStateFlow()
        private fun changed() { revision.update { it + 1 } }
    }
    private fun createSyncState(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE hr_state(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
    private fun createRuns(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sample_runs(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, bpm INTEGER NOT NULL CHECK(bpm BETWEEN 1 AND 65535), received INTEGER NOT NULL, elapsed INTEGER NOT NULL, last_received INTEGER NOT NULL, last_elapsed INTEGER NOT NULL, sample_count INTEGER NOT NULL CHECK(sample_count BETWEEN 1 AND 256), timing BLOB NOT NULL, epoch INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX sample_runs_session ON sample_runs(session_id,id)")
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, started INTEGER NOT NULL, ended INTEGER, status TEXT NOT NULL, device TEXT NOT NULL, zone_id TEXT NOT NULL DEFAULT 'UTC', prompt TEXT NOT NULL DEFAULT 'none', sync_status TEXT NOT NULL DEFAULT 'PENDING', sync_error TEXT NOT NULL DEFAULT '', deleted INTEGER NOT NULL DEFAULT 0, sealed INTEGER NOT NULL DEFAULT 0)")
        createSyncState(db)
        createRuns(db)
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, at INTEGER NOT NULL, state TEXT NOT NULL)")
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion < 2) {
            createRuns(db)
            // Existing samples become one-sample runs; no timing/provenance is inferred during migration.
            db.execSQL("INSERT INTO sample_runs(session_id,bpm,received,elapsed,last_received,last_elapsed,sample_count,timing,epoch) SELECT session_id,bpm,received,elapsed,received,elapsed,1,X'',0 FROM samples ORDER BY id")
            db.execSQL("DROP TABLE samples")
        }
        if(oldVersion < 3) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN zone_id TEXT NOT NULL DEFAULT 'UTC'")
            db.execSQL("ALTER TABLE sessions ADD COLUMN prompt TEXT NOT NULL DEFAULT 'none'")
            db.execSQL("ALTER TABLE sessions ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'PENDING'")
            db.execSQL("ALTER TABLE sessions ADD COLUMN sync_error TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE sessions ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN sealed INTEGER NOT NULL DEFAULT 1")
            db.execSQL("UPDATE sessions SET sealed=0 WHERE ended IS NULL")
            createSyncState(db)
        }
    }

    @Synchronized fun begin(device: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", id); put("started", now); put("status", "capturing"); put("device", device); put("zone_id", zone.id); put("sealed", 0)
        })
        return id
    }
    @Synchronized fun resume(id: String, device: String, now: Long = System.currentTimeMillis()): String {
        val updated = writableDatabase.update("sessions", ContentValues().apply { putNull("ended"); put("status", "capturing"); put("sync_status", "PENDING") },
            "id=? AND deleted=0 AND sealed=0 AND status IN ('capturing','interrupted')", arrayOf(id))
        if(updated == 0) return begin(device, now)
        event(id, "Capture recovered after process interruption; missing interval remains a gap", now)
        return id
    }
    @Synchronized fun sample(id: String, bpm: Int, received: Long, elapsed: Long) = samples(id, listOf(HrReading(bpm, received, elapsed)))

    private data class Run(var id: Long?, val bpm: Int, val firstWall: Long, val firstElapsed: Long,
        var wall: Long, var elapsed: Long, var count: Int, var timing: ByteArray, val epoch: Long)

    @Synchronized fun samples(id: String, readings: List<HrReading>) {
        if(readings.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val active = db.rawQuery("SELECT 1 FROM sessions WHERE id=? AND ended IS NULL", arrayOf(id)).use { it.moveToFirst() }
            if(!active) { db.setTransactionSuccessful(); return }
            val epoch = db.rawQuery("SELECT COALESCE(MAX(id),0) FROM events WHERE session_id=?", arrayOf(id)).use { it.moveToFirst(); it.getLong(0) }
            var run: Run? = db.rawQuery("SELECT id,bpm,received,elapsed,last_received,last_elapsed,sample_count,timing,epoch FROM sample_runs WHERE session_id=? ORDER BY id DESC LIMIT 1", arrayOf(id)).use { c ->
                if(!c.moveToFirst()) null else Run(c.getLong(0),c.getInt(1),c.getLong(2),c.getLong(3),c.getLong(4),c.getLong(5),c.getInt(6),c.getBlob(7),c.getLong(8))
            }
            var dirty = false
            fun save() {
                val r = run ?: return
                if(!dirty) return
                val values = ContentValues().apply {
                    put("session_id", id); put("bpm", r.bpm); put("received", r.firstWall); put("elapsed", r.firstElapsed)
                    put("last_received", r.wall); put("last_elapsed", r.elapsed); put("sample_count", r.count)
                    put("timing", r.timing); put("epoch", r.epoch)
                }
                if(r.id == null) r.id = db.insertOrThrow("sample_runs", null, values)
                else db.update("sample_runs", values, "id=?", arrayOf(r.id.toString()))
                dirty = false
            }
            for(sample in readings) {
                require(sample.bpm in 1..65535)
                val previous = run
                val distance = if(previous == null) -1 else sample.elapsed - previous.elapsed
                if(previous != null && previous.bpm == sample.bpm && previous.epoch == epoch && previous.count < 256 && distance in 0..15_000L) {
                    previous.timing += HrRunCodec.delta(sample.received - previous.wall, distance)
                    previous.wall = sample.received; previous.elapsed = sample.elapsed; previous.count++
                } else {
                    save()
                    run = Run(null, sample.bpm, sample.received, sample.elapsed, sample.received, sample.elapsed, 1, byteArrayOf(), epoch)
                }
                dirty = true
            }
            save()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun readings(id: String): List<HrReading> = readableDatabase.rawQuery(
        "SELECT bpm,received,elapsed,sample_count,timing FROM sample_runs WHERE session_id=? ORDER BY id", arrayOf(id)).use { c -> buildList {
        while(c.moveToNext()) addAll(HrRunCodec.readings(c.getInt(0),c.getLong(1),c.getLong(2),c.getInt(3),c.getBlob(4)))
    } }
    @Synchronized fun event(id: String, state: String, now: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL("INSERT INTO events(session_id,at,state) SELECT id,?,? FROM sessions WHERE id=? AND ended IS NULL", arrayOf<Any>(now, state, id))
    }
    @Synchronized fun end(id: String, status: String = "stopped", now: Long = System.currentTimeMillis(), offerBeatSaber: Boolean = false) {
        writableDatabase.execSQL("UPDATE sessions SET ended=?,status=?,prompt=?,sealed=?,sync_status=? WHERE id=? AND ended IS NULL", arrayOf<Any>(now, status, if(offerBeatSaber && status == "stopped") "pending" else "none", if(status == "interrupted") 0 else 1, if(status == "interrupted") "WAITING_RECOVERY" else "PENDING", id))
        changed()
    }
    @Synchronized fun recoverInterrupted() {
        writableDatabase.execSQL("UPDATE sessions SET ended=COALESCE((SELECT last_received FROM sample_runs WHERE session_id=sessions.id ORDER BY id DESC LIMIT 1),started),status='interrupted',sync_status='WAITING_RECOVERY' WHERE ended IS NULL")
    }
    @Synchronized fun sessions(id: String? = null, pendingPrompt: Boolean = false): List<HrSession> = readableDatabase.rawQuery("""
        SELECT s.id,s.started,s.ended,s.status,COALESCE(SUM(p.sample_count),0),SUM(p.bpm * 1.0 * p.sample_count)/SUM(p.sample_count),MAX(p.bpm),
        (SELECT last_received FROM sample_runs WHERE session_id=s.id ORDER BY id DESC LIMIT 1),s.zone_id,s.prompt,s.sync_status,s.sync_error
        FROM sessions s LEFT JOIN sample_runs p ON p.session_id=s.id WHERE s.deleted=0
        ${if(id != null) "AND s.id=?" else ""} ${if(pendingPrompt) "AND s.prompt='pending' AND s.ended IS NOT NULL" else ""}
        GROUP BY s.id ORDER BY s.started DESC LIMIT 100
    """.trimIndent(), if(id != null) arrayOf(id) else null).use { c -> buildList {
        while(c.moveToNext()) add(HrSession(c.getString(0), c.getLong(1), if(c.isNull(2)) null else c.getLong(2),
            c.getString(3), c.getInt(4), if(c.isNull(5)) null else c.getDouble(5),
            if(c.isNull(6)) null else c.getInt(6), if(c.isNull(7)) null else c.getLong(7), c.getString(8),c.getString(9),c.getString(10),c.getString(11)))
    } }
    @Synchronized fun seal(id: String): Boolean {
        writableDatabase.execSQL("UPDATE sessions SET sealed=1,sync_status='PENDING' WHERE id=? AND ended IS NOT NULL AND sealed=0 AND deleted=0", arrayOf(id))
        val kept = readableDatabase.rawQuery("SELECT 1 FROM sessions WHERE id=? AND ended IS NOT NULL AND sealed=1 AND deleted=0", arrayOf(id)).use { it.moveToFirst() }
        if(kept) changed()
        return kept
    }
    @Synchronized fun sealed(id: String): Boolean = readableDatabase.rawQuery("SELECT sealed FROM sessions WHERE id=?", arrayOf(id)).use { it.moveToFirst() && it.getInt(0) != 0 }
    @Synchronized fun decide(id: String, save: Boolean) {
        writableDatabase.execSQL("UPDATE sessions SET prompt=? WHERE id=? AND ended IS NOT NULL AND deleted=0", arrayOf(if(save) "saved" else "declined", id))
        changed()
    }
    @Synchronized fun requestPrompt(id: String) {
        writableDatabase.execSQL("UPDATE sessions SET prompt='pending' WHERE id=? AND ended IS NOT NULL AND deleted=0", arrayOf(id))
        changed()
    }
    @Synchronized fun deleted(id: String): Boolean = readableDatabase.rawQuery("SELECT deleted FROM sessions WHERE id=?", arrayOf(id)).use { it.moveToFirst() && it.getInt(0) != 0 }
    @Synchronized fun exists(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM sessions WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
    @Synchronized fun pendingSync(): List<String> = readableDatabase.rawQuery("SELECT id FROM sessions WHERE ended IS NOT NULL AND sealed=1 AND sync_status!='SYNCED' ORDER BY started LIMIT 100", null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    @Synchronized fun synced(id: String, wasDeleted: Boolean) {
        writableDatabase.execSQL("UPDATE sessions SET sync_status='SYNCED',sync_error='' WHERE id=? AND ended IS NOT NULL AND deleted=?", arrayOf<Any>(id, if(wasDeleted) 1 else 0))
        changed()
    }
    @Synchronized fun failed(id: String, error: String) {
        writableDatabase.execSQL("UPDATE sessions SET sync_status='FAILED',sync_error=? WHERE id=? AND sync_status!='SYNCED'", arrayOf(error, id))
        changed()
    }
    @Synchronized fun bind(identity: String) {
        val prior = readableDatabase.rawQuery("SELECT value FROM hr_state WHERE key='identity'", null).use { if(it.moveToFirst()) it.getString(0) else null }
        check(prior == null || prior == identity) { "Use the original server enrollment for these HR captures" }
        if(prior == null) writableDatabase.execSQL("INSERT INTO hr_state(key,value) VALUES('identity',?)", arrayOf(identity))
    }
    @Synchronized fun manifest(id: String): JSONObject {
        check(sealed(id) && !deleted(id)) { "Finish HR capture before syncing" }
        val s = sessions(id).single()
        fun count(table: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE session_id=?", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) }
        return JSONObject().put("id", s.id).put("started", s.started).put("ended", s.ended)
            .put("status", s.status).put("zone_id", s.zoneId).put("sample_count", s.samples)
            .put("run_count", count("sample_runs")).put("event_count", count("events"))
    }
    @Synchronized fun archiveChunk(id: String, kind: String, offset: Int): JSONArray {
        require(kind in listOf("runs", "events") && offset >= 0 && offset % 128 == 0)
        val table = if(kind == "runs") "sample_runs" else "events"
        return readableDatabase.rawQuery("SELECT * FROM $table WHERE session_id=? ORDER BY id LIMIT 128 OFFSET ?", arrayOf(id, offset.toString())).use { c ->
            JSONArray().apply { while(c.moveToNext()) {
                fun number(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
                val value = JSONObject()
                if(kind == "runs") {
                    for(key in listOf("bpm", "received", "elapsed", "last_received", "last_elapsed", "sample_count", "epoch")) value.put(key, number(key))
                    value.put("timing", Base64.encodeToString(c.getBlob(c.getColumnIndexOrThrow("timing")), Base64.NO_WRAP))
                } else value.put("at", number("at")).put("state", c.getString(c.getColumnIndexOrThrow("state")))
                put(value)
            } }
        }
    }
    @Synchronized fun delete(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if(db.update("sessions", ContentValues().apply { put("deleted", 1); put("sealed", 1); put("prompt", "declined"); put("sync_status", "PENDING"); put("sync_error", ""); put("device", ""); put("started", 0); put("ended", 0); put("zone_id", "UTC"); put("status", "deleted") }, "id=? AND ended IS NOT NULL AND deleted=0", arrayOf(id)) != 0) {
                db.delete("sample_runs", "session_id=?", arrayOf(id)); db.delete("events", "session_id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }
}
