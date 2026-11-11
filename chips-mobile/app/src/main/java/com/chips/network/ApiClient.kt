package com.chips.network

import android.content.Context
import com.chips.BuildConfig
import com.chips.data.SyncCredentials
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        // The current server has no voice request deduplication contract.
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun sendAudio(file: File): String {
        val config = try { SyncCredentials(context).read() } catch (_: Exception) { null }
        if (config == null && !BuildConfig.DEBUG) {
            throw IOException("Set up device enrollment in Settings before using voice.")
        }
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .build()
        val builder = Request.Builder().url("${config?.endpoint ?: BuildConfig.BASE_URL}/voice-log").post(requestBody)
        if (config != null) builder.header("Authorization", "Bearer ${config.token}")
        return awaitVoiceReply(client.newCall(builder.build()))
    }
}

/** Cancellation closes the socket, including while reading a response body. */
internal suspend fun awaitVoiceReply(call: Call): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(IOException(
                "Could not reach the voice service. Check your connection and try again.", e)))
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching {
                response.use {
                    if (!it.isSuccessful) throw IOException(when (it.code) {
                        401, 403 -> "Device access rejected. Check enrollment in Settings."
                        413 -> "Recording is too large. Try a shorter message."
                        429 -> "Voice service is busy. Wait a moment and try again."
                        else -> "Voice service could not complete the request. Try again shortly."
                    })
                    val reply = try { it.body?.string()?.trim() } catch (e: IOException) {
                        throw IOException("The voice reply was interrupted. Check your connection and try again.", e)
                    }
                    if (reply.isNullOrEmpty()) throw IOException("The voice service returned an empty reply. Try again.")
                    reply
                }
            }
            if (continuation.isActive) continuation.resumeWith(result)
        }
    })
}
