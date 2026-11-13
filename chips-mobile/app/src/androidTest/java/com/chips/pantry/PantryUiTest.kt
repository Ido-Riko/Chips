package com.chips.pantry

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.chips.ui.theme.ChipsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class PantryUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test fun manualGroceriesAndReceiptReviewWorkWithoutServer() {
        val name = "pantry-ui-${UUID.randomUUID()}.db"
        val store = PantryStore(context,name)
        val viewModels = ViewModelStore()
        lateinit var vm: PantryViewModel
        try {
            instrumentation.runOnMainSync {
                vm = PantryViewModel(context.applicationContext as Application,store)
                viewModels.put("pantry",vm)
            }
            compose.setContent { ChipsTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val state by vm.state.collectAsState()
                PantryScreen(state,vm,{})
            } } }
            compose.onNodeWithTag("pantry-add").performClick()
            compose.onNodeWithTag("pantry-name").performScrollTo().performTextInput("Rice")
            compose.onNodeWithTag("pantry-save").performScrollTo().performClick()
            compose.waitUntil(5000) { store.items().size == 1 }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Rice").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Rice").assertIsDisplayed()
            compose.onNodeWithText("Receipt",useUnmergedTree = true).performClick()
            compose.onNodeWithTag("receipt-draft").performScrollTo().performTextInput("Milk | 2 | item")
            assertEquals(1,store.items().size)
            compose.onNodeWithTag("receipt-confirm").performScrollTo().performClick()
            compose.waitUntil(5000) { store.items().size == 2 }
            assertTrue(store.transactions().all { it.getString("source") in listOf("manual","receipt") })
            assertEquals(2,store.transactions(true).size)
            compose.onNodeWithText("Close",useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithText("Milk").assertIsDisplayed()
            File(context.cacheDir,"pantry-preview.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { instrumentation.runOnMainSync { viewModels.clear() }; context.deleteDatabase(name) }
    }
}
