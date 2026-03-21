package com.chips

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.chips.dashboard.DashboardScreen
import com.chips.dashboard.DashboardViewModel
import com.chips.ui.theme.ChipsTheme

class MainActivity : ComponentActivity() {

    private var micPermissionGranted by mutableStateOf(false)
    private var isAssistant by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        micPermissionGranted = isGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateStates()
        
        setContent {
            ChipsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isAssistant && micPermissionGranted) {
                        val viewModel = remember { DashboardViewModel() }
                        DashboardScreen(viewModel)
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "CHIPS Assistant Setup",
                                style = MaterialTheme.typography.headlineMedium
                            )
                            
                            Spacer(modifier = Modifier.height(32.dp))
                            if (!isAssistant) {
                                Text(
                                    text = "To use CHIPS, you must set it as your default Digital Assistant app.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Button(onClick = {
                                    val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                                    startActivity(intent)
                                }) {
                                    Text("Open Assistant Settings")
                                }
                                
                                Spacer(modifier = Modifier.height(32.dp))
                            }

                            if (!micPermissionGranted) {
                                Text(
                                    text = "CHIPS needs microphone access to hear your requests.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Button(onClick = {
                                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }) {
                                    Text("Grant Microphone Permission")
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(32.dp))
                            TextButton(onClick = { updateStates() }) {
                                Text("Refresh Status")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStates()
    }

    private fun updateStates() {
        val assistant = Settings.Secure.getString(contentResolver, "voice_interaction_service")
        isAssistant = assistant?.startsWith(packageName) == true
        
        micPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}
