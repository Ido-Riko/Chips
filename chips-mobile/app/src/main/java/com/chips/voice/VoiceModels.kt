package com.chips.voice

import java.io.File
import java.io.IOException

/** This ID remains stable across explicit retries, including a lost response. */
data class VoiceRequest(val id: String, val file: File, val occurredAt: Long)
data class VoiceReply(val requestId: String, val transcript: String, val answer: String)
data class VoiceConversation(val id: String, val occurredAt: Long, val transcript: String, val answer: String)

class VoiceFailure(message: String, val retryable: Boolean = true) : IOException(message)

fun interface VoiceService {
    suspend fun send(request: VoiceRequest, onUploaded: () -> Unit): VoiceReply
}
