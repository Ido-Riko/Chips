package com.chips.practice

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.chips.data.ActivityRecord
import com.chips.data.ActivityStore
import com.chips.dashboard.ActivityType
import org.json.JSONArray
import java.time.*
import java.util.UUID

internal object PracticeTables {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS kanji_reviews (kanji TEXT PRIMARY KEY, step INTEGER NOT NULL, due_at INTEGER NOT NULL, reviewed_day TEXT NOT NULL, introduced_day TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS drawings (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL,
            goal TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '', uploaded INTEGER NOT NULL DEFAULT 0,
            queued INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, target TEXT NOT NULL DEFAULT '',
            feedback TEXT NOT NULL DEFAULT '', model TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '')""")
    }
}

data class KanjiCard(val kanji: String, val meanings: String, val kun: String, val on: String, val strokes: Int)
data class Review(val kanji: String, val step: Int, val due: Long, val day: String, val introducedDay: String)
data class DrawingEntry(val id: String, val createdAt: Long, val goal: String, val notes: String,
    val uploaded: Boolean, val queued: Boolean, val deleted: Boolean, val target: String,
    val feedback: String, val model: String, val error: String)

enum class Recall { AGAIN, GOOD, EASY }
object ReviewSchedule {
    private val days = listOf(1L, 3L, 7L, 14L, 30L, 60L)
    fun next(previousStep: Int?, recall: Recall, now: Instant, zone: ZoneId): Pair<Int, Long> {
        if (recall == Recall.AGAIN) return 0 to now.plusSeconds(600).toEpochMilli()
        val step = ((previousStep ?: -1) + if (recall == Recall.EASY) 2 else 1).coerceIn(0, days.lastIndex)
        return step to now.atZone(zone).toLocalDate().plusDays(days[step]).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

/** Study and activity completion share one SQLite transaction. No server is required. */
class PracticeStore(private val activity: ActivityStore) {
    fun deck(context: Context): List<KanjiCard> {
        val rows = JSONArray(context.assets.open("kanji.json").bufferedReader().use { it.readText() })
        fun text(array: JSONArray) = (0 until array.length()).joinToString(" · ") { array.getString(it) }
        return (0 until rows.length()).map { rows.getJSONObject(it).let { row -> KanjiCard(row.getString("kanji"),
            text(row.getJSONArray("meanings")), text(row.getJSONArray("kun_readings")), text(row.getJSONArray("on_readings")), row.getInt("stroke_count")) } }
    }
    fun reviews(): List<Review> = synchronized(activity) {
        activity.readableDatabase.rawQuery("SELECT kanji,step,due_at,reviewed_day,introduced_day FROM kanji_reviews", null).use { c ->
            buildList { while (c.moveToNext()) add(Review(c.getString(0), c.getInt(1), c.getLong(2), c.getString(3), c.getString(4))) }
        }
    }
    fun queue(deck: List<KanjiCard>, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<KanjiCard> {
        val reviews = reviews().associateBy { it.kanji }
        val day = now.atZone(zone).toLocalDate().toString()
        val due = deck.filter { (reviews[it.kanji]?.due ?: Long.MAX_VALUE) <= now.toEpochMilli() }.sortedBy { reviews[it.kanji]!!.due }
        val learnedToday = reviews.values.count { it.introducedDay == day }
        return due + deck.filter { it.kanji !in reviews }.take((3 - learnedToday).coerceAtLeast(0))
    }
    fun rate(kanji: String, recall: Recall, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) = synchronized(activity) {
        val old = reviews().firstOrNull { it.kanji == kanji }
        check(old == null || old.due <= now.toEpochMilli()) { "This review is already saved." }
        val (step, due) = ReviewSchedule.next(old?.step, recall, now, zone)
        val db = activity.writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("kanji", kanji); put("step", step); put("due_at", due); put("reviewed_day", now.atZone(zone).toLocalDate().toString()); put("introduced_day", old?.introducedDay ?: now.atZone(zone).toLocalDate().toString()) }
            db.insertWithOnConflict("kanji_reviews", null, values, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            completion("japanese:" + now.atZone(zone).toLocalDate(), ActivityType.JAPANESE, now, zone)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        activity.load()
    }
    private fun completion(key: String, type: ActivityType, now: Instant, zone: ZoneId) {
        val id = UUID.nameUUIDFromBytes(key.toByteArray()).toString()
        val exists = activity.readableDatabase.rawQuery("SELECT 1 FROM activities WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
        if (!exists) {
            val r = ActivityRecord(id, type, now.toEpochMilli(), zone.id, now.atZone(zone).toLocalDate().toString())
            // Write the linked completion in this transaction; refresh observers after commit.
            val v = ContentValues().apply {
                put("id", r.id); put("type", r.type.name); put("occurred_at", r.occurredAt); put("zone_id", r.zoneId); put("local_day", r.localDay)
                put("note", ""); put("revision", 1); put("deleted", 0); put("sync_status", "PENDING"); put("error", "")
            }
            activity.writableDatabase.insertOrThrow("activities", null, v)
        }
    }
    fun resetReviews() = synchronized(activity) { activity.writableDatabase.delete("kanji_reviews", null, null); Unit }
    fun drawings(includeDeleted: Boolean = false): List<DrawingEntry> = synchronized(activity) {
        activity.readableDatabase.rawQuery("SELECT id,created_at,goal,notes,uploaded,queued,deleted,target,feedback,model,error FROM drawings" +
            (if (includeDeleted) "" else " WHERE deleted=0") + " ORDER BY created_at DESC", null).use { c ->
            buildList { while(c.moveToNext()) add(DrawingEntry(c.getString(0),c.getLong(1),c.getString(2),c.getString(3),c.getInt(4)==1,c.getInt(5)==1,c.getInt(6)==1,c.getString(7),c.getString(8),c.getString(9),c.getString(10))) }
        }
    }
    fun addDrawing(id: String, goal: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) = synchronized(activity) {
        require(goal.length <= 2000)
        val db = activity.writableDatabase
        db.beginTransaction()
        try {
            val v = ContentValues().apply { put("id", id); put("created_at", now.toEpochMilli()); put("goal", goal) }
            db.insertOrThrow("drawings", null, v)
            completion("drawing:$id", ActivityType.DRAWING, now, zone)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        activity.load()
    }
    fun updateDrawing(id: String, values: ContentValues) = synchronized(activity) {
        check(activity.writableDatabase.update("drawings", values, "id=?", arrayOf(id)) == 1)
    }
    fun updateActiveDrawing(id: String, values: ContentValues): Boolean = synchronized(activity) {
        activity.writableDatabase.update("drawings", values, "id=? AND deleted=0", arrayOf(id)) == 1
    }
    fun deleteDrawing(id: String) = synchronized(activity) {
        updateDrawing(id, ContentValues().apply { put("deleted", 1); put("queued", 1); put("goal", ""); put("notes", ""); put("feedback", ""); put("error", "") })
    }
    fun finishDelete(id: String) = synchronized(activity) { activity.writableDatabase.delete("drawings", "id=? AND deleted=1", arrayOf(id)); Unit }
}
