package com.chips

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.chips.ui.AssistantVoiceContent
import com.chips.ui.theme.ChipsTheme
import com.chips.voice.VoiceController
import com.chips.voice.VoiceReply
import com.chips.voice.VoiceService
import com.chips.voice.VoiceFailure
import com.chips.voice.VoiceRecorder
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AssistantVoiceTest {
    @get:Rule val compose = createComposeRule()

    private class Recorder(private val failStart: Boolean = false) : VoiceRecorder {
        var file: File? = null
        override fun start(): Result<Unit> {
            if (failStart) return Result.failure(IOException("Microphone permission denied. Try again."))
            val context = ApplicationProvider.getApplicationContext<Context>()
            file = File.createTempFile("voice-ui-test", ".m4a", context.cacheDir)
            return Result.success(Unit)
        }
        override fun finish() = Result.success(file!!)
        override fun cancel() { file?.delete() }
    }

    @Test fun cancelledHoldDiscardsAudioWithoutSending() {
        val recorder = Recorder()
        var uploads = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { VoiceController(recorder, scope, VoiceService { request, _ -> uploads++; VoiceReply(request.id, "spoken words", "reply") }, {}) }
            ChipsTheme { AssistantVoiceContent(controller, {}) }
        }
        compose.onNodeWithText("Hold to speak").performTouchInput { down(center) }
        compose.onNodeWithText("Release to send").assertIsDisplayed()
        compose.onRoot().performTouchInput { cancel() }
        compose.onNodeWithText("Hold to speak").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, uploads)
            assertFalse(recorder.file!!.exists())
        }
    }

    @Test fun closeDuringHoldReleasesRecorderWithoutSending() {
        val recorder = Recorder()
        var uploads = 0
        var dismissed = false
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { VoiceController(recorder, scope, VoiceService { request, _ -> uploads++; VoiceReply(request.id, "spoken words", "reply") }, {}) }
            ChipsTheme { AssistantVoiceContent(controller, { dismissed = true }) }
        }
        compose.onNodeWithText("Hold to speak").performTouchInput { down(center) }
        compose.onNodeWithText("Release to send").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close assistant").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick
        ) { it() }
        compose.onRoot().performTouchInput { cancel() }
        compose.runOnIdle {
            assertTrue(dismissed)
            assertEquals(0, uploads)
            assertFalse(recorder.file!!.exists())
        }
    }

    @Test fun microphoneFailureShowsErrorAndKeepsRecordingActionAvailable() {
        var uploads = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { VoiceController(Recorder(failStart = true), scope, VoiceService { request, _ -> uploads++; VoiceReply(request.id, "spoken words", "reply") }, {}) }
            ChipsTheme { AssistantVoiceContent(controller, {}) }
        }
        compose.onNodeWithText("Hold to speak").performTouchInput { down(center) }
        compose.onRoot().performTouchInput { up() }
        compose.onNodeWithText("Microphone permission denied. Try again.").assertExists()
        compose.onNodeWithText("Hold to speak").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, uploads) }
    }

    @Test fun failedRequestCanRetrySameRecordingThenShowTranscriptAndSavedReply() {
        val recorder = Recorder()
        val ids = mutableListOf<String>()
        var saved = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { VoiceController(recorder, scope, VoiceService { request, _ ->
                ids += request.id
                if (ids.size == 1) throw VoiceFailure("PC service unavailable. Retry when it returns.")
                VoiceReply(request.id, "Please help me practise Japanese", "Let's practise together")
            }, { saved++ }) }
            ChipsTheme { AssistantVoiceContent(controller, {}) }
        }
        compose.onNodeWithText("Hold to speak").performTouchInput { down(center) }
        compose.onNodeWithText("Release to send").performTouchInput { up() }
        compose.onNodeWithText("Retry request").assertIsDisplayed().performClick()
        compose.onNodeWithText("Please help me practise Japanese").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Let's practise together").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, ids.size)
            assertEquals(ids[0], ids[1])
            assertEquals(1, saved)
            assertFalse(recorder.file!!.exists())
        }
    }

    @Test fun allowedLockscreenSupportsRecordingReplyAndFeatureActions() {
        val recorder = Recorder()
        var uploads = 0
        var selected: com.chips.ui.FeatureRoute? = null
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { VoiceController(recorder, scope, VoiceService { request, _ ->
                uploads++; VoiceReply(request.id, "locked transcript", "locked reply")
            }, {}) }
            ChipsTheme { AssistantVoiceContent(controller, {},
                accessAllowed = com.chips.assistant.assistantAccessAllowed(deviceLocked = true,allowLocked = true),
                onFeature = { selected = it }) }
        }
        compose.onNodeWithText("Hold to speak").performTouchInput { down(center) }
        compose.onNodeWithText("Release to send").performTouchInput { up() }
        compose.onNodeWithText("locked reply").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("assistant-feature-DRAWING").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,uploads); assertEquals(com.chips.ui.FeatureRoute.DRAWING,selected) }
    }

    @Test fun lockedSurfaceDiscardsCaptureAndHidesPrivateContent() {
        val recorder = Recorder()
        val unlocked = mutableStateOf(true)
        var uploads = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { VoiceController(recorder, scope, VoiceService { request, _ ->
                uploads++; VoiceReply(request.id, "private transcript", "private reply")
            }, {}) }
            ChipsTheme { AssistantVoiceContent(controller, {}, accessAllowed = unlocked.value) }
        }
        compose.onNodeWithText("Hold to speak").performTouchInput { down(center) }
        compose.onNodeWithText("Release to send").assertIsDisplayed()
        compose.runOnIdle { unlocked.value = false }
        compose.onRoot().performTouchInput { cancel() }
        compose.onNodeWithText("Unlock to use CHIPS").assertIsDisplayed()
        compose.onNodeWithText("private transcript").assertDoesNotExist()
        compose.onNodeWithText("private reply").assertDoesNotExist()
        compose.onNodeWithText("Hold to speak").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(recorder.file!!.exists())
            assertEquals(0, uploads)
            unlocked.value = true
        }
        compose.onNodeWithText("Hold to speak").assertIsDisplayed()
    }
}
