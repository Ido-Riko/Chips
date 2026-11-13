package com.chips.beatsaber

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit

interface BeatSaberSource {
    suspend fun snapshot(playerId: String): JSONObject
    suspend fun map(hash: String, mode: String, difficulty: String): JSONObject
}

/** Public read-only APIs: independent of CHIPS enrollment, VPN, and backend availability. */
internal class BeatLeaderApi(
    private val leader: HttpUrl = "https://api.beatleader.com/".toHttpUrl(),
    private val saver: HttpUrl = "https://api.beatsaver.com/".toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
) : BeatSaberSource {
    private class Busy(val source: String) : IOException()
    private suspend fun get(base: HttpUrl, segments: List<String>, query: Map<String, String> = emptyMap()): Any {
        val url = base.newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val source = if (base == leader) "BeatLeader" else "BeatSaver"
        repeat(2) { attempt ->
            try { return read(url, source) }
            catch (e: Busy) { if (attempt == 1) throw IOException("$source is busy. Retry shortly."); delay(750) }
        }
        error("Unreachable")
    }
    private suspend fun read(url: HttpUrl, sourceName: String): Any = suspendCancellableCoroutine { continuation ->
        // No credential header, cookie jar or shared service client is used here.
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "CHIPS/0.1 personal-dashboard").get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(IOException(beatConnectionMessage(e, sourceName), e)))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (it.code == 429 || it.code >= 500) throw Busy(sourceName)
                        if (it.code == 404) throw IOException("$sourceName could not find that player or map. Check the player ID.")
                        if (!it.isSuccessful) throw IOException("$sourceName could not provide this data (HTTP ${it.code}). Retry later.")
                        val body = it.body?.source() ?: throw IOException("$sourceName returned an empty response.")
                        body.request(12 * 1024 * 1024L + 1)
                        if (body.buffer.size > 12 * 1024 * 1024L) throw IOException("$sourceName returned too much data.")
                        JSONTokener(body.readUtf8()).nextValue().also { value ->
                            if (value !is JSONObject && value !is JSONArray) throw IOException("$sourceName returned invalid data.")
                        }
                    }
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }
    override suspend fun snapshot(playerId: String): JSONObject = withTimeout(120_000) {
        require(Regex("[A-Za-z0-9_-]{1,25}").matches(playerId))
        try {
            val profile = get(leader, listOf("player", playerId)) as JSONObject
            val canonical = profile.getString("id")
            require(Regex("[A-Za-z0-9_-]{1,25}").matches(canonical))
            val graph = get(leader, listOf("player", canonical, "accgraph"), mapOf("no_unranked_stars" to "true")) as JSONArray
            val history = get(leader, listOf("player", canonical, "history"), mapOf("count" to "30")) as JSONArray
            require(graph.length() <= 20000 && history.length() <= 1000)
            val scores = linkedMapOf<String, JSONObject>()
            var total = 0
            for (page in 1..3) {
                val body = get(leader, listOf("player", canonical, "scores"), mapOf("sortBy" to "date", "order" to "desc",
                    "count" to "100", "page" to page.toString(), "includeIO" to "true", "leaderboardContext" to "general")) as JSONObject
                total = body.getJSONObject("metadata").getInt("total")
                require(total >= 0)
                val data = body.optJSONArray("data") ?: JSONArray()
                require(data.length() <= 100)
                for (i in 0 until data.length()) {
                    val point = BeatSaberParser.score(data.getJSONObject(i))
                    scores[point.getString("key")] = point
                }
                if (data.length() == 0 || page * 100 >= total) break
            }
            val points = linkedMapOf<String, JSONObject>()
            for (i in 0 until graph.length()) {
                val point = BeatSaberParser.graph(graph.getJSONObject(i))
                points[point.getString("key")] = point
            }
            val days = (0 until minOf(history.length(), 30)).map { i ->
                val h = history.getJSONObject(i)
                JSONObject().put("timestamp", BeatSaberParser.epoch(h, "timestamp"))
                    .nullable("pp", h.number("pp")).nullable("rank", h.number("rank")).nullable("country_rank", h.number("countryRank"))
            }
            JSONObject().put("version", 1).put("fetched_at", System.currentTimeMillis() / 1000)
                .put("player_id", canonical).put("profile", JSONObject().put("name", profile.optString("name").take(300))
                    .nullable("pp", profile.number("pp")).nullable("rank", profile.number("rank"))
                    .put("country", profile.optString("country").take(4)).nullable("country_rank", profile.number("countryRank")))
                .put("graph", JSONArray(points.values.sortedBy { it.getLong("timestamp") }))
                .put("scores", JSONArray(scores.values.sortedByDescending { it.getLong("timestamp") }))
                .put("history", JSONArray(days.sortedBy { it.getLong("timestamp") })).put("total_scores", total)
                .put("coverage", "Retained general-context scores; not every attempt. Latest 300 scores at most. Ranked graph is a separate portfolio.")
                .also { BeatSnapshot.decode(it) }
        } catch (e: CancellationException) { throw e }
        catch (e: IOException) { throw e }
        catch (e: Exception) { throw IOException("BeatLeader returned unexpected data. Your previous import is unchanged.", e) }
    }
    override suspend fun map(hash: String, mode: String, difficulty: String): JSONObject {
        require(Regex("[a-fA-F0-9]{40}").matches(hash))
        val body = get(saver, listOf("maps", "hash", hash)) as? JSONObject ?: throw IOException("BeatSaver returned unexpected map data.")
        return BeatSaberParser.map(body, hash, mode, difficulty)
    }
}

