// Ported from Ido-Riko/MiBand-Converter @ a472d64a6743454db0b63c902684be36426f2ff9.
// Owner-authorized reuse. CHIPS changes are listed in docs/HEART_RATE.md.
// Fixed: app/src/main/java/com/mibandconverter/BleConnectionManager.kt
package com.chips.heartrate.converter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.chips.heartrate.HrProtocol
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.edit
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages BLE connection to Mi Band and HR data reading
 */
class BleConnectionManager(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val callback: BleConnectionCallback
) {
    companion object {
        private const val TAG = "BleConnectionManager"

        private const val PREF_LAST_DEVICE_ADDRESS = "lastDeviceAddress"
        private const val PREF_LAST_DEVICE_NAME = "lastDeviceName"

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val FIRST_CONNECT_TIMEOUT_MS = 3_000L  // NEW: Shorter timeout for first attempt
        private const val RECONNECT_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val HR_UPDATE_THROTTLE_MS = 500L
        private const val SCAN_TIMEOUT_MS = 30_000L

        private val HR_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HR_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    interface BleConnectionCallback {
        fun onConnectionStateChanged(state: ConnectionStateMachine.State)
        fun onHeartRateReceived(hr: Int)
        // CHIPS: receive every valid sample before the original UI/output filtering.
        fun onHeartRateSample(hr: Int, received: Long, elapsed: Long) {}
        fun onError(message: String)
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter
    private val handler = Handler(Looper.getMainLooper())

    private val stateMachine = ConnectionStateMachine()
    private var bluetoothGatt: BluetoothGatt? = null
    private var targetDeviceAddress: String? = null
    private var reconnectAttempts = 0
    private var connectTimeoutRunnable: Runnable? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var pendingReconnectRunnable: Runnable? = null
    private var lastHrUpdateTime = 0L
    private var lastReportedHr = -1
    private var disconnectRequested = false
    private var subscribed = false
    private var connectionGeneration = 0

    private val isClosed = AtomicBoolean(false)
    private val isScanning = AtomicBoolean(false)
    private val connectionSucceeded = AtomicBoolean(false)

    var bandMacAddress: String? = null
        private set

    init {
        stateMachine.addListener(object : ConnectionStateMachine.StateChangeListener {
            override fun onStateChanged(
                oldState: ConnectionStateMachine.State,
                newState: ConnectionStateMachine.State,
                event: ConnectionStateMachine.Event
            ) {
                callback.onConnectionStateChanged(newState)
            }
        })
    }

    fun getCurrentState(): ConnectionStateMachine.State = stateMachine.getCurrentState()

    fun getReconnectAttempts(): Int = reconnectAttempts

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun startScan() {
        if (isClosed.get()) {
            Log.w(TAG, "Cannot scan - manager closed")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            callback.onError("Bluetooth is OFF")
            return
        }

        val currentState = stateMachine.getCurrentState()
        if (currentState == ConnectionStateMachine.State.SCANNING) {
            Log.d(TAG, "Already scanning")
            return
        }

        if (currentState == ConnectionStateMachine.State.CONNECTING) {
            Log.d(TAG, "Already connecting")
            return
        }

        if (currentState == ConnectionStateMachine.State.CONNECTED) {
            Log.d(TAG, "Already connected")
            return
        }

        disconnectRequested = false

        // Cancel any pending reconnects and reset counter when user manually scans
        pendingReconnectRunnable?.let {
            handler.removeCallbacks(it)
            pendingReconnectRunnable = null
        }
        reconnectAttempts = 0

        // Try to reconnect to last device first
        val lastDevice = getLastConnectedDevice()
        if (lastDevice != null) {
            val (address, name) = lastDevice

            // Check if address is valid (not null or empty)
            if (address.isNullOrBlank()) {
                Log.w(TAG, "Last device address is invalid, starting scan")
                startBleScanInternal()
                return
            }

            Log.d(TAG, "Attempting direct reconnect to: ${name ?: "Unknown"} ($address)")

            try {
                val device = bluetoothAdapter.getRemoteDevice(address)
                targetDeviceAddress = address
                bandMacAddress = address

                // FIXED: Start direct connection immediately without delay
                // The previous 200ms delay was unnecessary and caused confusion
                if (!isClosed.get() && stateMachine.getCurrentState() == ConnectionStateMachine.State.DISCONNECTED) {
                    connectToDevice(device)
                }

                // Fallback to scan if direct connection fails
                handler.postDelayed({
                    val currentState = stateMachine.getCurrentState()
                    if (!isClosed.get() && currentState != ConnectionStateMachine.State.CONNECTED) {
                        Log.i(TAG, "Direct reconnect did not succeed within 4s (state=$currentState), ensuring scan is running")
                        
                        // If we are not scanning yet, force a scan now
                        if (currentState != ConnectionStateMachine.State.SCANNING) {
                            Log.d(TAG, "Forcing scan from startScan fallback")
                            cancelConnectTimeout()
                            closeGatt()
                            // Force state to DISCONNECTED to allow START_SCAN transition
                            stateMachine.forceState(ConnectionStateMachine.State.DISCONNECTED, "manual scan fallback")
                            startBleScanInternal()
                        }
                    }
                }, 4_000L) // 4s allows the 3s first-attempt timeout to fire first

                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reconnect to last device: ${e.message}")
            }
        }

        startBleScanInternal()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startBleScanInternal() {
        if (!stateMachine.transition(ConnectionStateMachine.Event.START_SCAN)) {
            Log.w(TAG, "Cannot start scan in current state: ${stateMachine.getCurrentState()}")
            return
        }

        if (isScanning.getAndSet(true)) {
            Log.w(TAG, "Scan already in progress")
            return
        }

        Log.i(TAG, "Starting BLE scan for Mi-Band...")

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner is null")
            isScanning.set(false)
            stateMachine.transition(ConnectionStateMachine.Event.SCAN_FAILED)
            callback.onError("BLE scanner not available")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
            Log.d(TAG, "BLE scan started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan: ${e.message}", e)
            isScanning.set(false)
            stateMachine.transition(ConnectionStateMachine.Event.SCAN_FAILED)
            callback.onError("Failed to start scan: ${e.message}")
            return
        }

        // Scan timeout
        cancelScanTimeout()
        scanTimeoutRunnable = Runnable {
            if (isScanning.get() && stateMachine.getCurrentState() == ConnectionStateMachine.State.SCANNING) {
                Log.w(TAG, "Scan timeout - no device found")
                stopScan()
                stateMachine.transition(ConnectionStateMachine.Event.SCAN_TIMEOUT)
                callback.onError("Scan timeout - no Mi-Band found")
                resumeAfterScanFailure()
            }
        }
        handler.postDelayed(scanTimeoutRunnable!!, SCAN_TIMEOUT_MS)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun stopScan() {
        if (!isScanning.getAndSet(false)) {
            return
        }

        cancelScanTimeout()

        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
            Log.d(TAG, "BLE scan stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}", e)
        }
    }

    private fun cancelScanTimeout() {
        scanTimeoutRunnable?.let {
            handler.removeCallbacks(it)
            scanTimeoutRunnable = null
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (stateMachine.getCurrentState() != ConnectionStateMachine.State.SCANNING) {
                return
            }

            val device = result.device
            val name = device.name ?: ""

            val isTargetDevice = (targetDeviceAddress != null && device.address == targetDeviceAddress) ||
                    name.contains("Xiaomi", ignoreCase = true) ||
                    name.contains("Mi Band", ignoreCase = true) ||
                    name.contains("Mi Smart Band", ignoreCase = true)

            if (isTargetDevice) {
                Log.i(TAG, "✅ Found Mi-Band: name='$name' address=${device.address} rssi=${result.rssi}")

                stopScan()

                targetDeviceAddress = device.address
                bandMacAddress = device.address

                stateMachine.transition(ConnectionStateMachine.Event.DEVICE_FOUND)

                // Connect immediately without delay
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            isScanning.set(false)
            cancelScanTimeout()
            stateMachine.transition(ConnectionStateMachine.Event.SCAN_FAILED)

            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error: $errorCode"
            }
            callback.onError("Scan failed: $errorMsg")
            resumeAfterScanFailure()
        }
    }

    private var unsubscribeRunnable: Runnable? = null

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    fun disconnect() {
        if (isClosed.get()) return
        disconnectRequested = true

        val currentState = stateMachine.getCurrentState()
        Log.i(TAG, "Disconnect requested (Current state: $currentState)")

        // Stop all future work
        handler.removeCallbacksAndMessages(null)
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS // Prevent auto-reconnect
        cancelConnectTimeout()

        // Stop scanning if active
        if (isScanning.get()) {
            stopScan()
        }

        // Check if we can perform a graceful disconnect (unsubscribe first)
        if (currentState == ConnectionStateMachine.State.CONNECTED && bluetoothGatt != null) {
             stateMachine.transition(ConnectionStateMachine.Event.DISCONNECT_REQUESTED)
             
             // Try to unsubscribe
             if (gracefulUnsubscribe()) {
                 Log.d(TAG, "Attempting graceful unsubscribe before disconnect...")
                 // Set a safety timeout to force disconnect if unsubscribe hangs
                 unsubscribeRunnable = Runnable {
                     Log.w(TAG, "Graceful unsubscribe timed out, forcing disconnect")
                     forceDisconnect()
                 }
                 handler.postDelayed(unsubscribeRunnable!!, 1000L)
                 return
             }
        }

        forceDisconnect()
    }
    
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun gracefulUnsubscribe(): Boolean {
        try {
            val gatt = bluetoothGatt ?: return false
            val service = gatt.getService(HR_SERVICE_UUID) ?: return false
            val characteristic = service.getCharacteristic(HR_MEASUREMENT_UUID) ?: return false
            
            // 1. Disable local notifications
            gatt.setCharacteristicNotification(characteristic, false)
            
            // 2. Disable remote notifications (CCCD)
            val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID) ?: return false
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            return gatt.writeDescriptor(descriptor)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during graceful unsubscribe: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing graceful unsubscribe: ${e.message}")
            return false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun forceDisconnect() {
        unsubscribeRunnable?.let { handler.removeCallbacks(it) }
        unsubscribeRunnable = null

        val currentState = stateMachine.getCurrentState()
        if (currentState == ConnectionStateMachine.State.DISCONNECTED) return

        if (bluetoothGatt != null) {
            if (currentState != ConnectionStateMachine.State.DISCONNECTING && currentState != ConnectionStateMachine.State.DISCONNECTED) {
                 // Verify we haven't already transitioned from disconnect()
                 if (stateMachine.getCurrentState() != ConnectionStateMachine.State.DISCONNECTING) {
                     stateMachine.transition(ConnectionStateMachine.Event.DISCONNECT_REQUESTED)
                 }
            }
            
            try {
                bluetoothGatt?.disconnect()
                Log.d(TAG, "GATT disconnect() called.")
            } catch (e: SecurityException) {
                 Log.e(TAG, "SecurityException during GATT disconnect: ${e.message}")
                 closeGatt()
                 stateMachine.forceState(ConnectionStateMachine.State.DISCONNECTED, "security exception on disconnect")
            } catch (e: Exception) {
                Log.e(TAG, "Error during GATT disconnect: ${e.message}")
                // If disconnect fails, force close
                closeGatt()
                stateMachine.forceState(ConnectionStateMachine.State.DISCONNECTED, "force close on error")
            }
        } else {
             // If no GATT, we are already disconnected
             stateMachine.transition(ConnectionStateMachine.Event.DISCONNECTED)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToDevice(device: BluetoothDevice, isReconnect: Boolean = false) {
        if (isClosed.get()) return

        val currentState = stateMachine.getCurrentState()

        if (currentState == ConnectionStateMachine.State.CONNECTED || currentState == ConnectionStateMachine.State.CONNECTING) {
            if (bluetoothGatt?.device?.address == device.address) {
                Log.d(TAG, "Already connecting or connected to the same device.")
                return
            }
        }

        Log.i(TAG, "Connecting to device: ${device.address} (isReconnect=$isReconnect currentState=$currentState)")

        // Always close previous connection before starting a new one
        closeGatt()
        val generation = connectionGeneration

        handler.postDelayed({
            if (!isClosed.get() && generation == connectionGeneration && !disconnectRequested) {
                continueConnectionAttempt(device, isReconnect)
            }
        }, 300) // Delay to allow resources to be freed
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun continueConnectionAttempt(device: BluetoothDevice, isReconnect: Boolean) {
        if (isClosed.get()) return

        val currentState = stateMachine.getCurrentState()

        // Transition to connecting state
        if (currentState == ConnectionStateMachine.State.SCANNING ||
            currentState == ConnectionStateMachine.State.DISCONNECTED) {
            if (!stateMachine.transition(ConnectionStateMachine.Event.CONNECTION_INITIATED)) {
                Log.e(TAG, "❌ Cannot transition to connecting state from $currentState")
                stateMachine.forceState(ConnectionStateMachine.State.CONNECTING, "transition failed")
            }
        }

        // Reset connection success flag
        connectionSucceeded.set(false)

        // Connection timeout - use shorter timeout for first attempt
        cancelConnectTimeout()

        // NEW: Use 3 seconds for first attempt (wake-up), 15 seconds for retries
        val timeoutMs = if (reconnectAttempts == 0 && !isReconnect) {
            FIRST_CONNECT_TIMEOUT_MS
        } else {
            CONNECT_TIMEOUT_MS
        }

        Log.d(TAG, "Setting connection timeout: ${timeoutMs}ms (reconnectAttempts=$reconnectAttempts)")

        connectTimeoutRunnable = Runnable {
            if (!connectionSucceeded.get() &&
                stateMachine.getCurrentState() == ConnectionStateMachine.State.CONNECTING) {

                // Log differently for first attempt vs retries
                if (reconnectAttempts == 0) {
                    Log.i(TAG, "⏰ First connection attempt timed out (wake-up phase) - retrying immediately")
                } else {
                    Log.e(TAG, "⏰ Connection timeout after ${timeoutMs}ms - no callback received")
                }

                try {
                    handleConnectionFailure(device, "Connection timeout - no GATT callback")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException handling timeout: ${e.message}")
                }
            } else {
                Log.d(TAG, "Connection timeout fired but state is ${stateMachine.getCurrentState()}, ignoring")
            }
        }
        handler.postDelayed(connectTimeoutRunnable!!, timeoutMs)

        // Attempt connection
        try {
            Log.d(TAG, "Calling device.connectGatt()...")
            bluetoothGatt = device.connectGatt(
                context,
                false, // autoConnect = false for faster connection
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                handler // CHIPS: serialize GATT callbacks with original handler timers.
            )

            if (bluetoothGatt == null) {
                Log.e(TAG, "❌ connectGatt returned null")
                handleConnectionFailure(device, "connectGatt returned null")
                return
            }

            Log.d(TAG, "connectGatt() called successfully, waiting for callback...")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException during connectGatt: ${e.message}")
            handleConnectionFailure(device, "Security Exception: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception during connectGatt: ${e.message}", e)
            handleConnectionFailure(device, "Exception: ${e.message}")
        }

        if (!isReconnect) {
            reconnectAttempts = 0
        }
    }

    private fun cancelConnectTimeout() {
        connectTimeoutRunnable?.let {
            handler.removeCallbacks(it)
            connectTimeoutRunnable = null
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    private fun handleConnectionFailure(device: BluetoothDevice, reason: String) {
        if (isClosed.get()) return

        Log.w(TAG, "❌ Connection failed: $reason")

        cancelConnectTimeout()

        // Always close GATT on failure
        closeGatt()

        if (!stateMachine.transition(ConnectionStateMachine.Event.CONNECTION_FAILED)) {
            // Force state if transition fails
            stateMachine.forceState(ConnectionStateMachine.State.DISCONNECTED, "connection failed")
        }

        callback.onHeartRateReceived(-1) // Clear HR with sentinel value
        lastReportedHr = -1

        // Schedule reconnect
        scheduleReconnect(device)
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    private fun scheduleReconnect(device: BluetoothDevice) {
        if (isClosed.get() || disconnectRequested) return

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnection attempts reached ($MAX_RECONNECT_ATTEMPTS)")
            callback.onError("Max reconnection attempts reached. Please scan again.")
            bandMacAddress = null  // Clear on final failure
            return
        }

        // Cancel any existing reconnect
        pendingReconnectRunnable?.let {
            handler.removeCallbacks(it)
        }

        reconnectAttempts++

        // If direct reconnect fails multiple times, fall back to scanning
        // This helps if the device MAC changed or the stack is in a weird state
        if (reconnectAttempts == 3) {
            Log.i(TAG, "🔄 Direct reconnect failed 3 times, falling back to full scan...")
            if (!isClosed.get() && stateMachine.getCurrentState() == ConnectionStateMachine.State.DISCONNECTED) {
                startBleScanInternal()
            }
            return
        }

        // NEW: Use immediate retry for first attempt (wake-up failed),
        // then exponential backoff for subsequent attempts
        val delay = if (reconnectAttempts == 1) {
            300L  // Almost immediate retry after wake-up attempt
        } else {
            (RECONNECT_BASE_DELAY_MS * reconnectAttempts).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        }

        Log.i(TAG, "🔄 Scheduling reconnect #$reconnectAttempts in ${delay}ms")

        pendingReconnectRunnable = Runnable {
            if (!isClosed.get() && stateMachine.getCurrentState() == ConnectionStateMachine.State.DISCONNECTED) {
                val reconnectDevice = targetDeviceAddress?.let {
                    try {
                        bluetoothAdapter.getRemoteDevice(it)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting device for reconnect: ${e.message}")
                        device
                    }
                } ?: device

                connectToDevice(reconnectDevice, isReconnect = true)
            }
            pendingReconnectRunnable = null
        }

        handler.postDelayed(pendingReconnectRunnable!!, delay)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun closeGatt() {
        connectionGeneration++
        subscribed = false
        if (bluetoothGatt == null) return
        Log.d(TAG, "Closing GATT connection")
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT: ${e.message}")
        }
        bluetoothGatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (isClosed.get() || gatt !== bluetoothGatt) return

            val stateStr = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                else -> "UNKNOWN($newState)"
            }

            Log.i(TAG, "onConnectionStateChange: status=$status newState=$stateStr device=${gatt.device.address}")

            cancelConnectTimeout()

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Connected but with error status: $status")
                        handleConnectionFailure(gatt.device, "GATT status: $status")
                        return
                    }

                    Log.i(TAG, "✅ STATE_CONNECTED with GATT_SUCCESS")

                    connectionSucceeded.set(true)
                    bluetoothGatt = gatt
                    // Retry budget resets only after subscription succeeds.

                    if (!stateMachine.transition(ConnectionStateMachine.Event.CONNECTED)) {
                        Log.w(TAG, "Failed to transition to CONNECTED state")
                        stateMachine.forceState(ConnectionStateMachine.State.CONNECTED, "onConnectionStateChange")
                    }

                    saveLastConnectedDevice(gatt.device)
                    connectTimeoutRunnable = Runnable {
                        if (!isClosed.get() && gatt === bluetoothGatt && !subscribed) {
                            handleConnectionFailure(gatt.device, "HR subscription timed out")
                        }
                    }
                    handler.postDelayed(connectTimeoutRunnable!!, CONNECT_TIMEOUT_MS)

                    // Start service discovery - reduced delay from 600ms to 400ms
                    Log.d(TAG, "Starting service discovery...")
                    handler.postDelayed({
                        if (!isClosed.get() && gatt === bluetoothGatt) {
                            var discoveryStarted = false
                            try {
                                discoveryStarted = gatt.discoverServices()
                                Log.d(TAG, "discoverServices() returned: $discoveryStarted")
                            } catch (e: SecurityException) {
                                Log.e(TAG, "❌ SecurityException starting discovery: ${e.message}")
                                discoveryStarted = false
                            }

                            if (!discoveryStarted) {
                                Log.e(TAG, "Failed to start service discovery")
                                handleConnectionFailure(gatt.device, "Service discovery failed to start")
                            }
                        }
                    }, 400L) // Reduced from 600ms
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "❌ STATE_DISCONNECTED (status=$status)")
                    closeGatt() // Always close GATT resources on disconnect
                    val currentState = stateMachine.getCurrentState()
                    if (currentState == ConnectionStateMachine.State.DISCONNECTING || currentState == ConnectionStateMachine.State.CONNECTED) {
                        stateMachine.transition(ConnectionStateMachine.Event.DISCONNECTED)
                        if (!disconnectRequested) scheduleReconnect(gatt.device)
                    } else if (currentState == ConnectionStateMachine.State.CONNECTING) {
                        val reason = when (status) {
                            133 -> "GATT_ERROR (133)"
                            else -> "Status code: $status"
                        }
                        handleConnectionFailure(gatt.device, reason)
                    }
                }
            }
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (isClosed.get() || gatt !== bluetoothGatt) return

            Log.i(TAG, "onServicesDiscovered: status=$status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                val hrService = gatt.getService(HR_SERVICE_UUID)

                if (hrService == null) {
                    Log.e(TAG, "❌ Heart Rate service not found")
                    callback.onError("Heart Rate service not found on device")
                    handleConnectionFailure(gatt.device, "HR service not available")
                    return
                }

                val hrChar = hrService.getCharacteristic(HR_MEASUREMENT_UUID)

                if (hrChar == null) {
                    Log.e(TAG, "❌ Heart Rate characteristic not found in service")
                    callback.onError("Heart Rate characteristic not found on device")
                    handleConnectionFailure(gatt.device, "HR characteristic not available")
                    return
                }

                Log.i(TAG, "✅ Heart Rate service found, enabling notifications...")

                // Enable local notifications
                var notifyEnabled = false
                try {
                     notifyEnabled = gatt.setCharacteristicNotification(hrChar, true)
                     Log.d(TAG, "setCharacteristicNotification returned: $notifyEnabled")
                } catch (e: SecurityException) {
                     Log.e(TAG, "❌ SecurityException enabling notification: ${e.message}")
                     notifyEnabled = false
                }

                if (!notifyEnabled) {
                    Log.e(TAG, "❌ Failed to enable characteristic notification")
                    callback.onError("Failed to enable heart rate notifications")
                    handleConnectionFailure(gatt.device, "Failed to enable notifications")
                    return
                }

                // Write CCCD to enable remote notifications
                val descriptor = hrChar.getDescriptor(CCC_DESCRIPTOR_UUID)
                if (descriptor == null) {
                    Log.e(TAG, "❌ CCCD descriptor not found")
                    callback.onError("CCCD descriptor not found")
                    handleConnectionFailure(gatt.device, "CCCD not available")
                    return
                }

                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                var writeStarted = false
                try {
                    @Suppress("DEPRECATION")
                    writeStarted = gatt.writeDescriptor(descriptor)
                    Log.d(TAG, "writeDescriptor returned: $writeStarted")
                } catch (e: SecurityException) {
                    Log.e(TAG, "❌ SecurityException writing CCCD: ${e.message}")
                    writeStarted = false
                }

                if (!writeStarted) {
                    Log.e(TAG, "❌ Failed to start CCCD write")
                    callback.onError("Failed to write CCCD")
                    handleConnectionFailure(gatt.device, "CCCD write failed")
                }
            } else {
                Log.e(TAG, "❌ Service discovery failed: $status")
                handleConnectionFailure(gatt.device, "Service discovery failed: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (isClosed.get() || gatt !== bluetoothGatt) return

            if (subscribed && characteristic.uuid == HR_MEASUREMENT_UUID && value.isNotEmpty()) {
                // Store valid receptions before unchanged-value suppression / UI throttling.
                HrProtocol.parse(value)?.let { hr ->
                    callback.onHeartRateSample(hr, System.currentTimeMillis(), SystemClock.elapsedRealtime())
                }
                try {
                    val flag = value[0].toInt()
                    // Parse HR value based on format bit (bit 0)
                    // 0 = UINT8, 1 = UINT16
                    val hrValue = if ((flag and 0x01) != 0) {
                         // UINT16
                         if (value.size >= 3) {
                             (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
                         } else {
                             Log.w(TAG, "Invalid buffer size for UINT16 HR")
                             null
                         }
                    } else {
                        // UINT8
                        if (value.size >= 2) {
                            value[1].toInt() and 0xFF
                        } else {
                             Log.w(TAG, "Invalid buffer size for UINT8 HR")
                             null
                        }
                    }

                    if (hrValue == null) {
                        Log.w(TAG, "⚠️ Failed to parse HR value")
                        return
                    }

                    if (hrValue in 1..249) {
                        // Skip if unchanged
                        if (hrValue == lastReportedHr) return

                        // Throttle updates
                        val now = System.currentTimeMillis()
                        if (now - lastHrUpdateTime < HR_UPDATE_THROTTLE_MS) return

                        lastHrUpdateTime = now
                        lastReportedHr = hrValue

                        Log.d(TAG, "❤️ HR: $hrValue bpm")
                        callback.onHeartRateReceived(hrValue)
                    } else {
                        Log.w(TAG, "⚠️ HR value out of valid range: $hrValue")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing HR: ${e.message}")
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (android.os.Build.VERSION.SDK_INT < 33) {
                characteristic.value?.let { onCharacteristicChanged(gatt, characteristic, it.copyOf()) }
            }
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (isClosed.get() || gatt !== bluetoothGatt) return

            Log.i(TAG, "onDescriptorWrite: status=$status descriptor=${descriptor.uuid}")

            if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    @Suppress("DEPRECATION")
                    val value = descriptor.value
                    @Suppress("DEPRECATION")
                    if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                        subscribed = true
                        reconnectAttempts = 0
                        cancelConnectTimeout()
                        Log.i(TAG, "✅ CCCD write successful - notifications enabled")
                    } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                         Log.i(TAG, "✅ CCCD write successful - notifications disabled. Proceeding to disconnect.")
                         forceDisconnect()
                    }
                } else {
                    Log.e(TAG, "❌ CCCD write failed: $status")
                    // Even if it failed, if we were trying to disconnect, we should probably just proceed
                   if (stateMachine.getCurrentState() == ConnectionStateMachine.State.DISCONNECTING) {
                       forceDisconnect()
                   } else {
                       handleConnectionFailure(gatt.device, "HR subscription failed: $status")
                   }
                }
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    fun reconnect() {
        if (isClosed.get()) return
        disconnect()
        handler.postDelayed({
            if (!isClosed.get()) {
                closeGatt()
                stateMachine.forceState(ConnectionStateMachine.State.DISCONNECTED, "manual reconnect")
                startScan()
            }
        }, 1_100L)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun resumeAfterScanFailure() {
        if (isClosed.get() || disconnectRequested) return
        targetDeviceAddress?.let { address ->
            try { scheduleReconnect(bluetoothAdapter.getRemoteDevice(address)) }
            catch (_: Exception) { callback.onError("Could not reconnect. Please scan again.") }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun saveLastConnectedDevice(device: BluetoothDevice) {
        try {
            val deviceName = device.name ?: "Unknown Device"
            prefs.edit {
                putString(PREF_LAST_DEVICE_ADDRESS, device.address)
                putString(PREF_LAST_DEVICE_NAME, deviceName)
            }
            Log.d(TAG, "💾 Saved last device: $deviceName (${device.address})")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot save device name due to missing permission")
            prefs.edit {
                putString(PREF_LAST_DEVICE_ADDRESS, device.address)
            }
        }
    }

    private fun getLastConnectedDevice(): Pair<String?, String?>? {
        val address = prefs.getString(PREF_LAST_DEVICE_ADDRESS, null)
        val name = prefs.getString(PREF_LAST_DEVICE_NAME, null)
        // Only return if address is non-null and non-empty
        return if (!address.isNullOrBlank()) Pair(address, name) else null
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun close() {
        if (isClosed.getAndSet(true)) return

        Log.i(TAG, "Closing BleConnectionManager")

        handler.removeCallbacksAndMessages(null)
        
        // Stop scanning
        stopScan()
        
        // Close GATT connection
        closeGatt()

        Log.i(TAG, "BleConnectionManager closed")
    }
}
