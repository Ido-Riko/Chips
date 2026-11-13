package com.chips

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.chips.data.ActivityStore
import com.chips.dashboard.ActivityType
import com.chips.ui.FeatureRoute
import com.chips.ui.featureIntent
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class ActivityLoggingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = ActivityStore.get(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun featureShortcutsOpenActualScreensWithoutChangingCompletions() {
        val seed = store.log(ActivityType.JAPANESE)
        val before = store.load()
        try {
            for(prefix in listOf("quick-log", "rhythm", "streak")) {
                for((type, screen) in listOf(ActivityType.JAPANESE to "japanese-screen", ActivityType.DRAWING to "drawing-screen", ActivityType.BEAT_SABER to "beat-saber-screen")) {
                    val tag = "$prefix-${type.name}"
                    if(prefix != "quick-log") compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag(tag))
                    compose.onNodeWithTag(tag).performClick()
                    compose.onNodeWithTag(screen).assertIsDisplayed()
                    compose.onNodeWithText("Quick log").assertDoesNotExist()
                    assertEquals(before,store.load())
                    compose.onNodeWithTag("nav-DASHBOARD").performClick()
                }
            }
        } finally { store.delete(seed.id) }
    }

    @Test fun workoutLoggingIsExplicitSurvivesRecreationAndSupportsUndo() {
        val before = store.load().map { it.id }.toSet()
        try {
            compose.onNodeWithTag("quick-log-WORKOUT").performClick()
            assertTrue(store.load().none { it.id !in before })
            compose.onNodeWithTag("save-workout").performClick()
            compose.waitUntil(5000) { store.load().any { !it.deleted && it.id !in before } }
            val created = store.load().first { !it.deleted && it.id !in before }.id
            compose.onNodeWithTag("nav-HISTORY").performClick()
            compose.onNodeWithTag("record-$created").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("record-$created").assertIsDisplayed()
            compose.onNodeWithTag("nav-DASHBOARD").performClick()
            compose.onNodeWithTag("quick-log-WORKOUT").performClick()
            compose.onNodeWithTag("save-workout").performClick()
            compose.waitUntil(5000) { store.load().count { !it.deleted && it.id !in before } == 2 }
            compose.onNodeWithText("Undo", useUnmergedTree = true).performClick()
            compose.waitUntil(5000) { store.load().count { !it.deleted && it.id !in before } == 1 }
        } finally { store.load().filter { it.id !in before && !it.deleted }.forEach { store.delete(it.id) } }
    }

    @Test fun beatSaberLogsOnlyOnExplicitCompletedSessionAction() {
        val before = store.load().map { it.id }.toSet()
        try {
            compose.onNodeWithTag("quick-log-BEAT_SABER").performClick()
            compose.onNodeWithTag("beat-saber-screen").assertIsDisplayed()
            assertTrue(store.load().none { it.id !in before })
            compose.onNodeWithTag("beat-log-session").performClick()
            compose.waitUntil(5000) { store.load().any { !it.deleted && it.id !in before && it.type == ActivityType.BEAT_SABER } }
        } finally { store.load().filter { it.id !in before && !it.deleted }.forEach { store.delete(it.id) } }
    }

    @Test fun assistantShortcutsWorkWithoutMicrophoneAndReuseExistingActivity() {
        val before = store.load()
        for((route, screen) in listOf(FeatureRoute.JAPANESE to "japanese-screen", FeatureRoute.DRAWING to "drawing-screen", FeatureRoute.BEAT_SABER to "beat-saber-screen", FeatureRoute.PANTRY to "pantry-screen")) {
            compose.onNodeWithTag("dashboard").performScrollToNode(hasText("Ask CHIPS"))
            compose.onNodeWithText("Ask CHIPS").performClick()
            compose.onNodeWithTag("assistant-feature-${route.name}").performScrollTo().performClick()
            compose.onNodeWithTag(screen).assertIsDisplayed()
            compose.onNodeWithContentDescription("Close assistant").assertDoesNotExist()
            assertEquals(before,store.load())
            compose.onNodeWithTag("nav-DASHBOARD").performClick()
        }
        compose.activityRule.scenario.onActivity { it.startActivity(featureIntent(it,FeatureRoute.DRAWING)) }
        compose.onNodeWithTag("drawing-screen").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("drawing-screen").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.startActivity(featureIntent(it,FeatureRoute.WORKOUT)) }
        compose.onNodeWithTag("save-workout").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(before,store.load())
    }
}
