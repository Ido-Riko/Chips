package com.chips.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.chips.dashboard.ActivityType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Native SQLite keeps the durable outbox and visible records in the same atomic row. */
class ActivityStore(context: Context, name: String = "chips.db") : SQLiteOpenHelper(context, name, null, 1) {
    private val mutableRecords = MutableStateFlow<List<ActivityRecord>>(emptyList())
    val records = mutableRecords.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE activities (
            id TEXT PRIMARY KEY, type TEXT NOT NULL, occurred_at INTEGER NOT NULL,
            zone_id TEXT NOT NULL, local_day TEXT NOT NULL, note TEXT NOT NULL,
            revision INTEGER NOT NULL, deleted INTEGER NOT NULL,
            sync_status TEXT NOT NULL, error TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX activity_day ON activities(local_day, deleted)")
        db.execSQL("CREATE TABLE sync_identity (slot INTEGER PRIMARY KEY CHECK(slot=1), identity TEXT NOT NULL)")
        // M3 will populate this; transient recordings are never stored here.
        db.execSQL("""CREATE TABLE voice_history (id TEXT PRIMARY KEY, occurred_at INTEGER NOT NULL,
            transcript TEXT NOT NULL, answer TEXT NOT NULL, sync_status TEXT NOT NULL)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No migration from $oldVersion to $newVersion; refusing to discard saved data")
    }

    @Synchronized fun load(): List<ActivityRecord> {
        val rows = mutableListOf<ActivityRecord>()
        readableDatabase.query("activities", null, null, null, null, null, "occurred_at DESC, id").use { c ->
            fun s(name: String) = c.getString(c.getColumnIndexOrThrow(name))
            while (c.moveToNext()) rows += ActivityRecord(
                s("id"), ActivityType.valueOf(s("type")), s("occurred_at").toLong(), s("zone_id"), s("local_day"),
                s("note"), s("revision").toLong(), s("deleted") == "1", s("sync_status"), s("error")
            )
        }
        mutableRecords.value = rows
        return rows
    }
    @Synchronized fun bind(identity: String) {
        val current = readableDatabase.rawQuery("SELECT identity FROM sync_identity WHERE slot=1", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        check(current == null || current == identity) { "This database is bound to a different service or device" }
        if (current == null) writableDatabase.execSQL("INSERT INTO sync_identity(slot, identity) VALUES(1, ?)", arrayOf(identity))
    }
    @Synchronized fun log(type: ActivityType, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): ActivityRecord {
        val record = ActivityRecord(UUID.randomUUID().toString(), type, now.toEpochMilli(), zone.id, ActivityRules.day(now, zone))
        put(record)
        return record
    }
    @Synchronized fun edit(id: String, type: ActivityType, note: String, day: String) {
        LocalDateValidation.validate(day)
        require(note.length <= 2000) { "Notes can contain up to 2000 characters" }
        val old = load().first { it.id == id && !it.deleted }
        put(old.copy(type = type, note = note, localDay = day, revision = old.revision + 1, syncStatus = "PENDING", error = ""))
    }
    @Synchronized fun delete(id: String) {
        val old = load().firstOrNull { it.id == id && !it.deleted } ?: return
        put(old.copy(deleted = true, note = "", revision = old.revision + 1, syncStatus = "PENDING", error = ""))
    }
    @Synchronized fun merge(remote: ActivityRecord) {
        val local = load().firstOrNull { it.id == remote.id }
        // Older acknowledgements must never overwrite an edit made during the request.
        if (local == null || remote.deleted || remote.revision >= local.revision) put(remote.copy(syncStatus = "SYNCED", error = ""))
    }
    @Synchronized fun failed(sent: ActivityRecord, message: String) {
        val local = load().firstOrNull { it.id == sent.id } ?: return
        if (local.revision == sent.revision) put(local.copy(syncStatus = "FAILED", error = message))
    }
    @Synchronized fun put(record: ActivityRecord) {
        val values = ContentValues().apply {
            put("id", record.id); put("type", record.type.name); put("occurred_at", record.occurredAt)
            put("zone_id", record.zoneId); put("local_day", record.localDay); put("note", record.note)
            put("revision", record.revision); put("deleted", if (record.deleted) 1 else 0)
            put("sync_status", record.syncStatus); put("error", record.error)
        }
        writableDatabase.insertWithOnConflict("activities", null, values, SQLiteDatabase.CONFLICT_REPLACE).also {
            check(it != -1L) { "Could not save activity" }
        }
        load()
    }
    companion object {
        @Volatile private var instance: ActivityStore? = null
        fun get(context: Context): ActivityStore = instance ?: synchronized(this) {
            instance ?: ActivityStore(context.applicationContext).also { instance = it }
        }
    }
}

private object LocalDateValidation {
    fun validate(value: String) { java.time.LocalDate.parse(value) }
}
