package com.chips.dashboard.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chips.ui.theme.Accent
import com.chips.ui.theme.AccentSecondary
import com.chips.ui.theme.TextPrimary
import com.chips.ui.theme.Warning

@Composable
fun StreakRings(streaks: Map<String, Int>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        streaks.forEach { (name, streak) ->
            val ringColor = when (name) {
                "Drawing" -> Accent
                "Japanese" -> AccentSecondary
                "Workout" -> Warning
                else -> TextPrimary
            }
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
                    CircularProgressIndicator(
                        progress = { 1f },
                        color = ringColor,
                        strokeWidth = 6.dp,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxSize()
                    )
                    Text("$streak", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
