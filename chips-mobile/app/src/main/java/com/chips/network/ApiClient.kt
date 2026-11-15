package com.chips.network

import android.content.Context
import com.chips.data.SyncCredentials
import com.chips.voice.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient(private val context: Context) : VoiceService {
    // Bind retries to the same saved service/credential snapshot; never silently switch targets.
    private val config = runCatching { SyncCredentials(context).read() }.getOrNull()

    private val service = config?.let { HttpVoiceService(it.endpoint, it.token) }
    override suspend fun send(request: VoiceRequest, onUploaded: () -> Unit): VoiceReply =
        (service ?: throw VoiceFailure("Set up device enrollment in Settings before using voice.", false)).send(request, onUploaded)
}

internal class HttpVoiceService(
    private val endpoint: String,
    private val token: String,
    private val client: OkHttpClient = voiceHttpClient()
) : VoiceService {
    private var compatible = false
    override suspend fun send(request: VoiceRequest, onUploaded: () -> Unit): VoiceReply {
        if (!compatible) {
            val check = Request.Builder().url("$endpoint/voice-capabilities")
                .header("Authorization", "Bearer $token").header("Accept", "application/json").get().build()
            VoiceReplyProtocol.checkCapabilities(awaitVoiceReply(client.newCall(check)))
            compatible = true
        }
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("request_id", request.id)
            .addFormDataPart("protocol_version", "1")
            .addFormDataPart("history", JSONArray(request.history.map { JSONObject().put("role", it.role).put("content", it.content) }).toString())
        if (request.text != null) builder.addFormDataPart("text", request.text)
        else {
            val file = request.file ?: throw VoiceFailure("The recording is missing. Please record again.", false)
            builder.addFormDataPart("audio", file.name, file.asRequestBody("audio/mp4".toMediaType()))
        }
        val multipart = builder.build()
        val http = voiceHttpRequest(endpoint, token, request.id, multipart, onUploaded)
        return VoiceReplyProtocol.decode(awaitVoiceReply(client.newCall(http)), request.id)
    }
}

internal fun voiceHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

internal fun voiceHttpRequest(endpoint: String, token: String, id: String, body: RequestBody, onUploaded: () -> Unit): Request =
    Request.Builder().url("$endpoint/voice-log")
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")
        .header("Idempotency-Key", id)
        .header("X-Chips-Voice-Protocol", "1")
        .post(object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun writeTo(sink: BufferedSink) {
                body.writeTo(sink)
                sink.flush()
                onUploaded()
            }
        }).build()

/** Cancellation closes the socket while uploading, waiting or reading the bounded response. */
internal suspend fun awaitVoiceReply(call: Call): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(VoiceFailure(
                "Could not reach the voice service, or it took too long. Check your connection and retry.")))
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching {
                response.use {
                    val body = it.body ?: throw VoiceFailure("The voice service returned an empty reply. Retry the request.")
                    val source = body.source()
                    source.request(256 * 1024L + 1)
                    if (source.buffer.size > 256 * 1024L) throw VoiceFailure("The voice reply was too large. Update the service before trying again.", false)
                    val text = source.readUtf8().trim()
                    if (!it.isSuccessful) throw VoiceReplyProtocol.failure(it.code, text)
                    if (text.isEmpty()) throw VoiceFailure("The voice service returned an empty reply. Retry the request.")
                    text
                }
            }.recoverCatching { e ->
                if (e is VoiceFailure) throw e
                throw VoiceFailure("The voice reply was interrupted. Check your connection and retry.")
            }
            if (continuation.isActive) continuation.resumeWith(result)
        }
    })
}
