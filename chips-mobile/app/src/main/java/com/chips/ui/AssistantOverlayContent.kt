package com.chips.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
                onClick = { /* Handled by pointer input */ },
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isRecording = true
                            responseText = "Listening..."
                            audioRecorder.startRecording()
                            
                            try {
                                awaitRelease()
                            } finally {
                                isRecording = false
                                responseText = "Processing..."
                                val audioFile = audioRecorder.stopRecording()
                                
                                if (audioFile != null) {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val response = apiClient.sendAudio(audioFile)
                                        responseText = response
                                        // Auto dismiss after 5 seconds
                                        delay(5000)
                                        // Call from UI thread
                                        launch(Dispatchers.Main) {
                                            onDismiss()
                                        }
                                    }
                                } else {
                                    responseText = "Failed to record audio. Ready."
                                }
                            }
                        }
                    )
                }
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
