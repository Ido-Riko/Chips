package com.chips.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.chips.data.*
import com.chips.dashboard.DashboardCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
fun SyncSettingsCard() {
    val context = LocalContext.current
    val credentials = remember { SyncCredentials(context) }
    val savedConfig = remember { runCatching { credentials.read() }.getOrNull() }
    var configured by remember { mutableStateOf(credentials.configured()) }
    var connected by remember { mutableStateOf(credentials.connected()) }
    var endpoint by remember { mutableStateOf(savedConfig?.endpoint.orEmpty()) }
    var token by remember { mutableStateOf(savedConfig?.token.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val syncPrefs = remember { context.getSharedPreferences("sync_state", Context.MODE_PRIVATE) }
    var syncError by remember { mutableStateOf(syncPrefs.getString("error", null)) }
    DisposableEffect(syncPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ -> syncError = prefs.getString("error", null) }
        syncPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { syncPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DashboardCard("Synchronization") {
        Text(when {
            connected -> "Sync configured"
            configured -> "Sync disconnected · credentials saved"
            else -> "Local only · entries stay saved without enrollment"
        })
        Text("Enter the HTTPS address ending in /api/v1 and your revocable device token. This installation keeps the same owner and device identity.")
        syncError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("HTTPS service URL") }, singleLine = true)
        OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Device token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(mask = '*'))
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { credentials.save(endpoint, token) }
                    token = token.trim(); configured = true; connected = true; error = null
                    ActivitySyncWorker.schedule(context)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "Could not save. Check the HTTPS address and enrolled device token." }
                finally { busy = false }
            }
        }) { Text(if (busy) "Saving…" else "Save sync settings") }
        if (configured) Row {
            if (connected) {
                TextButton(enabled = !busy, onClick = { ActivitySyncWorker.schedule(context, retryNow = true) }) { Text("Retry sync") }
                TextButton(enabled = !busy, onClick = { ActivitySyncWorker.disconnect(context); connected = false }) { Text("Disconnect") }
            } else {
                TextButton(enabled = !busy, onClick = {
                    credentials.reconnect()
                    connected = true
                    ActivitySyncWorker.schedule(context, retryNow = true)
                }) { Text("Reconnect") }
            }
        }
        if (configured) Text("Disconnect pauses sync and keeps your saved URL and token.", style = MaterialTheme.typography.bodyMedium)
    }
}
