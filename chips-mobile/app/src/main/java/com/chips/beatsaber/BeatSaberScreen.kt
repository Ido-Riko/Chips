package com.chips.beatsaber

import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chips.dashboard.DashboardCard
import com.chips.ui.theme.ChipsAccent
import com.chips.ui.theme.ChipsMint
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.stateDescription

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeatSaberScreen(state: BeatSaberState, onBack: () -> Unit, onConnect: (String) -> Unit,
                    onRefresh: () -> Unit, onReload: () -> Unit, onRemove: () -> Unit,
                    onMap: (BeatScore) -> Unit, onLogSession: (() -> Unit)? = null) {
    BackHandler(onBack = onBack)
    var player by rememberSaveable { mutableStateOf(state.playerId) }
    LaunchedEffect(state.playerId) { player = state.playerId }
    var graphMode by rememberSaveable { mutableStateOf(true) }
    var search by rememberSaveable { mutableStateOf("") }
    var x by rememberSaveable { mutableStateOf("stars") }
    var y by rememberSaveable { mutableStateOf("accuracy") }
    var sortBy by rememberSaveable { mutableStateOf("timestamp") }
    var ascending by rememberSaveable { mutableStateOf(false) }
    var selectedKey by rememberSaveable(state.playerId, state.snapshot?.fetchedAt, graphMode, search, x, y, sortBy, ascending) { mutableStateOf<String?>(null) }
    var detailKey by rememberSaveable(state.playerId, graphMode) { mutableStateOf<String?>(null) }
    var showProfile by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val snapshot = state.snapshot
    val source = if (graphMode) snapshot?.graph.orEmpty() else snapshot?.scores.orEmpty()
    val songs = rememberBeatPreparation(BeatInput(source), search, sortBy, ascending) {
        prepareBeatSongs(source, search, sortBy, ascending)
    }
    val points = songs?.points.orEmpty()
    val selected = songs?.byKey?.get(selectedKey)
    // Preparation intentionally publishes null while metadata is re-sorted. Keep the
    // sheet tied to the source identity so enrichment cannot remove/recreate it.
    val detail = remember(BeatInput(source), detailKey) { source.firstOrNull { it.key == detailKey } }
    val ordered = remember(songs, selectedKey) {
        promoteBeatScore(songs?.ordered.orEmpty(), songs?.orderedIndex?.get(selectedKey))
    }
    LazyColumn(Modifier.fillMaxSize().testTag("beat-saber-screen"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("← Today") }
                if(onLogSession != null) TextButton(onClick = onLogSession, modifier = Modifier.testTag("beat-log-session")) { Text("Log completed session") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Beat Saber", style = MaterialTheme.typography.headlineLarge)
                    snapshot?.let { Text(it.profile.optString("name", "Player"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                TextButton(onClick = { showProfile = !showProfile }, modifier = Modifier.testTag("beat-profile")) { Text("Profile") }
                if (snapshot != null) TextButton(onClick = onRefresh, enabled = !state.busy) { Text("Refresh") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("beat-error")) }
        }
        if (snapshot == null || showProfile) item {
            DashboardCard("BeatLeader profile") {
                OutlinedTextField(player, { player = it }, label = { Text("Player ID") }, singleLine = true,
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("beat-player-id"))
                Button(onClick = { selectedKey = null; onConnect(player) }, enabled = !state.busy && player.isNotBlank()) { Text("Save and import") }
                if (snapshot == null) Text("Enter the ID from your BeatLeader profile to get started.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                snapshot?.let {
                    Text("${fmt(it.profile.number("pp"))} PP · Global #${fmt(it.profile.number("rank"), 0)}")
                    Text("Updated ${date(it.fetchedAt)}", style = MaterialTheme.typography.bodySmall)
                    Text(it.data.getString("coverage"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val history = it.data.optJSONArray("history")
                    if (history != null && history.length() > 1) {
                        val before = history.getJSONObject(0).number("pp")
                        val after = history.getJSONObject(history.length() - 1).number("pp")
                        if (before != null && after != null) Text("PP change · ${fmt(after - before)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row {
                    TextButton(onClick = onReload, enabled = !state.busy) { Text("Reload saved") }
                    if (state.playerId.isNotEmpty()) TextButton(onClick = { confirmRemove = true }, enabled = !state.busy) { Text("Remove imports") }
                }
            }
        }
        if (snapshot != null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(graphMode, { graphMode = true; selectedKey = null }, label = { Text("Ranked") })
                    FilterChip(!graphMode, { graphMode = false; selectedKey = null }, label = { Text("Recent") })
                }
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ScoreScatter(points, x, y, selectedKey, onXChange = { x = it }, onYChange = { y = it }) { score ->
                            if (selectedKey == score.key) { detailKey = score.key; onMap(score) }
                            else selectedKey = score.key
                        }
                        selected?.let { score ->
                            Surface(onClick = { detailKey = score.key; onMap(score) }, shape = RoundedCornerShape(12.dp),
                                color = ChipsMint.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth().testTag("beat-point-preview")) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(score.song, style = MaterialTheme.typography.titleSmall)
                                    Text("${fmt(score.metric("stars"))} ★ · ${score.difficulty}", style = MaterialTheme.typography.bodySmall, color = ChipsMint)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Songs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { sortMenu = true }, modifier = Modifier.testTag("beat-sort-menu")) {
                            Text("${metrics[sortBy]} ${if (ascending) "↑" else "↓"}")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf("timestamp", "bpm", "stars", "accuracy", "pp").forEach { key ->
                                DropdownMenuItem(text = { Text(metrics[key]!!) }, onClick = { sortBy = key; sortMenu = false }, modifier = Modifier.testTag("beat-sort-$key"))
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(if (ascending) "Descending ↓" else "Ascending ↑") },
                                onClick = { ascending = !ascending; sortMenu = false }, modifier = Modifier.testTag("beat-sort-direction"))
                        }
                    }
                }
                OutlinedTextField(search, { search = it }, label = { Text("Find a song") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            }
            if (songs == null) item { Text("Preparing scores…", modifier = Modifier.testTag("beat-preparing")) }
            if (songs != null && points.isEmpty()) item { Text("No songs match your search.") }
            items(ordered, key = { it.key }) { score ->
                Surface(onClick = { if (selectedKey != score.key) selectedKey = null; detailKey = score.key; onMap(score) }, shape = RoundedCornerShape(18.dp),
                    color = if (selectedKey == score.key) ChipsMint.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("beat-score-${score.key}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(score.song, style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${fmt(score.accuracy)}%", color = ChipsMint, style = MaterialTheme.typography.titleMedium)
                            Text("${fmt(score.metric("stars"))} ★ · ${fmt(score.metric("pp"), 0)} PP", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (sortBy == "bpm") "${fmt(score.metric("bpm"), 1)} BPM · ${score.difficulty}"
                            else score.difficulty + if (score.modifiers.isNotEmpty()) " · ${score.modifiers}" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (detail != null) ModalBottomSheet(modifier = Modifier.testTag("beat-detail-sheet"), onDismissRequest = { detailKey = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            ScoreDetails(detail, state, onMap)
        }
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("Remove local Beat Saber imports?") },
        text = { Text("Removes saved scores and map details from this phone. Your BeatLeader scores remain available.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; selectedKey = null; onRemove() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } })
}

@Composable
private fun MetricMenu(selected: String, tag: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag(tag)) { Text("${metrics[selected]} ▾", style = MaterialTheme.typography.labelLarge) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            metrics.forEach { (key, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(key); expanded = false }) }
        }
    }
}

@Composable
internal fun ScoreScatter(points: List<BeatScore>, x: String, y: String, selected: String?, onXChange: (String) -> Unit = {}, onYChange: (String) -> Unit = {}, onSelect: (BeatScore) -> Unit) {
    val prepared = rememberBeatPreparation(BeatInput(points), x, y) { prepareBeatScatter(points, x, y) }
    val plotted = prepared?.plotted.orEmpty()
    if (plotted.isEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricMenu(y, "beat-axis-y", onYChange)
            MetricMenu(x, "beat-axis-x", onXChange)
        }
        Text(if (prepared == null) "Preparing graph…" else "No measurements for this combination.", modifier = Modifier.padding(vertical = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val (fullMinX, fullMaxX) = prepared!!.xBounds
    val (fullMinY, fullMaxY) = prepared.yBounds
    var viewport by remember(prepared) { mutableStateOf(ChartViewport()) }
    val minX = fullMinX + viewport.left * (fullMaxX - fullMinX)
    val maxX = minX + (fullMaxX - fullMinX) / viewport.zoom
    val minY = fullMinY + viewport.bottom * (fullMaxY - fullMinY)
    val maxY = minY + (fullMaxY - fullMinY) / viewport.zoom
    fun visible(a: Double, b: Double) = a in minX..maxX && b in minY..maxY
    fun tick(value: Double, key: String, span: Double): String {
        if (key == "timestamp") return Instant.ofEpochSecond(value.toLong()).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(if (span < 86400) "d MMM\nHH:mm" else "d MMM\nyyyy"))
        val step = span / 4
        val digits = if (step >= 1) 1 else (1 - kotlin.math.floor(kotlin.math.log10(step)).toInt()).coerceIn(1, 8)
        return fmt(value, digits)
    }
    val xTicks = (0..2).map { minX + it * (maxX - minX) / 2 }
    val yTicks = (0..4).map { minY + it * (maxY - minY) / 4 }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    val xLabels = xTicks.map { measurer.measure(tick(it, x, maxX - minX), labelStyle) }
    val yLabels = yTicks.map { measurer.measure(tick(it, y, maxY - minY), labelStyle) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = with(density) { 8.dp.toPx() }
    val left = maxOf(yLabels.maxOf { it.size.width }.toFloat() + gap, xLabels.first().size.width / 2f)
    val right = maxOf(gap, xLabels.last().size.width / 2f)
    val top = maxOf(gap, yLabels.maxOf { it.size.height } / 2f)
    val bottom = gap + xLabels.maxOf { it.size.height }
    val insets by rememberUpdatedState(listOf(left, right, top, bottom))
    fun position(a: Double, b: Double, width: Float, height: Float) = Offset(
        left + ((a - minX) / (maxX - minX)).toFloat() * (width - left - right),
        height - bottom - ((b - minY) / (maxY - minY)).toFloat() * (height - top - bottom))
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    var showHelp by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { MetricMenu(y, "beat-axis-y", onYChange) }
        TextButton(onClick = { viewport = viewport.transform(0.5f) }, enabled = viewport.zoom > 1f, modifier = Modifier.testTag("beat-zoom-out").semantics { contentDescription = "Zoom out" }) { Text("−") }
        TextButton(onClick = { viewport = viewport.transform(2f) }, enabled = viewport.zoom < 16f, modifier = Modifier.testTag("beat-zoom-in").semantics { contentDescription = "Zoom in" }) { Text("+") }
    }
    if (viewport.zoom > 1f) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        listOf(Triple("←", -0.25f, 0f), Triple("↑", 0f, 0.25f), Triple("↓", 0f, -0.25f), Triple("→", 0.25f, 0f)).forEachIndexed { i, (label, dx, dy) ->
            TextButton(onClick = { viewport = viewport.transform(1f, panX = -dx, panY = -dy) }, modifier = Modifier.weight(1f).testTag("beat-pan-$i")
                .semantics { contentDescription = listOf("Pan left", "Pan up", "Pan down", "Pan right")[i] }) { Text(label) }
        }
        TextButton(onClick = { viewport = ChartViewport() }, modifier = Modifier.testTag("beat-zoom-reset")) { Text("Reset") }
    }
    Spacer(Modifier.fillMaxWidth().height(260.dp).testTag("beat-scatter")
        .semantics {
            stateDescription = "Zoom ${fmt(viewport.zoom.toDouble(), 1)}×"
            contentDescription = "X axis: ${metrics[x]}. Ticks: ${xTicks.joinToString { tick(it, x, maxX - minX) }}. " +
                "Y axis: ${metrics[y]}. Ticks: ${yTicks.joinToString { tick(it, y, maxY - minY) }}. Score details are also available in the list."
        }
        .pointerInput(BeatInput(plotted), x, y) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed && it.previousPressed } >= 2) {
                        val (left, right, top, bottom) = insets
                        val focus = event.calculateCentroid(useCurrent = false)
                        val pan = event.calculatePan()
                        val width = (size.width - left - right).coerceAtLeast(1f)
                        val height = (size.height - top - bottom).coerceAtLeast(1f)
                        viewport = viewport.transform(event.calculateZoom(), (focus.x - left) / width,
                            1f - (focus.y - top) / height, pan.x / width, -pan.y / height)
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        .pointerInput(BeatInput(plotted), x, y, left, right, top, bottom, viewport, onSelect) {
            coroutineScope {
                var hitTest: Job? = null
                detectTapGestures { tap ->
                    if (tap.x !in left..(size.width - right) || tap.y !in top..(size.height - bottom)) return@detectTapGestures
                    val width = size.width.toFloat(); val height = size.height.toFloat()
                    val radius = 32.dp.toPx()
                    hitTest?.cancel()
                    hitTest = launch {
                        val nearest = withContext(Dispatchers.Default) {
                            plotted.asSequence().filter { (_, a, b) -> visible(a, b) }.minByOrNull { (_, a, b) ->
                                val p = position(a, b, width, height); hypot(p.x - tap.x, p.y - tap.y)
                            }
                        }
                        nearest?.let { (score, a, b) ->
                            val p = position(a, b, width, height)
                            if (hypot(p.x - tap.x, p.y - tap.y) <= radius) onSelect(score)
                        }
                    }
                }
            }
        }.drawWithCache {
            val dots = ArrayList<Offset>(plotted.size)
            var selectedDot: Offset? = null
            plotted.forEach { (score, a, b) ->
                if (visible(a, b)) {
                    val dot = position(a, b, size.width, size.height)
                    if (score.key == selected) selectedDot = dot else dots.add(dot)
                }
            }
            val dotColor = ChipsAccent.copy(alpha = 0.75f)
            onDrawBehind {
                yTicks.forEachIndexed { i, value ->
                    val p = position(minX, value, size.width, size.height)
                    drawLine(grid, p, Offset(size.width - right, p.y))
                    val label = yLabels[i]
                    drawText(label, topLeft = Offset(left - gap - label.size.width, p.y - label.size.height / 2f))
                }
                xTicks.forEachIndexed { i, value ->
                    val p = position(value, minY, size.width, size.height)
                    drawLine(grid, Offset(p.x, top), p)
                    drawText(xLabels[i], topLeft = Offset(p.x - xLabels[i].size.width / 2f, p.y + gap))
                }
                drawLine(axis, Offset(left, top), Offset(left, size.height - bottom))
                drawLine(axis, Offset(left, size.height - bottom), Offset(size.width - right, size.height - bottom))
                clipRect(left, top, size.width - right, size.height - bottom) {
                    drawPoints(dots, PointMode.Points, dotColor, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
                    selectedDot?.let { drawCircle(ChipsMint, 7.dp.toPx(), it) }
                }
            }
    })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(48.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { MetricMenu(x, "beat-axis-x", onXChange) }
        TextButton(onClick = { showHelp = true }, modifier = Modifier.width(48.dp).semantics { contentDescription = "Chart help" }) { Text("?") }
    }
    if (showHelp) AlertDialog(onDismissRequest = { showHelp = false }, title = { Text("Explore your scores") },
        text = { Text("Tap an axis name to change it. Pinch to zoom and drag with two fingers to pan, or use the buttons. Tap a point to preview it and bring its song first in the list. Tap it again, the preview or the song card for details.\n\nSongs with missing measurements stay in the list. Unknown BPM sorts last.") },
        confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Got it") } })
}

@Composable
private fun ScoreDetails(score: BeatScore, state: BeatSaberState, onMap: (BeatScore) -> Unit, onLogSession: (() -> Unit)? = null) {
    val uri = LocalUriHandler.current
    var linkError by remember(score.key) { mutableStateOf(false) }
    fun open(url: String) { runCatching { uri.openUri(url) }.onFailure { linkError = true } }
    DashboardCard(score.song) {
        Text("${fmt(score.accuracy)}% · ${fmt(score.metric("pp"))} PP", style = MaterialTheme.typography.titleLarge)
        Text("${score.difficulty} · ${score.mode} · ${date(score.timestamp)}")
        Text("Modifiers: ${score.modifiers.ifEmpty { "None" }}")
        Text("Stars · ${fmt(score.metric("stars"))} ★", modifier = Modifier.testTag("beat-detail-stars"))
        Text("Pass rating · ${fmt(score.metric("pass_rating"))}", modifier = Modifier.testTag("beat-detail-pass"))
        Text("Acc rating · ${fmt(score.metric("acc_rating"))}", modifier = Modifier.testTag("beat-detail-acc"))
        Text("Tech rating · ${fmt(score.metric("tech_rating"))}", modifier = Modifier.testTag("beat-detail-tech"))
        if (listOf("stars", "pass_rating", "acc_rating", "tech_rating").any { score.metric(it) == null }) {
            Text("— means unavailable in this import${if (score.modifiers.isNotEmpty()) "; unadjusted ratings are omitted for modified scores" else ""}.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Song BPM · ${fmt(score.metric("bpm"), 1)}")
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
