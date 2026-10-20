package com.chips.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chips.audio.AudioRecorder
import com.chips.network.ApiClient
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun AssistantOverlayContent(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val audioRecorder = remember { AudioRecorder(context) }
    val apiClient = remember { ApiClient(context.applicationContext) }
    
    var responseText by remember { mutableStateOf("Ready to listen...") }
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
            responseText = "Ready to listen..."
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
            responseText = "Listening..."
            audioRecorder.startRecording()
        } else {
            if (isRecording) { // Means it was just released
                isRecording = false
                responseText = "Processing..."
                val audioFile = audioRecorder.stopRecording()
                
                if (audioFile != null) {
                    isProcessing = true
                    requestJob = coroutineScope.launch {
                        try {
                            responseText = apiClient.sendAudio(audioFile)
                        } finally {
                            audioFile.delete()
                            isProcessing = false
                        }
                    }
                } else {
                    responseText = "Failed to record audio. Ready."
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                // Add a large bottom margin to completely clear the under-display fingerprint sensor
                .padding(bottom = 120.dp)
                .wrapContentHeight(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = responseText, 
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Button(
                    onClick = { },
                    enabled = !isProcessing,
                    interactionSource = interactionSource,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(if (isRecording) "Release to Send" else "Hold to Speak")
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}
