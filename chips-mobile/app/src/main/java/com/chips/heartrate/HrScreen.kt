package com.chips.heartrate

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chips.dashboard.DashboardCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

data class HrDevice(val address: String, val name: String)

@SuppressLint("MissingPermission")
private class HrDiscovery(context: Context, private val result: (HrDevice) -> Unit, private val status: (String) -> Unit) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var scanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, scan: ScanResult) {
            if(!active) return
            val name = scan.scanRecord?.deviceName ?: scan.device.name.orEmpty()
            if(scan.scanRecord?.serviceUuids?.any { it.uuid == HR_SERVICE } == true ||
                name.contains("Xiaomi", true) || name.contains("Mi Band", true) || name.contains("Smart Band", true)) {
                handler.post { if(active) result(HrDevice(scan.device.address, name.ifBlank { "Heart-rate device" })) }
            }
        }
        override fun onScanFailed(errorCode: Int) { handler.post { close(); status("Scan failed ($errorCode). Try again.") } }
    }
    fun start() {
        try {
            scanner = manager?.adapter?.bluetoothLeScanner
            check(scanner != null) { "Turn on Bluetooth to find your band." }
            active = true
            scanner!!.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            status("Looking for HR devices…")
            handler.postDelayed({ close(); status("Scan finished. Select your band, or enable Share HR and scan again.") }, 20_000)
        } catch (_: SecurityException) { close(); status("Allow nearby devices to find your band.") }
        catch (e: Exception) { close(); status(e.message ?: "Bluetooth scan unavailable") }
    }
    fun close() { active = false; handler.removeCallbacksAndMessages(null); runCatching { scanner?.stopScan(callback) } }
}