private fun JSONObject.nullable(key: String, value: Any?) = put(key, value ?: JSONObject.NULL)

internal object BeatSaberParser {
    fun epoch(row: JSONObject, key: String): Long {
        val value = row.get(key).toString().toLong()
        require(value in 1..253402300799L)
        return value
    }
    fun graph(row: JSONObject): JSONObject {
        val accuracy = requireNotNull(row.number("acc"))
        val id = row.getString("leaderboardId").take(80)
        require(accuracy in 0.0..1.0 && id.isNotBlank())
        return JSONObject().put("key", id).put("score_id", JSONObject.NULL).put("leaderboard_id", id)
            .put("song_name", row.getString("songName").take(300)).put("hash", row.getString("hash").lowercase())
            .put("mode", row.getString("mode").take(80)).put("difficulty", row.getString("diff").take(40))
            .put("accuracy", accuracy * 100).put("timestamp", epoch(row, "timeset"))
            .nullable("stars", row.number("stars")).nullable("pp", row.number("pp"))
            .nullable("pass_rating", row.number("passRating")).nullable("acc_rating", row.number("accRating"))
            .nullable("tech_rating", row.number("techRating")).put("modifiers", row.optString("modifiers").take(80))
    }
    fun score(row: JSONObject): JSONObject {
        val board = row.getJSONObject("leaderboard")
        val song = board.getJSONObject("song"); val diff = board.getJSONObject("difficulty")
        val id = row.getLong("id"); require(id > 0)
        val point = graph(JSONObject().put("acc", row.get("accuracy")).put("leaderboardId", row.getString("leaderboardId"))
            .put("songName", song.getString("name")).put("hash", song.getString("hash"))
            .put("mode", diff.getString("modeName")).put("diff", diff.getString("difficultyName"))
            .put("timeset", row.get("timepost")).nullable("pp", row.number("pp")).put("modifiers", row.optString("modifiers")))
            .put("key", id.toString()).put("score_id", id)
        val modified = point.getString("modifiers").isNotEmpty()
        for ((key, field) in mapOf("stars" to "stars", "pass_rating" to "passRating", "acc_rating" to "accRating", "tech_rating" to "techRating")) {
            point.nullable(key, if (modified) null else diff.number(field))
        }
        point.nullable("duration", diff.number("duration")).nullable("bpm", song.number("bpm")?.takeIf { it > 0 }).nullable("nps", diff.number("nps"))
        for ((key, field) in mapOf("bad_cuts" to "badCuts", "missed_notes" to "missedNotes", "bomb_cuts" to "bombCuts",
            "walls_hit" to "wallsHit", "pauses" to "pauses", "max_combo" to "maxCombo", "left_cut" to "accLeft", "right_cut" to "accRight")) {
            point.nullable(key, row.number(field))
        }
        val improvement = row.optJSONObject("scoreImprovement")
        return point.put("full_combo", row.optBoolean("fullCombo"))
            .nullable("accuracy_gain", improvement?.number("accuracy")?.times(100))
            .nullable("pp_gain", improvement?.number("pp"))
    }
    fun map(body: JSONObject, hash: String, mode: String, difficulty: String): JSONObject {
        val versions = body.optJSONArray("versions") ?: JSONArray()
        val version = (0 until versions.length()).map { versions.getJSONObject(it) }
            .firstOrNull { it.optString("hash").equals(hash, true) } ?: throw IOException("That exact map version is unavailable on BeatSaver.")
        val diffs = version.optJSONArray("diffs") ?: JSONArray()
        val diff = (0 until diffs.length()).map { diffs.getJSONObject(it) }
            .firstOrNull { it.optString("characteristic") == mode && it.optString("difficulty") == difficulty }
            ?: throw IOException("That map difficulty is unavailable on BeatSaver.")
        val metadata = body.optJSONObject("metadata") ?: JSONObject()
        val out = JSONObject().put("map_id", body.getString("id").take(40)).put("hash", hash.lowercase())
            .put("mode", mode).put("difficulty", difficulty).nullable("bpm", metadata.number("bpm")?.takeIf { it > 0 })
            .nullable("duration", metadata.number("duration")).put("tags", body.optJSONArray("tags") ?: JSONArray())
        for (key in listOf("njs", "nps", "notes", "bombs", "obstacles")) out.nullable(key, diff.number(key))
        return out
    }
}
