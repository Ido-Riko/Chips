package com.chips.assistant

import android.content.Intent
import android.speech.RecognitionService

class ChipsRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // Phase 0 STT is handled via Cloud, this is just a dummy required by Android OS
    }

    override fun onCancel(listener: Callback?) {
        // Phase 0
    }

    override fun onStopListening(listener: Callback?) {
        // Phase 0
    }
}
