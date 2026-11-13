package com.chips

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.chips.data.ActivityStore
import com.chips.dashboard.ActivityType
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class ActivityLoggingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun rhythmTilesToggleOffAndClearRepeatedSessionsWithoutAffectingOtherActivities() {
        val store = ActivityStore.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val before = store.load().map { it.id }.toSet()
        try {
            ActivityType.entries.forEach { type ->
                val tag = "rhythm-${type.name}"
                compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertIsOff().performClick()
                compose.waitUntil(5000) { store.load().any { !it.deleted && it.id !in before && it.type == type } }
                compose.onNodeWithTag(tag).assertIsOn().performClick()
                compose.waitUntil(5000) { store.load().none { !it.deleted && it.id !in before } }
                compose.onNodeWithTag(tag).assertIsOff()
            }
            repeat(2) {
                compose.onNodeWithTag("quick-log-WORKOUT").performClick()
                val count = it + 1
                compose.waitUntil(5000) { store.load().count { !it.deleted && it.type == ActivityType.WORKOUT } == count }
            }
            compose.onNodeWithTag("quick-log-DRAWING").performClick()
            compose.waitUntil(5000) { store.load().any { !it.deleted && it.type == ActivityType.DRAWING } }
            compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("rhythm-WORKOUT"))
            compose.onNodeWithTag("rhythm-WORKOUT").assertIsOn().performClick()
            compose.waitUntil(5000) { store.load().none { !it.deleted && it.type == ActivityType.WORKOUT } }
            compose.onNodeWithTag("rhythm-WORKOUT").assertIsOff()
            compose.onNodeWithTag("rhythm-DRAWING").assertIsOn()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("rhythm-WORKOUT"))
            compose.onNodeWithTag("rhythm-WORKOUT").assertIsOff()
            compose.onNodeWithTag("rhythm-DRAWING").assertIsOn()
        } finally { store.load().filter { it.id !in before && !it.deleted }.forEach { store.delete(it.id) } }
    }
    @Test fun workoutUpdatesHistorySurvivesActivityRecreationAndSupportsUndo() {
        val store = ActivityStore.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val before = store.load().filter { !it.deleted }.map { it.id }.toSet()
        var created: String? = null
        try {
            compose.onNodeWithTag("quick-log-WORKOUT").performClick()
            compose.waitUntil(5000) { store.load().any { !it.deleted && it.id !in before } }
            created = store.load().first { !it.deleted && it.id !in before }.id
            compose.onNodeWithTag("nav-HISTORY").performClick()
            compose.onNodeWithTag("record-$created").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("record-$created").assertIsDisplayed()
            compose.onNodeWithTag("nav-DASHBOARD").performClick()
            val count = store.load().count { !it.deleted }
            compose.onNodeWithTag("quick-log-DRAWING").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("Drawing saved on this phone", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Undo", useUnmergedTree = true).performClick()
            compose.waitUntil(5000) { store.load().count { !it.deleted } == count }
            assertEquals(count, store.load().count { !it.deleted })
        } finally { store.load().filter { !it.deleted && it.id !in before }.forEach { store.delete(it.id) } }
    }
}
