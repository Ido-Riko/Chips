package com.chips.dashboard.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chips.ui.theme.TextSecondary

@Composable
fun DrawingCard(lastFeedback: String, lastDate: String, onUploadClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Drawing", style = MaterialTheme.typography.titleMedium)
                FilledIconButton(onClick = onUploadClick) {
                    Text("+") // Plus icon placeholder
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(lastFeedback, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Last uploaded: $lastDate", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}
