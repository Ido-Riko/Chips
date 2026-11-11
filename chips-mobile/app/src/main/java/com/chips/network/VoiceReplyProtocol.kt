package com.chips.network

import com.chips.voice.VoiceFailure
import com.chips.voice.VoiceReply
import org.json.JSONObject

/** Versioned client contract. Legacy/demo answers must never become personal transcripts. */
internal object VoiceReplyProtocol {
    fun checkCapabilities(body: String) {
        val compatible = runCatching {
            val json = JSONObject(body)
            json.opt("protocol_version") == 1 && json.opt("idempotency") == true
        }.getOrDefault(false)
        if (!compatible) throw VoiceFailure("This voice service needs an update to safely handle voice requests and retries.", false)
    }

    fun decode(body: String, requestId: String): VoiceReply {
        try {
            val json = JSONObject(body)
            if (json.opt("protocol_version") != 1 || json.opt("is_demo") != false) {
                throw VoiceFailure("The voice service still uses demo transcription or an unsupported version. Update the service before trying again.", false)
            }
            val id = json.getString("request_id")
            val transcript = json.get("transcript") as? String
            val answer = json.get("answer") as? String
            if (id != requestId || transcript.isNullOrBlank() || answer.isNullOrBlank() ||
                transcript.length > 10_000 || answer.length > 64_000) {
                throw VoiceFailure("The voice service returned an invalid reply. Update the service before trying again.", false)
            }
            return VoiceReply(id, transcript, answer)
        } catch (e: VoiceFailure) {
            throw e
        } catch (_: Exception) {
            throw VoiceFailure("This voice service needs an update to return real transcripts and saved replies. Try again after the service is updated.", false)
        }
    }

    fun failure(status: Int, body: String): VoiceFailure {
        val code = runCatching { JSONObject(body).getJSONObject("error").getString("code") }.getOrNull()
        val message = when {
            status == 401 || status == 403 -> "Device access rejected. Check enrollment in Settings."
            status == 413 -> "Recording is too large. Try a shorter message."
            status == 408 -> "The voice request took too long. Retry when the service is ready."
            status == 429 -> "Voice service is busy. Wait a moment and retry."
            code == "stt_unavailable" -> "Speech recognition is unavailable. Check the configured speech service on your PC or server and retry."
            code == "llm_unavailable" -> "The answer service is unavailable. Check the configured model on your PC or server and retry."
            code == "no_speech" -> "No speech was detected. Hold to speak again."
            status in listOf(404, 415, 426) -> "This voice service needs an update before it can accept voice requests."
            status in 400..499 -> "The recording could not be accepted. Discard it and record again."
            else -> "Voice service could not complete the request. Try again shortly."
        }
        return VoiceFailure(message, status >= 500 || status == 429 || status == 408)
    }
}
