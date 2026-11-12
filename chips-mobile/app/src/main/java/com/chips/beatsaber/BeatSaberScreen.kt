package com.chips.beatsaber

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chips.dashboard.DashboardCard
import com.chips.ui.theme.ChipsAccent
import com.chips.ui.theme.ChipsMint
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.hypot
import kotlinx.coroutines.launch

internal fun fmt(value: Double?, digits: Int = 2) = value?.let { String.format(Locale.getDefault(), "%.${digits}f", it) } ?: "—"
internal fun date(seconds: Long): String = Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm"))

private val metrics = linkedMapOf("stars" to "Stars", "accuracy" to "Accuracy %", "pp" to "PP",
    "pass_rating" to "Pass rating", "acc_rating" to "Acc rating", "tech_rating" to "Tech rating",
    "bpm" to "BPM", "nps" to "NPS", "timestamp" to "Date")

@Composable
fun BeatSaberSummary(state: BeatSaberState, onOpen: () -> Unit) {
    val latest = state.snapshot?.scores?.firstOrNull()
    Text(latest?.let { "${fmt(it.accuracy)}%" } ?: "—", style = MaterialTheme.typography.headlineMedium)
    Text(latest?.song ?: "Connect your BeatLeader profile", style = MaterialTheme.typography.bodyMedium)
    state.snapshot?.let { Text("Updated ${date(it.fetchedAt)}", style = MaterialTheme.typography.labelMedium) }
    OutlinedButton(onClick = onOpen, modifier = Modifier.testTag("open-beat-saber")) { Text("Explore scores") }
}

