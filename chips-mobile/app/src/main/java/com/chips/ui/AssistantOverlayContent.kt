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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AssistantOverlayContent(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val audioRecorder = remember { AudioRecorder(context) }
    val apiClient = remember { ApiClient() }
    
    var responseText by remember { mutableStateOf("Ready to listen...") }
    var isRecording by remember { mutableStateOf(false) }

    // Crucial: Clean up the recorder if the Android system suddenly hides our session window
    // while the person is still holding the button!
    DisposableEffect(Unit) {
        onDispose {
            audioRecorder.stopRecording()
            isRecording = false
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed) {
        if (isPressed) {
            isRecording = true
            responseText = "Listening..."
            audioRecorder.startRecording()
        } else {
            if (isRecording) { // Means it was just released
                isRecording = false
                responseText = "Processing..."
                val audioFile = audioRecorder.stopRecording()
                
                if (audioFile != null) {
                    coroutineScope.launch(Dispatchers.IO) {
                        val response = apiClient.sendAudio(audioFile)
                        responseText = response
                        // Auto dismiss after 5 seconds
                        delay(5000)
                        launch(Dispatchers.Main) {
                            onDismiss()
                        }
                    }
                } else {
                    responseText = "Failed to record audio. Ready."
                }
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = responseText, 
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { },
                interactionSource = interactionSource
            ) {
                Text(if (isRecording) "Release to Send" else "Hold to Speak")
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}
