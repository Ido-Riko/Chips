package com.chips.beatsaber

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class BeatSaberLocalTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun point() = JSONObject().put("acc", 0.93).put("leaderboardId", "board91").put("songName", "Song")
        .put("hash", "a".repeat(40)).put("mode", "Standard").put("diff", "ExpertPlus")
        .put("timeset", 1794500000).put("stars", 8).put("pp", 250)
    private fun snapshot() = JSONObject().put("version", 1).put("player_id", "player1")
        .put("fetched_at", System.currentTimeMillis() / 1000 - 61).put("profile", JSONObject().put("name", "Player"))
        .put("graph", JSONArray().put(BeatSaberParser.graph(point()))).put("scores", JSONArray())
        .put("history", JSONArray()).put("total_scores", 0).put("coverage", "Retained scores")

    @Test fun importRestartRefreshFailureAndRemovalWorkWithoutAnyService() = runBlocking {
        val name = "beat-local-${UUID.randomUUID()}.db"
        var calls = 0
        var offline = false
        val source = object : BeatSaberSource {
            override suspend fun snapshot(playerId: String): JSONObject {
                calls++
                if (offline) throw IOException("No internet")
                return snapshot()
            }
            override suspend fun map(hash: String, mode: String, difficulty: String) = JSONObject()
        }
        var store = ViewModelStore()
        lateinit var vm: BeatSaberViewModel
        fun create() { instrumentation.runOnMainSync {
            vm = BeatSaberViewModel(context.applicationContext as Application, source, BeatSaberCache(context, name))
            store.put("beat", vm)
        } }
        suspend fun finished() = withTimeout(5000) { vm.state.first { !it.busy } }
        try {
            create()
            instrumentation.runOnMainSync { vm.connect("player1") }
            assertEquals(1, finished().snapshot!!.graph.size)
            assertEquals(1, calls)
            instrumentation.runOnMainSync { store.clear() }
            store = ViewModelStore(); offline = true; create()
            instrumentation.runOnMainSync { vm.load() }
            assertEquals(1, finished().snapshot!!.graph.size)
            assertEquals(1, calls) // Reopen uses SQLite, not the network or CHIPS backend.
            instrumentation.runOnMainSync { vm.refresh() }
            val failed = finished()
            assertEquals("No internet", failed.error)
            assertEquals(1, failed.snapshot!!.graph.size)
            assertEquals(2, calls)
            instrumentation.runOnMainSync { vm.remove() }
            assertNull(finished().snapshot)
            instrumentation.runOnMainSync { vm.load() }
            assertEquals("", finished().playerId)
            assertEquals(2, calls) // Removing imports also works offline.
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            context.deleteDatabase(name)
        }
    }

    @Test fun directAdapterPaginatesDeduplicatesAndNeverSendsCredentials() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            val path = request.url.encodedPath
            paths += path
            val body: Any = when {
                path.endsWith("/accgraph") -> JSONArray().put(point()).put(point())
                path.endsWith("/history") -> JSONArray().put(JSONObject().put("timestamp", 1794500000).put("pp", 1000))
                path.endsWith("/scores") -> {
                    val page = request.url.queryParameter("page")!!.toInt()
                    val score = JSONObject().put("id", page).put("leaderboardId", "board91").put("accuracy", 0.93)
                        .put("timepost", 1794500000).put("accLeft", 107).put("accRight", 109).put("modifiers", "FS")
                        .put("leaderboard", JSONObject().put("song", JSONObject().put("name", "Song").put("hash", "a".repeat(40)))
                            .put("difficulty", JSONObject().put("modeName", "Standard").put("difficultyName", "ExpertPlus").put("stars", 8)))
                    JSONObject().put("metadata", JSONObject().put("total", 500)).put("data", JSONArray().put(score).put(score))
                }
                else -> JSONObject().put("id", "player1").put("name", "Player").put("pp", 1000)
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val imported = BeatSnapshot.decode(BeatLeaderApi(client = client).snapshot("player1"))
        assertEquals(1, imported.graph.size)
        assertEquals(3, imported.scores.size)
        assertNull(imported.scores.first().metric("stars"))
        assertEquals(107.0, imported.scores.first().metric("left_cut")!!, 0.0)
        assertEquals(3, paths.count { it.endsWith("/scores") })
    }

    @Test fun parserMatchesExactMapVersionAndRejectsMalformedAccuracy() {
        val hash = "a".repeat(40)
        fun version(hash: String, nps: Int) = JSONObject().put("hash", hash).put("diffs", JSONArray().put(
            JSONObject().put("characteristic", "Standard").put("difficulty", "ExpertPlus").put("nps", nps)))
        val body = JSONObject().put("id", "abc").put("metadata", JSONObject()).put("versions", JSONArray()
            .put(version("b".repeat(40), 999)).put(version(hash.uppercase(), 5)))
        assertEquals(5.0, BeatSaberParser.map(body, hash, "Standard", "ExpertPlus").getDouble("nps"), 0.0)
        assertThrows(IllegalArgumentException::class.java) { BeatSaberParser.graph(point().put("acc", 93)) }
        assertThrows(IOException::class.java) { BeatSaberParser.map(body, hash, "Standard", "Easy") }
    }
}
