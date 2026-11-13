package com.chips.assistant

import android.service.voice.VoiceInteractionService

class ChipsVoiceInteractionService : VoiceInteractionService() {
    override fun onLaunchVoiceAssistFromKeyguard() {
        if(AssistantAccessSettings(this).allowLocked()) {
            startActivity(com.chips.ui.featureIntent(this, null, fromLockscreen = true)
                .putExtra(com.chips.MainActivity.OPEN_ASSISTANT, true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } else showSession(null, 0)
    }
}
