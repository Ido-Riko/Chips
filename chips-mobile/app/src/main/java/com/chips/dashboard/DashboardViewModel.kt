package com.chips.dashboard

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ActivityType(val label: String) {
    WORKOUT("Workout"), BEAT_SABER("Beat Saber"), DRAWING("Drawing"), JAPANESE("Japanese")
}

data class DashboardUiState(
    val demoMode: Boolean = false,
    val previewCompleted: Set<ActivityType> = emptySet()
)

/** Preview interactions never cross into personal data or the network. */
class DashboardViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(DashboardUiState())
    val state = mutableState.asStateFlow()

    fun setDemoMode(enabled: Boolean) {
        mutableState.value = DashboardUiState(demoMode = enabled)
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
