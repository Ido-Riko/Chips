package com.chips.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chips.dashboard.widgets.*

@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    val sleepScore by viewModel.sleepScore.collectAsState()
    val sleepDuration by viewModel.sleepDuration.collectAsState()
    val restingHR by viewModel.restingHR.collectAsState()
    val hrSparkline by viewModel.hrSparkline.collectAsState()
    
    val todayPlan by viewModel.todayPlan.collectAsState()
    val streaks by viewModel.streaks.collectAsState()
    
    val beatSaberAccuracy by viewModel.beatSaberAccuracy.collectAsState()
    val beatSaberTargetSkill by viewModel.beatSaberTargetSkill.collectAsState()
    val beatSaberLastScore by viewModel.beatSaberLastScore.collectAsState()
    
    val kanjiOfDay by viewModel.kanjiOfDay.collectAsState()
    val kanjiReading by viewModel.kanjiReading.collectAsState()
    val kanjiMeaning by viewModel.kanjiMeaning.collectAsState()
    
    val suggestedMeal by viewModel.suggestedMeal.collectAsState()
    val pantryNote by viewModel.pantryNote.collectAsState()
    
    val lastDrawingFeedback by viewModel.lastDrawingFeedback.collectAsState()
    val lastDrawingDate by viewModel.lastDrawingDate.collectAsState()

    Scaffold(
        bottomBar = {
            QuickLogBar(onLogTarget = { viewModel.logActivity(it) })
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.height(12.dp)) }
            
            item {
                SleepCard(
                    score = sleepScore,
                    duration = sleepDuration,
                    restingHR = restingHR,
                    sparkline = hrSparkline
                )
            }
            
            item {
                TodayPlanCard(
                    plan = todayPlan,
                    onLogActivity = { viewModel.logActivity(it) }
                )
            }
            
            item { StreakRings(streaks = streaks) }
            
            item {
                BeatSaberCard(
                    accuracyData = beatSaberAccuracy,
                    targetSkill = beatSaberTargetSkill,
                    lastScore = beatSaberLastScore
                )
            }
            
            item {
                KanjiCard(
                    kanji = kanjiOfDay,
                    reading = kanjiReading,
                    meaning = kanjiMeaning
                )
            }
            
            item {
                MealCard(
                    suggestedMeal = suggestedMeal,
                    pantryNote = pantryNote
                )
            }
            
            item {
                DrawingCard(
                    lastFeedback = lastDrawingFeedback,
                    lastDate = lastDrawingDate,
                    onUploadClick = { /* TODO Phase 6 */ }
                )
            }
            
            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}
