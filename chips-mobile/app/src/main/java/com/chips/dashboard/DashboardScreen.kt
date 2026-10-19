package com.chips.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chips.ui.theme.ChipsAccent
import com.chips.ui.theme.ChipsMint
import com.chips.ui.theme.ChipsWarning
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DashboardScreen(state: DashboardUiState, onLog: (ActivityType) -> Unit, onVoice: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text("Your day, at a glance", style = MaterialTheme.typography.headlineMedium)
            Text(
                LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onVoice) { Text("Ask CHIPS") }
            if (state.demoMode) {
                Text("Preview · fictional data · nothing is saved", color = ChipsWarning)
            }
        }
        item {
            DashboardCard("Sleep") {
                if (state.demoMode) {
                    Text("${DemoData.sleepScore}", style = MaterialTheme.typography.displaySmall, color = ChipsWarning)
                    Text("Sleep score · ${DemoData.sleepDuration} · ${DemoData.restingHeartRate} bpm")
                    TrendLine(DemoData.heartRate, ChipsMint, "Preview heart rate: 55, 57, 60, 58, 56, 54, 58 beats per minute")
                } else EmptyCardText("No sleep data yet", "Connect a sleep source in a later update.")
            }
        }
        item {
            DashboardCard("Today") {
                Text(if (state.demoMode) "Try a preview completion" else "Your activities will appear here when logging is available.")
                ActivityType.entries.forEach { type ->
                    OutlinedButton(onClick = { onLog(type) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (type in state.previewCompleted) "✓ ${type.label} · preview" else type.label)
                    }
                }
            }
        }
        item {
            DashboardCard("Streaks") {
                if (state.demoMode) {
                    ActivityType.entries.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { type ->
                                Column(Modifier.weight(1f)) {
                                    val days = DemoData.streaks.getValue(type)
                                    Box(Modifier.size(76.dp).semantics {
                                        contentDescription = "${type.label}, $days day preview streak"
                                    }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                                        CircularProgressIndicator(
                                            progress = { days.coerceAtMost(7) / 7f },
                                            modifier = Modifier.fillMaxSize(),
                                            color = activityColor(type),
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        Text("$days", style = MaterialTheme.typography.titleLarge)
                                    }
                                    Text(type.label)
                                }
                            }
                        }
                    }
                } else EmptyCardText("Start with one day", "Streaks will be calculated from your saved activities.")
            }
        }
        item {
            DashboardCard("Beat Saber") {
                if (state.demoMode) {
                    Text("93.4%", style = MaterialTheme.typography.headlineMedium)
                    TrendLine(DemoData.accuracy, ChipsAccent, "Preview session accuracy: 87, 89, 85, 91, 88, 93 percent")
                    Text("Focus: ${DemoData.beatSaberFocus}")
                } else EmptyCardText("No sessions yet", "Scores and trends will appear after a score source is connected.")
            }
        }
        item {
            DashboardCard("Japanese") {
                if (state.demoMode) {
                    Text(DemoData.kanji, style = MaterialTheme.typography.displayLarge)
                    Text(DemoData.reading)
                    Text(DemoData.meaning, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else EmptyCardText("Make room for a little practice", "Daily kanji and saved reviews are planned.")
            }
        }
        item {
            DashboardCard("Suggested meal") {
                if (state.demoMode) {
                    Text(DemoData.meal, style = MaterialTheme.typography.titleLarge)
                    Text(DemoData.pantry, color = ChipsWarning)
                } else EmptyCardText("No suggestion yet", "Meal suggestions will use your pantry and preferences.")
            }
        }
        item {
            DashboardCard("Drawing") {
                if (state.demoMode) {
                    Text(DemoData.drawingFeedback)
                    Text("Example feedback · no drawing uploaded", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else EmptyCardText("Your next sketch starts here", "Drawing uploads and feedback are planned.")
            }
        }
    }
}

@Composable
fun QuickLogBar(demoMode: Boolean, onLog: (ActivityType) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(if (demoMode) "Quick Log · preview only" else "Quick Log · saving not available yet", style = MaterialTheme.typography.labelMedium)
            ActivityType.entries.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { type ->
                        TextButton(onClick = { onLog(type) }, modifier = Modifier.weight(1f).testTag("quick-log-${type.name}")) {
                            Text(type.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DashboardCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = ChipsAccent)
            content()
        }
    }
}

@Composable
private fun EmptyCardText(title: String, detail: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun activityColor(type: ActivityType): Color = when (type) {
    ActivityType.WORKOUT -> ChipsWarning
    ActivityType.BEAT_SABER -> Color(0xFFEEEEF5)
    ActivityType.DRAWING -> ChipsAccent
    ActivityType.JAPANESE -> ChipsMint
}

@Composable
private fun TrendLine(values: List<Float>, color: Color, description: String) {
    Canvas(Modifier.fillMaxWidth().height(60.dp).semantics { contentDescription = description }) {
        val low = values.min()
        val span = (values.max() - low).coerceAtLeast(1f)
        val inset = 4.dp.toPx()
        val path = Path()
        values.forEachIndexed { index, value ->
            val point = Offset(
                inset + index.toFloat() / (values.size - 1) * (size.width - 2 * inset),
                size.height - inset - (value - low) / span * (size.height - 2 * inset)
            )
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}
