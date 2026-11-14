package com.chips.heartrate

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent

/** Local transient intent to keep an active capture alive; deliberately excluded from backups. */
// Synchronous, checked writes make restart intent durable before capture/Stop is acknowledged.
@SuppressLint("ApplySharedPref", "UseKtx")
internal class HrRecoverySettings(context: Context) {
    private val preferences = context.getSharedPreferences("hr_capture_recovery", Context.MODE_PRIVATE)
    fun restart(context: Context): Intent? {
        if(!preferences.getBoolean("active", false)) return null
        return Intent(context, HrCaptureService::class.java).setAction(HrCaptureService.START)
            .putExtra("device", preferences.getString("device", null))
            .putExtra("relay", preferences.getBoolean("relay", false))
            .putExtra("osc", preferences.getBoolean("osc", false))
            .putExtra("host", preferences.getString("host", "255.255.255.255"))
            .putExtra("port", preferences.getInt("port", 9000))
    }
    fun session(): String? = preferences.getString("session", null)
    fun save(id: String, device: String, config: HrOutputConfig) {
        check(preferences.edit().putBoolean("active", true).putString("session", id).putString("device", device)
            .putBoolean("relay", config.relay).putBoolean("osc", config.osc).putString("host", config.host).putInt("port", config.port).commit())
    }
    fun outputs(config: HrOutputConfig) {
        check(preferences.edit().putBoolean("relay", config.relay).putBoolean("osc", config.osc)
            .putString("host", config.host).putInt("port", config.port).commit())
    }
    fun clear() { preferences.edit().putBoolean("active", false).commit() }
}
