package com.chips.beatsaber

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.chips.data.SyncCredentials
import com.chips.ui.theme.ChipsTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

class BeatSaberTest {
    @get:Rule val compose = createComposeRule()

    private fun point() = JSONObject().put("key", "board91").put("leaderboard_id", "board91")
        .put("song_name", "Test song").put("hash", "a".repeat(40)).put("mode", "Standard")
        .put("difficulty", "ExpertPlus").put("accuracy", 93.0).put("timestamp", 1794500000L)
        .put("stars", 8.0).put("pp", 250.0).put("score_id", JSONObject.NULL)
        .put("modifiers", "").put("tech_rating", JSONObject.NULL)

    private fun snapshot() = JSONObject().put("version", 1).put("player_id", "player1")
        .put("fetched_at", 1794500100L).put("profile", JSONObject().put("name", "Test player").put("pp", 1000))
        .put("graph", JSONArray().put(point())).put("scores", JSONArray().put(point()))
        .put("history", JSONArray()).put("total_scores", 1).put("coverage", "Retained scores, not every attempt.")

    @Test fun cacheSurvivesReopenIsolatesEnrollmentAndDeletesImportsOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "beat-test-${UUID.randomUUID()}.db"
        var cache = BeatSaberCache(context, name)
        val a = enrollmentKey(SyncCredentials.Config("https://a.test/api/v1", "token-a"))
        val b = enrollmentKey(SyncCredentials.Config("https://a.test/api/v1", "token-b"))
        try {
            cache.write(a, JSONObject().put("player_id", "player1").put("snapshot", snapshot()))
            cache.write(a, JSONObject().put("map_id", "abc"), "map-a")
            cache.close(); cache = BeatSaberCache(context, name)
            assertEquals(93.0, BeatSnapshot.decode(cache.read(a)!!.getJSONObject("snapshot")).scores.single().accuracy, 0.0)
            assertNull(cache.read(b))
            cache.write(b, JSONObject().put("player_id", "player2"))
            cache.clear(a)
            assertNull(cache.read(a)); assertNull(cache.read(a, "map-a"))
            assertEquals("player2", cache.read(b)!!.getString("player_id"))
        } finally { cache.close(); context.deleteDatabase(name) }
    }

    @Test fun malformedSnapshotsAreRejectedAndMissingMetricsRemainMissing() {
        val good = BeatSnapshot.decode(snapshot())
        assertNull(good.graph.single().metric("tech_rating"))
        assertEquals(93.0, good.graph.single().accuracy, 0.0)
        val bad = snapshot().put("graph", JSONArray().put(point().put("accuracy", 9300)))
        assertThrows(IllegalArgumentException::class.java) { BeatSnapshot.decode(bad) }
    }

    @Test fun scoreSelectionShowsDetailsWhenMapEnrichmentFails() {
        val selected = mutableStateOf(BeatSaberState(playerId = "player1", snapshot = BeatSnapshot.decode(snapshot())))
        compose.setContent {
            ChipsTheme {
                BeatSaberScreen(selected.value, {}, {}, {}, {}, {}, { score ->
                    selected.value = selected.value.copy(mapKey = score.key, mapError = "Map temporarily unavailable")
                })
            }
        }
        compose.onNodeWithTag("beat-saber-screen").performScrollToNode(hasTestTag("beat-score-board91"))
        compose.onNodeWithTag("beat-score-board91").performClick()
        compose.onNodeWithText("Map temporarily unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open BeatLeader").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry map details").performScrollTo().assertIsDisplayed()
    }

    @Test fun httpReadsBoundedJsonAndKeepsEnrollmentAtTheChipsService() = runBlocking {
        ServerSocket(0).use { server ->
            var auth: String? = null
            val worker = thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    var line = reader.readLine()
                    while (!line.isNullOrEmpty()) {
                        if (line.startsWith("Authorization:")) auth = line
                        line = reader.readLine()
                    }
                    val body = "{\"player_id\":null,\"snapshot\":null}"
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                }
            }
            val response = BeatSaberHttp(SyncCredentials.Config("http://127.0.0.1:${server.localPort}/api/v1", "isolated-test-token")).request("GET")
            worker.join(3000)
            assertTrue(response.isNull("snapshot"))
            assertEquals("Authorization: Bearer isolated-test-token", auth)
        }
    }
}
