package com.chips.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface ActivityRemote {
    suspend fun identity(): String
    suspend fun put(record: ActivityRecord): ActivityRecord
    suspend fun list(): List<ActivityRecord>
}

/** Network results update SQLite; the UI never depends on an HTTP response. */
class ActivitySynchronizer(private val store: ActivityStore, private val remote: ActivityRemote) {
    suspend fun sync() {
        store.bind(remote.identity())
        store.load().filter { it.syncStatus != "SYNCED" }.forEach { record ->
            try {
                val response = remote.put(record)
                currentCoroutineContext().ensureActive()
                store.merge(response)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                store.failed(record, syncErrorMessage(e))
                throw e
            }
        }
        val snapshot = remote.list()
        currentCoroutineContext().ensureActive()
        snapshot.forEach { store.merge(it) }
    }
}

class SyncFailure(val code: Int) : Exception()
fun syncErrorMessage(e: Exception): String = when ((e as? SyncFailure)?.code) {
    401, 403 -> "Device access rejected. Check enrollment in Settings."
    409 -> "Conflicting record or device. Check enrollment in Settings."
    422 -> "Record rejected. Check its date and note."
    else -> if (e is IllegalStateException) "Sync identity differs from this phone’s saved data. Use the original enrollment."
        else "Sync could not finish. Saved on this phone; retry when connected."
}
