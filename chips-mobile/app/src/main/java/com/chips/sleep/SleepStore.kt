package com.chips.sleep

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

data class SleepEntry(val id: String, val origin: String, val start: Long, val end: Long,
                      val offset: Int?, val stages: String, val startOffset: Int? = offset) {
    val minutes get() = (end - start) / 60000
    val wakeDate get() = Instant.ofEpochMilli(end).atZone(offset?.let { java.time.ZoneOffset.ofTotalSeconds(it) }
        ?: ZoneId.systemDefault()).toLocalDate().toString()
    fun stageMinutes(): Map<String, Long> {
        val labels = mapOf(0 to "Unknown", 1 to "Awake", 2 to "Sleeping", 3 to "Out of bed", 4 to "Light", 5 to "Deep", 6 to "REM", 7 to "Awake in bed")
        val array = JSONArray(stages)
        return (0 until array.length()).map { array.getJSONObject(it) }.groupBy { labels[it.getInt("stage")] ?: "Unknown" }
            .mapValues { (_, rows) -> rows.sumOf { (minOf(end, it.getLong("end")) - maxOf(start, it.getLong("start"))).coerceAtLeast(0) } / 60000 }
    }
}

/** An atomic snapshot of the readable window, retaining older imports outside that window. */
class SleepStore(context: Context, name: String = "sleep.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sleep(id TEXT NOT NULL, origin TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, offset INTEGER, start_offset INTEGER, stages TEXT NOT NULL, PRIMARY KEY(origin,id))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported sleep schema")
    fun entries(): List<SleepEntry> = readableDatabase.rawQuery("SELECT id,origin,start,end,offset,stages,start_offset FROM sleep ORDER BY end DESC", null).use { c -> buildList {
        while(c.moveToNext()) add(SleepEntry(c.getString(0),c.getString(1),c.getLong(2),c.getLong(3),if(c.isNull(4)) null else c.getInt(4),c.getString(5),if(c.isNull(6)) null else c.getInt(6)))
    } }
    fun replaceWindow(start: Long, end: Long, rows: List<SleepEntry>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("sleep", "start>=? AND start<?", arrayOf(start.toString(), end.toString()))
            rows.forEach { row ->
                require(row.end > row.start)
                check(db.insertWithOnConflict("sleep", null, ContentValues().apply {
                    put("id",row.id); put("origin",row.origin); put("start",row.start); put("end",row.end)
                    put("offset",row.offset); put("start_offset",row.startOffset); put("stages",row.stages)
                }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun clear() { writableDatabase.delete("sleep", null, null) }
}
