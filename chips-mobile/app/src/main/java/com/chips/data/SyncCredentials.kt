package com.chips.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Device token is encrypted by a non-exportable Keystore key and excluded from backup. */
class SyncCredentials(context: Context, preferencesName: String = "sync_credentials") {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    data class Config(val endpoint: String, val token: String)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("chips-device", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("chips-device", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(endpoint: String, token: String) {
        val url = endpoint.trim().trimEnd('/').toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Use an HTTPS service URL without credentials or query parameters" }
        require(token.trim().length >= 32) { "Enter the enrolled device token" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = JSONObject().put("endpoint", url.toString().trimEnd('/')).put("token", token.trim()).toString()
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(body.toByteArray()), Base64.NO_WRAP)
        check(prefs.edit().putString("config", encoded).putBoolean("enabled", true).commit()) { "Could not save sync settings" }
    }
    fun read(): Config? {
        val encoded = prefs.getString("config", null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        val data = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size))))
        return Config(data.getString("endpoint"), data.getString("token"))
    }
    fun clear() { check(prefs.edit().clear().commit()) }
    fun configured() = prefs.contains("config")
    // Existing enrollments have no enabled flag and continue syncing after an app update.
    fun connected() = configured() && prefs.getBoolean("enabled", true)
    fun disconnect() { check(prefs.edit().putBoolean("enabled", false).commit()) { "Could not disconnect sync" } }
    fun reconnect() {
        check(configured()) { "Save device enrollment first" }
        check(prefs.edit().putBoolean("enabled", true).commit()) { "Could not reconnect sync" }
    }
}
