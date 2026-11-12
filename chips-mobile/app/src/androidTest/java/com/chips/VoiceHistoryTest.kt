package com.chips

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.chips.data.ActivityStore
import com.chips.data.VoiceHistoryStore
import com.chips.ui.VoiceHistoryScreen
import com.chips.ui.theme.ChipsTheme
import com.chips.voice.VoiceConversation
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class VoiceHistoryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedTranscriptAndReplyCanBeReadAndDeletedFromHistory(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "voice-ui-${UUID.randomUUID()}.db"
        val db = ActivityStore(context, name)
        try {
            val store = VoiceHistoryStore(db)
            store.save(VoiceConversation(UUID.randomUUID().toString(), 1234, "I practised Japanese today", "Great, what did you practise?"))
            compose.setContent { ChipsTheme { VoiceHistoryScreen(store) } }
            compose.onNodeWithText("I practised Japanese today").assertIsDisplayed()
            compose.onNodeWithText("Read conversation").performClick()
            compose.onNodeWithText("Great, what did you practise?").assertIsDisplayed()
            compose.onNodeWithText("Delete").performClick()
            compose.onNodeWithText("Delete from phone").performClick()
            compose.waitUntil(5_000) { store.records.value.isEmpty() }
            compose.onNodeWithText("No saved conversations yet").assertIsDisplayed()
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
