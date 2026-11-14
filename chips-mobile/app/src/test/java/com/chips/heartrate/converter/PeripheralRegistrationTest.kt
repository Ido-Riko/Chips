package com.chips.heartrate.converter

import com.chips.heartrate.converter.PeripheralRegistration.Service.*
import org.junit.Assert.*
import org.junit.Test

class PeripheralRegistrationTest {
    @Test fun advertisingWaitsForBothSuccessfulCallbacks() {
        val requests = mutableListOf<PeripheralRegistration.Service>()
        var starts = 0
        val registration = PeripheralRegistration({ requests.add(it); true }, { starts++ }, { fail(it) })
        registration.begin()
        registration.begin()
        assertEquals(listOf(HEART_RATE), requests)
        assertFalse(registration.isReady)
        assertEquals(0, starts)
        registration.complete(DEVICE_INFO, true) // Out-of-order callback cannot release the gate.
        assertEquals(0, starts)
        registration.complete(HEART_RATE, true)
        assertEquals(listOf(HEART_RATE, DEVICE_INFO), requests)
        assertFalse(registration.isReady)
        assertEquals(0, starts)
        registration.complete(HEART_RATE, true) // A duplicate cannot request a second DIS.
        assertEquals(2, requests.size)
        registration.complete(DEVICE_INFO, true)
        assertTrue(registration.isReady)
        assertEquals(1, starts)
        registration.complete(DEVICE_INFO, true)
        registration.timeout()
        assertEquals(1, starts)
    }

    @Test fun requestRejectionsExceptionsAndCallbackFailuresNeverAdvertise() {
        for (service in listOf(HEART_RATE, DEVICE_INFO)) for (failure in listOf("reject", "throw", "callback")) {
            val errors = mutableListOf<String>()
            val registration = PeripheralRegistration({ requested ->
                if (requested == service && failure == "throw") throw SecurityException("Permission revoked")
                !(requested == service && failure == "reject")
            }, { fail("Advertised despite $service $failure") }, { errors.add(it) })
            registration.begin()
            registration.complete(HEART_RATE, service != HEART_RATE || failure != "callback")
            registration.complete(DEVICE_INFO, service != DEVICE_INFO || failure != "callback")
            assertFalse(registration.isReady)
            assertEquals("$service $failure", 1, errors.size)
        }
    }

    @Test fun stopOrTimeoutRejectsLateSuccessIncludingAcrossReplacement() {
        for (stage in listOf(HEART_RATE, DEVICE_INFO)) for (timeout in listOf(false, true)) {
            var errors = 0
            val old = PeripheralRegistration({ true }, { fail("Old server became ready") }, { errors++ })
            old.begin()
            if (stage == DEVICE_INFO) old.complete(HEART_RATE, true)
            if (timeout) old.timeout() else old.stop()
            val replacement = PeripheralRegistration({ true }, {}, { fail(it) })
            replacement.begin()
            old.complete(HEART_RATE, true)
            old.complete(DEVICE_INFO, true)
            assertFalse(old.isReady)
            assertFalse(replacement.isReady)
            assertEquals(if (timeout) 1 else 0, errors)
            replacement.complete(HEART_RATE, true)
            replacement.complete(DEVICE_INFO, true)
            assertTrue(replacement.isReady)
            replacement.stop()
            assertFalse(replacement.isReady)
        }
    }
}
