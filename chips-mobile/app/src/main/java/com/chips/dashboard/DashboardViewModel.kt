package com.chips.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chips.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DashboardViewModel : ViewModel() {
    private val apiClient = ApiClient()

    private val _sleepScore = MutableStateFlow(MockData.sleepScore)
    val sleepScore: StateFlow<Int> = _sleepScore

    private val _sleepDuration = MutableStateFlow(MockData.sleepDuration)
    val sleepDuration: StateFlow<String> = _sleepDuration

    private val _restingHR = MutableStateFlow(MockData.restingHR)
    val restingHR: StateFlow<Int> = _restingHR

    private val _hrSparkline = MutableStateFlow(MockData.hrSparkline)
    val hrSparkline: StateFlow<List<Float>> = _hrSparkline

    private val _todayPlan = MutableStateFlow(MockData.todayPlan)
    val todayPlan: StateFlow<List<MockData.PlanItem>> = _todayPlan

    private val _beatSaberAccuracy = MutableStateFlow(MockData.beatSaberAccuracy)
    val beatSaberAccuracy: StateFlow<List<Float>> = _beatSaberAccuracy

    private val _beatSaberTargetSkill = MutableStateFlow(MockData.beatSaberTargetSkill)
    val beatSaberTargetSkill: StateFlow<String> = _beatSaberTargetSkill

    private val _beatSaberLastScore = MutableStateFlow(MockData.beatSaberLastScore)
    val beatSaberLastScore: StateFlow<String> = _beatSaberLastScore

    private val _streaks = MutableStateFlow(MockData.streaks)
    val streaks: StateFlow<Map<String, Int>> = _streaks

    private val _kanjiOfDay = MutableStateFlow(MockData.kanjiOfDay)
    val kanjiOfDay: StateFlow<String> = _kanjiOfDay

    private val _kanjiReading = MutableStateFlow(MockData.kanjiReading)
    val kanjiReading: StateFlow<String> = _kanjiReading

    private val _kanjiMeaning = MutableStateFlow(MockData.kanjiMeaning)
    val kanjiMeaning: StateFlow<String> = _kanjiMeaning

    private val _suggestedMeal = MutableStateFlow(MockData.suggestedMeal)
    val suggestedMeal: StateFlow<String> = _suggestedMeal

    private val _pantryNote = MutableStateFlow(MockData.pantryNote)
    val pantryNote: StateFlow<String> = _pantryNote

    private val _lastDrawingFeedback = MutableStateFlow(MockData.lastDrawingFeedback)
    val lastDrawingFeedback: StateFlow<String> = _lastDrawingFeedback

    private val _lastDrawingDate = MutableStateFlow(MockData.lastDrawingDate)
    val lastDrawingDate: StateFlow<String> = _lastDrawingDate

    fun logActivity(type: String) {
        viewModelScope.launch {
            try {
                // Post activity log. Silent fail on error.
                val jsonBody = """
                    {
                        "type": "$type",
                        "completed_at": "${java.time.Instant.now()}"
                    }
                """.trimIndent()
                apiClient.post("/activity-log", jsonBody)
            } catch (e: Exception) {
                // Silent fail as requested in Phase 1
            }
        }
    }
}
