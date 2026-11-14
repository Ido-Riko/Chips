package com.chips.sleep

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import com.chips.dashboard.DashboardCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SleepScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(emptyList<SleepEntry>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    suspend fun load() { rows = withContext(Dispatchers.IO) { SleepStore(context).use { it.entries() } } }
    fun import() { scope.launch {
        busy = true
        try {
            val count = withContext(Dispatchers.IO) { SleepImport.run(context) }
            load(); message = "Imported $count sleep sessions. Saved on this phone."
        } catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { message = e.message ?: "Import failed. Saved history is unchanged; try again." }
        finally { busy = false }
    } }
    val permissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        if(SleepImport.permission in it) import() else message = "Sleep access was not granted. Saved history remains available."
    }
    LaunchedEffect(Unit) { try { load() } catch(e: kotlinx.coroutines.CancellationException) { throw e } catch(_: Exception) { message = "Could not load saved sleep history." } }
    if(confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Remove saved sleep history?") },
        text = { Text("This removes CHIPS' local copy. Health Connect and Gadgetbridge retain their records; importing again restores accessible records.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch {
            withContext(Dispatchers.IO) { SleepStore(context).use { it.clear() } }; load()
        } }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { DashboardCard("Sleep history") {
            Text("Sync your Smart Band in Gadgetbridge. In Gadgetbridge → Settings → External Integrations → Health Connect, enable export, select your band and choose the initial history range. Enable sync after device sync.")
            Text("CHIPS reads sleep sessions and stages from Health Connect when you import. Sources are shown separately; overlapping records are not added together. No sleep score is invented.")
            Text("Without past-data access, imports cover the last 30 days. Older saved imports remain available offline.")
            Button(enabled = !busy, onClick = {
                when(HealthConnectClient.getSdkStatus(context)) {
                    HealthConnectClient.SDK_AVAILABLE -> scope.launch {
                        try {
                            val client = HealthConnectClient.getOrCreate(context)
                            val requested = mutableSetOf(SleepImport.permission)
                            if(client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE)
                                requested += SleepImport.historyPermission
                            permissions.launch(requested)
                        } catch(_: Exception) { message = "Could not open Health Connect permissions. Try opening Health Connect settings." }
                    }
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=com.google.android.apps.healthdata".toUri())) }
                            .onFailure { message = "Install or update Health Connect from Google Play." }
                    }
                    else -> message = "Health Connect is unavailable on this device. Android 9 or later with Health Connect is required."
                }
            }) { Text(if(busy) "Importing…" else "Connect / import sleep") }
            TextButton(enabled = !busy, onClick = { runCatching { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
                .onFailure { message = "Health Connect settings are unavailable on this device." } }) { Text("Health Connect settings") }
            if(message.isNotBlank()) Text(message)
            if(rows.isNotEmpty()) TextButton(enabled = !busy, onClick = { confirmClear = true }) { Text("Remove local sleep history") }
        } }
        if(rows.isEmpty()) item { Text("No imported sleep yet. After syncing the band and Gadgetbridge, tap Connect / import sleep.") }
        items(rows, key = { "${it.origin}/${it.id}" }) { row -> DashboardCard(row.wakeDate) {
            Text("${row.minutes / 60}h ${row.minutes % 60}m · session duration", style = MaterialTheme.typography.titleLarge)
            val format = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(row.offset?.let { java.time.ZoneOffset.ofTotalSeconds(it) } ?: java.time.ZoneId.systemDefault())
            Text("${format.withZone(row.startOffset?.let { java.time.ZoneOffset.ofTotalSeconds(it) } ?: java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(row.start))} → ${format.format(java.time.Instant.ofEpochMilli(row.end))}")
            Text("Source: ${row.origin}")
            val stages = row.stageMinutes()
            Text(if(stages.isEmpty()) "Sleep stages unavailable" else stages.entries.joinToString(" · ") { "${it.key} ${it.value}m" })
        } }
    }
}
