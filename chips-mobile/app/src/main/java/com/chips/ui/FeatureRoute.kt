package com.chips.ui

import android.content.Context
import android.content.Intent
import com.chips.MainActivity
import com.chips.dashboard.ActivityType

/** One destination contract for dashboard shortcuts and Android's assistant session. */
enum class FeatureRoute(val label: String) {
    WORKOUT("Workout"), BEAT_SABER("Beat Saber"), JAPANESE("Japanese"), DRAWING("Drawing"), PANTRY("Pantry");
    companion object {
        const val EXTRA = "com.chips.OPEN_FEATURE"
        fun from(intent: Intent?): FeatureRoute? = entries.firstOrNull { it.name == intent?.getStringExtra(EXTRA) }
        fun activity(type: ActivityType) = valueOf(type.name)
    }
}

fun featureIntent(context: Context, route: FeatureRoute) = Intent(context, MainActivity::class.java)
    .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    .putExtra(FeatureRoute.EXTRA, route.name)
    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
