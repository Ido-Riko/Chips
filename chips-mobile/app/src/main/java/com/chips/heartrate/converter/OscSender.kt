// Ported from Ido-Riko/MiBand-Converter @ a472d64a6743454db0b63c902684be36426f2ff9.
// Owner-authorized reuse. CHIPS changes are listed in docs/HEART_RATE.md.
package com.chips.heartrate.converter

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OscSender(defaultIp: String = "255.255.255.255", defaultPort: Int = 9000, private val onError: (String) -> Unit = {}) {
    companion object {
        private const val TAG = "OscSender"
        private const val SHUTDOWN_TIMEOUT_MS = 2000L
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
    }

    @Volatile
    private var ip: String = defaultIp
    
    @Volatile
    private var port: Int = defaultPort
    
    @Volatile
    private var targetAddress: InetAddress? = null
    
    private var socket: DatagramSocket? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val isClosed = AtomicBoolean(false)
    
    init {
        // Initialize with default target, but don't crash if invalid
        try {
            targetAddress = InetAddress.getByName(defaultIp)
            Log.d(TAG, "OscSender initialized with target $defaultIp:$defaultPort")
        } catch (e: UnknownHostException) {
            Log.e(TAG, "Invalid default IP address: $defaultIp", e)
            // targetAddress remains null - sends will be skipped
        }
    }

    /**
     * Updates the OSC target address and port.
     * @throws UnknownHostException if the IP address is invalid
     * @throws IllegalArgumentException if the port is out of range or IP is null/blank
     */
    @Throws(UnknownHostException::class, IllegalArgumentException::class)
    fun setTarget(newIp: String, newPort: Int) {
        if (isClosed.get()) {
            Log.w(TAG, "Cannot set target on closed OscSender")
            return
        }

        // Validate IP is not blank
        if (newIp.isBlank()) {
            throw IllegalArgumentException("IP address cannot be blank")
        }

        // Validate port range
        if (newPort !in MIN_PORT..MAX_PORT) {
            throw IllegalArgumentException("Port must be between $MIN_PORT and $MAX_PORT, got: $newPort")
        }

        // Validate and resolve IP address (this can throw UnknownHostException)
        val newAddress = InetAddress.getByName(newIp)

        // Additional null check (should not happen, but be defensive)
        if (newAddress == null) {
            throw UnknownHostException("Failed to resolve IP address: $newIp")
        }

        // Only update if validation succeeded
        ip = newIp
        port = newPort
        targetAddress = newAddress

        // Close existing socket to force recreation with new settings
        closeSocket()

        Log.d(TAG, "OSC target updated to $newIp:$newPort")
    }

    fun getTargetIp(): String = ip
    fun getTargetPort(): Int = port
    
    /**
     * Returns true if the OSC sender is properly configured and ready to send.
     */
    fun isConfigured(): Boolean = targetAddress != null && !isClosed.get()

    fun sendHeartRate(hr: Int) {
        if (isClosed.get()) {
            Log.w(TAG, "Cannot send HR on closed OscSender")
            return
        }

        if (targetAddress == null) {
            Log.w(TAG, "OSC target not configured, skipping send")
            return
        }

        // Treat -1 as "no HR" and convert to 0 for OSC transmission
        val hrValue = if (hr < 0) 0 else hr

        executor.submit {
            try {
                val msgBytes = buildOscMessage(hrValue)
                val packet = DatagramPacket(msgBytes, msgBytes.size, targetAddress, port)

                getSocket().send(packet)
                Log.d(TAG, "Sent HR $hrValue to $ip:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send HR $hrValue to $ip:$port: ${e.message}", e)

                onError("OSC send failed; capture continues")

                // Close socket to force recreation on next send attempt
                closeSocket()
            }
        }
    }

    private fun buildOscMessage(hr: Int): ByteArray {
        fun pad4(str: String): ByteArray {
            val bytes = str.toByteArray(Charsets.US_ASCII)
            val padding = (4 - (bytes.size % 4)) % 4
            return bytes + ByteArray(padding)
        }

        val address = pad4("/heartBeat")
        val typeTag = pad4(",i")
        val valueBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(hr)
            .array()

        return address + typeTag + valueBytes
    }

    @Synchronized
    private fun getSocket(): DatagramSocket {
        if (socket == null || socket?.isClosed == true) {
            socket = DatagramSocket().apply { 
                broadcast = true
                soTimeout = 1000 // 1 second timeout for send operations
            }
            Log.d(TAG, "Created new DatagramSocket")
        }
        return socket!!
    }

    @Synchronized
    private fun closeSocket() {
        try {
            socket?.close()
            socket = null
            Log.d(TAG, "Socket closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket: ${e.message}")
        }
    }

    fun close() {
        if (isClosed.getAndSet(true)) {
            Log.w(TAG, "OscSender already closed")
            return
        }

        Log.d(TAG, "Closing OscSender")
        
        // Shutdown executor gracefully
        executor.shutdown()
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Executor did not terminate in time, forcing shutdown")
                executor.shutdownNow()
                
                // Wait a bit more for forced shutdown
                if (!executor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    Log.e(TAG, "Executor did not terminate after forced shutdown")
                }
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for executor shutdown")
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        // Close socket
        closeSocket()
        
        Log.d(TAG, "OscSender closed")
    }
}
