package com.chips.practice

import android.content.ContentValues
import android.content.Context
import androidx.work.*
import com.chips.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal class DrawingRemote(private val config: SyncCredentials.Config) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(110, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
    init { require(config.endpoint.startsWith("https://")) }
    suspend fun request(id: String, action: String, file: File? = null, goal: String = "", createdAt: Long = 0): JSONObject {
        val body = if (file == null) null else MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("goal", goal).addFormDataPart("created_at", createdAt.toString()).addFormDataPart("image", "$id.jpg", file.asRequestBody("image/jpeg".toMediaType())).build()
        val builder = Request.Builder().url("${config.endpoint}/drawings/$id" + if (action == "analyze") "/analyze" else "")
            .header("Authorization", "Bearer ${config.token}").header("Accept", "application/json")
        when (action) {
            "upload" -> builder.put(body!!)
            "delete" -> builder.delete()
            "analyze" -> builder.post(ByteArray(0).toRequestBody(null))
        }
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        if (!it.isSuccessful) {
                            val message = when (it.code) {
                                401, 403 -> "Server access rejected. Check enrollment in Settings."
                                404 -> "Update the server to enable drawing uploads."
                                409, 410 -> "The server copy changed or was removed. Add this drawing again to upload a new copy."
                                503 -> "Saved on the server. Its vision model is not ready; retry analysis later."
                                502 -> "Saved on the server. Analysis failed; retry later."
                                else -> "The drawing service could not finish (HTTP ${it.code}). Retry later."
                            }
                            throw IOException(message)
                        }
                        val source = it.body?.source() ?: throw IOException("Empty drawing response")
                        source.request(128 * 1024L + 1)
                        check(source.buffer.size <= 128 * 1024) { "Drawing reply was too large" }
                        JSONObject(source.readUtf8()).also { reply ->
                            if (reply.optString("id") != id || (action == "delete" && !reply.optBoolean("deleted")) ||
                                (action == "analyze" && (reply.optString("feedback").isBlank() || reply.isNull("feedback") || reply.optString("feedback").length > 12000)))
                                throw IOException("Incompatible drawing response. Saved on this phone.")
                        }
                    } }
                    continuation.resumeWith(result)
                }
            })
        }
    }
}

class DrawingSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = mutex.withLock { withContext(Dispatchers.IO) {
        val activities = ActivityStore.get(applicationContext)
        val store = PracticeStore(activities)
        val pending = store.drawings(true).filter { it.queued }
        // Locally deleted, never-sent drawings need no server credential or cleanup.
        pending.filter { it.deleted && it.target.isEmpty() }.forEach { store.finishDelete(it.id) }
        val rows = store.drawings(true).filter { it.queued }
        if (rows.isEmpty()) return@withContext Result.success()
        val credentials = SyncCredentials(applicationContext)
        if (!credentials.connected()) return@withContext Result.success()
        val config = credentials.read() ?: return@withContext Result.success()
        try {
            val identity = HttpActivityRemote(config).identity()
            activities.bind(identity)
            val remote = DrawingRemote(config)
            var retryCleanup = false
            for (row in rows) {
                try {
                    check(row.target.isEmpty() || row.target == identity) { "Use the original server enrollment for this drawing." }
                    if (row.deleted) {
                        remote.request(row.id, "delete"); store.finishDelete(row.id); continue
                    }
                    // Persist target before any network mutation, so deletion can always clean it up.
                    if (!store.updateActiveDrawing(row.id, ContentValues().apply { put("target", identity) })) continue
                    var reply = remote.request(row.id, "upload", imageFile(applicationContext, row.id), row.goal, row.createdAt)
                    store.updateDrawing(row.id, ContentValues().apply { put("uploaded", 1) })
                    if (store.drawings(true).firstOrNull { it.id == row.id }?.deleted == true) {
                        remote.request(row.id, "delete"); store.finishDelete(row.id); continue
                    }
                    if (reply.isNull("feedback")) reply = remote.request(row.id, "analyze")
                    // A delete during analysis must remain pending rather than restoring the drawing.
                    if (store.drawings(true).firstOrNull { it.id == row.id }?.deleted == true) {
                        remote.request(row.id, "delete"); store.finishDelete(row.id); continue
                    }
                    store.updateActiveDrawing(row.id, ContentValues().apply {
                        put("feedback", reply.getString("feedback")); put("model", reply.optString("model", "Vision model")); put("queued", 0); put("error", "")
                    })
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val deleting = store.drawings(true).firstOrNull { it.id == row.id }?.deleted == true
                    if (deleting) retryCleanup = true
                    store.updateDrawing(row.id, ContentValues().apply {
                        if (!deleting) put("queued", 0)
                        put("error", e.message ?: "Could not reach the server. Retry when connected.")
                    })
                }
            }
            if (retryCleanup) Result.retry() else Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val permanent = e is IllegalStateException || (e as? SyncFailure)?.code in listOf(401, 403, 409, 422)
            rows.forEach { row -> store.updateDrawing(row.id, ContentValues().apply {
                put("error", if (permanent) syncErrorMessage(e) else "Waiting for your server. Saved on this phone.")
                if (permanent) put("queued", 0)
            }) }
            if (permanent) Result.failure() else Result.retry()
        }
    } }
    companion object {
        private val mutex = Mutex()
        fun schedule(context: Context, retryNow: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork("drawing-sync", if (retryNow) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<DrawingSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
internal fun imageFile(context: Context, id: String) = File(File(context.filesDir, "drawings").apply { mkdirs() }, "$id.jpg")
internal fun thumbFile(context: Context, id: String) = File(File(context.filesDir, "drawings").apply { mkdirs() }, "$id-thumb.jpg")
