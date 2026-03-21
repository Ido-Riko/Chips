package com.chips.dashboard.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chips.ui.theme.Warning

@Composable
fun MealCard(suggestedMeal: String, pantryNote: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Suggested Meal", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(suggestedMeal, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            if (!pantryNote.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(pantryNote, style = MaterialTheme.typography.bodyMedium, color = Warning)
            }
        }
    }
}
