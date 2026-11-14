package com.chips.heartrate

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

internal interface HrScan {
    // Resolving a device name can call Bluetooth's binder service as well.
    fun start(result: (() -> HrDevice?) -> Unit, failed: (Int) -> Unit)
    fun stop()
}

@SuppressLint("MissingPermission")
private class AndroidHrScan(private val context: Context) : HrScan {
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    override fun start(result: (() -> HrDevice?) -> Unit, failed: (Int) -> Unit) {
        val scan = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: error("Turn on Bluetooth to find your band.")
        val listener = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, scan: ScanResult) {
                result {
                    val name = scan.scanRecord?.deviceName ?: scan.device.name.orEmpty()
                    if(scan.scanRecord?.serviceUuids?.any { it.uuid == HR_SERVICE } == true ||
                        name.contains("Xiaomi", true) || name.contains("Mi Band", true) || name.contains("Smart Band", true))
                        HrDevice(scan.device.address, name.ifBlank { "Heart-rate device" }) else null
                }
            }
            override fun onScanFailed(errorCode: Int) { failed(errorCode) }
        }
        scanner = scan
        callback = listener
        scan.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), listener)
    }
    override fun stop() {
        try { callback?.let { scanner?.stopScan(it) } }
        finally { callback = null; scanner = null }
    }
}

/** Picker only. All Bluetooth binder calls serialize off the UI thread, including cleanup. */
internal class HrDiscovery(
    context: Context,
    private val result: (HrDevice) -> Unit,
    private val status: (String) -> Unit,
    private val scanFactory: () -> HrScan = { AndroidHrScan(context.applicationContext) },
    private val timeoutMillis: Long = 20_000
) {
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "CHIPS-HR-picker").apply { isDaemon = true } }
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    @Volatile private var active = false
    @Volatile private var disposed = false
    private var timeout: Runnable? = null // main thread
    private var scan: HrScan? = null // worker thread

    private fun current(token: Long) = !disposed && active && token == generation
    private fun execute(action: () -> Unit) {
        try { worker.execute(action) }
        catch (_: RejectedExecutionException) { /* A callback raced screen disposal. */ }
    }
    private fun stopScan() {
        val previous = scan
        scan = null
        runCatching { previous?.stop() }
    }
    private fun finish(token: Long, message: String) {
        if(!current(token)) return
        close()
        status(message)
    }
    fun start() {
        if(disposed) return
        close()
        val token = ++generation
        active = true
        status("Looking for HR devices…")
        timeout = Runnable { finish(token, "Scan finished. Select your band, or enable Share HR and scan again.") }
        handler.postDelayed(timeout!!, timeoutMillis)
        execute {
            if(!current(token)) return@execute
            try {
                val next = scanFactory()
                scan = next
                next.start({ resolve ->
                    if(current(token)) execute {
                        if(current(token)) try {
                            val device = resolve()
                            if(device != null) handler.post { if(current(token)) result(device) }
                        } catch (_: SecurityException) {
                            handler.post { finish(token, "Allow nearby devices to find your band.") }
                        } catch (_: Exception) { /* Ignore an unreadable advertisement. */ }
                    }
                }, { code -> handler.post { finish(token, "Scan failed ($code). Try again.") } })
            } catch (_: SecurityException) {
                handler.post { finish(token, "Allow nearby devices to find your band.") }
            } catch (e: Exception) {
                handler.post { finish(token, e.message ?: "Bluetooth scan unavailable") }
            } finally {
                // Stop/dispose may have happened while startScan was blocked inside Android.
                if(!current(token)) stopScan()
            }
        }
    }
    fun close() {
        if(disposed) return
        active = false
        generation++
        timeout?.let { handler.removeCallbacks(it) }; timeout = null
        execute { stopScan() }
    }
    fun dispose() {
        if(disposed) return
        close() // enqueue cleanup before shutdown; never cancel a pending radio cleanup
        disposed = true
        handler.removeCallbacksAndMessages(null)
        worker.shutdown()
    }
}
