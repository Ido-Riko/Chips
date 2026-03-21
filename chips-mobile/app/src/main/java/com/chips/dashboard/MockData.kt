package com.chips.dashboard

object MockData {
    val sleepScore = 74
    val sleepDuration = "7h 12m"
    val restingHR = 58
    val hrSparkline = listOf(55f, 57f, 60f, 58f, 56f, 54f, 58f)

    val todayPlan = listOf(
        PlanItem("Calisthenics", scheduled = true, done = false),
        PlanItem("Beat Saber", scheduled = true, done = false),
        PlanItem("Drawing", scheduled = true, done = false),
        PlanItem("Japanese", scheduled = true, done = false),
    )

    val beatSaberAccuracy = listOf(87f, 89f, 85f, 91f, 88f, 93f)
    val beatSaberTargetSkill = "Left hand timing on burst patterns"
    val beatSaberLastScore = "93.4%"

    val streaks = mapOf(
        "Drawing" to 3,
        "Japanese" to 7,
        "Workout" to 1,
        "Beat Saber" to 5
    )

    val kanjiOfDay = "食"
    val kanjiReading = "しょく / た(べる)"
    val kanjiMeaning = "eat / food"

    val suggestedMeal = "Oat porridge with eggs"
    val pantryNote = "Low on milk"

    val lastDrawingFeedback = "Work on chest/pelvis box tilt in 3/4 poses"
    val lastDrawingDate = "2 days ago"

    data class PlanItem(val name: String, val scheduled: Boolean, val done: Boolean)
}
