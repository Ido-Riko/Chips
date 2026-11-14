package com.chips.beatsaber

import android.os.Handler
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BeatPreparationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun blockedPreparationLeavesUiResponsiveAndCannotReplaceNewerResult() {
        val input = mutableStateOf(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            compose.setContent {
                val key = input.value
                val result = rememberBeatPreparation(key) {
                    assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                    if (key == 1) {
                        entered.countDown()
                        try { check(release.await(15, TimeUnit.SECONDS)) } finally { finished.countDown() }
                    }
                    key
                }
                Text(result?.toString() ?: "Preparing")
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val heartbeat = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
            assertTrue("Preparation blocked the UI thread", heartbeat.await(750, TimeUnit.MILLISECONDS))
            compose.runOnIdle { input.value = 2 }
            compose.waitUntil(5000) { compose.onAllNodesWithText("2").fetchSemanticsNodes().isNotEmpty() }
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("2").assertExists()
            compose.onNodeWithText("1").assertDoesNotExist()
        } finally { release.countDown() }
    }

    @Test fun fullSizePortfolioPreservesSearchSortMissingMetricsBoundsAndSelectionOrder() {
        val points = (0 until 20000).map { i ->
            BeatScore(i.toString().padStart(5, '0'), "board$i", "Song $i", "hash$i", "Standard", "Expert",
                90.0, 1700000000L + i, JSONObject().put("stars", i.toDouble()).apply {
                    if (i % 2 == 0) put("bpm", 120.0)
                })
        }
        val songs = prepareBeatSongs(points, "", "bpm", false)
        assertEquals(20000, songs.points.size)
        assertEquals("00000", songs.ordered.first().key)
        assertEquals("00002", songs.ordered[1].key)
        assertEquals("00001", songs.ordered[10000].key)
        val ascending = prepareBeatSongs(points, "", "bpm", true)
        assertEquals(songs.ordered, ascending.ordered)
        val selectedIndex = songs.orderedIndex.getValue("19999")
        val promoted = promoteBeatScore(songs.ordered, selectedIndex)
        assertEquals("19999", promoted.first().key)
        assertEquals(songs.ordered.filterNot { it.key == "19999" }, promoted.drop(1))
        assertEquals(11, prepareBeatSongs(points, "Song 1999", "timestamp", true).points.size)
        val chart = prepareBeatScatter(points, "stars", "accuracy")
        assertEquals(20000, chart.plotted.size)
        assertEquals(0.0 to 19999.0, chart.xBounds)
        assertEquals(85.5 to 94.5, chart.yBounds)
        assertEquals(10000, prepareBeatScatter(points, "bpm", "accuracy").plotted.size)
        val date = prepareBeatScatter(points.take(1), "timestamp", "accuracy")
        assertEquals((1700000000.0 - 3600) to (1700000000.0 + 3600), date.xBounds)
    }
}
