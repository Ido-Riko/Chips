package com.chips.heartrate

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.chips.ui.FeatureRoute
import com.chips.ui.featureIntent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HrOutputConfig(val relay: Boolean = false, val osc: Boolean = false,
    val host: String = "255.255.255.255", val port: Int = 9000)

data class HrCaptureState(val active: Boolean = false, val status: String = "Capture is off", val bpm: Int? = null,
    val received: Long? = null, val session: String? = null, val output: String = "", val stopping: Boolean = false,
    val elapsed: Long? = null, val outputs: HrOutputConfig = HrOutputConfig(),
    val updatingOutputs: Boolean = false, val outputError: String? = null)

/** Explicitly started connected-device foreground service. Closing the screen does not stop capture. */
class HrCaptureService : Service() {
    companion object {
        const val CHANNEL = "heart_rate_capture"
        const val NOTIFICATION_ID = 18013
        const val START = "com.chips.hr.START"
        const val STOP = "com.chips.hr.STOP"
        const val RECONNECT = "com.chips.hr.RECONNECT"
        const val UPDATE_OUTPUTS = "com.chips.hr.UPDATE_OUTPUTS"
        private val mutable = MutableStateFlow(HrCaptureState())
        val state = mutable.asStateFlow()
        internal var transportFactory: ((Context, String, (String) -> Unit, (Int, Long, Long) -> Unit) -> HrTransport)? = null
        internal var relayFactory: ((Context, String, (String) -> Unit) -> HrRelayOutput)? = null
        fun permissions(): Array<String> = if(Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        fun permitted(context: Context) = permissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        fun notificationAvailable(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }
        fun command(context: Context, action: String) { context.startService(Intent(context, HrCaptureService::class.java).setAction(action)) }
    }
    private data class Sample(val bpm: Int, val wall: Long, val elapsed: Long)
    private sealed interface Event {
        data class Measurement(val value: Sample) : Event
        data class Connection(val value: String, val at: Long = System.currentTimeMillis()) : Event
        data class Configure(val config: HrOutputConfig) : Event
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<Event>(256)
    private var reader: HrTransport? = null
    private var relay: HrRelayOutput? = null
    private var osc: HrOsc? = null
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var running = false
    private var ownsState = false
    private var workerStarted = false
    private var shuttingDown = false
    private var ending = "stopped"
    private var offerBeatSaber = false
    private var stoppedAt: Long? = null
    private var ticker: Job? = null
    private var lastWakeRefresh = 0L
    private var cleanupDeadline = 0L
    private var bluetoothReceiverRegistered = false
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if(!running || intent?.action != android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) return
            val value = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, android.bluetooth.BluetoothAdapter.ERROR)
            try {
                when(value) {
                    android.bluetooth.BluetoothAdapter.STATE_OFF -> {
                        reader?.bluetoothChanged(false)
                        relay?.bluetoothChanged(false)
                        connection("Bluetooth is off")
                    }
                    android.bluetooth.BluetoothAdapter.STATE_ON -> {
                        relay?.source(reader?.sourceAddress)
                        if(mutable.value.outputs.relay) relay?.bluetoothChanged(true)
                        reader?.bluetoothChanged(true)
                    }
                }
            } catch (_: SecurityException) {
                mutable.value = mutable.value.copy(status = "Capture stopped: Bluetooth permission was removed.")
                shutdown()
            } catch (_: Exception) { connection("Bluetooth recovery failed. Tap Reconnect.") }
        }
    }
    private val store by lazy { HrStore(this) }
    private val recovery by lazy { HrRecoverySettings(this) }

    override fun onBind(intent: Intent?): IBinder? = null
    @SuppressLint("MissingPermission", "WakelockTimeout")
    override fun onStartCommand(request: Intent?, flags: Int, startId: Int): Int {
        val recovering = request == null
        val intent = request ?: recovery.restart(this) ?: run { stopSelf(); return START_NOT_STICKY }
        when(intent.action) {
            STOP -> {
                val expected = intent.getStringExtra("session")
                if(running && expected != null && expected != mutable.value.session) return START_STICKY
                offerBeatSaber = running
                shutdown()
            }
            RECONNECT -> { if(running) reader?.reconnect() else stopSelf() }
            UPDATE_OUTPUTS -> {
                if(running) {
                    val config = outputConfig(intent)
                    val error = validateOutputs(config)
                    if(error != null) mutable.update { it.copy(outputError = error) }
                    else {
                        mutable.update { it.copy(updatingOutputs = true, outputError = null) }
                        enqueue(Event.Configure(config))
                    }
                } else stopSelf()
            }
            START -> {
                if(running) return START_STICKY
                if(mutable.value.stopping) { stopSelf(); return START_NOT_STICKY }
                ownsState = true
                val address = intent.getStringExtra("device").orEmpty()
                if(!permitted(this) || !notificationAvailable(this) || !android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) {
                    recovery.clear()
                    mutable.value = HrCaptureState(status = "Capture stopped: allow nearby devices and notifications, then select your band.")
                    stopSelf(); return START_NOT_STICKY
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Heart-rate capture", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while heart-rate capture is on, including reconnects."
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                })
                if(!notificationAvailable(this)) {
                    mutable.value = HrCaptureState(status = "Capture stopped: enable the Heart-rate capture notification channel.")
                    stopSelf(); return START_NOT_STICKY
                }
                mutable.value = HrCaptureState(active = true, status = "Starting capture")
                try {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), if(Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
                    running = true
                    wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CHIPS:HrCapture").apply {
                        setReferenceCounted(false); acquire(10 * 60_000L)
                    }
                    lastWakeRefresh = SystemClock.elapsedRealtime()
                } catch (_: Exception) {
                    mutable.value = HrCaptureState(status = "Capture stopped: check Bluetooth and notification permissions.")
                    shutdown(); return START_NOT_STICKY
                }
                val initialOutputs = outputConfig(intent)
                mutable.update { it.copy(updatingOutputs = true) }
                workerStarted = true
                scope.launch {
                    var id: String? = null
                    val pending = mutableListOf<HrReading>()
                    fun flush() {
                        if(pending.isNotEmpty()) { store.samples(id!!, pending); pending.clear() }
                    }
                    try {
                        store.recoverInterrupted()
                        id = if(recovering) recovery.session()?.let { store.resume(it, address) } ?: store.begin(address) else store.begin(address)
                        withContext(Dispatchers.Main) {
                            if(running) {
                                recovery.save(id!!, address, initialOutputs)
                                mutable.value = mutable.value.copy(session = id)
                                reader = (transportFactory?.invoke(this@HrCaptureService, address, ::connection, ::measurement)
                                    ?: HrBleReader(this@HrCaptureService, address, ::connection, ::measurement))
                                ContextCompat.registerReceiver(this@HrCaptureService, bluetoothReceiver,
                                    IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
                                bluetoothReceiverRegistered = true
                                reader?.start()
                            }
                        }
                        applyOutputs(initialOutputs, address)
                        var lastFlush = SystemClock.elapsedRealtime()
                        while(true) {
                            val received = withTimeoutOrNull(2_000) { events.receiveCatching() }
                            if(received == null) { flush(); lastFlush = SystemClock.elapsedRealtime(); continue }
                            val event = received.getOrNull() ?: break
                            when(event) {
                                is Event.Measurement -> {
                                    val s = event.value
                                    pending += HrReading(s.bpm, s.wall, s.elapsed)
                                    try { osc?.send(s.bpm) } catch (_: Exception) { output("OSC send failed; capture continues") }
                                }
                                is Event.Connection -> {
                                    flush()
                                    store.event(id, event.value, event.at)
                                    osc?.clear()
                                }
                                is Event.Configure -> { flush(); applyOutputs(event.config, address) }
                            }
                            if(pending.size >= 32 || SystemClock.elapsedRealtime() - lastFlush >= 2_000) { flush(); lastFlush = SystemClock.elapsedRealtime() }
                        }
                    } catch (_: Exception) {
                        ending = "error"
                        withContext(Dispatchers.Main) {
                            mutable.value = mutable.value.copy(status = "Capture stopped: could not save or read HR. Saved samples are retained.")
                            shutdown()
                        }
                    } finally {
                        runCatching { flush() }
                        id?.let { runCatching {
                            store.end(it, ending, stoppedAt ?: System.currentTimeMillis(), offerBeatSaber = offerBeatSaber)
                            com.chips.data.ActivitySyncWorker.schedule(this@HrCaptureService)
                        } }
                        runCatching { osc?.close() }; osc = null
                        runCatching { store.close() }
                        delay((cleanupDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        withContext(Dispatchers.Main) {
                            mutable.value = mutable.value.copy(stopping = false, session = null)
                            finishShutdown()
                        }
                        scope.cancel()
                    }
                }
                ticker = CoroutineScope(Dispatchers.Main).launch {
                    while(running) {
                        delay(5_000)
                        if(!permitted(this@HrCaptureService) || !notificationAvailable(this@HrCaptureService)) {
                            mutable.value = mutable.value.copy(status = "Capture stopped: Bluetooth or notifications were disabled.")
                            shutdown(); break
                        }
                        updateWakeLock()
                        if(mutable.value.elapsed?.let { SystemClock.elapsedRealtime() - it >= 15_000 } == true) relay?.clear()
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                    }
                }
            }
            else -> stopSelf()
        }
        return if(running && !shuttingDown) START_STICKY else START_NOT_STICKY
    }
    private fun output(value: String) { if(running) mutable.update { it.copy(output = value) } }
    private fun enqueue(event: Event): Boolean {
        if(!running) return false
        if(events.trySend(event).isFailure) {
            ending = "error"
            mutable.value = mutable.value.copy(status = "Capture stopped: storage could not keep up.")
            shutdown()
            return false
        }
        return true
    }
    private fun connection(value: String) {
        if(!running) return
        mutable.value = mutable.value.copy(status = value, bpm = null, received = null, elapsed = null)
        relay?.source(reader?.sourceAddress)
        updateWakeLock()
        relay?.clear()
        if(!enqueue(Event.Connection(value))) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }
    private fun measurement(bpm: Int, wall: Long, elapsed: Long) {
        if(!running || bpm !in 1..65535) return
        if(!enqueue(Event.Measurement(Sample(bpm, wall, elapsed)))) return
        mutable.value = mutable.value.copy(status = "Receiving HR", bpm = bpm, received = wall, elapsed = elapsed)
        if(mutable.value.outputs.relay) relay?.send(bpm)
    }
    private fun outputConfig(intent: Intent) = HrOutputConfig(intent.getBooleanExtra("relay", false),
        intent.getBooleanExtra("osc", false), intent.getStringExtra("host")?.trim() ?: "255.255.255.255",
        intent.getIntExtra("port", 9000))
    private fun validateOutputs(config: HrOutputConfig): String? {
        if(config.osc && (config.host.isBlank() || config.port !in 1..65535)) return "Enter an OSC host and port from 1 to 65535."
        if(config.relay && Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
            return "Allow Bluetooth advertising to enable the BLE relay. Capture continues."
        return null
    }
    /** Runs in the existing IO actor so output changes cannot race sample sends or Stop. */
    private suspend fun applyOutputs(config: HrOutputConfig, selectedAddress: String) {
        if(!running) return
        var candidate: HrOsc? = null
        try {
            validateOutputs(config)?.let { throw IllegalArgumentException(it) }
            val previous = mutable.value.outputs
            val replace = config.osc && (osc == null || previous.host != config.host || previous.port != config.port)
            if(replace) candidate = HrOsc(config.host, config.port, ::output)
            val applied = withContext(Dispatchers.Main) {
                if(!running) false else {
                    if(config.relay != previous.relay) {
                        if(config.relay) {
                            if(relay == null) relay = relayFactory?.invoke(this@HrCaptureService, reader?.sourceAddress ?: selectedAddress, ::output)
                                ?: HrRelay(this@HrCaptureService, reader?.sourceAddress ?: selectedAddress, ::output)
                            relay?.start()
                        } else relay?.stop()
                    }
                    true
                }
            }
            if(!applied) return
            if(!config.osc || replace) {
                osc?.close()
                osc = candidate
                candidate = null
            }
            if(!running) return
            getSharedPreferences("heart_rate", MODE_PRIVATE).edit {
                putBoolean("relay", config.relay); putBoolean("osc", config.osc)
                putString("host", config.host); putInt("port", config.port)
            }
            withContext(Dispatchers.Main) {
                if(running) {
                    recovery.outputs(config)
                    mutable.update { it.copy(outputs = config, updatingOutputs = false, outputError = null) }
                }
            }
        } catch (_: Exception) {
            // Preserve the prior OSC sender/configuration if a replacement fails.
            withContext(Dispatchers.Main) {
                if(running) {
                    recovery.outputs(mutable.value.outputs)
                    mutable.update { it.copy(updatingOutputs = false, outputError = "Could not apply outputs. Capture continues. Check permissions, Bluetooth and OSC target.") }
                }
            }
        } finally { candidate?.close() }
    }
    private fun notification(): Notification {
        val current = mutable.value
        val fresh = current.elapsed?.let { SystemClock.elapsedRealtime() - it < 15_000 } == true
        val text = if(fresh) "${current.bpm} bpm · saving on this phone" else if(current.received != null) "No recent HR · check band connection" else current.status
        fun action(action: String) = PendingIntent.getService(this, action.hashCode(), Intent(this, HrCaptureService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getActivity(this, current.session?.hashCode() ?: STOP.hashCode(),
            Intent(this, HrStopActivity::class.java).putExtra("session", current.session), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.chips.R.drawable.ic_hr_notification)
            .setContentTitle(if(current.stopping) "CHIPS · stopping HR capture" else "CHIPS · HR capture is on").setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 18013, featureIntent(this, FeatureRoute.HEART_RATE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Reconnect", action(RECONNECT)).addAction(0, "Stop", stop).build()
    }
    private fun shutdown(interrupted: Boolean = false) {
        if(shuttingDown) return
        shuttingDown = true
        stoppedAt = System.currentTimeMillis()
        running = false
        if(interrupted) ending = "interrupted" else recovery.clear()
        val hardwareDelay = maxOf(reader?.shutdownDelayMillis ?: 0L, if(relay != null) 2_100L else 0L)
        cleanupDeadline = SystemClock.elapsedRealtime() + hardwareDelay
        runCatching { reader?.close() }; reader = null
        runCatching { relay?.close() }; relay = null
        if(bluetoothReceiverRegistered) { runCatching { unregisterReceiver(bluetoothReceiver) }; bluetoothReceiverRegistered = false }
        ticker?.cancel(); ticker = null
        events.close() // drain accepted samples before ending the session
        if(ownsState) {
            val old = mutable.value
            mutable.value = HrCaptureState(status = if(old.status.startsWith("Capture stopped")) old.status else "Capture is off",
                stopping = workerStarted, session = if(workerStarted) old.session else null)
        }
        if(workerStarted) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        else finishShutdown()
    }
    @SuppressLint("WakelockTimeout")
    private fun updateWakeLock() {
        if(!running) return
        if(reader?.needsWakeLock == false) {
            if(wake?.isHeld == true) wake?.release()
        } else if(wake?.isHeld != true || SystemClock.elapsedRealtime() - lastWakeRefresh >= 5 * 60_000L) {
            wake?.acquire(10 * 60_000L)
            lastWakeRefresh = SystemClock.elapsedRealtime()
        }
    }
    private fun finishShutdown() {
        if(wake?.isHeld == true) wake?.release()
        wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() { shutdown(interrupted = !shuttingDown && running); super.onDestroy() }
}
