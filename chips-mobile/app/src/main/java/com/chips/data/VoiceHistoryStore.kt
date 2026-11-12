package com.chips.data

import com.chips.voice.VoiceConversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Uses the reserved M2 voice_history table; no database migration or activity edits. */
class VoiceHistoryStore(private val database: ActivityStore) {
    private val mutableRecords = MutableStateFlow<List<VoiceConversation>>(emptyList())
    val records = mutableRecords.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        synchronized(database) { mutableRecords.value = database.loadVoiceHistory() }
    }

    suspend fun save(record: VoiceConversation) = withContext(Dispatchers.IO) {
        synchronized(database) {
            database.saveVoiceConversation(record)
            mutableRecords.value = database.loadVoiceHistory()
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        synchronized(database) {
            database.writableDatabase.delete("voice_history", "id=?", arrayOf(id))
            mutableRecords.value = database.loadVoiceHistory()
        }
    }

    companion object {
        @Volatile private var instance: VoiceHistoryStore? = null
        fun get(context: android.content.Context): VoiceHistoryStore = instance ?: synchronized(this) {
            instance ?: VoiceHistoryStore(ActivityStore.get(context)).also { instance = it }
        }
    }
}
