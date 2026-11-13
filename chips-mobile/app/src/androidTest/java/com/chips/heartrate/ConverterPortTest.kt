package com.chips.heartrate

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.chips.heartrate.converter.BleConnectionManager
import com.chips.heartrate.converter.ConnectionStateMachine
import com.chips.heartrate.converter.OscSender
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.UUID

/** Exercises the ported classes themselves, in addition to the CHIPS service's injected-transport tests. */
@SuppressLint("MissingPermission")
class ConverterPortTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun field(instance: Any, name: String) = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun prepareBluetooth(): BluetoothAdapter {
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        for(permission in HrCaptureService.permissions()) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        assumeTrue(adapter != null && adapter.isEnabled)
        return adapter
    }
    @Test fun originalStateMachineRetainsScanReconnectAndGracefulDisconnectTransitions() {
        val state = ConnectionStateMachine()
        val changes = mutableListOf<ConnectionStateMachine.State>()
        state.addListener(object : ConnectionStateMachine.StateChangeListener {
            override fun onStateChanged(oldState: ConnectionStateMachine.State, newState: ConnectionStateMachine.State, event: ConnectionStateMachine.Event) { changes += newState }
        })
        assertFalse(state.transition(ConnectionStateMachine.Event.CONNECTED))
        for(event in listOf(ConnectionStateMachine.Event.START_SCAN, ConnectionStateMachine.Event.DEVICE_FOUND,
            ConnectionStateMachine.Event.CONNECTED, ConnectionStateMachine.Event.DISCONNECT_REQUESTED,
            ConnectionStateMachine.Event.DISCONNECTED, ConnectionStateMachine.Event.CONNECTION_INITIATED,
            ConnectionStateMachine.Event.CONNECTION_FAILED)) assertTrue(state.transition(event))
        assertEquals(ConnectionStateMachine.State.DISCONNECTED, state.getCurrentState())
        assertEquals(listOf(ConnectionStateMachine.State.SCANNING, ConnectionStateMachine.State.CONNECTING,
            ConnectionStateMachine.State.CONNECTED, ConnectionStateMachine.State.DISCONNECTING,
            ConnectionStateMachine.State.DISCONNECTED, ConnectionStateMachine.State.CONNECTING,
            ConnectionStateMachine.State.DISCONNECTED), changes)
    }
    @Test fun converterRawHookRunsBeforeItsOriginalDuplicateFilterAndRejectsLateGatt() {
        val adapter = prepareBluetooth()
        val prefs = context.getSharedPreferences("converter-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val raw = mutableListOf<Int>()
        val displayed = mutableListOf<Int>()
        lateinit var manager: BleConnectionManager
        var gatt: BluetoothGatt? = null
        instrumentation.runOnMainSync {
            manager = BleConnectionManager(context, prefs, object : BleConnectionManager.BleConnectionCallback {
                override fun onConnectionStateChanged(state: ConnectionStateMachine.State) {}
                override fun onHeartRateReceived(hr: Int) { displayed += hr }
                override fun onHeartRateSample(hr: Int, received: Long, elapsed: Long) { assertTrue(received > 0 && elapsed > 0); raw += hr }
                override fun onError(message: String) {}
            })
            gatt = adapter.getRemoteDevice("00:11:22:33:44:55").connectGatt(context, false, object : BluetoothGattCallback() {}, BluetoothDevice.TRANSPORT_LE)
            try {
                assertNotNull(gatt)
                field(manager, "bluetoothGatt").set(manager, gatt)
                field(manager, "subscribed").setBoolean(manager, true)
                val callback = field(manager, "gattCallback").get(manager) as BluetoothGattCallback
                val characteristic = BluetoothGattCharacteristic(HR_MEASUREMENT, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
                repeat(3) { callback.onCharacteristicChanged(gatt!!, characteristic, byteArrayOf(6, 80)) }
                callback.onCharacteristicChanged(gatt!!, characteristic, byteArrayOf(7, 44, 1))
                callback.onCharacteristicChanged(gatt!!, characteristic, byteArrayOf(4, 80)) // no contact
                callback.onCharacteristicChanged(gatt!!, characteristic, byteArrayOf(0, 0))
                assertEquals(listOf(80, 80, 80, 300), raw)
                assertEquals(listOf(80), displayed) // original converter's UI filtering is intact
                field(manager, "bluetoothGatt").set(manager, null)
                callback.onCharacteristicChanged(gatt!!, characteristic, byteArrayOf(6, 90))
                assertEquals(4, raw.size)
                manager.close()
                callback.onCharacteristicChanged(gatt!!, characteristic, byteArrayOf(6, 90))
                assertEquals(4, raw.size)
            } finally { manager.close(); gatt?.close() }
        }
        prefs.edit().clear().commit()
    }
    @Test fun unexpectedBandDisconnectSchedulesOriginalReconnectButExplicitStopCancelsIt() {
        val adapter = prepareBluetooth()
        val prefs = context.getSharedPreferences("converter-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        instrumentation.runOnMainSync {
            val manager = BleConnectionManager(context, prefs, object : BleConnectionManager.BleConnectionCallback {
                override fun onConnectionStateChanged(state: ConnectionStateMachine.State) {}
                override fun onHeartRateReceived(hr: Int) {}
                override fun onError(message: String) {}
            })
            val gatt = adapter.getRemoteDevice("00:11:22:33:44:55").connectGatt(context, false, object : BluetoothGattCallback() {}, BluetoothDevice.TRANSPORT_LE)!!
            try {
                field(manager, "bluetoothGatt").set(manager, gatt)
                val state = field(manager, "stateMachine").get(manager) as ConnectionStateMachine
                state.forceState(ConnectionStateMachine.State.CONNECTED)
                val callback = field(manager, "gattCallback").get(manager) as BluetoothGattCallback
                callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
                assertEquals(ConnectionStateMachine.State.DISCONNECTED, manager.getCurrentState())
                assertEquals(1, manager.getReconnectAttempts())
                assertNotNull(field(manager, "pendingReconnectRunnable").get(manager))
                manager.disconnect()
                manager.close()
                callback.onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
                assertTrue(field(manager, "isClosed").get(manager).let { it as java.util.concurrent.atomic.AtomicBoolean }.get())
                assertEquals(5, manager.getReconnectAttempts()) // manual disconnect exhausts/cancels automatic retries
            } finally { manager.close(); gatt.close() }
        }
        prefs.edit().clear().commit()
    }
    @Test fun converterOscRecreatesClosedSocketAndKeepsZeroOnlyAsOutputDisconnectSignal() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { receiver ->
            receiver.soTimeout = 3000
            val sender = OscSender("127.0.0.1", receiver.localPort)
            try {
                fun received(): Int { val packet = DatagramPacket(ByteArray(128), 128); receiver.receive(packet); assertEquals(20, packet.length); return ByteBuffer.wrap(packet.data, 16, 4).int }
                sender.sendHeartRate(80)
                assertEquals(80, received())
                (field(sender, "socket").get(sender) as DatagramSocket).close()
                sender.sendHeartRate(81)
                assertEquals(81, received())
                sender.sendHeartRate(-1)
                assertEquals(0, received())
            } finally { sender.close() }
        }
    }
    @Test fun chipsAdapterPreservesConvertersSuccessfulFallbackUntilUserChoosesAnotherBand() {
        prepareBluetooth()
        val prefs = context.getSharedPreferences("miband_converter", Context.MODE_PRIVATE)
        val keys = listOf("selectedDeviceAddress", "lastDeviceAddress")
        val before = keys.associateWith { prefs.getString(it, null) }
        try {
            prefs.edit().remove("selectedDeviceAddress").remove("lastDeviceAddress").commit()
            instrumentation.runOnMainSync { HrBleReader(context, "00:11:22:33:44:55", {}, { _, _, _ -> }).close() }
            assertEquals("00:11:22:33:44:55", prefs.getString("lastDeviceAddress", null))
            // A successful converter scan discovers the band's changed address.
            prefs.edit().putString("lastDeviceAddress", "00:11:22:33:44:66").commit()
            instrumentation.runOnMainSync { HrBleReader(context, "00:11:22:33:44:55", {}, { _, _, _ -> }).close() }
            assertEquals("00:11:22:33:44:66", prefs.getString("lastDeviceAddress", null))
            instrumentation.runOnMainSync { HrBleReader(context, "00:11:22:33:44:77", {}, { _, _, _ -> }).close() }
            assertEquals("00:11:22:33:44:77", prefs.getString("lastDeviceAddress", null))
        } finally {
            prefs.edit().apply { before.forEach { (key, value) -> if(value == null) remove(key) else putString(key, value) } }.commit()
        }
    }
}
