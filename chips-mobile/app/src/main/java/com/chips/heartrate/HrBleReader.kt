package com.chips.heartrate

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import com.chips.heartrate.converter.BleConnectionManager
import com.chips.heartrate.converter.ConnectionStateMachine
import java.util.UUID

internal val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
internal val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
internal val HR_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

internal interface HrTransport {
    fun start()
    fun reconnect()
    fun close()
    fun bluetoothChanged(enabled: Boolean) {}
    val sourceAddress: String? get() = null
    val needsWakeLock: Boolean get() = true
    val shutdownDelayMillis: Long get() = 0L
}

/** Thin CHIPS adapter. Connection/retry/scan/unsubscribe behavior lives in the ported converter. */
@SuppressLint("MissingPermission")
internal class HrBleReader(context: Context, address: String,
    private val state: (String) -> Unit, private val sample: (Int, Long, Long) -> Unit) : HrTransport {
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private val preferences = context.getSharedPreferences("miband_converter", Context.MODE_PRIVATE).apply {
        // Seed a deliberate picker change; otherwise preserve the converter's successful
        // fallback address, rather than overwriting it with an older picker snapshot.
        if(getString("selectedDeviceAddress", null) != address) edit {
            putString("selectedDeviceAddress", address)
            putString("lastDeviceAddress", address)
        }
    }
    private val manager = BleConnectionManager(context, preferences, object : BleConnectionManager.BleConnectionCallback {
        override fun onConnectionStateChanged(state: ConnectionStateMachine.State) {
            if(!closed) this@HrBleReader.state(when(state) {
                ConnectionStateMachine.State.DISCONNECTED -> "Band disconnected"
                ConnectionStateMachine.State.SCANNING -> "Scanning for band"
                ConnectionStateMachine.State.CONNECTING -> "Connecting · ${sourceAddress.orEmpty()}"
                ConnectionStateMachine.State.CONNECTED -> "Connected · ${sourceAddress.orEmpty()}"
                ConnectionStateMachine.State.DISCONNECTING -> "Disconnecting"
            })
        }
        override fun onHeartRateReceived(hr: Int) {
            // The converter's negative sentinel is connection state, never a stored measurement.
            if(!closed && hr < 0) state("Band disconnected")
        }
        override fun onHeartRateSample(hr: Int, received: Long, elapsed: Long) {
            if(!closed) sample(hr, received, elapsed)
        }
        override fun onError(message: String) { if(!closed) state(message) }
    })
    override val sourceAddress: String? get() = manager.bandMacAddress
    override val needsWakeLock: Boolean get() = manager.getCurrentState() in setOf(
        ConnectionStateMachine.State.CONNECTING, ConnectionStateMachine.State.CONNECTED,
        ConnectionStateMachine.State.DISCONNECTING)
    override val shutdownDelayMillis: Long get() = 1_100L
    override fun start() { if(!closed) manager.startScan() }
    override fun reconnect() { if(!closed) manager.reconnect() }
    override fun bluetoothChanged(enabled: Boolean) {
        if(closed) return
        if(enabled) manager.reconnect() else manager.disconnect()
    }
    override fun close() {
        if(closed) return
        closed = true
        // Retain the converter's CCCD unsubscribe and disconnect timeout, then release GATT.
        try { manager.disconnect() } finally { handler.postDelayed({ manager.close() }, 1_100L) }
    }
}
