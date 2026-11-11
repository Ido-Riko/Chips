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
import com.chips.voice.VoiceController
import com.chips.voice.VoicePhase

@Composable
fun AssistantOverlayContent(onDismiss: () -> Unit, reserveFingerprintSpace: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) {
        val api = ApiClient(context)
        VoiceController(AudioRecorder(context), scope, api::sendAudio)
    }
    AssistantVoiceContent(controller, onDismiss, reserveFingerprintSpace)
}

@Composable
internal fun AssistantVoiceContent(
    controller: VoiceController,
    onDismiss: () -> Unit,
    reserveFingerprintSpace: Boolean = false
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
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(controller, interactionSource, lifecycleOwner) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.press()
                is PressInteraction.Release -> controller.release()
                is PressInteraction.Cancel -> controller.cancelGesture()
            }
        }
    }
    val phase = when (state.phase) {
        VoicePhase.READY -> AssistantPhase.READY
        VoicePhase.RECORDING -> AssistantPhase.LISTENING
        VoicePhase.PROCESSING -> AssistantPhase.PROCESSING
        VoicePhase.REPLY -> AssistantPhase.REPLY
        VoicePhase.ERROR -> AssistantPhase.ERROR
    }
    AssistantPanel(phase, state.text, interactionSource, {
        controller.cancel()
        onDismiss()
    }, reserveFingerprintSpace)
}
