package com.chips

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun practiceScreensOpenFromDashboardAndTodayClosesThem() {
        compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("open-japanese"))
        compose.onNodeWithTag("open-japanese").performClick()
        compose.onNodeWithTag("japanese-screen").assertIsDisplayed()
        compose.onNodeWithText("Quick log").assertDoesNotExist()
        compose.onNodeWithTag("nav-DASHBOARD").performClick()
        compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("open-drawing"))
        compose.onNodeWithTag("open-drawing").performClick()
        compose.onNodeWithTag("drawing-screen").assertIsDisplayed()
        compose.onNodeWithTag("nav-DASHBOARD").performClick()
        compose.onNodeWithText("Your day, at a glance").assertIsDisplayed()
    }

    @Test fun lockscreenSecuritySwitchPersistsAcrossRecreation() {
        val access = com.chips.assistant.AssistantAccessSettings(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
        val before = access.allowLocked()
        try {
            access.setAllowLocked(true)
            compose.onNodeWithTag("nav-SETTINGS").performClick()
            compose.onNodeWithContentDescription("Allow assistant while locked").performScrollTo().assertIsOn().performClick()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithContentDescription("Allow assistant while locked").performScrollTo().assertIsOff()
        } finally { access.setAllowLocked(before) }
    }

    @Test
    fun todayTabClosesScoreExplorerEvenWhenAlreadySelected() {
        compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("open-beat-saber"))
        compose.onNodeWithTag("open-beat-saber").performClick()
        compose.onNodeWithTag("beat-saber-screen").assertIsDisplayed()
        compose.onNodeWithTag("nav-DASHBOARD").performClick()
        compose.onNodeWithTag("beat-saber-screen").assertDoesNotExist()
        compose.onNodeWithText("Your day, at a glance").assertIsDisplayed()
        compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("open-beat-saber"))
        compose.onNodeWithTag("open-beat-saber").performClick()
        compose.onNodeWithTag("nav-HISTORY").performClick()
        compose.onNodeWithTag("nav-DASHBOARD").performClick()
        compose.onNodeWithTag("beat-saber-screen").assertDoesNotExist()
    }

    @Test
    fun dashboardIsUsableWithoutAssistantSetup() {
        compose.onNodeWithText("Your day, at a glance").assertIsDisplayed()
        compose.onNodeWithText("Quick log").assertDoesNotExist()
        compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("activity-WORKOUT"))
        compose.onNodeWithText("Activities").assertIsDisplayed()
        compose.onNodeWithTag("nav-HISTORY").performClick()
        compose.onNodeWithText("No saved entries yet").assertIsDisplayed()
        compose.onNodeWithTag("nav-SETTINGS").performClick()
        compose.onNodeWithText("Dashboard preview").assertIsDisplayed()
    }

    @Test
    fun previewDataRequiresOptInAndNeverCreatesHistory() {
        compose.onNodeWithText("Preview · fictional data · nothing is saved").assertDoesNotExist()
        compose.onNodeWithTag("nav-SETTINGS").performClick()
        compose.onNodeWithContentDescription("Show preview data").performClick()
        compose.onNodeWithTag("nav-DASHBOARD").performClick()
        compose.onNodeWithText("Preview · fictional data · nothing is saved").assertIsDisplayed()
        compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("activity-WORKOUT"))
        compose.onNodeWithTag("activity-WORKOUT").performClick()
        compose.onNodeWithTag("nav-HISTORY").performClick()
        compose.onNodeWithText("No saved entries yet").assertIsDisplayed()
    }
}
