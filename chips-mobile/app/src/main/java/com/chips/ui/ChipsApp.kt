package com.chips.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chips.dashboard.*
import kotlinx.coroutines.launch

private enum class Destination(val label: String, val symbol: String) {
    DASHBOARD("Today", "◫"), HISTORY("History", "◷"), SETTINGS("Settings", "⚙")
}

@Composable
fun ChipsApp(
    micPermissionGranted: Boolean,
    isAssistant: Boolean,
    settingsError: String?,
    onRequestMicrophone: () -> Unit,
    onAssistantSettings: () -> Unit,
    onAppSettings: () -> Unit,
    dashboard: DashboardViewModel = viewModel()
) {
    val state by dashboard.state.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.DASHBOARD) }
    var showVoice by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val onLog: (ActivityType) -> Unit = { type ->
        if (state.demoMode) dashboard.previewActivity(type)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(
                if (state.demoMode) "${type.label}: preview changed. Nothing saved."
                else "Logging is not available yet. No activity was saved."
            )
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                if (destination == Destination.DASHBOARD) QuickLogBar(state.demoMode, onLog)
                NavigationBar {
                    Destination.entries.forEach { target ->
                        NavigationBarItem(
                            modifier = Modifier.testTag("nav-${target.name}"),
                            selected = destination == target,
                            onClick = { destination = target },
                            icon = { Text(target.symbol, modifier = Modifier.clearAndSetSemantics { }) },
                            label = { Text(target.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (destination) {
                Destination.DASHBOARD -> DashboardScreen(state, onLog) {
                    if (micPermissionGranted) showVoice = true else destination = Destination.SETTINGS
                }
                Destination.HISTORY -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("History", style = MaterialTheme.typography.headlineMedium)
                    Text("No saved entries yet", style = MaterialTheme.typography.titleLarge)
                    Text("Saved activities and voice conversations will appear here when storage is connected.")
                    Text("Dashboard previews never create history.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        DashboardCard("Dashboard preview") {
            Text("Explore fictional data. Preview completions are temporary and never saved or sent.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Show preview data", modifier = Modifier.weight(1f).padding(top = 12.dp))
                Switch(
                    checked = demoMode, onCheckedChange = onDemoMode,
                    modifier = Modifier.semantics { contentDescription = "Show preview data" }
                )
            }
        }
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
            Text("Optional. You can also open voice from Today. Device gestures and lock-screen support need verification on your phone.")
            OutlinedButton(onClick = onAssistantSettings) { Text("Choose device assistant") }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        }
        DashboardCard("Development build") {
            Text("Voice currently uses the existing demo service. Real transcription and saved history are not connected yet.")
        }
    }
}
