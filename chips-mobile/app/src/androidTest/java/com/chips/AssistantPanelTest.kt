package com.chips

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.chips.ui.AssistantPanel
import com.chips.ui.AssistantPhase
import com.chips.ui.FeatureRoute
import com.chips.ui.theme.ChipsTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AssistantPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun keyboardKeepsTypedMessageAndSendFullyVisible() {
        var sent: String? = null
        compose.setContent {
            ChipsTheme {
                AssistantPanel(AssistantPhase.READY, null, remember { MutableInteractionSource() }, {},
                    onSendText = { sent = it })
            }
        }
        val input = compose.onNodeWithTag("assistant-chat-input")
        input.performScrollTo().performClick().performTextInput("Help me plan today")
        input.performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("assistant-chat-send").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val full = input.getUnclippedBoundsInRoot()
        val visible = input.fetchSemanticsNode().boundsInRoot
        val fullHeight = with(compose.density) { full.height.toPx() }
        assertTrue("The keyboard must not clip the message field", visible.height >= fullHeight - 1f)
        compose.onNodeWithTag("assistant-chat-send").performClick()
        compose.runOnIdle { assertTrue(sent == "Help me plan today") }
        androidx.test.espresso.Espresso.pressBack()
    }

    @Test
    fun compactWindowKeepsVoiceControlAndCloseReachableAcrossStates() {
        val phase = mutableStateOf(AssistantPhase.READY)
        var dismissed = false
        compose.setContent {
            ChipsTheme {
                Box(Modifier.requiredSize(320.dp, 480.dp)) {
                    AssistantPanel(phase.value, null, remember { MutableInteractionSource() }, { dismissed = true })
                }
            }
        }
        compose.onNodeWithText("Hold to speak").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Conversations are saved on this phone.").assertIsDisplayed()
        compose.runOnIdle { phase.value = AssistantPhase.LISTENING }
        compose.onNodeWithText("Release to send").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { phase.value = AssistantPhase.PROCESSING }
        compose.onNodeWithText("Getting your reply…").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close assistant").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun longReplyAtLargeTextScrollsWithoutHidingActions() {
        val reply = List(30) { "A longer reply should remain readable without covering the recording control." }.joinToString("\n")
        compose.setContent {
            ChipsTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                    Box(Modifier.requiredSize(320.dp, 560.dp)) {
                        AssistantPanel(AssistantPhase.REPLY, reply, remember { MutableInteractionSource() }, {})
                    }
                }
            }
        }
        compose.onNodeWithText("REPLY").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(reply).assertExists()
        compose.onNodeWithText("Hold to speak").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Close assistant").assertIsDisplayed()
    }

    @Test fun shortcutsScrollInCompactWindowWithoutMicrophoneAccess() {
        val chosen = mutableListOf<FeatureRoute>()
        val phase = mutableStateOf(AssistantPhase.READY)
        compose.setContent {
            ChipsTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                    Box(Modifier.requiredSize(320.dp,480.dp)) {
                        AssistantPanel(phase.value,null,remember { MutableInteractionSource() },{},
                            onFeature = { chosen += it },microphoneAvailable = false)
                    }
                }
            }
        }
        for(route in FeatureRoute.entries) compose.onNodeWithTag("assistant-feature-${route.name}").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(chosen == FeatureRoute.entries.toList()) }
        compose.onNodeWithText("Hold to speak").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { phase.value = AssistantPhase.PROCESSING }
        compose.onNodeWithTag("assistant-feature-JAPANESE").assertDoesNotExist()
    }

    @Test fun retryAndCancelRemainReachableAtLargeTextInCompactWindow() {
        val phase = mutableStateOf(AssistantPhase.ERROR)
        var retries = 0
        var discarded = 0
        compose.setContent {
            ChipsTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                    Box(Modifier.requiredSize(320.dp, 480.dp)) {
                        AssistantPanel(phase.value, "Service unavailable. Retry when your PC is ready.",
                            remember { MutableInteractionSource() }, {}, canRetry = true,
                            onRetry = { retries++ }, onDiscard = { discarded++ })
                    }
                }
            }
        }
        compose.onNodeWithText("Retry request").assertIsDisplayed().performClick()
        compose.onNodeWithText("Discard").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(retries == 1 && discarded == 1); phase.value = AssistantPhase.SAVING }
        compose.onNodeWithText("Dismiss").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Close assistant").assertIsDisplayed()
    }
}
