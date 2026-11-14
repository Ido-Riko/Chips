package com.chips.beatsaber

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.concurrent.thread

class BeatSaberTest {
    @get:Rule val compose = createComposeRule()

    private fun awaitChart() {
        compose.waitUntil(5000) { compose.onAllNodesWithTag("beat-scatter").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun point() = JSONObject().put("key", "board91").put("leaderboard_id", "board91")
        .put("song_name", "Test song").put("hash", "a".repeat(40)).put("mode", "Standard")
        .put("difficulty", "ExpertPlus").put("accuracy", 93.0).put("timestamp", 1794500000L)
        .put("stars", 8.0).put("pass_rating", 7.5).put("acc_rating", 8.5).put("pp", 250.0).put("score_id", JSONObject.NULL)
        .put("modifiers", "").put("tech_rating", JSONObject.NULL)

    private fun snapshot() = JSONObject().put("version", 1).put("player_id", "player1")
        .put("fetched_at", 1794500100L).put("profile", JSONObject().put("name", "Test player").put("pp", 1000))
        .put("graph", JSONArray().put(point())).put("scores", JSONArray().put(point()))
        .put("history", JSONArray()).put("total_scores", 1).put("coverage", "Retained scores, not every attempt.")

    @Test fun chartShowsNamedNumberedAxesAndKeepsPointsSelectable() {
        val scores = listOf(point(), point().put("key", "middle").put("stars", 10).put("accuracy", 94),
            point().put("key", "end").put("stars", 12).put("accuracy", 95))
        val data = BeatSnapshot.decode(snapshot().put("graph", JSONArray(scores))).graph
        var selected: String? = null
        compose.setContent { ChipsTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(Modifier.padding(20.dp)) { ScoreScatter(data, "stars", "accuracy", null) { selected = it.key } } } } }
        compose.onNodeWithTag("beat-axis-x").assertIsDisplayed()
        compose.onNodeWithTag("beat-axis-y").assertIsDisplayed()
        awaitChart()
        compose.onNodeWithTag("beat-scatter").assertContentDescriptionEquals(
            "X axis: Stars. Ticks: ${fmt(8.0, 1)}, ${fmt(10.0, 1)}, ${fmt(12.0, 1)}. " +
                "Y axis: Accuracy %. Ticks: ${listOf(93.0, 93.5, 94.0, 94.5, 95.0).joinToString { fmt(it, 2) }}. Score details are also available in the list.")
        // The top/right endpoint is inset by half its measured tick label, within the hit radius.
        awaitChart()
        compose.onNodeWithTag("beat-scatter").performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 16.dp.toPx(), 12.dp.toPx())) }
        compose.waitUntil(5000) { selected != null }
        compose.runOnIdle { assertEquals("end", selected) }
    }

    @Test fun fullPortfolioCanDrawZoomPanResetAndSelectAnExactScore() {
        val data = (0 until 20000).map { i ->
            BeatScore(i.toString(), "board$i", "Song $i", "hash$i", "Standard", "Expert", 90.0,
                1700000000L + i, JSONObject().put("stars", i.toDouble()))
        }
        val selected = mutableStateOf<String?>(null)
        compose.setContent { ChipsTheme { Column { ScoreScatter(data, "stars", "accuracy", selected.value) { selected.value = it.key } } } }
        awaitChart()
        compose.onNodeWithTag("beat-zoom-in").performClick()
        compose.onNodeWithTag("beat-pan-3").performClick()
        compose.onNodeWithTag("beat-zoom-reset").performClick()
        compose.onNodeWithTag("beat-scatter").performTouchInput { click(center) }
        compose.waitUntil(5000) { selected.value != null }
        // All scores remain candidates; a middle hit must select a middle score, not an endpoint/sample.
        assertTrue(selected.value!!.toInt() in 9000..11000)
    }

    @Test fun chartZoomButtonsPinchPanAndResetKeepAxesInSync() {
        val points = listOf(point().put("stars", 8).put("accuracy", 92),
            point().put("key", "end").put("stars", 12).put("accuracy", 96))
        val data = BeatSnapshot.decode(snapshot().put("graph", JSONArray(points))).graph
        compose.setContent { ChipsTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(Modifier.padding(20.dp)) { ScoreScatter(data, "stars", "accuracy", null) {} } } } }
        awaitChart()
        compose.onNodeWithTag("beat-zoom-in").performClick()
        awaitChart()
        compose.onNodeWithTag("beat-scatter").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom ${fmt(2.0, 1)}×"))
        awaitChart()
        compose.onNodeWithTag("beat-scatter").assert(hasContentDescription("X axis: Stars. Ticks: ${fmt(9.0, 2)}, ${fmt(10.0, 2)}, ${fmt(11.0, 2)}", substring = true))
        compose.onNodeWithTag("beat-pan-3").performClick()
        awaitChart()
        compose.onNodeWithTag("beat-scatter").assert(hasContentDescription("X axis: Stars. Ticks: ${fmt(9.5, 2)}, ${fmt(10.5, 2)}, ${fmt(11.5, 2)}", substring = true))
        compose.onNodeWithTag("beat-zoom-reset").performClick()
        awaitChart()
        compose.onNodeWithTag("beat-scatter").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom ${fmt(1.0, 1)}×"))
        awaitChart()
        compose.onNodeWithTag("beat-scatter").performTouchInput {
            down(0, center.copy(x = center.x - 20.dp.toPx()))
            down(1, center.copy(x = center.x + 20.dp.toPx()))
            moveTo(0, center.copy(x = center.x - 60.dp.toPx()))
            moveTo(1, center.copy(x = center.x + 60.dp.toPx()))
            up(0); up(1)
        }
        awaitChart()
        compose.onNodeWithTag("beat-scatter").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom ${fmt(1.0, 1)}×").not())
        compose.onNodeWithTag("beat-zoom-reset").performClick()
        compose.onNodeWithTag("beat-zoom-out").assertIsNotEnabled()
    }

    @Test fun explorerKeepsSetupAndDiagnosticsOutOfTheMainView() {
        compose.setContent { ChipsTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BeatSaberScreen(BeatSaberState(playerId = "player1", snapshot = BeatSnapshot.decode(snapshot())), {}, {}, {}, {}, {}, {})
        } } }
        compose.onNodeWithTag("beat-player-id").assertDoesNotExist()
        compose.onNodeWithText("Retained scores, not every attempt.").assertDoesNotExist()
        compose.onNodeWithText("X · Stars").assertDoesNotExist()
        compose.onNodeWithTag("beat-zoom-status").assertDoesNotExist()
        compose.onNodeWithTag("beat-axis-x").performClick()
        compose.onNodeWithText("BPM", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("beat-axis-x").assert(hasText("BPM ▾"))
        compose.onNodeWithTag("beat-profile").performClick()
        compose.onNodeWithTag("beat-player-id").assertExists()
    }

    @Test fun firstPointTapPreviewsAndTemporarilyPromotesSongWithoutOpeningDetails() {
        val graph = JSONArray().put(point().put("timestamp", 1794400000L))
            .put(point().put("key", "newer").put("song_name", "Newer song").put("timestamp", 1794500000L))
        val data = BeatSnapshot.decode(snapshot().put("graph", graph))
        var requests = 0
        compose.setContent { ChipsTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BeatSaberScreen(BeatSaberState(playerId = "player1", snapshot = data), {}, {}, {}, {}, {}, { requests++ })
        } } }
        // Both points share coordinates; the first imported point wins this overlap.
        compose.onNodeWithTag("beat-saber-screen").performScrollToNode(hasTestTag("beat-scatter"))
        awaitChart()
        compose.onNodeWithTag("beat-scatter").performTouchInput { click(center) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("beat-point-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("beat-point-preview").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("beat-point-preview").assert(hasText("Test song"))
        compose.onNodeWithTag("beat-point-preview").assert(hasText("${fmt(8.0)} ★ · ExpertPlus"))
        compose.onNodeWithTag("beat-detail-stars").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithTag("beat-saber-screen").performScrollToNode(hasTestTag("beat-score-newer"))
        val promoted = compose.onNodeWithTag("beat-score-board91").fetchSemanticsNode().boundsInRoot.top
        val other = compose.onNodeWithTag("beat-score-newer").fetchSemanticsNode().boundsInRoot.top
        assertTrue(promoted < other)
        compose.onNodeWithTag("beat-sort-menu").performScrollTo().performClick()
        compose.onNodeWithTag("beat-sort-stars").performClick()
        compose.onNodeWithTag("beat-point-preview").assertDoesNotExist()
        compose.onNodeWithTag("beat-saber-screen").performScrollToNode(hasTestTag("beat-scatter"))
        awaitChart()
        compose.onNodeWithTag("beat-scatter").performTouchInput { click(center) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("beat-point-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("beat-point-preview").performScrollTo().performClick()
        compose.onNodeWithTag("beat-detail-stars").assertExists()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun bpmSortReusesSavedSongDataAndKeepsMissingValuesLast() {
        val recent = point().put("bpm", 180)
        val imported = BeatSnapshot.decode(snapshot().put("scores", JSONArray().put(recent)))
        assertEquals(180.0, imported.graph.single().metric("bpm")!!, 0.0)
        val slow = imported.graph.single().copy(key = "slow", fields = point().put("bpm", 90))
        val unknown = imported.graph.single().copy(key = "unknown", fields = point().put("bpm", JSONObject.NULL))
        val points = listOf(unknown, imported.graph.single(), slow)
        assertEquals(listOf("slow", "board91", "unknown"), sortBeatScores(points, "bpm", true).map { it.key })
        assertEquals(listOf("board91", "slow", "unknown"), sortBeatScores(points, "bpm", false).map { it.key })
    }

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
        compose.onNodeWithTag("beat-detail-stars").performScrollTo().assertTextEquals("Stars · ${fmt(8.0)} ★")
        compose.onNodeWithTag("beat-detail-pass").performScrollTo().assertTextEquals("Pass rating · ${fmt(7.5)}")
        compose.onNodeWithTag("beat-detail-acc").performScrollTo().assertTextEquals("Acc rating · ${fmt(8.5)}")
        compose.onNodeWithTag("beat-detail-tech").performScrollTo().assertTextEquals("Tech rating · —")
        compose.onNodeWithText("Map temporarily unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open BeatLeader").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry map details").performScrollTo().assertIsDisplayed()
    }

    @Test fun publicApiReadsMapDirectlyWithoutSendingAnEnrollmentToken() = runBlocking {
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
                    val body = JSONObject().put("id", "test-map").put("metadata", JSONObject().put("bpm", 150))
                        .put("versions", JSONArray().put(JSONObject().put("hash", "a".repeat(40))
                            .put("diffs", JSONArray().put(JSONObject().put("characteristic", "Standard").put("difficulty", "ExpertPlus").put("nps", 5)))))
                        .toString()
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                }
            }
            val response = BeatLeaderApi(saver = "http://127.0.0.1:${server.localPort}/".toHttpUrl())
                .map("a".repeat(40), "Standard", "ExpertPlus")
            worker.join(3000)
            assertEquals("test-map", response.getString("map_id"))
            assertEquals(5.0, response.getDouble("nps"), 0.0)
            assertNull(auth)
        }
    }
}
