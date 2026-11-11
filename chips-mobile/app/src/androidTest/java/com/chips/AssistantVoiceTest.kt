package com.chips

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.chips.ui.AssistantVoiceContent
import com.chips.ui.theme.ChipsTheme
import com.chips.voice.VoiceController
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
            val controller = remember { VoiceController(recorder, scope, { uploads++; "reply" }) }
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
            val controller = remember { VoiceController(recorder, scope, { uploads++; "reply" }) }
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
            val controller = remember { VoiceController(Recorder(failStart = true), scope, { uploads++; "reply" }) }
            ChipsTheme { AssistantVoiceContent(controller, {}) }
        }
        compose.onNodeWithText("Hold to speak").performTouchInput { down(center) }
        compose.onRoot().performTouchInput { up() }
        compose.onNodeWithText("Microphone permission denied. Try again.").assertExists()
        compose.onNodeWithText("Hold to speak").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, uploads) }
    }
}
