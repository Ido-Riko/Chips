package com.chips

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.chips.assistant.ChipsVoiceInteractionService
import com.chips.ui.ChipsApp
import com.chips.ui.theme.ChipsTheme

class MainActivity : ComponentActivity() {
    private var micPermissionGranted by mutableStateOf(false)
    private var isAssistant by mutableStateOf(false)
    private var settingsError by mutableStateOf<String?>(null)

    private val requestMicrophone = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { micPermissionGranted = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateStates()
        setContent {
            ChipsTheme {
                ChipsApp(
                    micPermissionGranted = micPermissionGranted,
                    isAssistant = isAssistant,
                    settingsError = settingsError,
                    onRequestMicrophone = {
                        requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onAssistantSettings = ::openAssistantSettings,
                    onAppSettings = {
                        openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        })
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStates()
    }

    private fun updateStates() {
        val activeService = Settings.Secure.getString(contentResolver, "voice_interaction_service")
        isAssistant = ComponentName.unflattenFromString(activeService.orEmpty()) ==
            ComponentName(this, ChipsVoiceInteractionService::class.java)
        micPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAssistantSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                openSettings(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                return
            }
        }
        openSettings(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
    }

    private fun openSettings(intent: Intent) {
        settingsError = null
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            settingsError = "Open Android Settings → Apps → Default apps to select CHIPS."
        } catch (_: SecurityException) {
            settingsError = "Android could not open this setting. Open it from your device Settings app."
        }
    }
}
