package com.chips.beatsaber

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class BeatSaberErrorsTest {
    @Test fun networkFailuresGiveRelevantActionsWithoutClaimingSavedDataExists() {
        for ((error, expected) in listOf(
            UnknownHostException("private-host") to "resolve",
            ConnectException("private-address") to "internet connection",
            SocketTimeoutException("private-request") to "timed out",
            SSLHandshakeException("private-certificate") to "secure connection",
            IOException("private-request") to "internet connection"
        )) {
            val message = beatConnectionMessage(error)
            assertTrue(message.contains(expected))
            assertFalse(message.contains("private-"))
            assertFalse(message.contains("saved data"))
        }
    }

    @Test fun wrappedDnsFailuresStillIdentifyNameResolution() {
        val error = IOException("transport", UnknownHostException("private-host"))
        assertTrue(beatConnectionMessage(error).contains("resolve"))
    }
}
