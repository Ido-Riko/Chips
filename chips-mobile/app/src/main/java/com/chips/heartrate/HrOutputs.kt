package com.chips.heartrate

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.chips.heartrate.converter.OscSender
import com.chips.heartrate.converter.PeripheralCallback
import com.chips.heartrate.converter.PeripheralManager

internal interface HrRelayOutput {
    fun start()
    fun stop()
    fun source(address: String?)
    fun send(bpm: Int)
    fun clear()
    fun bluetoothChanged(enabled: Boolean)
    fun close()
}

/** Thin adapter around the converter's bonding, subscriptions, advertising recovery and pulse mode. */
@SuppressLint("MissingPermission")
internal class HrRelay(context: Context, band: String, private val status: (String) -> Unit) : HrRelayOutput {
    private var closed = false
    private val handler = Handler(Looper.getMainLooper())
    private var restartAfter = 0L
    private var pendingStart: Runnable? = null
    private val manager = PeripheralManager(context, object : PeripheralCallback {
        override fun onPeripheralDeviceConnected() { if(!closed) status("BLE relay connected") }
        override fun onPeripheralDeviceDisconnected() { if(!closed) status("BLE relay disconnected · advertising for receivers") }
    }, requireBonding = true).apply { bandMacAddress = band }
    override fun start() {
        if(closed) return
        pendingStart?.let { handler.removeCallbacks(it) }
        val remaining = restartAfter - android.os.SystemClock.elapsedRealtime()
        if(remaining > 0) {
            pendingStart = Runnable { pendingStart = null; if(!closed) { manager.startPeripheral(); status("BLE relay enabled · pair your receiver") } }
            handler.postDelayed(pendingStart!!, remaining)
        } else { manager.startPeripheral(); status("BLE relay enabled · pair your receiver") }
    }
    override fun stop() {
        if(closed) return
        pendingStart?.let { handler.removeCallbacks(it) }; pendingStart = null
        manager.stopPeripheral()
        restartAfter = android.os.SystemClock.elapsedRealtime() + 2_100L
        status("BLE relay off · capture continues")
    }
    override fun source(address: String?) { if(address != null) manager.bandMacAddress = address }
    override fun send(bpm: Int) { if(!closed) manager.updateHeartRate(bpm) }
    override fun clear() { if(!closed) manager.updateHeartRate(-1) }
    override fun bluetoothChanged(enabled: Boolean) {
        if(closed) return
        if(enabled) start() else {
            pendingStart?.let { handler.removeCallbacks(it) }; pendingStart = null
            manager.stopPeripheral(force = true)
        }
    }
    override fun close() {
        if(closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        // Preserve graceful central disconnection and its 2-second cleanup fallback.
        try { manager.stopPeripheral() } finally {
            Handler(Looper.getMainLooper()).postDelayed({ manager.cleanup() }, 2_100L)
        }
    }
}

/** Uses the converter's executor, socket recovery and shutdown rather than the storage worker's socket. */
internal class HrOsc(host: String, port: Int, onError: (String) -> Unit = {}) : AutoCloseable {
    private val sender = OscSender(onError = onError)
    init { try { sender.setTarget(host, port) } catch(e: Exception) { sender.close(); throw e } }
    fun send(bpm: Int) { sender.sendHeartRate(bpm) }
    fun clear() { sender.sendHeartRate(-1) }
    override fun close() { sender.close() }
}
