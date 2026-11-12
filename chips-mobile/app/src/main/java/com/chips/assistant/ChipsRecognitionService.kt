package com.chips.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

class ChipsRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // CHIPS records through its own assistant surface, not the system recognizer API.
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }
}
