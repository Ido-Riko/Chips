package com.chips.voice

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface VoiceRecorder {
    fun start(): Result<Unit>
    fun finish(): Result<File>
    fun cancel()
}

enum class VoicePhase { READY, RECORDING, PROCESSING, REPLY, ERROR }
data class VoiceState(val phase: VoicePhase = VoicePhase.READY, val text: String? = null)

/** Called on the UI dispatcher by both assistant surfaces. Each session owns its jobs/files. */
class VoiceController(
    private val recorder: VoiceRecorder,
    private val scope: CoroutineScope,
    private val send: suspend (File) -> String,
    private val recordingLimitMillis: Long = 60_000
) {
    private val mutableState = MutableStateFlow(VoiceState())
    val state = mutableState.asStateFlow()
    private var recordingTimer: Job? = null
    private var request: Job? = null
    private var generation = 0L

    fun press() {
        if (state.value.phase == VoicePhase.RECORDING || state.value.phase == VoicePhase.PROCESSING) return
        val started = recorder.start()
        if (started.isFailure) {
            recorder.cancel()
            error(started.exceptionOrNull()?.message ?: "Could not start the microphone. Check permission and try again.")
            return
        }
        mutableState.value = VoiceState(VoicePhase.RECORDING)
        recordingTimer = scope.launch {
            delay(recordingLimitMillis)
            recorder.cancel()
            error("Recording reached the 60-second limit. Hold to speak again with a shorter message.")
        }
    }

    fun release() {
        if (state.value.phase != VoicePhase.RECORDING) return
        recordingTimer?.cancel()
        val captured = recorder.finish()
        val file = captured.getOrNull()
        if (file == null) {
            recorder.cancel()
            error(captured.exceptionOrNull()?.message ?: "Could not record audio. Hold the button a little longer and try again.")
            return
        }
        mutableState.value = VoiceState(VoicePhase.PROCESSING)
        val session = ++generation
        request = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val reply = send(file)
                if (generation == session) mutableState.value = VoiceState(VoicePhase.REPLY, reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == session) error(e.message ?: "Could not send the recording. Check your connection and try again.")
            } finally {
                file.delete()
            }
        }
    }

    /** A cancelled pointer gesture must discard audio rather than submit it. */
    fun cancelGesture() {
        if (state.value.phase == VoicePhase.RECORDING) cancel()
    }

    fun cancel() {
        generation++
        recordingTimer?.cancel()
        request?.cancel()
        recorder.cancel()
        mutableState.value = VoiceState()
    }

    private fun error(message: String) {
        mutableState.value = VoiceState(VoicePhase.ERROR, message)
    }
}
