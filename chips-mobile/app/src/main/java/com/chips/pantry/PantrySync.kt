package com.chips.pantry

import android.content.Context
import androidx.work.*
import com.chips.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class PantryRemote(private val config: SyncCredentials.Config) {
    init { require(config.endpoint.startsWith("https://")) }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(120, TimeUnit.SECONDS).readTimeout(110, TimeUnit.SECONDS).build()
    fun request(path: String, body: RequestBody? = null, method: String = "GET"): String {
        val request = Request.Builder().url(config.endpoint + "/pantry" + path).header("Authorization", "Bearer ${config.token}")
            .method(method, body).build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException(when(r.code) {
                401, 403 -> "Check server enrollment in Settings. Your pantry is saved on this phone."
                404 -> "Update the server to enable pantry storage."
                409 -> "Conflicting transaction. Your local pantry is unchanged; retry sync."
                503 -> "Configure the server vision model to read receipt photos. Manual entry still works."
                else -> "Pantry service could not finish (HTTP ${r.code}). Try again later."
            })
            val source = r.body?.source() ?: throw IOException("Empty pantry response")
            source.request(2 * 1024 * 1024L + 1)
            check(source.buffer.size <= 2 * 1024 * 1024) { "Pantry response too large" }
            return source.readUtf8()
        }
    }
    fun put(tx: JSONObject): JSONObject = JSONObject(request("/transactions/${tx.getString("id")}", tx.toString().toRequestBody("application/json".toMediaType()), "PUT"))
    fun list(after: String) = JSONArray(request("/transactions?after=$after"))
    fun receipt(file: File): String = JSONObject(request("/receipt", MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("image", "receipt.jpg", file.asRequestBody("image/jpeg".toMediaType())).build(), "POST")).getString("draft")
}

class PantrySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = mutex.withLock { withContext(Dispatchers.IO) {
        val credentials = SyncCredentials(applicationContext)
        if(!credentials.connected()) return@withContext Result.success()
        val config = credentials.read() ?: return@withContext Result.success()
        PantryStore(applicationContext).use { store ->
            try {
                val identity = HttpActivityRemote(config).identity()
                ActivityStore.get(applicationContext).bind(identity); store.bind(identity)
                val remote = PantryRemote(config)
                for(tx in store.transactions(true)) { ensureActive(); val reply = remote.put(tx); check(canonical(reply) == canonical(tx)) { "Incompatible pantry acknowledgement" }; store.merge(reply, true) }
                var after = ""
                do {
                    ensureActive()
                    val page = remote.list(after)
                    for(i in 0 until page.length()) store.merge(page.getJSONObject(i), true)
                    if(page.length() > 0) {
                        val next = page.getJSONObject(page.length() - 1).getString("id")
                        check(next > after) { "Invalid pantry cursor" }; after = next
                    }
                } while(page.length() == 200)
                store.state("error", ""); store.state("last_sync", System.currentTimeMillis().toString())
                Result.success()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { store.state("error", e.message ?: "Waiting for your server. Saved on this phone."); Result.retry() }
        }
    } }
    companion object {
        private val mutex = Mutex()
        fun schedule(context: Context, retryNow: Boolean = false) {
            if(!SyncCredentials(context).connected()) return
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).apply {
                enqueueUniqueWork("pantry-sync", if(retryNow) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<PantrySyncWorker>().setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
                enqueueUniquePeriodicWork("pantry-periodic", ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<PantrySyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
            }
        }
    }
}
