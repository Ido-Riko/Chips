package com.chips.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chips.data.*
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chips.dashboard.*
import com.chips.ui.theme.ChipsAccent
import kotlinx.coroutines.launch

private enum class Destination(val label: String, val symbol: ChipsSymbol) {
    DASHBOARD("Today", ChipsSymbol.TODAY), HISTORY("History", ChipsSymbol.HISTORY), SETTINGS("Settings", ChipsSymbol.SETTINGS)
}

@Composable
fun ChipsApp(
    micPermissionGranted: Boolean,
    isAssistant: Boolean,
    settingsError: String?,
    onRequestMicrophone: () -> Unit,
    onAssistantSettings: () -> Unit,
    onAppSettings: () -> Unit,
    dashboard: DashboardViewModel = viewModel(factory = viewModelFactory {
        initializer { DashboardViewModel(ActivityStore.get(this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!)) }
    })
) {
    val state by dashboard.state.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.DASHBOARD) }
    var showVoice by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(Unit) { ActivitySyncWorker.schedule(context) }
    val onActivity: (ActivityType, Boolean) -> Unit = { type, toggle ->
        if (state.demoMode) {
            dashboard.previewActivity(type)
            snackbar.currentSnackbarData?.dismiss()
            if (!toggle) scope.launch {
                snackbar.showSnackbar("${type.label}: preview changed. Nothing saved.")
            }
        } else scope.launch {
            try {
                val saved = if (toggle) dashboard.toggleToday(type) else dashboard.log(type)
                runCatching { ActivitySyncWorker.schedule(context) }
                snackbar.currentSnackbarData?.dismiss()
                // The checked tile itself confirms a rhythm toggle. A snackbar can cover
                // that same tile on compact screens and intercept the next tap.
                if (!toggle && saved != null && snackbar.showSnackbar("${type.label} saved on this phone", "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                    dashboard.delete(saved.id)
                    ActivitySyncWorker.schedule(context)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { snackbar.showSnackbar("Could not save this change. Try again.") }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                if (destination == Destination.DASHBOARD) QuickLogBar(state.demoMode) { onActivity(it, false) }
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp)) {
                            Destination.entries.forEach { target ->
                                val selected = destination == target
                                val color = if (selected) ChipsAccent else MaterialTheme.colorScheme.onSurfaceVariant
                                Column(
                                    Modifier.weight(1f).testTag("nav-${target.name}")
                                        .clip(RoundedCornerShape(14.dp))
                                        .selectable(selected = selected, role = Role.Tab, onClick = { destination = target })
                                        .padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    ChipsIcon(target.symbol, color, Modifier.size(21.dp))
                                    Text(target.label, style = MaterialTheme.typography.labelMedium, color = color)
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (destination) {
                Destination.DASHBOARD -> DashboardScreen(state, { onActivity(it, true) }) {
                    if (micPermissionGranted) showVoice = true else destination = Destination.SETTINGS
                }
                Destination.HISTORY -> {
                    var conversations by rememberSaveable { mutableStateOf(false) }
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilterChip(selected = !conversations, onClick = { conversations = false }, label = { Text("Activities") })
                            FilterChip(selected = conversations, onClick = { conversations = true }, label = { Text("Conversations") })
                        }
                        Box(Modifier.weight(1f)) {
                            if (conversations) VoiceHistoryScreen() else ActivityHistoryScreen(state, dashboard)
                        }
                    }
                }
                Destination.SETTINGS -> SettingsScreen(
                    micPermissionGranted, isAssistant, settingsError, state.demoMode,
                    onRequestMicrophone, onAssistantSettings, onAppSettings, dashboard::setDemoMode
                )
            }
        }
    }
    if (showVoice && micPermissionGranted) {
        Dialog(onDismissRequest = { showVoice = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                AssistantOverlayContent(onDismiss = { showVoice = false })
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    microphoneGranted: Boolean,
    assistantSelected: Boolean,
    error: String?,
    demoMode: Boolean,
    onRequestMicrophone: () -> Unit,
    onAssistantSettings: () -> Unit,
    onAppSettings: () -> Unit,
    onDemoMode: (Boolean) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Make it yours.", style = MaterialTheme.typography.headlineLarge)
        Text("Settings", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DashboardCard("Dashboard preview") {
            Text("Take a look around with sample data. Nothing here is saved or sent.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Show preview data", modifier = Modifier.weight(1f).padding(top = 12.dp))
                Switch(
                    checked = demoMode, onCheckedChange = onDemoMode,
                    modifier = Modifier.semantics { contentDescription = "Show preview data" }
                )
            }
        }
        SyncSettingsCard()
        DashboardCard("Microphone") {
            Text(if (microphoneGranted) "Microphone access granted" else "Microphone access is off")
            Text("Needed only when speaking to CHIPS. You can use the dashboard without it.")
            if (!microphoneGranted) {
                Button(onClick = onRequestMicrophone) { Text("Allow microphone") }
                Text("If Android no longer shows the permission prompt, enable it in App settings.")
            }
            TextButton(onClick = onAppSettings) { Text("Open App settings") }
        }
        DashboardCard("Device assistant") {
            Text(if (assistantSelected) "CHIPS is your selected assistant" else "CHIPS is not your selected assistant")
            Text("On the next screen, open Digital assistant app and select CHIPS.")
            Text("Optional. You can also speak from Today without changing your device assistant.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onAssistantSettings) { Text("Choose device assistant") }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        }
        DashboardCard("Voice service") {
            Text("Voice uses your saved service enrollment. The service selects speech recognition and the answer model independently; either may run on your PC or use an explicitly configured API.")
            Text("A compatible service must return a real transcript and reply. Successful conversations are saved on this phone. The earlier demo service needs an update before it can be used.")
            Text("Voice is available only while the phone is unlocked. Closing the assistant discards any recording waiting for retry.")
        }
    }
}
