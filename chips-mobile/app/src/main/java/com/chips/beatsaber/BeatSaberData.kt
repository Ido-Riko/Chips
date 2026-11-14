package com.chips.beatsaber

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chips.data.SyncCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.security.MessageDigest

internal fun JSONObject.number(key: String): Double? =
    if (isNull(key)) null else (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }

data class BeatScore(
    val key: String, val leaderboardId: String, val song: String, val hash: String,
    val mode: String, val difficulty: String, val accuracy: Double, val timestamp: Long,
    val fields: JSONObject
) {
    fun metric(key: String) = if (key == "accuracy") accuracy else fields.number(key)
    val scoreId get() = fields.number("score_id")?.toLong()
    val modifiers get() = fields.optString("modifiers")
}

data class BeatSnapshot(val data: JSONObject, val graph: List<BeatScore>, val scores: List<BeatScore>) {
    val fetchedAt get() = data.getLong("fetched_at")
    val profile get() = data.getJSONObject("profile")
    companion object {
        fun decode(data: JSONObject): BeatSnapshot {
            require(data.getInt("version") == 1) { "Update CHIPS to read this score data." }
            fun points(name: String): List<BeatScore> {
                val array = data.getJSONArray(name)
                require(array.length() <= 20000)
                return (0 until array.length()).map { i ->
                    val p = array.getJSONObject(i)
                    val accuracy = requireNotNull(p.number("accuracy"))
                    require(accuracy in 0.0..100.0 && p.getLong("timestamp") > 0)
                    BeatScore(p.getString("key"), p.getString("leaderboard_id"), p.getString("song_name"),
                        p.getString("hash"), p.getString("mode"), p.getString("difficulty"),
                        accuracy, p.getLong("timestamp"), p)
                }.distinctBy { it.key }
            }
            data.getJSONObject("profile")
            require(data.getLong("fetched_at") > 0)
            data.getString("player_id")
            val scores = points("scores")
            // Song BPM is hash-specific and shared across difficulties. Reuse the import,
            // including older saved snapshots, without a request per ranked graph point.
            val bpms = scores.filter { it.metric("bpm") != null }.associate { it.hash to it.metric("bpm")!! }
            val graph = points("graph").map { p ->
                if (p.metric("bpm") != null || bpms[p.hash] == null) p
                else p.copy(fields = JSONObject(p.fields.toString()).put("bpm", bpms[p.hash]))
            }
            return BeatSnapshot(data, graph, scores)
        }
    }
}

