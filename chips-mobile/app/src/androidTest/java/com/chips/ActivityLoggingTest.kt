package com.chips

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.chips.data.ActivityStore
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class ActivityLoggingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
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
            compose.onNodeWithText("Undo").performClick()
            compose.waitUntil(5000) { store.load().count { !it.deleted } == count }
            assertEquals(count, store.load().count { !it.deleted })
        } finally { created?.let { store.delete(it) } }
    }
}
