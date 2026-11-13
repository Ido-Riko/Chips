package com.chips.heartrate

import android.content.Context
import androidx.work.*
import com.chips.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface HrRemote {
    suspend fun begin(manifest: JSONObject): JSONObject
    suspend fun chunk(id: String, kind: String, offset: Int, records: JSONArray): JSONObject
    suspend fun complete(id: String): JSONObject
    suspend fun delete(id: String): JSONObject
}

internal fun hrCanonical(value: Any?): String = when(value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + hrCanonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { hrCanonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

/** Acknowledged immutable chunks are safe to retry after any response/process loss. */
internal class HrSynchronizer(private val store: HrStore, private val remote: HrRemote) {
    suspend fun sync(id: String) {
        currentCoroutineContext().ensureActive()
        if(store.deleted(id)) {
            val reply = remote.delete(id)
            check(reply.getString("id") == id && reply.getBoolean("deleted")) { "Invalid HR deletion acknowledgement" }
            store.synced(id, wasDeleted = true)
            return
        }
        val manifest = store.manifest(id)
        fun verify(reply: JSONObject) {
            check(reply.getString("id") == id && !reply.getBoolean("deleted") && hrCanonical(reply.getJSONObject("manifest")) == hrCanonical(manifest)) { "Conflicting HR acknowledgement" }
        }
        val begun = remote.begin(manifest)
        verify(begun)
        if(!begun.getBoolean("complete")) {
            for(kind in listOf("runs", "events")) {
                val total = manifest.getInt(if(kind == "runs") "run_count" else "event_count")
                for(offset in 0 until total step 128) {
                    currentCoroutineContext().ensureActive()
                    if(store.deleted(id)) { sync(id); return }
                    val records = store.archiveChunk(id, kind, offset)
                    val reply = remote.chunk(id, kind, offset, records)
                    check(reply.getInt("offset") == offset && hrCanonical(reply.getJSONArray("records")) == hrCanonical(records)) { "Conflicting HR chunk acknowledgement" }
                }
            }
            val done = remote.complete(id)
            verify(done)
            check(done.getBoolean("complete")) { "HR upload is incomplete" }
        }
        currentCoroutineContext().ensureActive()
        store.synced(id, wasDeleted = false) // a concurrent local delete remains queued
    }
}

internal class HttpHrRemote(private val config: SyncCredentials.Config) : HrRemote {
    init { require(config.endpoint.startsWith("https://")) }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(45, TimeUnit.SECONDS).build()
    private suspend fun request(path: String, method: String, json: JSONObject? = null): JSONObject {
        val body = json?.toString()?.toRequestBody("application/json".toMediaType())
            ?: if(method == "POST") ByteArray(0).toRequestBody() else null
        val request = Request.Builder().url(config.endpoint + "/heart-rate" + path)
            .header("Authorization", "Bearer ${config.token}").method(method, body).build()
        val response: Response = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, r, _ -> r.close() } }
            })
        }
        response.use { r ->
            if(!r.isSuccessful) throw SyncFailure(r.code)
            val source = r.body?.source() ?: throw IOException("Empty HR acknowledgement")
            source.request(2 * 1024 * 1024L + 1)
            check(source.buffer.size <= 2 * 1024 * 1024) { "HR response too large" }
            return JSONObject(source.readUtf8())
        }
    }
    override suspend fun begin(manifest: JSONObject) = request("/${manifest.getString("id")}", "PUT", manifest)
    override suspend fun chunk(id: String, kind: String, offset: Int, records: JSONArray) = request("/$id/$kind/$offset", "PUT", JSONObject().put("records", records))
    override suspend fun complete(id: String) = request("/$id/complete", "POST")
    override suspend fun delete(id: String) = request("/$id", "DELETE")
}

class HrSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val credentials = SyncCredentials(applicationContext)
        if(!credentials.connected()) return@withContext Result.success()
        val config = credentials.read() ?: return@withContext Result.success()
        val store = ActivityStore.get(applicationContext)
        try {
            val identity = HttpActivityRemote(config).identity()
            HrStore(applicationContext).use { hr ->
                store.bind(identity); hr.bind(identity)
                var failed = false
                var permanent = false
                for(id in hr.pendingSync()) {
                    ensureActive()
                    try { upload(hr, HttpHrRemote(config), id) }
                    catch(e: CancellationException) { throw e }
                    catch(e: Exception) {
                        failed = true
                        permanent = permanent || (e as? SyncFailure)?.code in listOf(401,403,409,410,422) || e is IllegalStateException
                        hr.failed(id, hrSyncError(e))
                    }
                }
                when { permanent -> Result.failure(); failed || hr.pendingSync().isNotEmpty() -> Result.retry(); else -> Result.success() }
            }
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            runCatching { HrStore(applicationContext).use { hr -> hr.pendingSync().forEach { hr.failed(it, hrSyncError(e)) } } }
            if(e is IllegalStateException || (e as? SyncFailure)?.code in listOf(401,403)) Result.failure() else Result.retry()
        }
    }
    companion object {
        private val mutex = Mutex()
        internal suspend fun upload(store: HrStore, remote: HrRemote, id: String) = mutex.withLock { HrSynchronizer(store, remote).sync(id) }
        fun schedule(context: Context, retryNow: Boolean = false) {
            if(!SyncCredentials(context).connected()) return
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).apply {
                enqueueUniqueWork("hr-sync", if(retryNow) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<HrSyncWorker>().setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
                enqueueUniquePeriodicWork("hr-periodic", ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<HrSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
            }
        }
    }
}

internal fun hrSyncError(e: Exception): String = when((e as? SyncFailure)?.code) {
    404 -> "Update the server to enable HR storage. Saved on this phone."
    410 -> "This HR capture was deleted on the server. Local readings are retained."
    else -> syncErrorMessage(e)
}