/** Independent local cache; legacy enrollment namespaces remain available for one-time migration. */
class BeatSaberCache(context: Context, name: String = "beat_saber.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cache (namespace TEXT NOT NULL, entry TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(namespace, entry))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported score cache version") }
    @Synchronized fun read(namespace: String, entry: String = "state"): JSONObject? =
        readableDatabase.rawQuery("SELECT payload FROM cache WHERE namespace=? AND entry=?", arrayOf(namespace, entry)).use {
            if (it.moveToFirst()) JSONObject(it.getString(0)) else null
        }
    @Synchronized fun write(namespace: String, value: JSONObject, entry: String = "state") {
        val values = ContentValues().apply { put("namespace", namespace); put("entry", entry); put("payload", value.toString()) }
        check(writableDatabase.insertWithOnConflict("cache", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }
    @Synchronized fun clear(namespace: String) { writableDatabase.delete("cache", "namespace=?", arrayOf(namespace)) }
}

data class BeatSaberState(
    val playerId: String = "", val snapshot: BeatSnapshot? = null, val busy: Boolean = false,
    val error: String? = null, val mapKey: String? = null, val map: JSONObject? = null,
    val mapBusy: Boolean = false, val mapError: String? = null
)

internal fun enrollmentKey(config: SyncCredentials.Config): String = MessageDigest.getInstance("SHA-256")
    .digest((config.endpoint + "\n" + config.token).toByteArray()).joinToString("") { "%02x".format(it) }

internal const val LOCAL_BEAT_CACHE = "local-beat-saber-v1"

class BeatSaberViewModel(
    application: android.app.Application,
    private val source: BeatSaberSource = BeatLeaderApi(),
    private val cache: BeatSaberCache = BeatSaberCache(application)
) : ViewModel() {
    private val context = application
    private val mutableState = MutableStateFlow(BeatSaberState())
    val state = mutableState.asStateFlow()
    private var task: Job? = null
    private var mapTask: Job? = null

    private suspend fun applyState(body: JSONObject, busy: Boolean = false) {
        val snapshot = withContext(Dispatchers.Default) {
            body.optJSONObject("snapshot")?.let { BeatSnapshot.decode(it) }
        }
        withContext(Dispatchers.IO) { cache.write(LOCAL_BEAT_CACHE, body) }
        mutableState.value = BeatSaberState(body.optString("player_id").takeUnless { it == "null" } ?: "", snapshot, busy)
    }
    private fun runAction(action: suspend () -> Unit) {
        if (task?.isActive == true) return
        mapTask?.cancel()
        mutableState.update { it.copy(busy = true, error = null, mapBusy = false) }
        task = viewModelScope.launch {
            try { action() }
            catch (e: TimeoutCancellationException) { mutableState.update { it.copy(error = "BeatLeader took too long. Check your internet connection and retry.") } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(error = e.message ?: "Could not read saved scores.") } }
            finally { if (currentCoroutineContext().isActive) mutableState.update { it.copy(busy = false) } }
        }
    }
    /** Opening the app or explorer only reads local storage; no service is needed. */
    fun load() {
        if (task?.isActive == true) return
        mapTask?.cancel()
        mutableState.value = BeatSaberState(busy = true)
        task = viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    cache.read(LOCAL_BEAT_CACHE) ?: run {
                        // One-time migration only; enrollment never gates normal operation.
                        val legacy = runCatching { SyncCredentials(context).read() }.getOrNull()
                            ?.let { cache.read(enrollmentKey(it)) }
                        legacy ?: JSONObject().put("player_id", JSONObject.NULL).put("snapshot", JSONObject.NULL)
                    }
                }
                applyState(saved)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(error = "Could not read the local score cache. Import your profile again to retry.") } }
            finally { if (currentCoroutineContext().isActive) mutableState.update { it.copy(busy = false) } }
        }
    }
    fun connect(playerId: String) {
        val id = playerId.trim()
        if (!Regex("[A-Za-z0-9_-]{1,25}").matches(id)) {
            mutableState.update { it.copy(error = "Enter your BeatLeader player ID, rather than a profile URL.") }; return
        }
        runAction {
            if (id != mutableState.value.playerId) {
                withContext(Dispatchers.IO) { cache.clear(LOCAL_BEAT_CACHE) }
                applyState(JSONObject().put("player_id", id).put("snapshot", JSONObject.NULL), busy = true)
            }
            importProfile(id)
        }
    }
    private suspend fun importProfile(id: String) {
        val previous = mutableState.value.snapshot
        if (previous != null && System.currentTimeMillis() / 1000 - previous.fetchedAt in 0..59) return
        val snapshot = source.snapshot(id)
        withContext(Dispatchers.Default) {
            val knownBpms = (previous?.graph.orEmpty() + previous?.scores.orEmpty())
                .mapNotNull { p -> p.metric("bpm")?.let { p.hash to it } }.toMap()
            for (name in listOf("graph", "scores")) {
                val rows = snapshot.getJSONArray(name)
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val known = knownBpms[row.optString("hash")]
                    if (row.number("bpm") == null && known != null) row.put("bpm", known)
                }
            }
        }
        applyState(JSONObject().put("player_id", id).put("snapshot", snapshot))
    }
    fun refresh() = runAction {
        val id = mutableState.value.playerId
        check(id.isNotEmpty()) { "Save your BeatLeader player ID first." }
        importProfile(id)
    }
    fun remove() = runAction {
        withContext(Dispatchers.IO) { cache.clear(LOCAL_BEAT_CACHE) }
        // An empty marker prevents the legacy enrollment cache from reappearing.
        applyState(JSONObject().put("player_id", JSONObject.NULL).put("snapshot", JSONObject.NULL))
    }
    fun map(score: BeatScore) {
        mapTask?.cancel()
        mutableState.update { it.copy(mapKey = score.key, map = null, mapBusy = true, mapError = null) }
        mapTask = viewModelScope.launch {
            try {
                require(Regex("[a-fA-F0-9]{40}").matches(score.hash)) { "This score has no BeatSaver map hash." }
                val entry = "${score.hash}/${score.mode}/${score.difficulty}"
                val old = withContext(Dispatchers.IO) { cache.read(LOCAL_BEAT_CACHE, entry) }
                if (old != null) { rememberMapBpm(score.hash, old); mutableState.update { it.copy(map = old) }; return@launch }
                val body = source.map(score.hash, score.mode, score.difficulty)
                withContext(Dispatchers.IO) { cache.write(LOCAL_BEAT_CACHE, body, entry) }
                rememberMapBpm(score.hash, body)
                mutableState.update { it.copy(map = body) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(mapError = e.message ?: "Map enrichment failed. Score retained.") } }
            finally { if (currentCoroutineContext().isActive && mutableState.value.mapKey == score.key) mutableState.update { it.copy(mapBusy = false) } }
        }
    }
    private suspend fun rememberMapBpm(hash: String, map: JSONObject) {
        val bpm = map.number("bpm")?.takeIf { it > 0 } ?: return
        val current = mutableState.value
        val snapshot = current.snapshot ?: return
        val updated = withContext(Dispatchers.Default) {
            val data = JSONObject(snapshot.data.toString())
            var changed = false
            for (name in listOf("graph", "scores")) {
                val rows = data.getJSONArray(name)
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    if (row.optString("hash").equals(hash, true) && row.number("bpm") == null) { row.put("bpm", bpm); changed = true }
                }
            }
            if (!changed) null else BeatSnapshot.decode(data)
        } ?: return
        withContext(Dispatchers.IO) { cache.write(LOCAL_BEAT_CACHE, JSONObject().put("player_id", current.playerId).put("snapshot", updated.data)) }
        mutableState.update { it.copy(snapshot = updated) }
    }
    override fun onCleared() { task?.cancel(); mapTask?.cancel(); cache.close() }
}