@Composable
fun BeatSaberScreen(state: BeatSaberState, onBack: () -> Unit, onConnect: (String) -> Unit,
                    onRefresh: () -> Unit, onReload: () -> Unit, onRemove: () -> Unit,
                    onMap: (BeatScore) -> Unit) {
    BackHandler(onBack = onBack)
    var player by rememberSaveable { mutableStateOf(state.playerId) }
    LaunchedEffect(state.playerId) { player = state.playerId }
    var graphMode by rememberSaveable { mutableStateOf(true) }
    var search by rememberSaveable { mutableStateOf("") }
    var x by rememberSaveable { mutableStateOf("stars") }
    var y by rememberSaveable { mutableStateOf("accuracy") }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val snapshot = state.snapshot
    val source = if (graphMode) snapshot?.graph.orEmpty() else snapshot?.scores.orEmpty()
    val points = source.filter { search.isBlank() || "${it.song} ${it.difficulty} ${it.mode} ${it.modifiers}".contains(search, true) }
    val selected = points.firstOrNull { it.key == selectedKey }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize().testTag("beat-saber-screen"), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            TextButton(onClick = onBack) { Text("Back to Today") }
            Text("Beat Saber", style = MaterialTheme.typography.headlineLarge)
            Text("Your scores, in context.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            DashboardCard("BeatLeader connection") {
                OutlinedTextField(player, { player = it }, label = { Text("Player ID") }, singleLine = true,
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("beat-player-id"))
                Button(onClick = { selectedKey = null; onConnect(player) }, enabled = !state.busy && player.isNotBlank()) { Text("Save and import") }
                Text("Use the ID from your BeatLeader profile. Imported scores are saved on this phone; importing and refreshing need internet.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefresh, enabled = !state.busy && state.playerId.isNotEmpty()) { Text("Refresh") }
                    TextButton(onClick = onReload, enabled = !state.busy) { Text("Reload saved") }
                }
                if (state.playerId.isNotEmpty()) TextButton(onClick = { confirmRemove = true }, enabled = !state.busy) { Text("Remove local imports") }
                if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Loading scores…") }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("beat-error")) }
            }
        }
        if (snapshot != null) {
            item {
                DashboardCard(snapshot.profile.optString("name", "Player")) {
                    Text("${fmt(snapshot.profile.number("pp"))} PP", style = MaterialTheme.typography.headlineMedium)
                    Text("Global #${fmt(snapshot.profile.number("rank"), 0)} · ${snapshot.profile.optString("country")} #${fmt(snapshot.profile.number("country_rank"), 0)}")
                    Text("Updated ${date(snapshot.fetchedAt)} · cached on this phone", style = MaterialTheme.typography.labelMedium)
                    Text(snapshot.data.getString("coverage"), style = MaterialTheme.typography.bodyMedium)
                    val history = snapshot.data.optJSONArray("history")
                    if (history != null && history.length() > 1) {
                        val first = history.getJSONObject(0)
                        val last = history.getJSONObject(history.length() - 1)
                        val before = first.number("pp")
                        val after = last.number("pp")
                        if (before != null && after != null) Text("${history.length()} daily snapshots · PP change ${fmt(after - before)}")
                        Text("${date(first.getLong("timestamp"))} → ${date(last.getLong("timestamp"))}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(graphMode, { graphMode = true; selectedKey = null }, label = { Text("Ranked portfolio") })
                        FilterChip(!graphMode, { graphMode = false; selectedKey = null }, label = { Text("Recent scores") })
                    }
                    Text(if (graphMode) "${source.size} ranked graph points" else "${source.size} of ${snapshot.data.optInt("total_scores")} retained scores")
                    OutlinedTextField(search, { search = it }, label = { Text("Find song, difficulty or modifiers") }, modifier = Modifier.fillMaxWidth())
                    AxisPicker("X", x) { x = it }
                    AxisPicker("Y", y) { y = it }
                    ScoreScatter(points, x, y, selectedKey) { score -> selectedKey = score.key; onMap(score) }
                    Text("Tap a point or choose a score below. Missing metrics are omitted from this chart; the scores remain in the list.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (selected != null) item {
                ScoreDetails(selected, state, onMap)
            }
            if (points.isEmpty()) item { Text("No scores match this view.") }
            items(points.sortedByDescending { it.timestamp }, key = { it.key }) { score ->
                OutlinedCard(onClick = {
                    selectedKey = score.key; onMap(score)
                    scope.launch { listState.animateScrollToItem(4) }
                }, modifier = Modifier.fillMaxWidth().testTag("beat-score-${score.key}")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(score.song, style = MaterialTheme.typography.titleMedium)
                        Text("${fmt(score.accuracy)}% · ${fmt(score.metric("stars"))}★ · ${fmt(score.metric("pp"))} PP")
                        Text("${score.difficulty} · ${score.mode}${if (score.modifiers.isNotEmpty()) " · ${score.modifiers}" else ""}", style = MaterialTheme.typography.bodyMedium)
                        Text(date(score.timestamp), style = MaterialTheme.typography.labelMedium)
                        if (score.key == selectedKey) Text("Selected · details above", color = ChipsMint)
                    }
                }
            }
        } else if (!state.busy) item { Text("Connect your profile to import real scores. Saved scores stay available offline.") }
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("Remove local Beat Saber imports?") },
        text = { Text("Removes this phone's selected profile, imported scores and map cache. Your upstream scores and activity logs remain available.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; selectedKey = null; onRemove() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } })
}

@Composable
private fun AxisPicker(label: String, selected: String, onSelect: (String) -> Unit) {
    Text("$label axis · ${metrics[selected]}", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        metrics.forEach { (key, name) -> FilterChip(selected == key, { onSelect(key) }, label = { Text(name) }) }
    }
}

private fun BeatScore.axis(key: String): Double? = if (key == "timestamp") timestamp.toDouble() else metric(key)

@Composable
private fun ScoreScatter(points: List<BeatScore>, x: String, y: String, selected: String?, onSelect: (BeatScore) -> Unit) {
    val plotted = remember(points, x, y) { points.mapNotNull { p -> p.axis(x)?.let { a -> p.axis(y)?.let { b -> Triple(p, a, b) } } } }
    if (plotted.isEmpty()) { Text("No ${metrics[x]} / ${metrics[y]} measurements in this view."); return }
    val minX = plotted.minOf { it.second }; val maxX = plotted.maxOf { it.second }
    val minY = plotted.minOf { it.third }; val maxY = plotted.maxOf { it.third }
    fun position(a: Double, b: Double, width: Float, height: Float): Offset {
        fun scale(v: Double, low: Double, high: Double) = if (high == low) 0.5f else ((v - low) / (high - low)).toFloat()
        return Offset(16 + scale(a, minX, maxX) * (width - 32), height - 16 - scale(b, minY, maxY) * (height - 32))
    }
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    Canvas(Modifier.fillMaxWidth().height(230.dp).testTag("beat-scatter")
        .semantics { contentDescription = "${plotted.size} scores. ${metrics[x]} versus ${metrics[y]}. Score details are also available in the list." }
        .pointerInput(plotted, x, y) {
            detectTapGestures { tap ->
                val nearest = plotted.minByOrNull { (_, a, b) -> val p = position(a, b, size.width.toFloat(), size.height.toFloat()); hypot(p.x - tap.x, p.y - tap.y) }
                nearest?.let { (score, a, b) ->
                    val p = position(a, b, size.width.toFloat(), size.height.toFloat())
                    if (hypot(p.x - tap.x, p.y - tap.y) <= 32.dp.toPx()) onSelect(score)
                }
            }
        }) {
        for (i in 0..4) {
            val line = 16 + i * (size.height - 32) / 4
            drawLine(grid, Offset(16f, line), Offset(size.width - 16, line))
        }
        plotted.forEach { (score, a, b) -> drawCircle(if (score.key == selected) ChipsMint else ChipsAccent.copy(alpha = 0.65f), if (score.key == selected) 7.dp.toPx() else 3.dp.toPx(), position(a, b, size.width, size.height)) }
    }
    fun axisLabel(v: Double, key: String) = if (key == "timestamp") date(v.toLong()) else fmt(v)
    Text("${metrics[x]}: ${axisLabel(minX, x)} – ${axisLabel(maxX, x)}", style = MaterialTheme.typography.labelMedium)
    Text("${metrics[y]}: ${axisLabel(minY, y)} – ${axisLabel(maxY, y)}", style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun ScoreDetails(score: BeatScore, state: BeatSaberState, onMap: (BeatScore) -> Unit) {
    val uri = LocalUriHandler.current
    var linkError by remember(score.key) { mutableStateOf(false) }
    fun open(url: String) { runCatching { uri.openUri(url) }.onFailure { linkError = true } }
    DashboardCard(score.song) {
        Text("${fmt(score.accuracy)}% · ${fmt(score.metric("pp"))} PP", style = MaterialTheme.typography.titleLarge)
        Text("${score.difficulty} · ${score.mode} · ${date(score.timestamp)}")
        Text("Modifiers: ${score.modifiers.ifEmpty { "None" }}")
        if (score.scoreId != null) {
            Text("Bad cuts ${fmt(score.metric("bad_cuts"), 0)} · Misses ${fmt(score.metric("missed_notes"), 0)} · Bombs ${fmt(score.metric("bomb_cuts"), 0)} · Walls ${fmt(score.metric("walls_hit"), 0)}")
            Text("${if (score.fields.optBoolean("full_combo")) "Full combo" else "Max combo ${fmt(score.metric("max_combo"), 0)}"} · Pauses ${fmt(score.metric("pauses"), 0)}")
            Text("Average cut / 115 · Left ${fmt(score.metric("left_cut"))} · Right ${fmt(score.metric("right_cut"))}")
            Text("Improvement · ${fmt(score.metric("accuracy_gain"))} percentage points · ${fmt(score.metric("pp_gain"))} PP")
        }
        if (state.mapKey == score.key) {
            if (state.mapBusy) Text("Loading map details…")
            state.map?.let { map ->
                Text("${fmt(map.number("bpm"))} BPM · ${fmt(map.number("nps"))} NPS · ${fmt(map.number("njs"))} NJS")
                Text("${fmt(map.number("notes"), 0)} notes · ${fmt(map.number("duration"), 0)} seconds")
                map.optJSONArray("tags")?.let { tags -> if (tags.length() > 0) Text((0 until tags.length()).joinToString(" · ") { tags.getString(it) }) }
                val id = map.optString("map_id")
                if (Regex("[A-Za-z0-9_-]{1,40}").matches(id)) TextButton(onClick = { open("https://beatsaver.com/maps/$id") }) { Text("Open BeatSaver") }
            }
            state.mapError?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { onMap(score) }) { Text("Retry map details") } }
        }
        if (Regex("[A-Za-z0-9_-]{1,80}").matches(score.leaderboardId)) TextButton(onClick = { open("https://beatleader.com/leaderboard/global/${score.leaderboardId}") }) { Text("Open BeatLeader") }
        if (linkError) Text("No app could open the source link.", color = MaterialTheme.colorScheme.error)
    }
}
