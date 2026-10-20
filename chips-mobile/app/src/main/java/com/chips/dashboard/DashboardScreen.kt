package com.chips.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chips.ui.ChipsIcon
import com.chips.ui.ChipsSymbol
import com.chips.ui.theme.ChipsAccent
import com.chips.ui.theme.ChipsMint
import com.chips.ui.theme.ChipsWarning
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DashboardScreen(state: DashboardUiState, onLog: (ActivityType) -> Unit, onVoice: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().testTag("dashboard")
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("CHIPS / YOUR DAILY SPACE", color = ChipsAccent, style = MaterialTheme.typography.labelMedium)
                    Text("Today.", style = MaterialTheme.typography.headlineLarge)
                    Text("Your day, at a glance", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(LocalDate.now().format(DateTimeFormatter.ofPattern("dd", Locale.getDefault())), style = MaterialTheme.typography.headlineMedium)
                    Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEE · MMM", Locale.getDefault())), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (state.demoMode) {
            item {
                Surface(color = ChipsWarning.copy(alpha = 0.08f), shape = RoundedCornerShape(10.dp)) {
                    Text("Preview · fictional data · nothing is saved", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = ChipsWarning, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF302A43), Color(0xFF201F2C))))
                    .clickable(role = Role.Button, onClick = onVoice).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                IconTile(ChipsSymbol.VOICE, ChipsAccent, 44)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Ask CHIPS", style = MaterialTheme.typography.titleMedium)
                    Text("A little space to think out loud.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC5BFD7))
                }
                ChipsIcon(ChipsSymbol.ARROW, ChipsAccent)
            }
        }
        item { SleepCard(state.demoMode) }
        item {
            DashboardCard("Your rhythm", trailing = if (state.demoMode) "${state.previewCompleted.size} / 4 · preview" else "Not connected") {
                AdaptivePair(
                    first = { ActivityTile(ActivityType.WORKOUT, state, onLog) },
                    second = { ActivityTile(ActivityType.BEAT_SABER, state, onLog) }
                )
                AdaptivePair(
                    first = { ActivityTile(ActivityType.DRAWING, state, onLog) },
                    second = { ActivityTile(ActivityType.JAPANESE, state, onLog) }
                )
            }
        }
        item {
            AdaptivePair(
                first = {
                    DashboardCard("Beat Saber") {
                        IconTile(ChipsSymbol.SABER, ChipsAccent)
                        Text(if (state.demoMode) "93.4%" else "—", style = MaterialTheme.typography.headlineMedium)
                        Text(if (state.demoMode) "Latest accuracy" else "No score source", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.demoMode) {
                            TrendLine(DemoData.accuracy, ChipsAccent, "Preview session accuracy: 87, 89, 85, 91, 88, 93 percent")
                            Text("Focus: ${DemoData.beatSaberFocus}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                second = {
                    DashboardCard("Japanese") {
                        if (state.demoMode) {
                            Text(DemoData.kanji, fontSize = 48.sp, lineHeight = 54.sp, color = ChipsMint)
                            Text(DemoData.reading, style = MaterialTheme.typography.bodyMedium)
                            Text(DemoData.meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            IconTile(ChipsSymbol.JAPANESE, ChipsMint)
                            Text("A little, often.", style = MaterialTheme.typography.titleLarge)
                            Text("No reviews yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            )
        }
        item {
            DashboardCard("Keep showing up", trailing = if (state.demoMode) "Preview streaks" else "No activity history") {
                if (LocalDensity.current.fontScale > 1.3f) {
                    ActivityType.entries.chunked(2).forEach { pair -> StreakRow(pair, state.demoMode) }
                } else StreakRow(ActivityType.entries, state.demoMode)
            }
        }
        item {
            DashboardCard("At your own pace") {
                DetailRow(
                    ChipsSymbol.MEAL, ChipsWarning, "Meal ideas",
                    if (state.demoMode) DemoData.meal else "Your pantry is a blank page.",
                    if (state.demoMode) DemoData.pantry else "No pantry connected"
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                DetailRow(
                    ChipsSymbol.DRAWING, ChipsAccent, "Drawing",
                    if (state.demoMode) DemoData.drawingFeedback else "Make a little room to create.",
                    if (state.demoMode) "Example feedback · no drawing uploaded" else "No drawings yet"
                )
            }
        }
    }
}

@Composable
private fun SleepCard(demo: Boolean) {
    DashboardCard("Last night's rest", trailing = if (demo) "Preview" else "No sleep source") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(88.dp).clearAndSetSemantics {
                contentDescription = if (demo) "Preview sleep score ${DemoData.sleepScore} out of 100" else "No sleep score yet"
            }, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { if (demo) DemoData.sleepScore / 100f else 0f },
                    modifier = Modifier.fillMaxSize(), color = ChipsMint,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeWidth = 4.dp
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (demo) "${DemoData.sleepScore}" else "—", style = MaterialTheme.typography.headlineMedium)
                    Text("SLEEP SCORE", style = MaterialTheme.typography.labelMedium.copy(fontSize = 8.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChipsIcon(ChipsSymbol.MOON, ChipsMint, Modifier.size(16.dp))
                    Text(if (demo) DemoData.sleepDuration else "Waiting for data", style = MaterialTheme.typography.titleMedium)
                }
                Text(if (demo) "${DemoData.restingHeartRate} bpm · resting heart rate" else "Sleep and heart rate, in one place.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (demo) TrendLine(DemoData.heartRate, ChipsMint, "Preview heart rate: 55, 57, 60, 58, 56, 54, 58 beats per minute")
            }
        }
    }
}

@Composable
private fun ActivityTile(type: ActivityType, state: DashboardUiState, onLog: (ActivityType) -> Unit) {
    val completed = type in state.previewCompleted
    val color = activityColor(type)
    Surface(
        onClick = { onLog(type) }, shape = RoundedCornerShape(14.dp),
        color = if (completed) color.copy(alpha = 0.13f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChipsIcon(activitySymbol(type), color, Modifier.size(19.dp))
            Text(type.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            ChipsIcon(if (completed) ChipsSymbol.CHECK else ChipsSymbol.PLUS, if (completed) color else MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(15.dp))
        }
    }
}

@Composable
private fun StreakRow(types: List<ActivityType>, demo: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        types.forEach { type ->
            val days = if (demo) DemoData.streaks.getValue(type) else null
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(48.dp).clearAndSetSemantics {
                    contentDescription = if (days == null) "${type.label}, no history" else "${type.label}, $days day preview streak"
                }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { (days ?: 0).coerceAtMost(7) / 7f },
                        modifier = Modifier.fillMaxSize(), color = activityColor(type),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeWidth = 3.dp
                    )
                    Text(days?.toString() ?: "—", style = MaterialTheme.typography.titleMedium)
                }
                Text(type.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DetailRow(symbol: ChipsSymbol, color: Color, title: String, description: String, note: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        IconTile(symbol, color, 36)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun QuickLogBar(demoMode: Boolean, onLog: (ActivityType) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Quick log", style = MaterialTheme.typography.labelMedium)
                Text(if (demoMode) "Preview only" else "Not connected", modifier = Modifier.testTag("quick-log-status"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
                ActivityType.entries.forEach { type ->
                    Column(
                        Modifier.weight(1f).testTag("quick-log-${type.name}")
                            .clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onLog(type) }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        ChipsIcon(activitySymbol(type), activityColor(type), Modifier.size(20.dp))
                        Text(type.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DashboardCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.045f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            content()
        }
    }
}

@Composable
private fun AdaptivePair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 310.dp || largeText) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { first(); second() }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { first() }
                Box(Modifier.weight(1f)) { second() }
            }
        }
    }
}

@Composable
private fun IconTile(symbol: ChipsSymbol, color: Color, dimension: Int = 34) {
    Box(Modifier.size(dimension.dp).background(color.copy(alpha = 0.1f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
        ChipsIcon(symbol, color, Modifier.size(20.dp))
    }
}

private fun activityColor(type: ActivityType): Color = when (type) {
    ActivityType.WORKOUT -> ChipsWarning
    ActivityType.BEAT_SABER -> ChipsAccent
    ActivityType.DRAWING -> Color(0xFFE0A9AF)
    ActivityType.JAPANESE -> ChipsMint
}

private fun activitySymbol(type: ActivityType): ChipsSymbol = when (type) {
    ActivityType.WORKOUT -> ChipsSymbol.WORKOUT
    ActivityType.BEAT_SABER -> ChipsSymbol.SABER
    ActivityType.DRAWING -> ChipsSymbol.DRAWING
    ActivityType.JAPANESE -> ChipsSymbol.JAPANESE
}

@Composable
private fun TrendLine(values: List<Float>, color: Color, description: String) {
    Canvas(Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = description }) {
        val low = values.min()
        val span = (values.max() - low).coerceAtLeast(1f)
        val inset = 3.dp.toPx()
        val path = Path()
        values.forEachIndexed { index, value ->
            val point = Offset(inset + index.toFloat() / (values.size - 1) * (size.width - 2 * inset), size.height - inset - (value - low) / span * (size.height - 2 * inset))
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}
