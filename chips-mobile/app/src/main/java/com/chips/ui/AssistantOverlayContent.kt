package com.chips.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.chips.audio.AudioRecorder
import com.chips.network.ApiClient
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AssistantOverlayContent(onDismiss: () -> Unit, reserveFingerprintSpace: Boolean = false) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val audioRecorder = remember { AudioRecorder(context) }
    val apiClient = remember { ApiClient(context.applicationContext) }
    
    var responseText by remember { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Crucial: Clean up the recorder if the Android system suddenly hides our session window
    // while the person is still holding the button!
    DisposableEffect(lifecycleOwner) {
        val stop = {
            requestJob?.cancel()
            if (isRecording) audioRecorder.stopRecording()?.delete()
            isRecording = false
            isProcessing = false
            responseText = null
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed) {
        if (isPressed && !isProcessing) {
            isRecording = true
            responseText = null
            audioRecorder.startRecording()
        } else {
            if (isRecording) { // Means it was just released
                isRecording = false
                val audioFile = audioRecorder.stopRecording()
                
                if (audioFile != null) {
                    isProcessing = true
                    requestJob = coroutineScope.launch {
                        try {
                            responseText = apiClient.sendAudio(audioFile)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            responseText = "Error: Could not send the recording. Check your connection and try again."
                        } finally {
                            audioFile.delete()
                            isProcessing = false
                        }
                    }
                } else {
                    responseText = "Error: Could not record audio. Try holding the button a little longer."
                }
            }
        }
    }

    val phase = when {
        isRecording -> AssistantPhase.LISTENING
        isProcessing -> AssistantPhase.PROCESSING
        responseText?.startsWith("Error:") == true -> AssistantPhase.ERROR
        responseText != null -> AssistantPhase.REPLY
        else -> AssistantPhase.READY
    }
    AssistantPanel(phase, responseText, interactionSource, onDismiss, reserveFingerprintSpace)
}
