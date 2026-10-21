package com.chips.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chips.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ActivityType(val label: String) {
    WORKOUT("Workout"), BEAT_SABER("Beat Saber"), DRAWING("Drawing"), JAPANESE("Japanese")
}

data class DashboardUiState(
    val demoMode: Boolean = false,
    val previewCompleted: Set<ActivityType> = emptySet(),
    val records: List<ActivityRecord> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val loaded: Boolean = false,
    val storageError: String? = null
) {
    val completed get() = if (demoMode) previewCompleted else ActivityRules.completed(records, today)
    fun streak(type: ActivityType) = ActivityRules.streak(records, type, today)
    val visibleRecords get() = records.filter { !it.deleted }
}


/** Production state comes from SQLite; preview interactions never write to storage. */
class DashboardViewModel(private val store: ActivityStore? = null) : ViewModel() {
    private val mutableState = MutableStateFlow(DashboardUiState())
    val state = mutableState.asStateFlow()

    init {
        if (store != null) {
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) { store.load() }
                    store.records.collect { rows -> mutableState.update { it.copy(records = rows, loaded = true, storageError = null) } }
                } catch (_: Exception) {
                    mutableState.update { it.copy(storageError = "Could not open saved activities. Restart to retry.") }
                }
            }
            viewModelScope.launch {
                while (true) { mutableState.update { it.copy(today = LocalDate.now()) }; delay(1000) }
            }
        }
    }
    fun setDemoMode(enabled: Boolean) {
        mutableState.update { it.copy(demoMode = enabled, previewCompleted = emptySet()) }
    }
    suspend fun log(type: ActivityType): ActivityRecord = withContext(Dispatchers.IO) {
        checkNotNull(store).log(type)
    }
    suspend fun toggleToday(type: ActivityType): ActivityRecord? = withContext(Dispatchers.IO) {
        checkNotNull(store).toggleToday(type)
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { checkNotNull(store).delete(id) }
    suspend fun edit(id: String, type: ActivityType, note: String, day: String) = withContext(Dispatchers.IO) {
        checkNotNull(store).edit(id, type, note, day)
    }

    fun previewActivity(type: ActivityType) {
        mutableState.update { state ->
            if (!state.demoMode) state else state.copy(
                previewCompleted = if (type in state.previewCompleted) {
                    state.previewCompleted - type
                } else state.previewCompleted + type
            )
        }
    }
}

