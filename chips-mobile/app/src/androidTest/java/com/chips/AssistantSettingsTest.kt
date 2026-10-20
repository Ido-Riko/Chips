package com.chips

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.provider.Settings
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import org.junit.Rule
import org.junit.Test

class AssistantSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun chooseAssistantOpensSystemSettingsRatherThanSilentRoleRequest() {
        Intents.init()
        try {
            // Intercept the launch so the test cannot change the device's assistant.
            Intents.intending(hasAction(Settings.ACTION_VOICE_INPUT_SETTINGS))
                .respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
            compose.onNodeWithTag("nav-SETTINGS").performClick()
            compose.onNodeWithText("Choose device assistant").performScrollTo().performClick()
            Intents.intended(hasAction(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } finally {
            Intents.release()
        }
    }
}
