package com.chips.pantry

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

internal val pantryUnits = listOf("item", "g", "kg", "ml", "l")
internal fun decimal(text: String): BigDecimal = text.trim().toBigDecimal().also {
    require(it.abs() <= BigDecimal("1000000") && it.scale() <= 6) { "Use a quantity up to 1,000,000 with at most 6 decimal places" }
}
internal data class PantryItem(val key: String, val name: String, val barcode: String, val unit: String,
    val pack: String, val expiry: String, val quantity: BigDecimal, val low: BigDecimal) {
    fun line(delta: BigDecimal) = JSONObject().put("key", key).put("name", name).put("barcode", barcode)
        .put("unit", unit).put("package", pack).put("expiry", expiry).put("delta", delta.toPlainString()).put("low", low.toPlainString())
}
internal fun pantryItem(name: String, barcode: String, unit: String, pack: String, expiry: String, quantity: String, low: String): PantryItem {
    require(name.trim().isNotEmpty() && name.trim().length <= 200) { "Enter a product name (up to 200 characters)" }
    require(barcode.length <= 32 && barcode.all { it in '0'..'9' }) { "Use a numeric product barcode" }
    require(unit in pantryUnits && pack.length <= 100) { "Check the unit and package size" }
    if (expiry.isNotBlank()) LocalDate.parse(expiry.trim())
    val qty = decimal(quantity); val threshold = decimal(low)
    require(qty > BigDecimal.ZERO && threshold >= BigDecimal.ZERO) { "Quantity must be positive and low-stock level cannot be negative" }
    val identity = listOf(barcode.ifBlank { name.trim().lowercase(java.util.Locale.ROOT) }, unit, pack.trim(), expiry.trim()).joinToString("\u001f")
    return PantryItem(UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString(), name.trim(), barcode, unit, pack.trim(), expiry.trim(), qty, threshold)
}
internal fun receiptLines(text: String): List<PantryItem> {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    require(lines.size in 1..100) { "Enter 1–100 purchase lines" }
    return lines.mapIndexed { i, line ->
        val parts = line.split('|').map { it.trim() }
        require(parts.size == 3) { "Line ${i + 1}: use name | quantity | unit" }
        pantryItem(parts[0], "", parts[2], "", "", parts[1], "0")
    }
}
internal fun inventory(transactions: List<JSONObject>): List<PantryItem> {
    val result = linkedMapOf<String, PantryItem>()
    transactions.sortedWith(compareBy<JSONObject> { it.getLong("created_at") }.thenBy { it.getString("id") }).forEach { tx ->
        val lines = tx.getJSONArray("lines")
        for (i in 0 until lines.length()) {
            val line = lines.getJSONObject(i); val key = line.getString("key")
            result[key] = PantryItem(key, line.getString("name"), line.optString("barcode"), line.getString("unit"), line.optString("package"),
                line.optString("expiry"), (result[key]?.quantity ?: BigDecimal.ZERO) + decimal(line.getString("delta")), decimal(line.getString("low")))
        }
    }
    return result.values.filter { it.quantity.compareTo(BigDecimal.ZERO) != 0 }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
}

internal class PantryStore(context: Context, name: String = "pantry.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE movements(id TEXT PRIMARY KEY, payload TEXT NOT NULL, synced INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE state(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Missing pantry migration") }
    @Synchronized fun transactions(pending: Boolean = false): List<JSONObject> = readableDatabase.rawQuery(
        "SELECT payload FROM movements" + if (pending) " WHERE synced=0" else "", null).use { c -> buildList { while(c.moveToNext()) add(JSONObject(c.getString(0))) } }
    @Synchronized fun items() = inventory(transactions())
    @Synchronized fun state(key: String): String = readableDatabase.rawQuery("SELECT value FROM state WHERE key=?", arrayOf(key)).use { if(it.moveToFirst()) it.getString(0) else "" }
    @Synchronized fun state(key: String, value: String) { writableDatabase.insertWithOnConflict("state", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE) }
    @Synchronized fun bind(identity: String) {
        val previous = state("identity"); check(previous.isEmpty() || previous == identity) { "Use the original server enrollment for this pantry" }
        state("identity", identity)
    }
    @Synchronized fun rememberBarcode(code: String, name: String, pack: String) { state("barcode:$code", JSONObject().put("name", name).put("package", pack).toString()) }
    @Synchronized fun duplicate(fingerprint: String) = fingerprint.isNotEmpty() && transactions().any { it.optString("fingerprint") == fingerprint }
    @Synchronized fun add(items: List<PantryItem>, source: String, fingerprint: String = "", allowDuplicate: Boolean = false): JSONObject {
        require(items.isNotEmpty() && items.size <= 100)
        check(allowDuplicate || !duplicate(fingerprint)) { "This receipt was already added. Confirm another purchase explicitly." }
        val tx = JSONObject().put("id", UUID.randomUUID().toString()).put("created_at", System.currentTimeMillis()).put("source", source)
            .put("fingerprint", fingerprint).put("lines", JSONArray().apply { items.forEach { put(it.line(it.quantity)) } })
        merge(tx, false)
        return tx
    }
    @Synchronized fun adjust(item: PantryItem, amount: String, consume: Boolean) {
        val qty = decimal(amount)
        val current = items().firstOrNull { it.key == item.key } ?: error("This item is no longer in your pantry")
        require(qty >= BigDecimal.ZERO && (!consume || (qty > BigDecimal.ZERO && qty <= current.quantity))) { "Use a positive amount no greater than current stock" }
        val delta = if(consume) -qty else qty - current.quantity
        require(delta.abs() <= BigDecimal("1000000")) { "Adjustment is too large" }
        require(item.name.isNotBlank() && item.name.length <= 200 && item.low >= BigDecimal.ZERO && item.low <= BigDecimal("1000000"))
        val metadata = current.copy(name = item.name.trim(), low = item.low)
        val tx = JSONObject().put("id", UUID.randomUUID().toString()).put("created_at", System.currentTimeMillis())
            .put("source", if(consume) "consume" else "correction").put("fingerprint", "").put("lines", JSONArray().put(metadata.line(delta)))
        merge(tx, false)
    }
    @Synchronized fun merge(tx: JSONObject, synced: Boolean) {
        val id = tx.getString("id")
        // Immutable IDs: a changed server reply must never silently rewrite local stock.
        val previous = readableDatabase.rawQuery("SELECT payload FROM movements WHERE id=?", arrayOf(id)).use { if(it.moveToFirst()) JSONObject(it.getString(0)) else null }
        if (previous != null) {
            check(canonical(previous) == canonical(tx)) { "Conflicting pantry transaction" }
            if(synced) writableDatabase.execSQL("UPDATE movements SET synced=1 WHERE id=?", arrayOf(id))
        } else writableDatabase.insertOrThrow("movements", null, ContentValues().apply { put("id", id); put("payload", tx.toString()); put("synced", if(synced) 1 else 0) })
    }
}
// JSON key order and decimal string formatting differ between Python and Android.
internal fun canonical(tx: JSONObject): String {
    val lines = tx.getJSONArray("lines")
    return listOf(tx.getString("id"), tx.getLong("created_at").toString(), tx.getString("source"), tx.optString("fingerprint")).joinToString("\u001f") +
        (0 until lines.length()).joinToString("\u001e") { i -> val l = lines.getJSONObject(i)
            listOf("key", "name", "barcode", "unit", "package", "expiry").joinToString("\u001f") { l.optString(it) } +
                listOf("delta", "low").joinToString("\u001f") { decimal(l.getString(it)).stripTrailingZeros().toPlainString() }
        }
}
