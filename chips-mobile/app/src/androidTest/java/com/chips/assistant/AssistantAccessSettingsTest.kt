package com.chips.assistant

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AssistantAccessSettingsTest {
    @Test fun lockscreenUseDefaultsOnAndOptOutPersists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "assistant-access-${UUID.randomUUID()}"
        try {
            val access = AssistantAccessSettings(context,name)
            assertTrue(access.allowLocked())
            assertTrue(access.allowed(true))
            access.setAllowLocked(false)
            val reopened = AssistantAccessSettings(context,name)
            assertFalse(reopened.allowLocked())
            assertFalse(reopened.allowed(true))
            assertTrue(reopened.allowed(false))
            reopened.setAllowLocked(true)
            assertTrue(access.allowed(true))
        } finally { context.deleteSharedPreferences(name) }
    }
}
