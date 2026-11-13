// Ported from Ido-Riko/MiBand-Converter @ a472d64a6743454db0b63c902684be36426f2ff9.
// Owner-authorized reuse. CHIPS changes are listed in docs/HEART_RATE.md.
package com.chips.heartrate.converter

import android.bluetooth.le.AdvertiseSettings

data class AdvertisingConfig(
    val mode: Int = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
    val txPower: Int = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
    val timeout: Int = 0 // 0 = Infinite
) {
    companion object {
        val HIGH_PERFORMANCE = AdvertisingConfig(
            AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
            AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
            0
        )
        val BALANCED = AdvertisingConfig(
            AdvertiseSettings.ADVERTISE_MODE_BALANCED,
            AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
            0
        )
        val LOW_POWER = AdvertisingConfig(
            AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
            AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
            0
        )
    }
}

