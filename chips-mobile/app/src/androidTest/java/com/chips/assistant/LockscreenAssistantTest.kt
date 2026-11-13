package com.chips.assistant

import android.app.KeyguardManager
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.chips.MainActivity
import com.chips.ui.featureIntent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LockscreenAssistantTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun assistantEntryAndFeatureHandoffPreserveKeyguardState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val lockedBefore = keyguard.isDeviceLocked
        val intent = featureIntent(context,null,fromLockscreen = true)
            .putExtra(MainActivity.OPEN_ASSISTANT,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<LockscreenAssistantActivity>(intent).use { scenario ->
            compose.onNodeWithTag("assistant-feature-DRAWING").performScrollTo().performClick()
            compose.onNodeWithTag("drawing-screen").assertIsDisplayed()
            assertEquals(lockedBefore,keyguard.isDeviceLocked)
            scenario.recreate()
            compose.onNodeWithTag("drawing-screen").assertIsDisplayed()
            assertEquals(lockedBefore,keyguard.isDeviceLocked)
        }
    }
}
