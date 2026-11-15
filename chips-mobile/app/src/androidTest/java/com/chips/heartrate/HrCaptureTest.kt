package com.chips.heartrate

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.chips.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class HrCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    @Test fun outputsChangeLiveWithoutRestartingReaderOrCapture() {
        assumeEmulator()
        for(permission in HrCaptureService.permissions().toList() + listOf(android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.BLUETOOTH_ADVERTISE)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        var emit: ((Int, Long, Long) -> Unit)? = null
        var readerStarts = 0
        var readerClosed = false
        var relayStarts = 0
        var relayStops = 0
        var relayClosed = false
        val relayed = mutableListOf<Int>()
        HrCaptureService.transportFactory = { _, _, _, received ->
            emit = received
            object : HrTransport {
                override fun start() { readerStarts++ }
                override fun reconnect() {}
                override fun close() { readerClosed = true }
            }
        }
        HrCaptureService.relayFactory = { _, _, _ -> object : HrRelayOutput {
            override fun start() { relayStarts++ }
            override fun stop() { relayStops++ }
            override fun source(address: String?) {}
            override fun send(bpm: Int) { relayed += bpm }
            override fun clear() {}
            override fun bluetoothChanged(enabled: Boolean) {}
            override fun close() { relayClosed = true }
        } }
        val prefs = context.getSharedPreferences("heart_rate", android.content.Context.MODE_PRIVATE)
        val oldPrefs = prefs.all
        val before = HrStore(context).use { it.sessions().map { s -> s.id }.toSet() }
        fun configure(config: HrOutputConfig) {
            compose.runOnIdle {
                context.startService(Intent(context, HrCaptureService::class.java).setAction(HrCaptureService.UPDATE_OUTPUTS)
                    .putExtra("relay", config.relay).putExtra("osc", config.osc).putExtra("host", config.host).putExtra("port", config.port))
            }
            compose.waitUntil(5000) { HrCaptureService.state.value.outputs == config && !HrCaptureService.state.value.updatingOutputs }
        }
        fun emit(bpm: Int) { compose.runOnIdle { emit!!(bpm, System.currentTimeMillis(), SystemClock.elapsedRealtime()) } }
        fun receive(receiver: DatagramSocket): Int {
            val packet = DatagramPacket(ByteArray(128), 128)
            receiver.receive(packet)
            return ByteBuffer.wrap(packet.data, 16, 4).int
        }
        try {
            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { first ->
                DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { second ->
                    first.soTimeout = 2000; second.soTimeout = 2000
                    compose.activityRule.scenario.onActivity { activity ->
                        ContextCompat.startForegroundService(activity, Intent(activity, HrCaptureService::class.java)
                            .setAction(HrCaptureService.START).putExtra("device", "00:11:22:33:44:55"))
                    }
                    compose.waitUntil(5000) { emit != null && HrCaptureService.state.value.session != null && !HrCaptureService.state.value.updatingOutputs }
                    val id = HrCaptureService.state.value.session!!
                    val initial = HrOutputConfig(true, true, "127.0.0.1", first.localPort)
                    configure(initial)
                    emit(70)
                    assertEquals(70, receive(first))
                    val changed = initial.copy(port = second.localPort)
                    configure(changed)
                    emit(71)
                    assertEquals(71, receive(second))
                    // Invalid targets are rejected while the last acknowledged output remains usable.
                    compose.runOnIdle { context.startService(Intent(context, HrCaptureService::class.java)
                        .setAction(HrCaptureService.UPDATE_OUTPUTS).putExtra("osc", true).putExtra("port", 0)) }
                    compose.waitUntil(5000) { HrCaptureService.state.value.outputError != null }
                    assertEquals(changed, HrCaptureService.state.value.outputs)
                    emit(72)
                    assertEquals(72, receive(second))
                    configure(changed.copy(relay = false, osc = false))
                    emit(73)
                    second.soTimeout = 300
                    assertThrows(java.net.SocketTimeoutException::class.java) { receive(second) }
                    assertEquals(listOf(70,71,72), relayed)
                    configure(changed.copy(osc = false))
                    emit(74)
                    assertEquals(listOf(70,71,72,74), relayed)
                    assertEquals(2, relayStarts)
                    assertEquals(1, relayStops)
                    assertEquals(1, readerStarts)
                    assertFalse(readerClosed)
                    assertEquals(id, HrCaptureService.state.value.session)
                    assertEquals(second.localPort, HrRecoverySettings(context).restart(context)!!.getIntExtra("port", 0))
                    compose.runOnIdle { HrCaptureService.command(context, HrCaptureService.STOP) }
                    compose.waitUntil(5000) { !HrCaptureService.state.value.stopping && !HrCaptureService.state.value.active }
                    assertTrue(readerClosed)
                    assertTrue(relayClosed)
                    assertNull(HrRecoverySettings(context).restart(context))
                    assertEquals(5, HrStore(context).use { it.sessions().first { s -> s.id == id }.samples })
                }
            }
        } finally {
            compose.runOnIdle { HrCaptureService.command(context, HrCaptureService.STOP) }
            compose.waitUntil(5000) { !HrCaptureService.state.value.stopping && !HrCaptureService.state.value.active }
            HrCaptureService.transportFactory = null
            HrCaptureService.relayFactory = null
            HrStore(context).use { store -> store.sessions().filter { it.id !in before }.forEach { store.end(it.id); store.delete(it.id) } }
            prefs.edit().clear().apply {
                oldPrefs.forEach { (key,value) -> when(value) {
                    is String -> putString(key,value)
                    is Boolean -> putBoolean(key,value)
                    is Int -> putInt(key,value)
                } }
            }.commit()
        }
    }

    @Test fun notificationRemainsThroughReconnectAndRecreationAndStopsDurableCapture() {
        assumeEmulator()
        val automation = instrumentation.uiAutomation
        for(permission in HrCaptureService.permissions().toList() + android.Manifest.permission.POST_NOTIFICATIONS) {
            automation.grantRuntimePermission(context.packageName, permission)
        }
        var emit: ((Int, Long, Long) -> Unit)? = null
        var closed = false
        val reconnects = java.util.concurrent.atomic.AtomicInteger()
        HrCaptureService.transportFactory = { _, _, state, received ->
            emit = received
            object : HrTransport {
                override fun start() { state("Waiting for HR") }
                override fun reconnect() { reconnects.incrementAndGet(); state("Band disconnected · reconnect 1/5") }
                override fun close() { closed = true }
            }
        }
        val before = HrStore(context).use { it.sessions().map { s -> s.id }.toSet() }
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            compose.activityRule.scenario.onActivity { activity ->
                ContextCompat.startForegroundService(activity, Intent(activity, HrCaptureService::class.java).setAction(HrCaptureService.START).putExtra("device", "00:11:22:33:44:55"))
            }
            compose.waitUntil(5000) { emit != null && HrCaptureService.state.value.session != null }
            val id = HrCaptureService.state.value.session!!
            compose.runOnIdle {
                val received = emit!!
                val wall = System.currentTimeMillis()
                repeat(3) { received(80, wall + it, SystemClock.elapsedRealtime() + it) }
            }
            compose.waitUntil(5000) { HrStore(context).use { it.sessions().first { s -> s.id == id }.samples == 3 } }
            val notification = manager.activeNotifications.single { it.id == HrCaptureService.NOTIFICATION_ID }.notification
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertNotNull(notification.actions.firstOrNull { it.title == "Stop" })
            // Verify foreground capture and its notification persist with the display off.
            shell("input keyevent 223")
            val power = context.getSystemService(android.os.PowerManager::class.java)
            val sleepDeadline = SystemClock.elapsedRealtime() + 5000
            while(power.isInteractive && SystemClock.elapsedRealtime() < sleepDeadline) SystemClock.sleep(25)
            assertFalse(power.isInteractive)
            instrumentation.runOnMainSync { emit!!(80, System.currentTimeMillis(), SystemClock.elapsedRealtime()) }
            val saveDeadline = SystemClock.elapsedRealtime() + 5000
            while(HrStore(context).use { it.sessions().first { s -> s.id == id }.samples } < 4 && SystemClock.elapsedRealtime() < saveDeadline) SystemClock.sleep(25)
            assertEquals(4, HrStore(context).use { it.sessions().first { s -> s.id == id }.samples })
            assertTrue(manager.activeNotifications.any { it.id == HrCaptureService.NOTIFICATION_ID })
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            notification.contentIntent.send()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("heart-rate-screen").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("heart-rate-screen").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("heart-rate-screen").assertIsDisplayed()
            assertTrue(HrCaptureService.state.value.active)
            notification.actions.first { it.title == "Reconnect" }.actionIntent.send()
            // PendingIntent delivery runs on the service thread. Entering reconnect()
            // does not mean its connection callback has published the new state yet.
            compose.waitUntil(5000) {
                val state = HrCaptureService.state.value
                reconnects.get() == 1 && state.status == "Band disconnected · reconnect 1/5" && state.bpm == null
            }
            assertNull(HrCaptureService.state.value.bpm)
            assertTrue(manager.activeNotifications.any { it.id == HrCaptureService.NOTIFICATION_ID })
            // Duplicate Start must not create a second session or reset capture.
            compose.runOnIdle { context.startService(Intent(context, HrCaptureService::class.java).setAction(HrCaptureService.START).putExtra("device", "00:11:22:33:44:55")) }
            compose.waitForIdle()
            assertEquals(id, HrCaptureService.state.value.session)
            notification.actions.first { it.title == "Stop" }.actionIntent.send()
            compose.waitUntil(5000) { !HrCaptureService.state.value.active && closed }
            compose.waitUntil(5000) { HrStore(context).use { it.sessions().first { s -> s.id == id }.ended != null } }
            compose.runOnIdle { emit!!(200, System.currentTimeMillis(), SystemClock.elapsedRealtime()) }
            HrStore(context).use { store ->
                val saved = store.sessions().first { it.id == id }
                assertEquals(4, saved.samples)
                assertEquals(80.0, saved.average!!, 0.0)
                assertEquals("stopped", saved.status)
                assertEquals(1, store.sessions().count { it.id !in before })
            }
            assertFalse(manager.activeNotifications.any { it.id == HrCaptureService.NOTIFICATION_ID })
        } finally {
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            context.stopService(Intent(context, HrCaptureService::class.java))
            HrCaptureService.transportFactory = null
            HrStore(context).use { store -> store.sessions().filter { it.id !in before }.forEach { store.end(it.id); store.delete(it.id) } }
        }
    }

    @Test fun oscOutputReachesConfiguredReceiver() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { receiver ->
            receiver.soTimeout = 2000
            HrOsc("127.0.0.1", receiver.localPort).use { sender -> sender.send(123) }
            val packet = DatagramPacket(ByteArray(128), 128)
            receiver.receive(packet)
            assertEquals(20, packet.length)
            assertEquals(123, ByteBuffer.wrap(packet.data, 16, 4).int)
        }
    }
}
