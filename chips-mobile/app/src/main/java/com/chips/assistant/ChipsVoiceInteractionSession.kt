package com.chips.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.chips.ui.AssistantOverlayContent
import com.chips.ui.theme.ChipsTheme

class ChipsVoiceInteractionSession(context: Context) : VoiceInteractionSession(context), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onLockscreenShown() {
        super.onLockscreenShown()
        // Never leave private replies or an active microphone above the lockscreen.
        hide()
    }

    override fun onHide() {
        super.onHide()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onCreateContentView(): View {
        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@ChipsVoiceInteractionSession)
            setViewTreeSavedStateRegistryOwner(this@ChipsVoiceInteractionSession)
            setContent {
                ChipsTheme {
                    AssistantOverlayContent(onDismiss = { hide() }, reserveFingerprintSpace = true, onFeature = { route ->
                        if (!context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) {
                            startAssistantActivity(com.chips.ui.featureIntent(context, route))
                            hide()
                        }
                    })
                }
            }
        }
    }
}
