package com.chips.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SyncCredentialsTest {
    @Test fun disconnectKeepsEnrollmentAndStaysPausedAcrossReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "credential-test-${UUID.randomUUID()}"
        val credentials = SyncCredentials(context, name)
        val token = "test-credential-" + UUID.randomUUID()
        try {
            credentials.save("https://example.invalid/api/v1", token)
            val original = credentials.read()
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val encrypted = prefs.getString("config", null)
            // Older installed versions have an enrollment but no connection flag.
            assertTrue(prefs.edit().remove("enabled").commit())
            assertTrue(SyncCredentials(context, name).connected())
            credentials.disconnect()
            val reopened = SyncCredentials(context, name)
            assertTrue(reopened.configured())
            assertFalse(reopened.connected())
            assertEquals(original, reopened.read())
            assertEquals(encrypted, prefs.getString("config", null))
            reopened.reconnect()
            assertTrue(SyncCredentials(context, name).connected())
            assertEquals(original, reopened.read())
            reopened.disconnect()
            reopened.save("https://example.invalid/api/v1", token)
            assertTrue(SyncCredentials(context, name).connected())
        } finally { credentials.clear(); context.deleteSharedPreferences(name) }
    }

    @Test fun enrollmentIsEncryptedSurvivesReopenAndRejectsCleartext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "credential-test-${UUID.randomUUID()}"
        val credentials = SyncCredentials(context, name)
        val token = "test-credential-" + UUID.randomUUID()
        try {
            assertTrue(runCatching { credentials.save("http://example.invalid/api/v1", token) }.isFailure)
            assertFalse(credentials.configured())
            credentials.save("https://example.invalid/api/v1/", token)
            val encrypted = context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("config", "")!!
            assertFalse(encrypted.contains(token))
            assertFalse(encrypted.contains("example.invalid"))
            assertEquals(SyncCredentials.Config("https://example.invalid/api/v1", token), SyncCredentials(context, name).read())
            credentials.clear()
            assertNull(SyncCredentials(context, name).read())
        } finally { credentials.clear(); context.deleteSharedPreferences(name) }
    }
}
