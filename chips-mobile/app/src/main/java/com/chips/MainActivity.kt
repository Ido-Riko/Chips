package com.chips

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.chips.assistant.ChipsVoiceInteractionService
import com.chips.ui.ChipsApp
import com.chips.ui.FeatureRoute
import com.chips.ui.theme.ChipsTheme

open class MainActivity : ComponentActivity() {
    companion object { const val OPEN_ASSISTANT = "com.chips.OPEN_ASSISTANT" }
    private var assistantRequest by mutableStateOf(false)
    private var assistantWindow = false
    private val access by lazy { com.chips.assistant.AssistantAccessSettings(this) }
    private val accessListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if(key == com.chips.assistant.AssistantAccessSettings.KEY) updateAssistantWindow()
    }
    private var featureRequest by mutableStateOf<FeatureRoute?>(null)
    private var micPermissionGranted by mutableStateOf(false)
    private var isAssistant by mutableStateOf(false)
    private var settingsError by mutableStateOf<String?>(null)

    private val requestMicrophone = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { micPermissionGranted = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        featureRequest = FeatureRoute.from(intent)
        assistantRequest = intent.getBooleanExtra(OPEN_ASSISTANT, false)
        assistantWindow = this is com.chips.assistant.LockscreenAssistantActivity || featureRequest != null || assistantRequest || savedInstanceState?.getBoolean("assistant_window") == true
        access.preferences.registerOnSharedPreferenceChangeListener(accessListener)
        updateAssistantWindow()
        updateStates()
        setContent {
            ChipsTheme {
                ChipsApp(
                    requestedAssistant = assistantRequest,
                    onAssistantHandled = { assistantRequest = false; intent.removeExtra(OPEN_ASSISTANT) },
                    onAssistantClosed = { if(assistantWindow && getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) finish() },
                    requestedFeature = featureRequest,
                    onFeatureHandled = { featureRequest = null; intent.removeExtra(FeatureRoute.EXTRA) },
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        featureRequest = FeatureRoute.from(intent)
        assistantRequest = intent.getBooleanExtra(OPEN_ASSISTANT, false)
        assistantWindow = this is com.chips.assistant.LockscreenAssistantActivity || featureRequest != null || assistantRequest
        updateAssistantWindow()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("assistant_window", assistantWindow)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        access.preferences.unregisterOnSharedPreferenceChangeListener(accessListener)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun updateAssistantWindow() {
        val show = assistantWindow && access.allowLocked()
        if(android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(show)
        else if(show) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
    }

    override fun onResume() {
        super.onResume()
        updateAssistantWindow()
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
        settingsError = null
        // ASSISTANT is not a requestable role on standard Android. A role request
        // can immediately return canceled without displaying a picker.
        for (action in listOf(
            Settings.ACTION_VOICE_INPUT_SETTINGS,
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
        )) {
            try {
                startActivity(Intent(action))
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // Some manufacturers omit the dedicated assistant screen.
            } catch (_: SecurityException) {
                // Try the public Default apps screen if this route is restricted.
            }
        }
        settingsError = "Open Android Settings → Apps → Default apps → Digital assistant app, then choose CHIPS."
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
