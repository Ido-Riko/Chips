// Ported from Ido-Riko/MiBand-Converter @ a472d64a6743454db0b63c902684be36426f2ff9.
// Owner-authorized reuse. CHIPS changes are listed in docs/HEART_RATE.md.
package com.chips.heartrate.converter

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.edit
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

interface PeripheralCallback {
    fun onPeripheralDeviceConnected()
    fun onPeripheralDeviceDisconnected()
    fun onPeripheralReady() {}
    fun onPeripheralError(message: String) {}
}

class PeripheralManager(
    private val context: Context,
    private val callback: PeripheralCallback? = null,
    // Enable bonding by default for security and proper OS integration
    private val requireBonding: Boolean = true
) {

    companion object {
        private const val TAG = "PeripheralManager"
        private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HR_MEASUREMENT_CHAR_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Device Information Service
        private val DEVICE_INFO_SERVICE_UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val MANUFACTURER_NAME_CHAR_UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        private val MODEL_NUMBER_CHAR_UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")

        private const val MANUFACTURER_ID = 0xFFFF
        private val MANUFACTURER_DATA = byteArrayOf(
            0x48, 0x52, 0x42, 0x52, // "HRBR"
            0x01                    // version
        )

        private const val ADVERTISE_RESTART_DELAY_MS = 1500L
        private const val ADVERTISE_MAX_RETRIES = 5
        private const val ADVERTISE_RETRY_BASE_DELAY_MS = 500L
        private const val BONDING_TIMEOUT_MS = 30_000L
        
        // Feature Flag for Pulse Mode
        private const val ENABLE_PULSE_MODE = true

    }

    // Device state tracking
    private data class DeviceState(
        val device: BluetoothDevice,
        var isConnected: Boolean = false,
        var isSubscribed: Boolean = false,
        var isBonded: Boolean = false,
        var bondingRequested: Boolean = false,
        var bondingTimeoutRunnable: Runnable? = null,
        var isLoopbackDevice: Boolean = false,  // NEW: Track if this is the band trying to connect back

    )

    // Core components
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val handler = Handler(Looper.getMainLooper())

    // State management
    private val devices = ConcurrentHashMap<String, DeviceState>()
    private val prefs: SharedPreferences = context.getSharedPreferences("PeripheralPrefs", Context.MODE_PRIVATE)
    private var gattServer: BluetoothGattServer? = null
    private var serviceRegistration: PeripheralRegistration? = null
    private var registrationTimeout: Runnable? = null
    private var hrMeasurementChar: BluetoothGattCharacteristic? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val isAdvertising = AtomicBoolean(false)
    private val isPeripheralRunning = AtomicBoolean(false)
    private var advertiseRetryCount = 0
    private var restartAdvertisingRunnable: Runnable? = null
    private var bondStateReceiverRegistered = false
    private var pairingRequestReceiverRegistered = false
    private val isShuttingDown = AtomicBoolean(false)
    private var lastLegitimateCentralAddress: String? = null
    
    // Configurable advertising
    private var currentConfig: AdvertisingConfig = AdvertisingConfig.BALANCED
    
    // Pulse Mode
    private var isPulseModeEnabled = false
    private var pulseRunnable: Runnable? = null
    private val PULSE_ON_DURATION_MS = 20_000L // Reduced from 30s
    private val PULSE_OFF_DURATION_MS = 10_000L // Increased from 2s to ensure "Out of Range" is registered

    var bandMacAddress: String? = null
    // Local copy of HR value for read requests (replaces deprecated characteristic.value)
    private var currentHeartRateValue: ByteArray = byteArrayOf(0x04, 0)

    // NEW: Track legitimate (non-loopback) connected devices
    val connectedDevices: Set<BluetoothDevice>
        get() = devices.values
            .filter { it.isConnected && !it.isLoopbackDevice }
            .map { it.device }
            .toSet()

    // NEW: Check if any legitimate devices are subscribed
    val hasSubscribedDevices: Boolean
        get() = devices.values.any { it.isSubscribed && it.isConnected && !it.isLoopbackDevice }



    // Pairing request receiver to auto-confirm Quest bonding and reject loopbacks
    private val pairingRequestReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return

            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
            val isLoopback = bandMacAddress?.equals(device.address, ignoreCase = true) == true
            val isKnownLegit = lastLegitimateCentralAddress?.equals(device.address, ignoreCase = true) == true

            Log.i(TAG, "🔐 Pairing request from ${device.address} variant=$variant loopback=$isLoopback legit=$isKnownLegit")

            if (isLoopback) {
                val cancelled = cancelBondProcessCompat(device)
                if (cancelled) {
                    Log.w(TAG, "Rejected pairing from loopback device ${device.address}")
                } else {
                    Log.e(TAG, "Failed to cancel loopback pairing for ${device.address}")
                }
                abortBroadcastSafely()
                return
            }

            if (!isKnownLegit) {
                Log.w(TAG, "Pairing request from unknown device ${device.address}, ignoring")
                return
            }

            // Auto-confirmation requires BLUETOOTH_PRIVILEGED which is a system-only permission.
            // We must let the user manually confirm the pairing dialog.
            if (variant == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION) {
                Log.i(TAG, "🔐 Pairing confirmation required for ${device.address}. Waiting for user input on device...")
            } else {
                Log.w(TAG, "Pairing variant $variant for ${device.address} requires manual confirmation")
            }
            abortBroadcastSafely()
        }

        private fun abortBroadcastSafely() {
            try {
                abortBroadcast()
            } catch (_: Exception) {
                // Ignore – broadcast might not be ordered on some builds
            }
        }
    }

    // Bond state receiver
    private val bondStateReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)

            device?.let { dev ->
                // Ensure a device state exists, especially if the broadcast arrives before onConnectionStateChange
                val deviceState = devices.getOrPut(dev.address) {
                    Log.w(TAG, "Bond state received before connection callback for ${dev.address}. Creating transient state.")
                    DeviceState(dev)
                }

                Log.i(TAG, "Bond state changed for ${dev.address}: $prevBondState -> $bondState")

                // Cancel bonding timeout
                deviceState.bondingTimeoutRunnable?.let {
                    handler.removeCallbacks(it)
                    deviceState.bondingTimeoutRunnable = null
                }

                when (bondState) {
                    BluetoothDevice.BOND_BONDED -> {
                        Log.i(TAG, "✅ Device ${dev.address} successfully bonded")
                        deviceState.isBonded = true
                        deviceState.bondingRequested = false
                    }
                    BluetoothDevice.BOND_NONE -> {
                        Log.w(TAG, "❌ Device ${dev.address} bonding failed or removed")
                        deviceState.isBonded = false
                        deviceState.bondingRequested = false

                        // If bonding was required and failed, disconnect
                        if (requireBonding && deviceState.isConnected) {
                            Log.w(TAG, "Bonding required but failed, disconnecting ${dev.address}")
                            handler.post {
                                disconnectDevice(dev)
                            }
                        }
                    }
                    BluetoothDevice.BOND_BONDING -> {
                        Log.d(TAG, "⏳ Device ${dev.address} is bonding...")
                        deviceState.bondingRequested = true

                        // Set bonding timeout
                        deviceState.bondingTimeoutRunnable = Runnable {
                            if (deviceState.bondingRequested && !deviceState.isBonded) {
                                Log.w(TAG, "Bonding timeout for ${dev.address}")
                                if (requireBonding) {
                                    disconnectDevice(dev)
                                }
                            }
                        }
                        handler.postDelayed(deviceState.bondingTimeoutRunnable!!, BONDING_TIMEOUT_MS)
                    }
                }
            }
        }
    }

    private val mAdvertiseCallback = object : AdvertiseCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            if (!isPeripheralRunning.get() || isShuttingDown.get() || serviceRegistration?.isReady != true) {
                stopAdvertising()
                return
            }
            isAdvertising.set(true)
            advertiseRetryCount = 0
            Log.i(TAG, "✅ Advertising started successfully")
            callback?.onPeripheralReady()
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
        override fun onStartFailure(errorCode: Int) {
            isAdvertising.set(false)
            val errorMsg = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
                ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                else -> "UNKNOWN($errorCode)"
            }
            Log.w(TAG, "❌ Advertising failed: $errorMsg")

            if (isPeripheralRunning.get() &&
                bluetoothAdapter?.isEnabled == true &&
                advertiseRetryCount < ADVERTISE_MAX_RETRIES) {

                val delay = (ADVERTISE_RETRY_BASE_DELAY_MS shl advertiseRetryCount)
                    .coerceAtMost(10_000L)
                advertiseRetryCount++

                Log.w(TAG, "⏳ Retrying advertising in ${delay}ms (attempt $advertiseRetryCount/$ADVERTISE_MAX_RETRIES)")

                handler.postDelayed({
                    if (isPeripheralRunning.get() && !isAdvertising.get()) {
                        try {
                            startAdvertising()
                        } catch (e: Exception) {
                            Log.e(TAG, "Advertising retry failed: ${e.message}", e)
                        }
                    }
                }, delay)
            } else {
                Log.e(TAG, "❌ Advertising retry limit reached or peripheral stopped")
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun setAdvertisingConfig(config: AdvertisingConfig) {
        Log.i(TAG, "⚙️ Updating Advertising Config: $config")
        currentConfig = config
        // Restart advertising if currently running to apply changes
        if (isAdvertising.get()) {
            stopAdvertising()
            handler.postDelayed({
                if (isPeripheralRunning.get()) {
                    startAdvertising()
                }
            }, 200)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startPulseMode() {
        if (serviceRegistration?.isReady != true) return
        if (!ENABLE_PULSE_MODE) return
        if (isPulseModeEnabled) return
        if (connectedDevices.isNotEmpty()) return // Don't pulse if connected
        
        Log.i(TAG, "💓 Starting Pulse Mode (On: ${PULSE_ON_DURATION_MS}ms, Off: ${PULSE_OFF_DURATION_MS}ms)")
        isPulseModeEnabled = true
        schedulePulse()
    }

    private fun stopPulseMode() {
        if (!isPulseModeEnabled) return
        Log.i(TAG, "🛑 Stopping Pulse Mode")
        isPulseModeEnabled = false
        pulseRunnable?.let { handler.removeCallbacks(it) }
        pulseRunnable = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun schedulePulse() {
        if (!isPeripheralRunning.get() || !isPulseModeEnabled || connectedDevices.isNotEmpty()) {
            stopPulseMode()
            return
        }

        // We assume we are currently advertising (or about to be).
        // Schedule the "OFF" phase.
        
        pulseRunnable = Runnable {
            if (!isPeripheralRunning.get() || !isPulseModeEnabled || connectedDevices.isNotEmpty()) return@Runnable
            
            // Phase 1: Stop Advertising (Rest)
            Log.d(TAG, "💓 Pulse: Resting for ${PULSE_OFF_DURATION_MS}ms")
            stopAdvertising()
            
            // Schedule Phase 2: Restart
            handler.postDelayed({
                if (isPeripheralRunning.get() && isPulseModeEnabled && connectedDevices.isEmpty()) {
                     Log.d(TAG, "💓 Pulse: Resuming Advertising")
                     startAdvertising()
                     // Schedule next loop
                     schedulePulse()
                }
            }, PULSE_OFF_DURATION_MS)
        }
        
        // Wait for the ON duration before triggering the rest phase
        handler.postDelayed(pulseRunnable!!, PULSE_ON_DURATION_MS)
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun startPeripheral() {
        Log.d(TAG, "startPeripheral called (requireBonding=$requireBonding)")

        if (isPeripheralRunning.get()) {
            Log.d(TAG, "Peripheral already running")
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "❌ Bluetooth is OFF, cannot start peripheral")
            return
        }

        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            Log.w(TAG, "⚠️ BLE advertising not supported on this device")
        }

        isShuttingDown.set(false)
        isPeripheralRunning.set(true)

        // Register UUID discovery receiver for Meta Quest 2 compatibility


        // Register bond state receiver if bonding is required
        if (requireBonding && !bondStateReceiverRegistered) {
            try {
                val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(bondStateReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(bondStateReceiver, filter)
                }
                bondStateReceiverRegistered = true
                Log.d(TAG, "Bond state receiver registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register bond state receiver: ${e.message}", e)
            }
        }

        // Register pairing request receiver to silently handle Quest bonding prompts
        if (!pairingRequestReceiverRegistered) {
            try {
                val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(pairingRequestReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(pairingRequestReceiver, filter)
                }
                pairingRequestReceiverRegistered = true
                Log.d(TAG, "Pairing request receiver registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register pairing receiver: ${e.message}", e)
            }
        }

        // Initialize advertiser
        if (advertiser == null) {
            advertiser = bluetoothAdapter.bluetoothLeAdvertiser
            if (advertiser == null) {
                Log.e(TAG, "❌ BluetoothLeAdvertiser is null")
                isPeripheralRunning.set(false)
                return
            }
        }

        // Setup GATT server if needed
        if (gattServer == null) {
            setupGattServer()
            if (gattServer == null) {
                Log.e(TAG, "❌ Failed to setup GATT server")
                isPeripheralRunning.set(false)
                return
            }
        }

        // CRITICAL: Clean up "Zombie" connections from the OS level.
        // Even if our map is empty, the OS might hold a connection (e.g. Nrf Connect).
        // We must kill these to force the client to rediscover services.
        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            .filterNot { it.address.equals(bandMacAddress, ignoreCase = true) }
        if (connectedDevices.isNotEmpty()) {
            Log.w(TAG, "⚠️ Found ${connectedDevices.size} orphaned OS connections on startup. Forcing disconnect.")
            connectedDevices.forEach { device ->
                Log.w(TAG, "   🔌 Killing orphan: ${device.address}")
                try {
                    gattServer?.cancelConnection(device)
                    // Also fire a manual disconnect in case cancelConnection is lazy
                    // But cancelConnection is usually the right way for server.
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting orphan: ${e.message}")
                }
            }
        }

        // Clear stale device state
        devices.clear()

        // Registration callbacks start advertising/pulse mode only after both services succeed.
        // An already registered server can be reused without publishing incomplete services.
        if (serviceRegistration?.isReady == true) {
            startAdvertising()
            startPulseMode()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    fun stopPeripheral(force: Boolean = false) {
        Log.d(TAG, "stopPeripheral called (force=$force)")

        if (!isPeripheralRunning.get() && !force) {
            Log.d(TAG, "Peripheral already stopped or stopping.")
            return
        }

        isPeripheralRunning.set(false)
        isShuttingDown.set(true)
        cancelServiceRegistration()

        // Cancel all pending callbacks to prevent scheduled restarts etc.
        cancelAllPendingCallbacks("stopPeripheral")
        stopPulseMode()

        // Stop advertising immediately to refuse new connections
        stopAdvertising()

        // If Bluetooth is OFF or Force is true, we can't wait for disconnect callbacks.
        // We must clean up immediately to prevent holding onto a dead GATT server.
        val bluetoothOff = force || bluetoothAdapter?.isEnabled != true
        
        // Check actual OS connections, not just our internal map
        val osConnectedDevices = if (bluetoothOff || force) emptyList() else bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            .filterNot { it.address.equals(bandMacAddress, ignoreCase = true) }
        val hasConnections = connectedDevices.isNotEmpty() || osConnectedDevices.isNotEmpty()
        
        if (!hasConnections || bluetoothOff || force) {
            Log.d(TAG, "Stopping immediately (internal=${connectedDevices.size}, os=${osConnectedDevices.size}, btOff=$bluetoothOff, force=$force)")
            performGattServerCleanup()
        } else {
            Log.d(TAG, "Disconnecting ${osConnectedDevices.size} OS devices before closing server...")
            // Disconnect all known OS devices
            osConnectedDevices.forEach { dev ->
                 try {
                     gattServer?.cancelConnection(dev)
                 } catch (e: Exception) {
                     Log.e(TAG, "Error disconnecting ${dev.address}: ${e.message}")
                 }
            }
            
            // Failsafe: connect disconnect callback never comes
            handler.postDelayed({
                if (gattServer != null) {
                    Log.w(TAG, "Timed out waiting for disconnect, forcing cleanup.")
                    performGattServerCleanup()
                }
            }, 2000L)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun performGattServerCleanup() {
        cancelServiceRegistration()

        Log.d(TAG, "Closing GATT server...")
        try {
            gattServer?.close()
            gattServer = null
            hrMeasurementChar = null
            Log.d(TAG, "🔌 GATT Server closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT server: ${e.message}", e)
        }

        // Unregister receivers

        if (bondStateReceiverRegistered) {
            try {
                context.unregisterReceiver(bondStateReceiver)
                bondStateReceiverRegistered = false
            } catch (e: Exception) { /* Ignore */ }
        }
        if (pairingRequestReceiverRegistered) {
            try {
                context.unregisterReceiver(pairingRequestReceiver)
                pairingRequestReceiverRegistered = false
            } catch (e: Exception) { /* Ignore */ }
        }

        devices.clear()
        isShuttingDown.set(false) // Shutdown complete
        Log.d(TAG, "✅ Peripheral shutdown complete.")

        // Since all devices are disconnected, notify the service layer.
        callback?.onPeripheralDeviceDisconnected()
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun stopAdvertising() {
        // Cancel in-flight startup too; onStartSuccess may not have arrived yet.
        try {
            advertiser?.stopAdvertising(mAdvertiseCallback)
            isAdvertising.set(false)
            Log.d(TAG, "✅ Advertising stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising: ${e.message}", e)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun updateHeartRate(hr: Int) {
        if (!isPeripheralRunning.get()) return

        // Treat -1 as "no HR" and convert to 0 for BLE transmission
        val hrValue = if (hr < 0) 0 else hr

        // CHIPS: keep the converter's contact bits and encode wide HR without truncation.
        val value = if (hr < 0) byteArrayOf(0x04, 0)
            else if (hrValue <= 255) byteArrayOf(0x06, hrValue.toByte())
            else byteArrayOf(0x07, hrValue.toByte(), (hrValue shr 8).toByte())
        
        // Update local copy first
        currentHeartRateValue = value

        // Get subscribed devices that are NOT loopback devices
        val subscribedDevices = devices.values
            .filter { it.isSubscribed && it.isConnected && !it.isLoopbackDevice }
            .filter { !requireBonding || it.isBonded }
            .toList()

        if (subscribedDevices.isEmpty()) {
            return
        }

        subscribedDevices.forEach { deviceState ->
            try {
                val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                     gattServer?.notifyCharacteristicChanged(
                        deviceState.device,
                        hrMeasurementChar!!,
                        false,
                        value
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                     // Fallback for older Android versions which require setting the value on the characteristic
                     @Suppress("DEPRECATION")
                     hrMeasurementChar?.value = value
                     @Suppress("DEPRECATION")
                     gattServer?.notifyCharacteristicChanged(
                        deviceState.device,
                        hrMeasurementChar,
                        false
                    )
                }

                if (success == true) {
                    Log.d(TAG, "📤 Sent HR $hrValue to ${deviceState.device.address}")
                } else {
                    Log.w(TAG, "⚠️ Failed to send HR to ${deviceState.device.address}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error notifying ${deviceState.device.address}: ${e.message}")
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnectDevice(device: BluetoothDevice) {
        Log.d(TAG, "disconnectDevice: ${device.address}")
        try {
            // Cancel any bonding timeouts
            devices[device.address]?.let { state ->
                state.bondingTimeoutRunnable?.let {
                    handler.removeCallbacks(it)
                    state.bondingTimeoutRunnable = null
                }

            }

            gattServer?.cancelConnection(device)
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting device: ${e.message}", e)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnectAllDevices() {
        Log.d(TAG, "disconnectAllDevices: ${devices.size} device(s)")
        devices.values.toList().forEach { deviceState ->
            if (deviceState.isConnected) {
                disconnectDevice(deviceState.device)
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    private fun setupGattServer() {
        Log.d(TAG, "Setting up GATT server (requireBonding=$requireBonding)")

        // CRITICAL: Do not re-create if it exists. This preserves handles.
        if (gattServer != null) {
            Log.d(TAG, "GATT server already exists. Skipping setup to preserve handles.")
            return
        }

        val registration = PeripheralRegistration(
            add = { service ->
                when (service) {
                    PeripheralRegistration.Service.HEART_RATE -> addHeartRateService()
                    PeripheralRegistration.Service.DEVICE_INFO -> addDeviceInfoService()
                }
            },
            onReady = {
                registrationTimeout?.let { handler.removeCallbacks(it) }
                registrationTimeout = null
                startAdvertising()
                startPulseMode()
            },
            onFailure = { message ->
                Log.e(TAG, message)
                stopPeripheral(force = true)
                callback?.onPeripheralError(message)
            }
        )
        serviceRegistration = registration
        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback(registration))
            if (gattServer == null) {
                registration.fail("openGattServer returned null")
                return
            }

            registrationTimeout = Runnable { registration.timeout() }.also { handler.postDelayed(it, 15_000L) }
            registration.begin()
        } catch (e: Exception) {
            registration.fail("Error setting up GATT server: ${e.message}")
        }
    }

    private fun cancelServiceRegistration() {
        serviceRegistration?.stop()
        serviceRegistration = null
        registrationTimeout?.let { handler.removeCallbacks(it) }
        registrationTimeout = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun addHeartRateService(): Boolean {
        // Create Heart Rate Service
        val heartRateService = BluetoothGattService(
            HEART_RATE_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // When requireBonding is true, set encrypted permissions to trigger OS-level bonding.
        // Use standard PERMISSION_READ. We enforce bonding manually in onCharacteristicReadRequest
        // by returning GATT_INSUFFICIENT_AUTHENTICATION. Using PERMISSION_READ_ENCRYPTED can cause issues.
        val hrPermissions = BluetoothGattCharacteristic.PERMISSION_READ

        // CCCD permissions should be open, even when bonding.
        // The bonding is enforced on the characteristic read, not the descriptor write.
        // This prevents the PIN prompt on devices like the Quest 2.
        val cccdPermissions = BluetoothGattDescriptor.PERMISSION_READ or
                BluetoothGattDescriptor.PERMISSION_WRITE

        hrMeasurementChar = BluetoothGattCharacteristic(
            HR_MEASUREMENT_CHAR_UUID,
            // Add PROPERTY_READ to allow bonding to be triggered by a read attempt.
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            hrPermissions
        )

        val cccd = BluetoothGattDescriptor(CCCD_UUID, cccdPermissions)
        hrMeasurementChar?.addDescriptor(cccd)
        heartRateService.addCharacteristic(hrMeasurementChar)

        return gattServer?.addService(heartRateService) == true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun addDeviceInfoService(): Boolean {
         // Setup Device Information Service
        val deviceInfoService = BluetoothGattService(
            DEVICE_INFO_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Manufacturer Name
        val manufacturerChar = BluetoothGattCharacteristic(
            MANUFACTURER_NAME_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // manufacturerChar.value not set here, handled in onCharacteristicReadRequest
        deviceInfoService.addCharacteristic(manufacturerChar)

        // Model Number
        val modelChar = BluetoothGattCharacteristic(
            MODEL_NUMBER_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // modelChar.value not set here, handled in onCharacteristicReadRequest
        deviceInfoService.addCharacteristic(modelChar)

        return gattServer?.addService(deviceInfoService) == true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising() {
        // Every entry point (startup, pulse, config change and retries) uses this gate.
        if (!isPeripheralRunning.get() || isShuttingDown.get() || serviceRegistration?.isReady != true) return
        if (isAdvertising.get()) {
            Log.d(TAG, "Already advertising")
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "❌ Bluetooth is OFF, cannot advertise")
            return
        }

        if (advertiser == null) {
            advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            if (advertiser == null) {
                Log.e(TAG, "❌ BluetoothLeAdvertiser is null")
                return
            }
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(currentConfig.mode)
            .setConnectable(true)
            .setTimeout(currentConfig.timeout)
            .setTxPowerLevel(currentConfig.txPower)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // Moved to Scan Response to ensure UUID fits
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true) // Name goes here (Unlimited size in Extended, but safer here for Legacy)
            .setIncludeTxPowerLevel(true)
            .addServiceUuid(ParcelUuid(DEVICE_INFO_SERVICE_UUID)) // Moved secondary service here
            .addManufacturerData(MANUFACTURER_ID, MANUFACTURER_DATA)
            .build()

        try {
            Log.d(TAG, "📡 Starting advertising...")
            advertiser?.startAdvertising(settings, advertiseData, scanResponse, mAdvertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting advertising: ${e.message}", e)
            isAdvertising.set(false)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    private fun scheduleAdvertisingRestart() {
        if (!isPeripheralRunning.get()) return

        restartAdvertisingRunnable?.let {
            handler.removeCallbacks(it)
        }

        restartAdvertisingRunnable = Runnable {
            if (isPeripheralRunning.get() && bluetoothAdapter?.isEnabled == true) {
                Log.d(TAG, "🔄 Restarting advertising after disconnect")
                stopAdvertising()
                handler.postDelayed({
                    if (isPeripheralRunning.get()) {
                        startAdvertising()
                    }
                }, 100L)
            }
            restartAdvertisingRunnable = null
        }

        handler.postDelayed(restartAdvertisingRunnable!!, ADVERTISE_RESTART_DELAY_MS)
    }




    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun ensureBonding(deviceState: DeviceState, reason: String) {
        if (!requireBonding) return
        if (deviceState.isBonded) return
        if (deviceState.bondingRequested) {
            Log.d(TAG, "Bonding already requested for ${deviceState.device.address} ($reason)")
            return
        }

        try {
            val started = deviceState.device.createBond()
            if (started) {
                deviceState.bondingRequested = true
                Log.i(TAG, "🔐 Bonding initiated with ${deviceState.device.address} ($reason)")
            } else {
                Log.w(TAG, "⚠️ createBond() returned false for ${deviceState.device.address} ($reason)")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "createBond failed: ${e.message}", e)
        }
    }

    private fun cancelAllPendingCallbacks(reason: String, includeDeviceRunnables: Boolean = true) {
        var cancelled = false

        restartAdvertisingRunnable?.let {
            handler.removeCallbacks(it)
            restartAdvertisingRunnable = null
            cancelled = true
        }

        if (includeDeviceRunnables) {
            devices.values.forEach { state ->
                state.bondingTimeoutRunnable?.let {
                    handler.removeCallbacks(it)
                    state.bondingTimeoutRunnable = null
                    cancelled = true
                }
            }
        }

        if (cancelled) {
            Log.d(TAG, "Cleared pending callbacks ($reason)")
        } else {
            Log.d(TAG, "No pending callbacks to clear ($reason)")
        }
    }


// Enhanced loopback detection - Add to PeripheralManager.kt

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun cancelBondProcessCompat(device: BluetoothDevice): Boolean {
        return try {
            val method = try {
                BluetoothDevice::class.java.getMethod("cancelBondProcess")
            } catch (_: NoSuchMethodException) {
                BluetoothDevice::class.java.getDeclaredMethod("cancelBondProcess").apply { isAccessible = true }
            }

            val result = (method.invoke(device) as? Boolean) ?: false
            Log.d(TAG, "cancelBondProcessCompat result=$result for ${device.address}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "cancelBondProcessCompat failed for ${device.address}: ${e.message}", e)
            false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun isLoopbackDevice(device: BluetoothDevice, deviceName: String?): Boolean {
        // Log all connection details for debugging
        Log.i(TAG, "🔍 Checking device for loopback:")
        Log.i(TAG, "   Device MAC: ${device.address}")
        Log.i(TAG, "   Device Name: '$deviceName'")
        Log.i(TAG, "   Band MAC: ${bandMacAddress ?: "not set"}")

        // Check 1: MAC address match (most reliable)
        if (bandMacAddress != null && device.address.equals(bandMacAddress, ignoreCase = true)) {
            Log.w(TAG, "🔒 LOOPBACK DETECTED: Band MAC matches (${device.address})")
            return true
        }

        // Check 2: Xiaomi/Mi Band device names
        val suspiciousNames = listOf(
            "xiaomi", "mi band", "mi smart band", "redmi band",
            "amazfit", "huami"
        )

        val nameLower = deviceName?.lowercase() ?: ""
        val matchedName = suspiciousNames.find { nameLower.contains(it) }

        if (matchedName != null) {
            Log.w(TAG, "🔒 LOOPBACK DETECTED: Suspicious device name detected ('$deviceName' contains '$matchedName')")
            return true
        }

        // Check 3: Device type (if available via Bluetooth class)
        try {
            val bluetoothClass = device.bluetoothClass
            if (bluetoothClass != null) {
                val deviceClass = bluetoothClass.deviceClass
                Log.d(TAG, "   Device Class: $deviceClass")

                // Wearable devices are in the 0x0704 range
                if (deviceClass >= 0x0700 && deviceClass <= 0x0740) {
                    Log.w(TAG, "🔒 LOOPBACK SUSPECTED: Device class indicates wearable ($deviceClass)")
                    // Don't reject solely on device class, but log it
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "   Could not check device class: ${e.message}")
        }

        Log.i(TAG, "✅ Device appears legitimate (not loopback)")
        return false
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendResponseSafe(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?): Boolean {
        return try {
            gattServer?.sendResponse(device, requestId, status, offset, value) == true
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException sending response to ${device.address}: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending response to ${device.address}: ${e.message}")
            false
        }
    }

    private fun gattServerCallback(registration: PeripheralRegistration) = object : BluetoothGattServerCallback() {
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
// Enhanced loopback detection in onConnectionStateChange
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            val d = device ?: return

            val deviceName = try {
                d.name ?: "<no name>"
            } catch (e: SecurityException) {
                "<permission denied>"
            } catch (e: Exception) {
                "<error getting name>"
            }

            val bondStateStr = when (d.bondState) {
                BluetoothDevice.BOND_NONE -> "NONE"
                BluetoothDevice.BOND_BONDING -> "BONDING"
                BluetoothDevice.BOND_BONDED -> "BONDED"
                else -> "UNKNOWN"
            }

            Log.i(TAG, "╔═══════════════════════════════════════════════════")
            Log.i(TAG, "📱 CONNECTION STATE CHANGE")
            Log.i(TAG, "   Device MAC: ${d.address}")
            Log.i(TAG, "   Device Name: '$deviceName'")
            Log.i(TAG, "   Status: $status")
            Log.i(TAG, "   New State: $newState (${if (newState == 2) "CONNECTED" else if (newState == 0) "DISCONNECTED" else "OTHER"})")
            Log.i(TAG, "   Bond State: $bondStateStr")
            Log.i(TAG, "   Band MAC (for loopback check): ${bandMacAddress ?: "not set"}")
            Log.i(TAG, "╚═══════════════════════════════════════════════════")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (isShuttingDown.get()) {
                        Log.w(TAG, "Device connected during shutdown, immediately disconnecting.")
                        disconnectDevice(d)
                        return
                    }
                    // Check for loopback BEFORE creating device state
                    val isLoopback = isLoopbackDevice(d, deviceName)

                    if (isLoopback) {
                        Log.w(TAG, "❌ REJECTING LOOPBACK CONNECTION from ${d.address}")
                        Log.w(TAG, "   This device appears to be the Mi-Band trying to connect back")
                        Log.w(TAG, "   Immediately cancelling connection...")
                        gattServer?.cancelConnection(d)

                        // Failsafe: Force disconnect after brief delay if cancelConnection fails
                        handler.postDelayed({
                            try {
                                gattServer?.cancelConnection(d)
                                Log.d(TAG, "🔒 Failsafe disconnect executed for loopback device")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failsafe disconnect failed: ${e.message}")
                            }
                        }, 500L)
                        return
                    }

                    val deviceState = devices.getOrPut(d.address) {
                        DeviceState(d)
                    }
                    deviceState.isConnected = true
                    
                    // Restore subscription state for bonded devices
                    if (d.bondState == BluetoothDevice.BOND_BONDED) {
                        // Fallback to TRUE if no record exists (safe default for Quest 2)
                        val savedSubState = prefs.getBoolean("subscribed_${d.address}", true)
                        deviceState.isSubscribed = savedSubState
                        Log.i(TAG, "🔄 Restored subscription state for ${d.address}: $savedSubState")
                    } else {
                        deviceState.isSubscribed = false
                    }
                    
                    deviceState.isBonded = (d.bondState == BluetoothDevice.BOND_BONDED)
                    deviceState.isLoopbackDevice = false
                    lastLegitimateCentralAddress = d.address

                    Log.i(TAG, "✅ Device connected: ${d.address} name='$deviceName' bonded=${deviceState.isBonded} (legitimate devices: ${connectedDevices.size})")

                    if (requireBonding && !deviceState.isBonded) {
                        ensureBonding(deviceState, "initial connection")
                    }
                    
                    // Only notify callback if this is a legitimate device
                    if (connectedDevices.size == 1) {  // First legitimate device
                        stopPulseMode() // Stop pulsing when connected
                        callback?.onPeripheralDeviceConnected()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = devices[d.address]?.isConnected == true
                    val deviceState = devices.remove(d.address)

                    // Cancel any pending bonding timeout
                    deviceState?.bondingTimeoutRunnable?.let {
                        handler.removeCallbacks(it)
                    }



                    if (wasConnected && deviceState?.isLoopbackDevice == false) {
                        Log.i(TAG, "❌ Device disconnected: ${d.address} name='$deviceName' status=$status (legitimate devices: ${connectedDevices.size})")

                        // Restart advertising so we can be found again
                        Log.i(TAG, "🔄 Valid disconnect. Orchestrating advertising restart...")
                        scheduleAdvertisingRestart()
                        // Restart Pulse Mode if we lost all clients
                         if (connectedDevices.isEmpty()) {
                              startPulseMode()
                         }

                        // Only notify if no more legitimate devices are connected
                        if (connectedDevices.isEmpty()) {
                            lastLegitimateCentralAddress = null
                            callback?.onPeripheralDeviceDisconnected()
                        }
                    }

                    // If shutdown is in progress and this was the last device, complete the cleanup.
                    if (isShuttingDown.get() && connectedDevices.isEmpty()) {
                        Log.i(TAG, "Last device disconnected during shutdown, performing final cleanup.")
                        handler.post { performGattServerCleanup() }
                    }
                }
            }
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            val d = device ?: return
            val deviceState = devices[d.address]

            Log.w(TAG, "⚡ onCharacteristicReadRequest: UUID=${characteristic?.uuid} Offset=$offset From=${d.address} Bonded=${deviceState?.isBonded}")

            if (characteristic?.uuid == HR_MEASUREMENT_CHAR_UUID) {

                if (requireBonding && deviceState?.isBonded != true) {
                    Log.w(TAG, "❌ Read denied for ${d.address} - device not bonded")
                    deviceState?.let { ensureBonding(it, "characteristic read") }
                    sendResponseSafe(
                        d,
                        requestId,
                        BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
                        0,
                        null
                    )
                    return
                }

                Log.d(TAG, "✅ Read request approved for bonded device ${d.address}")
                val success = sendResponseSafe(
                    d,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    currentHeartRateValue
                )
                Log.i(TAG, "⚡ Sent HR Read Response: Result=$success")
            } else if (characteristic?.uuid == MANUFACTURER_NAME_CHAR_UUID) {
                Log.d(TAG, "✅ Read request for Manufacturer Name")
                val success = sendResponseSafe(
                    d,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    "IdoVibe".toByteArray()
                )
                Log.i(TAG, "⚡ Sent Manufacturer Read Response: Result=$success")
            } else if (characteristic?.uuid == MODEL_NUMBER_CHAR_UUID) {
                Log.d(TAG, "✅ Read request for Model Number")
                val success = sendResponseSafe(
                    d,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    "MiBand-Converter".toByteArray()
                )
                 Log.i(TAG, "⚡ Sent Model Read Response: Result=$success")
            } else {
                Log.w(TAG, "❌ Read request for unknown characteristic: ${characteristic?.uuid}")
                sendResponseSafe(d, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?
        ) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor)
            val d = device ?: return

            Log.d(TAG, "📖 onDescriptorReadRequest from ${d.address}")

            if (descriptor?.uuid == CCCD_UUID) {
                val characteristicUuid = descriptor.characteristic.uuid
                val deviceState = devices[d.address]
                val value = when (characteristicUuid) {
                    HR_MEASUREMENT_CHAR_UUID -> if (deviceState?.isSubscribed == true) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    }
                    else -> null
                }

                if (value != null) {
                    sendResponseSafe(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                } else {
                    sendResponseSafe(d, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            } else {
                sendResponseSafe(d, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            val d = device ?: return

            val bondStateStr = when (d.bondState) {
                BluetoothDevice.BOND_NONE -> "NONE"
                BluetoothDevice.BOND_BONDING -> "BONDING"
                BluetoothDevice.BOND_BONDED -> "BONDED"
                else -> "UNKNOWN"
            }

            Log.w(TAG, "⚡ onDescriptorWriteRequest: UUID=${descriptor?.uuid} ResponseNeeded=$responseNeeded From=${d.address}")

            if (descriptor?.uuid == CCCD_UUID) {
                val deviceState = devices[d.address]
                val characteristicUuid = descriptor.characteristic.uuid

                if (requireBonding && deviceState?.isBonded != true) {
                    Log.w(TAG, "❌ CCCD write rejected for ${d.address} ($characteristicUuid) - device not bonded")
                    deviceState?.let { ensureBonding(it, "CCCD write") }
                    if (responseNeeded) {
                        Log.i(TAG, "⚡ Sending INSUFFICIENT_AUTHENTICATION (RequestId=$requestId)")
                        sendResponseSafe(
                            d,
                            requestId,
                            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
                            0,
                            null
                        )
                    }
                    return
                }

                if (deviceState?.isLoopbackDevice == true) {
                    Log.w(TAG, "🔒 Rejecting CCCD write from loopback device ${d.address}")
                    if (responseNeeded) {
                        sendResponseSafe(d, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                    }
                    return
                }

                var handled = false
                when (characteristicUuid) {
                    HR_MEASUREMENT_CHAR_UUID -> {
                        handled = true
                        when {
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value) -> {
                                Log.i(TAG, "🔔 Device ${d.address} subscribed to HR notifications")
                                deviceState?.isSubscribed = true
                            }
                            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE.contentEquals(value) -> {
                                Log.i(TAG, "🔕 Device ${d.address} unsubscribed from HR notifications")
                                deviceState?.isSubscribed = false
                            }
                        }
                    }
                }

                // Persist state if Bonded (or simply always)
                if (handled && descriptor?.uuid == CCCD_UUID) {
                     val isEnabled = deviceState?.isSubscribed == true
                     prefs.edit { putBoolean("subscribed_${d.address}", isEnabled) }
                     Log.d(TAG, "💾 Persisted subscription state for ${d.address}: $isEnabled")
                }

                if (responseNeeded) {
                    val status = if (handled) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                    val success = sendResponseSafe(d, requestId, status, 0, null)
                    Log.i(TAG, "⚡ Sending Response: $status (RequestId=$requestId) -> Result: $success")
                }
            } else {
                if (responseNeeded) {
                    sendResponseSafe(d, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            val statusStr = if (status == BluetoothGatt.GATT_SUCCESS) "SUCCESS" else "FAILURE ($status)"
            Log.i(TAG, "onServiceAdded: ${service?.uuid} status: $statusStr")

            handler.post {
                // A closed server's late callback must never advance a replacement server.
                if (serviceRegistration !== registration || !isPeripheralRunning.get() || isShuttingDown.get()) return@post
                val registered = when (service?.uuid) {
                    HEART_RATE_SERVICE_UUID -> PeripheralRegistration.Service.HEART_RATE
                    DEVICE_INFO_SERVICE_UUID -> PeripheralRegistration.Service.DEVICE_INFO
                    else -> return@post
                }
                registration.complete(registered, status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            super.onMtuChanged(device, mtu)
            Log.d(TAG, "onMtuChanged: device=${device?.address} mtu=$mtu")
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            super.onNotificationSent(device, status)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "❌ onNotificationSent failed: device=${device?.address} status=$status")
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun cleanup() {
        Log.d(TAG, "cleanup called")
        cancelAllPendingCallbacks("cleanup")



        // Unregister bond state receiver
        if (bondStateReceiverRegistered) {
            try {
                context.unregisterReceiver(bondStateReceiver)
                bondStateReceiverRegistered = false
                Log.d(TAG, "Bond state receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister bond state receiver: ${e.message}", e)
            }
        }
        if (pairingRequestReceiverRegistered) {
            try {
                context.unregisterReceiver(pairingRequestReceiver)
                pairingRequestReceiverRegistered = false
                Log.d(TAG, "Pairing request receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister pairing receiver: ${e.message}", e)
            }
        }

        try {
            gattServer?.close()
            gattServer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT server: ${e.message}", e)
        }

    }
}

