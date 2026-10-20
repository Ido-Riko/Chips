package com.chips

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun dashboardIsUsableWithoutAssistantSetup() {
        compose.onNodeWithText("Your day, at a glance").assertIsDisplayed()
        compose.onNodeWithText("Quick log").assertIsDisplayed()
        compose.onNodeWithTag("quick-log-status").assertTextEquals("Saved on this phone").assertIsDisplayed()
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
        // The pinned bar is available even when the Today card is below the viewport.
        compose.onNodeWithTag("quick-log-WORKOUT").performClick()
        compose.onNodeWithTag("nav-HISTORY").performClick()
        compose.onNodeWithText("No saved entries yet").assertIsDisplayed()
    }
}
