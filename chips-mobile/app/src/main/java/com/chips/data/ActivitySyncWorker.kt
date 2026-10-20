package com.chips.data

import android.content.Context
import androidx.work.*
import com.chips.dashboard.ActivityType
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ActivitySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val store = ActivityStore.get(applicationContext)
            val status = applicationContext.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
            try {
                val config = SyncCredentials(applicationContext).read() ?: return@withLock Result.success()
                ActivitySynchronizer(store, HttpActivityRemote(config)).sync()
                status.edit().remove("error").apply()
                Result.success()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                val message = syncErrorMessage(e)
                store.load().filter { it.syncStatus != "SYNCED" }.forEach { store.failed(it, message) }
                status.edit().putString("error", message).apply()
                if ((e as? SyncFailure)?.code in listOf(401, 403, 409, 422) || e is IllegalStateException) Result.failure() else Result.retry()
            }
        }
    }
    companion object {
        private val syncMutex = Mutex()
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun schedule(context: Context, retryNow: Boolean = false) {
            if (!SyncCredentials(context).configured()) return
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniqueWork("activity-sync", if (retryNow) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ActivitySyncWorker>().setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
            manager.enqueueUniquePeriodicWork("activity-sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ActivitySyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
        }
        fun disconnect(context: Context) {
            SyncCredentials(context).clear()
            WorkManager.getInstance(context).cancelUniqueWork("activity-sync")
            WorkManager.getInstance(context).cancelUniqueWork("activity-sync-periodic")
            context.getSharedPreferences("sync_state", Context.MODE_PRIVATE).edit().remove("error").apply()
        }
    }
}

class HttpActivityRemote(private val config: SyncCredentials.Config) : ActivityRemote {
    init { require(config.endpoint.startsWith("https://")) { "HTTPS required" } }
    // Redirects cannot forward an enrollment credential to another origin.
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build()
    private suspend fun request(path: String, body: JSONObject? = null): String {
        val builder = Request.Builder().url(config.endpoint + path).header("Authorization", "Bearer ${config.token}")
        if (body != null) builder.put(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).awaitResponse().use { response ->
            if (!response.isSuccessful) throw SyncFailure(response.code)
            return response.body?.string() ?: throw IOException("Empty sync response")
        }
    }
    override suspend fun identity(): String {
        val identity = JSONObject(request("/device"))
        return config.endpoint + "|" + identity.getString("owner_id") + "|" + identity.getString("device_id")
    }
    override suspend fun put(record: ActivityRecord) = decode(JSONObject(request("/activities/${record.id}", encode(record))))
    override suspend fun list(): List<ActivityRecord> {
        val response = JSONArray(request("/activities"))
        return (0 until response.length()).map { decode(response.getJSONObject(it)) }
    }
    private fun encode(r: ActivityRecord): JSONObject = JSONObject().put("id", r.id).put("type", r.type.name)
        .put("occurred_at", r.occurredAt).put("zone_id", r.zoneId).put("local_day", r.localDay)
        .put("note", r.note).put("revision", r.revision).put("deleted", r.deleted)
    private fun decode(j: JSONObject) = ActivityRecord(j.getString("id"), ActivityType.valueOf(j.getString("type")),
        j.getLong("occurred_at"), j.getString("zone_id"), j.getString("local_day"), j.getString("note"),
        j.getLong("revision"), j.getBoolean("deleted"), "SYNCED")
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
