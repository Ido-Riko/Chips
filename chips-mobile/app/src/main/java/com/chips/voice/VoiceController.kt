package com.chips.voice

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface VoiceRecorder {
    fun start(): Result<Unit>
    fun finish(): Result<File>
    fun cancel()
    fun onFailure(listener: (String) -> Unit) {}
}

enum class VoicePhase { READY, RECORDING, UPLOADING, PROCESSING, SAVING, REPLY, ERROR }
data class VoiceState(
    val phase: VoicePhase = VoicePhase.READY,
    val text: String? = null,
    val transcript: String? = null,
    val canRetry: Boolean = false,
    val retrySaving: Boolean = false
)

/** UI-dispatcher controller shared by the app and system assistant. Audio lives only in cache. */
class VoiceController(
    private val recorder: VoiceRecorder,
    private val scope: CoroutineScope,
    private val service: VoiceService,
    private val save: suspend (VoiceConversation) -> Unit,
    private val recordingLimitMillis: Long = 60_000
) {
    private val mutableState = MutableStateFlow(VoiceState())
    val state = mutableState.asStateFlow()
    private var recordingTimer: Job? = null
    private var requestJob: Job? = null
    private var generation = 0L
    private var pending: VoiceRequest? = null
    private var received: VoiceReply? = null

    init {
        recorder.onFailure { message ->
            cancel()
            error(message)
        }
    }

    fun press() {
        if (state.value.phase in busyPhases || state.value.phase == VoicePhase.RECORDING) return
        discardPending()
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
        pending = VoiceRequest(UUID.randomUUID().toString(), file, System.currentTimeMillis())
        submit()
    }

    fun retry() {
        if (state.value.phase == VoicePhase.ERROR && state.value.canRetry) submit()
    }

    private fun submit() {
        val capture = pending ?: return
        val session = ++generation
        mutableState.value = VoiceState(if (received == null) VoicePhase.UPLOADING else VoicePhase.SAVING)
        requestJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val reply = received ?: service.send(capture) {
                    scope.launch {
                        if (session == generation && state.value.phase == VoicePhase.UPLOADING) {
                            mutableState.value = VoiceState(VoicePhase.PROCESSING)
                        }
                    }
                }
                ensureActive()
                if (generation != session) return@launch
                if (reply.requestId != capture.id || reply.transcript.isBlank() || reply.answer.isBlank()) {
                    throw VoiceFailure("The voice service returned an invalid reply. Update the service before trying again.", false)
                }
                received = reply
                capture.file.delete()
                mutableState.value = VoiceState(VoicePhase.SAVING)
                // A completed transaction is retained even if the window is dismissed during the save.
                // A stable primary key makes retrying a lost local acknowledgement idempotent.
                withContext(NonCancellable) {
                    save(VoiceConversation(capture.id, capture.occurredAt, reply.transcript, reply.answer))
                }
                if (generation == session) {
                    mutableState.value = VoiceState(VoicePhase.REPLY, reply.answer, reply.transcript)
                    discardPending()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == session) {
                    val saving = received != null
                    val retryable = saving || (e as? VoiceFailure)?.retryable != false
                    if (!retryable) discardPending()
                    error(if (saving) "Could not save the reply on this phone. Retry saving before closing."
                        else e.message ?: "Could not reach the voice service. Check your connection and retry.",
                        retryable, saving)
                }
            } finally {
                if (generation != session) capture.file.delete()
            }
        }
    }

    fun cancelGesture() {
        if (state.value.phase == VoicePhase.RECORDING) cancel()
    }

    fun cancel() {
        generation++
        recordingTimer?.cancel()
        requestJob?.cancel()
        recorder.cancel()
        discardPending()
        mutableState.value = VoiceState()
    }

    private fun discardPending() {
        pending?.file?.delete()
        pending = null
        received = null
    }

    private fun error(message: String, retryable: Boolean = false, saving: Boolean = false) {
        mutableState.value = VoiceState(VoicePhase.ERROR, message, canRetry = retryable, retrySaving = saving)
    }

    companion object {
        private val busyPhases = setOf(VoicePhase.UPLOADING, VoicePhase.PROCESSING, VoicePhase.SAVING)
    }
}
