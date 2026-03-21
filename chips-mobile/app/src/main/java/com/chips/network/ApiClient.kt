package com.chips.network

import com.chips.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient {
    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

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

        val request = Request.Builder()
            .url("$BASE_URL/voice-log")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
                response.body?.string() ?: "Empty response"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "Error: ${e.localizedMessage}"
        }
    }

    suspend fun post(endpoint: String, jsonBody: String): String = withContext(Dispatchers.IO) {
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = if (endpoint.startsWith("http")) endpoint else "$BASE_URL$endpoint"
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
                response.body?.string() ?: ""
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }
}
