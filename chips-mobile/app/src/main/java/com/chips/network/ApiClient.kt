package com.chips.network

import com.chips.BuildConfig
import android.content.Context
import com.chips.data.SyncCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Using BASE_URL injected from local.properties via BuildConfig
    private val BASE_URL = BuildConfig.BASE_URL

    suspend fun sendAudio(file: File): String = withContext(Dispatchers.IO) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio", file.name,
                file.asRequestBody("audio/aac".toMediaType())
            )
            .build()

        val config = try { SyncCredentials(context).read() } catch (_: Exception) { null }
        if (config == null && !BuildConfig.DEBUG) return@withContext "Set up device enrollment in Settings before using voice."
        val builder = Request.Builder().url("${config?.endpoint ?: BASE_URL}/voice-log").post(requestBody)
        if (config != null) builder.header("Authorization", "Bearer ${config.token}")
        val request = builder.build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Service returned HTTP ${response.code}")
                response.body?.string() ?: "Empty response"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error: ${e.localizedMessage}"
        }
    }
}
