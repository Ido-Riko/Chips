package com.chips.dashboard.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chips.ui.theme.TextSecondary
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.entryModelOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeatSaberCard(accuracyData: List<Float>, targetSkill: String, lastScore: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Beat Saber", style = MaterialTheme.typography.titleMedium)
                Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Text(lastScore, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            val chartEntryModel = remember(accuracyData) { entryModelOf(*accuracyData.toTypedArray()) }
            Chart(
                chart = lineChart(),
                model = chartEntryModel,
                modifier = Modifier.height(100.dp).fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("Focus: $targetSkill", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}
