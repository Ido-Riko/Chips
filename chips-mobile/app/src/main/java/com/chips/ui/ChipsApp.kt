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
import com.chips.beatsaber.*
import com.chips.practice.*
import com.chips.pantry.*
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
    requestedAssistant: Boolean = false,
    onAssistantHandled: () -> Unit = {},
    onAssistantClosed: () -> Unit = {},
    requestedFeature: FeatureRoute? = null,
    onFeatureHandled: () -> Unit = {},
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
    val beatSaber: BeatSaberViewModel = viewModel(factory = viewModelFactory { initializer { BeatSaberViewModel(context.applicationContext as android.app.Application) } })
    val beatState by beatSaber.state.collectAsStateWithLifecycle()
    val practice: PracticeViewModel = viewModel(factory = viewModelFactory { initializer { PracticeViewModel(context.applicationContext as android.app.Application) } })
    val practiceState by practice.state.collectAsStateWithLifecycle()
    val pantry: PantryViewModel = viewModel(factory = viewModelFactory { initializer { PantryViewModel(context.applicationContext as android.app.Application) } })
    val pantryState by pantry.state.collectAsStateWithLifecycle()
    var practicePage by rememberSaveable { mutableStateOf("") }
    var showBeatSaber by rememberSaveable { mutableStateOf(false) }
    var showWorkoutLog by rememberSaveable { mutableStateOf(false) }
    val openFeature: (FeatureRoute) -> Unit = { feature ->
        dashboard.setDemoMode(false)
        showVoice = false
        showWorkoutLog = false
        destination = Destination.DASHBOARD
        showBeatSaber = false
        practicePage = ""
        when(feature) {
            FeatureRoute.JAPANESE -> { practicePage = "japanese"; practice.refresh() }
            FeatureRoute.DRAWING -> { practicePage = "drawing"; practice.refresh() }
            FeatureRoute.BEAT_SABER -> { showBeatSaber = true; beatSaber.load() }
            FeatureRoute.PANTRY -> { practicePage = "pantry"; pantry.refresh() }
            FeatureRoute.WORKOUT -> showWorkoutLog = true
            FeatureRoute.HEART_RATE -> practicePage = "heart_rate"
        }
    }
    LaunchedEffect(requestedAssistant) { if(requestedAssistant) { showVoice = true; onAssistantHandled() } }
    LaunchedEffect(requestedFeature) {
        requestedFeature?.let { openFeature(it); onFeatureHandled() }
    }
    LaunchedEffect(destination) { if (destination == Destination.DASHBOARD) beatSaber.load() }
    LaunchedEffect(Unit) { ActivitySyncWorker.schedule(context) }
    val onLog: (ActivityType) -> Unit = { type ->
        if (state.demoMode) dashboard.previewActivity(type)
        else scope.launch {
            try {
                val saved = dashboard.log(type)
                runCatching { ActivitySyncWorker.schedule(context) }
                snackbar.currentSnackbarData?.dismiss()
                if (snackbar.showSnackbar("${type.label} saved on this phone", "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                    dashboard.delete(saved.id)
                    ActivitySyncWorker.schedule(context)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { snackbar.showSnackbar("Could not save this change. Try again.") }
        }
    }
    val onShortcut: (ActivityType) -> Unit = { type ->
        if(state.demoMode) dashboard.previewActivity(type) else openFeature(FeatureRoute.activity(type))
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
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
                                        .selectable(selected = selected, role = Role.Tab, onClick = {
                                            if (target == Destination.DASHBOARD) { showBeatSaber = false; practicePage = "" }
                                            destination = target
                                        })
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
                Destination.DASHBOARD -> if (practicePage == "heart_rate") com.chips.heartrate.HrScreen(onBack = { practicePage = "" })
                else if (practicePage == "pantry") PantryScreen(pantryState, pantry, onBack = { practicePage = "" })
                else if (practicePage == "japanese") JapaneseScreen(practiceState,
                    onBack = { practicePage = "" }, onRate = practice::rate, onReset = practice::reset, onRefresh = practice::reload)
                else if (practicePage == "drawing") DrawingScreen(practiceState, onBack = { practicePage = "" },
                    onImport = practice::importDrawing, onSend = practice::send, onNotes = practice::notes, onDelete = practice::remove, onRefresh = practice::reload)
                else if (showBeatSaber) BeatSaberScreen(beatState,
                    onBack = { showBeatSaber = false }, onConnect = beatSaber::connect,
                    onRefresh = beatSaber::refresh, onReload = beatSaber::load,
                    onRemove = beatSaber::remove, onMap = beatSaber::map, onLogSession = { onLog(ActivityType.BEAT_SABER) }
                ) else DashboardScreen(state, onShortcut, beatState,
                    onBeatSaber = { openFeature(FeatureRoute.BEAT_SABER) },
                    onPantry = { openFeature(FeatureRoute.PANTRY) }, pantryCount = pantryState.items.size,
                    onHeartRate = { openFeature(FeatureRoute.HEART_RATE) },
                    onJapanese = { openFeature(FeatureRoute.JAPANESE) }, onDrawing = { openFeature(FeatureRoute.DRAWING) },
                    japaneseDue = practiceState.due.size, drawingCount = practiceState.drawings.size,
                    drawingSummary = practiceState.drawings.firstOrNull { it.feedback.isNotEmpty() }?.feedback?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(120).orEmpty()) {
                    showVoice = true
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
    if(showWorkoutLog) AlertDialog(
        onDismissRequest = { showWorkoutLog = false }, title = { Text("Log a workout") },
        text = { Text("Save a completed workout on this phone. Open Heart rate from Today to capture live HR separately.") },
        confirmButton = { TextButton(onClick = { showWorkoutLog = false; onLog(ActivityType.WORKOUT) }, modifier = Modifier.testTag("save-workout")) { Text("Save workout") } },
        dismissButton = { TextButton(onClick = { showWorkoutLog = false }) { Text("Cancel") } })
    if (showVoice) {
        Dialog(onDismissRequest = { showVoice = false; onAssistantClosed() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                AssistantOverlayContent(onDismiss = { showVoice = false; onAssistantClosed() }, onFeature = openFeature)
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
        AssistantSecurityCard()
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
            Text("Lockscreen access follows your security setting above. Closing the assistant discards any recording waiting for retry.")
        }
    }
}

@Composable
internal fun AssistantSecurityCard() {
    val context = LocalContext.current
    val access = remember { com.chips.assistant.AssistantAccessSettings(context) }
    val keyguard = remember { context.getSystemService(android.app.KeyguardManager::class.java) }
    var enabled by remember { mutableStateOf(access.allowLocked()) }
    var unlocked by remember { mutableStateOf(!keyguard.isDeviceLocked) }
    LaunchedEffect(Unit) { while(true) { unlocked = !keyguard.isDeviceLocked; enabled = access.allowLocked(); kotlinx.coroutines.delay(250) } }
    DashboardCard("Assistant security", modifier = Modifier.testTag("assistant-security")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Allow assistant while locked", Modifier.weight(1f))
            Switch(checked = enabled, enabled = unlocked, onCheckedChange = { value ->
                if(!keyguard.isDeviceLocked) { access.setAllowLocked(value); enabled = value }
            }, modifier = Modifier.semantics { contentDescription = "Allow assistant while locked" })
        }
        Text("Voice, replies and activity screens can be used without unlocking. Anyone holding your phone may see that information.", style = MaterialTheme.typography.bodyMedium)
        if(!unlocked) Text("Unlock your phone to change this setting.", style = MaterialTheme.typography.bodySmall)
    }
}
