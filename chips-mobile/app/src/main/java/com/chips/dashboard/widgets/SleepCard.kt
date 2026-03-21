package com.chips.dashboard.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.entryModelOf

@Composable
fun SleepCard(score: Int, duration: String, restingHR: Int, sparkline: List<Float>) {
    val scoreColor = when {
        score >= 80 -> Color(0xFF00D4AA)
        score >= 60 -> Color(0xFFFFB347)
        else -> Color(0xFFFF4D6D)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Sleep Score", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$score", style = MaterialTheme.typography.displayLarge, color = scoreColor)
            Text("$duration · $restingHR bpm", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))
            val chartEntryModel = entryModelOf(*sparkline.toTypedArray())
            Chart(
                chart = lineChart(),
                model = chartEntryModel,
                modifier = Modifier.height(60.dp).fillMaxWidth()
            )
        }
    }
}
