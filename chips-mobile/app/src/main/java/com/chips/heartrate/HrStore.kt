package com.chips.heartrate

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class HrSession(val id: String, val started: Long, val ended: Long?, val status: String,
    val samples: Int, val average: Double?, val peak: Int?, val lastSample: Long?)

/** Independent local store. Equal consecutive BPM share a run, retaining all reception times. */
class HrStore(context: Context, name: String = "heart_rate.db") : SQLiteOpenHelper(context, name, null, 2) {
    private fun createRuns(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sample_runs(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, bpm INTEGER NOT NULL CHECK(bpm BETWEEN 1 AND 65535), received INTEGER NOT NULL, elapsed INTEGER NOT NULL, last_received INTEGER NOT NULL, last_elapsed INTEGER NOT NULL, sample_count INTEGER NOT NULL CHECK(sample_count BETWEEN 1 AND 256), timing BLOB NOT NULL, epoch INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX sample_runs_session ON sample_runs(session_id,id)")
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, started INTEGER NOT NULL, ended INTEGER, status TEXT NOT NULL, device TEXT NOT NULL)")
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
    }

    @Synchronized fun begin(device: String, now: Long = System.currentTimeMillis()): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", id); put("started", now); put("status", "capturing"); put("device", device)
        })
        return id
    }
    @Synchronized fun resume(id: String, device: String, now: Long = System.currentTimeMillis()): String {
        val updated = writableDatabase.update("sessions", ContentValues().apply { putNull("ended"); put("status", "capturing") },
            "id=? AND status IN ('capturing','interrupted')", arrayOf(id))
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
    @Synchronized fun end(id: String, status: String = "stopped", now: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL("UPDATE sessions SET ended=?,status=? WHERE id=? AND ended IS NULL", arrayOf<Any>(now, status, id))
    }
    @Synchronized fun recoverInterrupted() {
        writableDatabase.execSQL("UPDATE sessions SET ended=COALESCE((SELECT last_received FROM sample_runs WHERE session_id=sessions.id ORDER BY id DESC LIMIT 1),started),status='interrupted' WHERE ended IS NULL")
    }
    @Synchronized fun sessions(): List<HrSession> = readableDatabase.rawQuery("""
        SELECT s.id,s.started,s.ended,s.status,COALESCE(SUM(p.sample_count),0),SUM(p.bpm * 1.0 * p.sample_count)/SUM(p.sample_count),MAX(p.bpm),
        (SELECT last_received FROM sample_runs WHERE session_id=s.id ORDER BY id DESC LIMIT 1)
        FROM sessions s LEFT JOIN sample_runs p ON p.session_id=s.id GROUP BY s.id ORDER BY s.started DESC LIMIT 100
    """.trimIndent(), null).use { c -> buildList {
        while(c.moveToNext()) add(HrSession(c.getString(0), c.getLong(1), if(c.isNull(2)) null else c.getLong(2),
            c.getString(3), c.getInt(4), if(c.isNull(5)) null else c.getDouble(5),
            if(c.isNull(6)) null else c.getInt(6), if(c.isNull(7)) null else c.getLong(7)))
    } }
    @Synchronized fun delete(id: String) { writableDatabase.delete("sessions", "id=? AND ended IS NOT NULL", arrayOf(id)) }
}
