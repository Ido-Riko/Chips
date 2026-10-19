package com.chips.dashboard

/** Fictional values, read only when the owner explicitly turns on dashboard preview. */
internal object DemoData {
    const val sleepScore = 74
    const val sleepDuration = "7h 12m"
    const val restingHeartRate = 58
    val heartRate = listOf(55f, 57f, 60f, 58f, 56f, 54f, 58f)
    val accuracy = listOf(87f, 89f, 85f, 91f, 88f, 93f)
    val streaks = mapOf(
        ActivityType.WORKOUT to 1, ActivityType.BEAT_SABER to 5,
        ActivityType.DRAWING to 3, ActivityType.JAPANESE to 7
    )
    const val kanji = "食"
    const val reading = "しょく / た(べる)"
    const val meaning = "eat / food"
    const val meal = "Oat porridge with eggs"
    const val pantry = "Low on milk"
    const val drawingFeedback = "Work on chest/pelvis box tilt in 3/4 poses"
    const val beatSaberFocus = "Left hand timing on burst patterns"
}
