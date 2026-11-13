package com.chips.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chips.audio.AudioRecorder
import com.chips.network.ApiClient
import com.chips.data.VoiceHistoryStore
import android.app.KeyguardManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.chips.voice.VoiceController
import com.chips.voice.VoicePhase

@Composable
fun AssistantOverlayContent(onDismiss: () -> Unit, reserveFingerprintSpace: Boolean = false, onFeature: ((FeatureRoute) -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) {
        val api = ApiClient(context)
        VoiceController(AudioRecorder(context), scope, api, VoiceHistoryStore.get(context)::save)
    }
    val keyguard = remember(context) { context.getSystemService(KeyguardManager::class.java) }
    var microphoneAvailable by remember { mutableStateOf(false) }
    var unlocked by remember { mutableStateOf(!keyguard.isDeviceLocked) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, controller) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                microphoneAvailable = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                unlocked = !keyguard.isDeviceLocked
                if (!unlocked) controller.cancel()
                delay(250)
            }
        }
    }
    AssistantVoiceContent(controller, onDismiss, reserveFingerprintSpace, unlocked,
        canUseVoice = { !keyguard.isDeviceLocked && microphoneAvailable }, microphoneAvailable = microphoneAvailable,
        onFeature = { route ->
            if(!keyguard.isDeviceLocked) {
                controller.cancel()
                if(onFeature != null) onFeature(route) else {
                    context.startActivity(featureIntent(context, route).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    onDismiss()
                }
            }
        })
}

@Composable
internal fun AssistantVoiceContent(
    controller: VoiceController,
    onDismiss: () -> Unit,
    reserveFingerprintSpace: Boolean = false,
    unlocked: Boolean = true,
    canUseVoice: () -> Boolean = { true },
    onFeature: ((FeatureRoute) -> Unit)? = null,
    microphoneAvailable: Boolean = true
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.cancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.cancel()
        }
    }
    LaunchedEffect(unlocked) { if (!unlocked) controller.cancel() }
    if (!unlocked) {
        LockedVoicePanel(onDismiss)
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(controller, interactionSource, lifecycleOwner) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> if (canUseVoice() && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.press()
                is PressInteraction.Release -> if (canUseVoice()) controller.release() else controller.cancel()
                is PressInteraction.Cancel -> controller.cancelGesture()
            }
        }
    }
    val phase = when (state.phase) {
        VoicePhase.READY -> AssistantPhase.READY
        VoicePhase.RECORDING -> AssistantPhase.LISTENING
        VoicePhase.UPLOADING -> AssistantPhase.UPLOADING
        VoicePhase.PROCESSING -> AssistantPhase.PROCESSING
        VoicePhase.SAVING -> AssistantPhase.SAVING
        VoicePhase.REPLY -> AssistantPhase.REPLY
        VoicePhase.ERROR -> AssistantPhase.ERROR
    }
    AssistantPanel(phase, state.text, interactionSource, {
        controller.cancel()
        onDismiss()
    }, reserveFingerprintSpace, state.transcript, state.canRetry, state.retrySaving,
        onRetry = { if (canUseVoice()) controller.retry() else controller.cancel() },
        onDiscard = controller::cancel, onFeature = onFeature, microphoneAvailable = microphoneAvailable)
}

@Composable
internal fun LockedVoicePanel(onDismiss: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Unlock to use CHIPS", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Text("Voice and saved conversations are available after you unlock your phone.")
            TextButton(onClick = onDismiss) { Text("Close assistant") }
        }
    }
}