@Composable
fun HrScreen(onBack: () -> Unit, focusSession: String? = null, onOpenActivity: (String) -> Unit = {}) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state by HrCaptureService.state.collectAsStateWithLifecycle()
    val activities by com.chips.data.ActivityStore.get(context).records.collectAsStateWithLifecycle()
    val preferences = remember { context.getSharedPreferences("heart_rate", Context.MODE_PRIVATE) }
    var selected by rememberSaveable { mutableStateOf(preferences.getString("device", "").orEmpty()) }
    var deviceName by rememberSaveable { mutableStateOf(preferences.getString("name", "").orEmpty()) }
    var relay by rememberSaveable { mutableStateOf(if(state.active) state.outputs.relay else preferences.getBoolean("relay", false)) }
    var osc by rememberSaveable { mutableStateOf(if(state.active) state.outputs.osc else preferences.getBoolean("osc", false)) }
    var host by rememberSaveable { mutableStateOf(if(state.active) state.outputs.host else preferences.getString("host", "255.255.255.255").orEmpty()) }
    var port by rememberSaveable { mutableStateOf((if(state.active) state.outputs.port else preferences.getInt("port", 9000)).toString()) }
    var message by remember { mutableStateOf("") }
    var permitted by remember { mutableStateOf(HrCaptureService.permitted(context)) }
    var notifications by remember { mutableStateOf(HrCaptureService.notificationAvailable(context)) }
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var devices by remember { mutableStateOf(emptyList<HrDevice>()) }
    var sessions by remember { mutableStateOf(emptyList<HrSession>()) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var deletion by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val discovery = remember { HrDiscovery(context, { device -> devices = (devices.filterNot { it.address == device.address } + device) }, { message = it }) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = HrCaptureService.permitted(context)
        notifications = HrCaptureService.notificationAvailable(context)
        message = if(state.active) "Permission updated. Tap Apply outputs to enable the relay."
        else if(permitted && notifications) "Permissions ready. Select your band and start capture." else "Nearby devices and visible notifications are required for capture."
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_STOP) discovery.close()
            if(event == Lifecycle.Event.ON_RESUME) {
                permitted = HrCaptureService.permitted(context)
                notifications = HrCaptureService.notificationAvailable(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); discovery.close() }
    }
    LaunchedEffect(refresh, focusSession) {
        while(true) {
            try {
                sessions = withContext(Dispatchers.IO) { HrStore(context).use { hr ->
                    val all = hr.sessions()
                    val focus = focusSession?.let { hr.sessions(it).singleOrNull() }
                    if(focus == null) all else listOf(focus) + all.filterNot { it.id == focus.id }
                } }
                loaded = true
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message = "Could not read saved captures. Try reopening Heart rate." }
            now = android.os.SystemClock.elapsedRealtime()
            delay(2_000)
        }
    }
    LaunchedEffect(deletion) {
        deletion?.let { id ->
            try {
                withContext(Dispatchers.IO) { HrStore(context).use { it.delete(id) } }
                com.chips.data.ActivitySyncWorker.schedule(context)
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message = "Could not delete capture. Try again." }
            deletion = null; refresh++
        }
    }
    if(confirmDelete != null) AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete capture?") },
        text = { Text("Remove this HR capture and its readings from this phone and queue deletion on the server. A linked Beat Saber activity stays saved.") },
        confirmButton = { TextButton(onClick = { deletion = confirmDelete; confirmDelete = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    Column(Modifier.fillMaxSize().testTag("heart-rate-screen").verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Heart rate", style = MaterialTheme.typography.headlineLarge)
        DashboardCard("Live capture") {
            val fresh = state.elapsed?.let { now - it < 15_000 } == true
            Text(if(fresh) "${state.bpm} bpm" else "— bpm", style = MaterialTheme.typography.headlineLarge)
            Text(if(state.active && state.received != null && !fresh) "No recent HR · check band connection" else state.status)
            state.elapsed?.let { Text("Last received ${((now - it).coerceAtLeast(0) / 1000)} seconds ago") }
            Text("Enable Settings → Share HR on your Smart Band 10. Capture saves on this phone, including when the screen is off.")
            Text("A persistent notification stays on during capture and reconnects. Stop from here or the notification.")
            if(state.output.isNotEmpty()) Text(state.output)
            state.outputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if(state.active) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { HrCaptureService.command(context, HrCaptureService.STOP) }, modifier = Modifier.testTag("hr-stop")) { Text("Stop capture") }
                    OutlinedButton(onClick = { HrCaptureService.command(context, HrCaptureService.RECONNECT) }) { Text("Reconnect") }
                }
            } else {
                if(!permitted || !notifications) {
                    Button(onClick = {
                        val needed = HrCaptureService.permissions().toMutableList()
                        if(Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
                        permissions.launch(needed.toTypedArray())
                    }) { Text("Allow Bluetooth and notifications") }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) { Text("Open app permissions") }
                }
                Text(if(selected.isBlank()) "Choose your band below." else "Selected: $deviceName · $selected")
                OutlinedButton(enabled = permitted, onClick = { discovery.close(); devices = emptyList(); discovery.start() }) { Text("Find band") }
                devices.forEach { device ->
                    TextButton(onClick = {
                        discovery.close(); selected = device.address; deviceName = device.name
                        preferences.edit { putString("device", selected); putString("name", deviceName) }
                    }) { Text("${device.name} · ${device.address}") }
                }
                if(state.stopping) Text("Finishing saved capture…")
                Button(enabled = !state.stopping && permitted && notifications && selected.isNotEmpty(), modifier = Modifier.testTag("hr-start"), onClick = {
                    val targetPort = port.toIntOrNull()
                    if(osc && (host.isBlank() || targetPort == null || targetPort !in 1..65535)) message = "Enter an OSC host and a port from 1 to 65535."
                    else if(relay && Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE))
                    else {
                        discovery.close()
                        preferences.edit { putBoolean("relay", relay); putBoolean("osc", osc); putString("host", host.trim()); putInt("port", targetPort ?: 9000) }
                        try {
                            ContextCompat.startForegroundService(context, Intent(context, HrCaptureService::class.java).setAction(HrCaptureService.START)
                                .putExtra("device", selected).putExtra("relay", relay).putExtra("osc", osc).putExtra("host", host.trim()).putExtra("port", targetPort ?: 9000))
                            message = ""
                        } catch (_: Exception) { message = "Could not start HR capture. Check permissions and try again." }
                    }
                }) { Text("Start capture") }
            }
            if(message.isNotBlank()) Text(message)
        }
        DashboardCard("Optional outputs") {
            Row { Text("Rebroadcast HR over Bluetooth", Modifier.weight(1f)); Switch(relay, { relay = it }, enabled = !state.stopping && !state.updatingOutputs) }
            Text("Pair the phone on your Quest or receiver and confirm Android's pairing prompt. Bonding is required, as in MiBand-Converter.")
            Row { Text("Send OSC /heartBeat", Modifier.weight(1f)); Switch(osc, { osc = it }, enabled = !state.stopping && !state.updatingOutputs) }
            if(osc) {
                OutlinedTextField(host, { host = it }, label = { Text("OSC host / IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it }, label = { Text("OSC port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if(state.active) {
                Text("Enabled: BLE ${if(state.outputs.relay) "on" else "off"} · OSC ${if(state.outputs.osc) "${state.outputs.host}:${state.outputs.port}" else "off"}")
                Button(enabled = !state.updatingOutputs && !state.stopping, modifier = Modifier.testTag("hr-apply-outputs"), onClick = {
                    val targetPort = port.toIntOrNull()
                    if(osc && (host.isBlank() || targetPort == null || targetPort !in 1..65535)) message = "Enter an OSC host and a port from 1 to 65535."
                    else if(relay && Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE))
                    else {
                        try {
                            context.startService(Intent(context, HrCaptureService::class.java).setAction(HrCaptureService.UPDATE_OUTPUTS)
                                .putExtra("relay", relay).putExtra("osc", osc).putExtra("host", host.trim()).putExtra("port", targetPort ?: 9000))
                            message = ""
                        } catch (_: Exception) { message = "Could not update outputs. Capture is still running." }
                    }
                }) { Text(if(state.updatingOutputs) "Updating outputs…" else "Apply outputs") }
                Text("Change outputs or the OSC target without stopping this capture.")
            }
        }
        Text("Saved captures", style = MaterialTheme.typography.titleLarge)
        if(loaded && focusSession != null && sessions.none { it.id == focusSession }) Text("This linked HR capture is unavailable on this phone. The activity stays saved.")
        if(sessions.isEmpty()) Text("No saved captures yet.")
        sessions.forEach { session -> DashboardCard(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(session.started)), modifier = Modifier.testTag("hr-session-${session.id}")) {
            Text("${session.status} · ${session.samples} received samples")
            Text(if(session.average == null) "No HR samples received" else "Average %.1f bpm · peak %d bpm".format(session.average, session.peak))
            Text("Statistics describe received samples. Disconnects and missing samples are not zero HR.")
            if(session.ended != null) {
                Text(when(session.syncStatus) { "SYNCED" -> "HR saved on this phone and server"; "WAITING_RECOVERY" -> "Interrupted · waiting for recovery or your save choice"; else -> "HR saved on this phone · waiting for server sync" })
                if(session.syncError.isNotBlank()) Text(session.syncError, color = MaterialTheme.colorScheme.error)
                val linked = activities.firstOrNull { it.hrSessionId == session.id }
                if(linked != null) {
                    Text(if(linked.deleted) "Linked Beat Saber activity was deleted. HR is retained." else "Linked to a Beat Saber session")
                    if(!linked.deleted) TextButton(onClick = { onOpenActivity(linked.id) }, modifier = Modifier.testTag("hr-open-activity-${session.id}")) { Text("Open Beat Saber session") }
                } else TextButton(onClick = { scope.launch {
                    try { withContext(Dispatchers.IO) { HrStore(context).use { it.requestPrompt(session.id) } } }
                    catch(e: kotlinx.coroutines.CancellationException) { throw e }
                    catch(_: Exception) { message = "Could not open the save choice. Your HR capture is retained." }
                } }) { Text("Save Beat Saber session") }
                if(session.syncStatus == "WAITING_RECOVERY") TextButton(onClick = { scope.launch {
                    try {
                        check(withContext(Dispatchers.IO) { HrStore(context).use { it.seal(session.id) } })
                        com.chips.data.ActivitySyncWorker.schedule(context)
                        refresh++
                    } catch(e: kotlinx.coroutines.CancellationException) { throw e }
                    catch(_: Exception) { message = "Could not finalize this capture. If it recovered, stop it first." }
                } }) { Text("Keep interrupted HR capture") }
                TextButton(onClick = { com.chips.data.ActivitySyncWorker.schedule(context, retryNow = true) }) { Text("Retry sync") }
                TextButton(onClick = { confirmDelete = session.id }) { Text("Delete capture") }
            }
        } }
    }
}
