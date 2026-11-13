package com.chips.practice

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.chips.data.ActivityStore
import com.chips.ui.theme.ChipsTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PracticeUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun revealPrecedesRecallAndSavesCompletion() {
        val name = "ui-study-${UUID.randomUUID()}.db"
        val activity = ActivityStore(context,name)
        val store = PracticeStore(activity)
        val deck = store.deck(context)
        val current = mutableStateOf(PracticeState(deck = deck,due = store.queue(deck)))
        try {
            compose.setContent { ChipsTheme { Surface(Modifier.fillMaxSize(), color=MaterialTheme.colorScheme.background) {
                JapaneseScreen(current.value, {}, { card, recall ->
                    store.rate(card.kanji,recall)
                    current.value=current.value.copy(reviews=store.reviews(),due=store.queue(deck))
                }, {}, {})
            } } }
            compose.onNodeWithTag("review-GOOD").assertDoesNotExist()
            compose.onNodeWithTag("review-reveal").performClick()
            compose.onNodeWithText(deck.first().meanings).assertIsDisplayed()
            compose.onNodeWithTag("review-GOOD").performClick()
            compose.onNodeWithTag("review-reveal").assertIsDisplayed()
            assertEquals(1,store.reviews().size)
            assertEquals(1,activity.load().size)
        } finally { activity.close();context.deleteDatabase(name) }
    }

    @Test fun actualImageImportPersistsImageNotesAndActivityWithoutServer() = runBlocking {
        val name="image-import-${UUID.randomUUID()}.db"
        val activity=ActivityStore(context,name)
        val store=PracticeStore(activity)
        val viewModels=ViewModelStore()
        val input=File.createTempFile("drawing-test-", ".png",context.cacheDir)
        var drawing: DrawingEntry?=null
        lateinit var vm: PracticeViewModel
        try {
            val bitmap=Bitmap.createBitmap(80,120,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            instrumentation.runOnMainSync {
                vm=PracticeViewModel(context.applicationContext as Application,store)
                viewModels.put("practice",vm)
                vm.importDrawing(Uri.fromFile(input),"Shading a cube")
            }
            val saved=withTimeout(5000) { vm.state.first { !it.busy && it.drawings.isNotEmpty() } }
            drawing=saved.drawings.single()
            assertTrue(imageFile(context,drawing.id).length()>0)
            assertTrue(thumbFile(context,drawing.id).length()>0)
            assertFalse(drawing.uploaded)
            assertEquals(1,activity.load().size)
            instrumentation.runOnMainSync { vm.notes(drawing.id,"Try a softer edge") }
            withTimeout(5000) { vm.state.first { !it.busy && it.drawings.single().notes=="Try a softer edge" } }
            assertEquals("Try a softer edge",store.drawings().single().notes)
            instrumentation.runOnMainSync { vm.remove(drawing.id) }
            withTimeout(5000) { vm.state.first { !it.busy && it.drawings.isEmpty() } }
            assertFalse(imageFile(context,drawing.id).exists())
            assertEquals(1,activity.load().size)
        } finally {
            instrumentation.runOnMainSync { viewModels.clear() }
            drawing?.let { imageFile(context,it.id).delete();thumbFile(context,it.id).delete() }
            input.delete();activity.close();context.deleteDatabase(name)
        }
    }
}
