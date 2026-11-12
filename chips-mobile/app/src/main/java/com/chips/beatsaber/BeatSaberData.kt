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
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
            return BeatSnapshot(data, points("graph"), points("scores"))
        }
    }
}

/** Independent cache leaves activity/conversation schemas untouched. Namespaces isolate enrollments. */
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

internal class BeatSaberHttp(private val config: SyncCredentials.Config) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(100, TimeUnit.SECONDS).callTimeout(115, TimeUnit.SECONDS).build()
    suspend fun request(method: String, path: String = "", body: JSONObject? = null, query: Map<String, String> = emptyMap()): JSONObject {
        val url = (config.endpoint + "/beat-saber" + path).toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val bytes = (body?.toString() ?: "").toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${config.token}")
            .header("Accept", "application/json").method(method, if (method in listOf("GET", "DELETE")) null else bytes).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(IOException("Could not reach the score service. Your saved data is still available.")))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val source = it.body?.source() ?: error("Empty score response")
                            source.request(12 * 1024 * 1024L + 1)
                            require(source.buffer.size <= 12 * 1024 * 1024L) { "Score response is too large." }
                            val text = source.readUtf8()
                            if (!it.isSuccessful) {
                                val message = when (it.code) {
                                    401, 403 -> "Device access was rejected. Check enrollment in Settings."
                                    404 -> "Score service or player not found. The backend may need its Beat Saber update."
                                    else -> runCatching { JSONObject(text).optString("detail").take(250) }.getOrNull()
                                        ?.takeIf { m -> m.isNotBlank() } ?: "Score request failed. Retry later."
                                }
                                throw IOException(message)
                            }
                            JSONObject(text)
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }
}

class BeatSaberViewModel(application: android.app.Application) : ViewModel() {
    private val context = application
    private val cache = BeatSaberCache(context.applicationContext)
    private val mutableState = MutableStateFlow(BeatSaberState())
    val state = mutableState.asStateFlow()
    private var task: Job? = null
    private var mapTask: Job? = null

    private fun config(): SyncCredentials.Config = requireNotNull(SyncCredentials(context).read()) {
        "Set up device enrollment in Settings to connect BeatLeader."
    }
    private suspend fun applyState(body: JSONObject, key: String, busy: Boolean = false) {
        val snapshot = body.optJSONObject("snapshot")?.let { BeatSnapshot.decode(it) }
        withContext(Dispatchers.IO) { cache.write(key, body) }
        mutableState.value = BeatSaberState(body.optString("player_id").takeUnless { it == "null" } ?: "", snapshot, busy)
    }
    private fun runAction(action: suspend (SyncCredentials.Config, String) -> Unit) {
        if (task?.isActive == true) return
        mapTask?.cancel()
        mutableState.update { it.copy(busy = true, error = null, mapBusy = false) }
        task = viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { config() }
                check(SyncCredentials(context).connected()) { "Reconnect synchronization in Settings first." }
                action(saved, enrollmentKey(saved))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(error = e.message ?: "Could not read saved scores.") } }
            finally { if (currentCoroutineContext().isActive) mutableState.update { it.copy(busy = false) } }
        }
    }
    fun load() {
        task?.cancel(); mapTask?.cancel()
        mutableState.value = BeatSaberState(busy = true)
        task = viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { config() }
                val key = enrollmentKey(saved)
                withContext(Dispatchers.IO) { cache.read(key) }?.let { applyState(it, key, busy = true) }
                if (SyncCredentials(context).connected()) {
                    val body = BeatSaberHttp(saved).request("GET")
                    check(config() == saved) { "Enrollment changed. Reopen Beat Saber." }
                    applyState(body, key)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(error = e.message ?: "Could not load scores.") } }
            finally { if (currentCoroutineContext().isActive) mutableState.update { it.copy(busy = false) } }
        }
    }
    fun connect(playerId: String) {
        if (!Regex("[A-Za-z0-9_-]{1,25}").matches(playerId.trim())) {
            mutableState.update { it.copy(error = "Enter your BeatLeader player ID, rather than a profile URL.") }; return
        }
        runAction { saved, key ->
            val http = BeatSaberHttp(saved)
            val connected = http.request("PUT", "/connection", JSONObject().put("player_id", playerId.trim()))
            check(config() == saved) { "Enrollment changed. Reopen Beat Saber." }
            withContext(Dispatchers.IO) { cache.clear(key) }; applyState(connected, key, busy = true)
            val body = http.request("POST", "/refresh")
            check(config() == saved) { "Enrollment changed. Reopen Beat Saber." }
            applyState(body, key)
        }
    }
    fun refresh() = runAction { saved, key ->
        val body = BeatSaberHttp(saved).request("POST", "/refresh")
        check(config() == saved) { "Enrollment changed. Reopen Beat Saber." }
        applyState(body, key)
    }
    fun remove() = runAction { saved, key ->
        val body = BeatSaberHttp(saved).request("DELETE")
        check(config() == saved) { "Enrollment changed. Reopen Beat Saber." }
        withContext(Dispatchers.IO) { cache.clear(key) }; applyState(body, key)
    }
    fun map(score: BeatScore) {
        mapTask?.cancel()
        mutableState.update { it.copy(mapKey = score.key, map = null, mapBusy = true, mapError = null) }
        mapTask = viewModelScope.launch {
            try {
                require(Regex("[a-fA-F0-9]{40}").matches(score.hash)) { "This score has no BeatSaver map hash." }
                val saved = withContext(Dispatchers.IO) { config() }
                val key = enrollmentKey(saved)
                val entry = "${score.hash}/${score.mode}/${score.difficulty}"
                val old = withContext(Dispatchers.IO) { cache.read(key, entry) }
                if (old != null) { mutableState.update { it.copy(map = old) }; return@launch }
                check(SyncCredentials(context).connected()) { "Map details are unavailable offline until first opened." }
                val body = BeatSaberHttp(saved).request("GET", "/maps/${score.hash}", query = mapOf("mode" to score.mode, "difficulty" to score.difficulty))
                check(config() == saved) { "Enrollment changed. Reopen Beat Saber." }
                withContext(Dispatchers.IO) { cache.write(key, body, entry) }
                mutableState.update { it.copy(map = body) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(mapError = e.message ?: "Map enrichment failed. Score retained.") } }
            finally { if (currentCoroutineContext().isActive && mutableState.value.mapKey == score.key) mutableState.update { it.copy(mapBusy = false) } }
        }
    }
    override fun onCleared() { task?.cancel(); mapTask?.cancel(); cache.close() }
}
