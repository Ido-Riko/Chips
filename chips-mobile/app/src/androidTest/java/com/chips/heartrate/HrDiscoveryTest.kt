package com.chips.heartrate

import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class HrDiscoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) { instrumentation.runOnMainSync(action) }
    private fun responsive() {
        val tick = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { tick.countDown() }
        assertTrue("UI thread stalled behind Bluetooth work", tick.await(750, TimeUnit.MILLISECONDS))
    }
    private class Scan : HrScan {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var startGate: CountDownLatch? = null
        var stopGate: CountDownLatch? = null
        var startError: Exception? = null
        lateinit var result: (() -> HrDevice?) -> Unit
        lateinit var failed: (Int) -> Unit
        override fun start(result: (() -> HrDevice?) -> Unit, failed: (Int) -> Unit) {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            this.result = result; this.failed = failed
            started.countDown()
            startGate?.let { check(it.await(5, TimeUnit.SECONDS)) }
            startError?.let { throw it }
        }
        override fun stop() {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            stopped.countDown()
            stopGate?.let { check(it.await(5, TimeUnit.SECONDS)) }
        }
    }

    @Test fun blockingScanStartNeverBlocksUiAndDisposeStillStopsIt() {
        val gate = CountDownLatch(1)
        val scan = Scan().apply { startGate = gate }
        val results = CopyOnWriteArrayList<HrDevice>()
        lateinit var picker: HrDiscovery
        try {
            main { picker = HrDiscovery(context, { results += it }, {}, { scan }); picker.start() }
            assertTrue(scan.started.await(2, TimeUnit.SECONDS))
            responsive()
            main { picker.dispose() }
            responsive()
            gate.countDown()
            assertTrue("Disposal lost pending scan cleanup", scan.stopped.await(2, TimeUnit.SECONDS))
            var resolved = false
            scan.result { resolved = true; HrDevice("old", "Mi Band") }
            instrumentation.waitForIdleSync()
            assertFalse(resolved)
            assertTrue(results.isEmpty())
        } finally { gate.countDown(); main { picker.dispose() } }
    }

    @Test fun slowStopAndDeviceLookupStayOffUiAndOldScanCannotAffectNewScan() {
        val stopGate = CountDownLatch(1)
        val lookupGate = CountDownLatch(1)
        val first = Scan().apply { this.stopGate = stopGate }
        val second = Scan()
        val starts = AtomicInteger()
        val statuses = CopyOnWriteArrayList<String>()
        val results = CopyOnWriteArrayList<HrDevice>()
        val lookupStarted = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        lateinit var picker: HrDiscovery
        try {
            main {
                picker = HrDiscovery(context, { assertEquals(Looper.getMainLooper(), Looper.myLooper()); results += it; delivered.countDown() },
                    { statuses += it }, { if(starts.getAndIncrement() == 0) first else second })
                picker.start()
            }
            assertTrue(first.started.await(2, TimeUnit.SECONDS))
            main { picker.close(); picker.start() }
            assertTrue(first.stopped.await(2, TimeUnit.SECONDS))
            responsive()
            stopGate.countDown()
            assertTrue(second.started.await(2, TimeUnit.SECONDS))
            first.failed(42)
            first.result { error("Stale result must not even resolve its device name") }
            second.result {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                lookupStarted.countDown()
                check(lookupGate.await(5, TimeUnit.SECONDS))
                HrDevice("new", "Smart Band 10")
            }
            assertTrue(lookupStarted.await(2, TimeUnit.SECONDS))
            responsive()
            lookupGate.countDown()
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(HrDevice("new", "Smart Band 10")), results)
            assertFalse(statuses.any { it.contains("42") })
            assertEquals(1L, second.stopped.count)
        } finally { stopGate.countDown(); lookupGate.countDown(); main { picker.dispose() } }
    }

    @Test fun permissionFailureStopsTheScanAndReportsOnUi() {
        val scan = Scan().apply { startError = SecurityException("Nearby permission removed") }
        val reported = CountDownLatch(1)
        lateinit var picker: HrDiscovery
        try {
            main {
                picker = HrDiscovery(context, {}, {
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    if(it.startsWith("Allow nearby")) reported.countDown()
                }, { scan })
                picker.start()
            }
            assertTrue(reported.await(2, TimeUnit.SECONDS))
            assertTrue(scan.stopped.await(2, TimeUnit.SECONDS))
            responsive()
        } finally { main { picker.dispose() } }
    }

    @Test fun timeoutStopsOffUiAndIgnoresLateResultsAndErrors() {
        val scan = Scan()
        val reported = CountDownLatch(1)
        val statuses = CopyOnWriteArrayList<String>()
        val results = CopyOnWriteArrayList<HrDevice>()
        lateinit var picker: HrDiscovery
        try {
            main {
                picker = HrDiscovery(context, { results += it }, {
                    statuses += it
                    if(it.startsWith("Scan finished")) reported.countDown()
                }, { scan }, timeoutMillis = 500)
                picker.start()
            }
            assertTrue(scan.started.await(2, TimeUnit.SECONDS))
            assertTrue(reported.await(2, TimeUnit.SECONDS))
            assertTrue(scan.stopped.await(2, TimeUnit.SECONDS))
            scan.result { error("Timed-out result must be ignored") }
            scan.failed(99)
            instrumentation.waitForIdleSync()
            assertTrue(results.isEmpty())
            assertFalse(statuses.any { it.contains("99") })
        } finally { main { picker.dispose() } }
    }
}
