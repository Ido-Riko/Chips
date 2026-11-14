package com.chips.heartrate.converter

/** One GATT server's asynchronous registration. Call on the peripheral's handler thread. */
internal class PeripheralRegistration(
    private val add: (Service) -> Boolean,
    private val onReady: () -> Unit,
    private val onFailure: (String) -> Unit
) {
    enum class Service { HEART_RATE, DEVICE_INFO }
    private var pending: Service? = null
    private var active = true
    private var begun = false
    var isReady = false
        private set

    fun begin() {
        if (!active || begun) return
        begun = true
        request(Service.HEART_RATE)
    }

    private fun request(service: Service) {
        pending = service
        try {
            if (!add(service)) fail("Could not request $service service registration")
        } catch (e: Exception) { fail("Service registration failed: ${e.message}") }
    }

    fun complete(service: Service, success: Boolean) {
        if (!active || pending != service) return
        pending = null
        if (!success) { fail("$service service registration failed"); return }
        if (service == Service.HEART_RATE) request(Service.DEVICE_INFO)
        else {
            isReady = true
            try { onReady() } catch (e: Exception) { fail("Peripheral startup failed: ${e.message}") }
        }
    }

    fun timeout() { if (active && !isReady) fail("Service registration timed out") }
    fun fail(message: String) {
        if (!active) return
        stop()
        onFailure(message)
    }
    fun stop() { active = false; pending = null; isReady = false }
}
