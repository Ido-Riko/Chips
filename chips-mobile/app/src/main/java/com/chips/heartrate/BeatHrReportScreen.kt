package com.chips.heartrate

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.chips.beatsaber.BeatSaberCache
import com.chips.beatsaber.BeatSnapshot
import com.chips.beatsaber.LOCAL_BEAT_CACHE
import com.chips.dashboard.DashboardCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BeatHrReportScreen(session: HrSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("beat_hr_alignment", Context.MODE_PRIVATE) }
    var offset by rememberSaveable(session.id) { mutableStateOf(preferences.getInt(session.id, 0).toString()) }
    var report by remember { mutableStateOf<List<PlayHr>?>(null) }
    var profile by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val seconds = offset.toIntOrNull()?.takeIf { it in -3600..3600 }
    LaunchedEffect(session.id, seconds, refresh) {
        report = null; error = null
        if(seconds == null) return@LaunchedEffect
        try {
            val loaded = withContext(Dispatchers.IO) {
                val snapshot = BeatSaberCache(context).use { it.read(LOCAL_BEAT_CACHE)?.optJSONObject("snapshot")?.let(BeatSnapshot::decode) }
                    ?: error("Import your BeatLeader scores in Beat Saber, then reload this report.")
                val plays = snapshot.scores.mapNotNull { s -> s.scoreId?.let { id -> BeatPlay(id,s.song,s.difficulty,s.timestamp*1000,
                    s.metric("duration"),s.modifiers,s.metric("pauses")?.toInt(),s.accuracy,s.metric("stars"),s.metric("play_started_at")?.toLong()?.times(1000)) } }
                HrStore(context).use { store ->
                    check(store.sessions(session.id).isNotEmpty()) { "This HR capture was deleted." }
                    snapshot.profile.optString("name", "Player") to correlateBeatHr(plays,store.readings(session.id),session.started,session.ended ?: System.currentTimeMillis(),seconds)
                }
            }
            profile = loaded.first
            report = loaded.second
            preferences.edit { putInt(session.id,seconds) }
        } catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { error = e.message ?: "Could not build report. Try reloading." }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack) { Text("Back to capture") }
            Text("Beat Saber + HR", style = MaterialTheme.typography.headlineLarge)
            Text("Capture: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(session.started))}")
            if(profile.isNotBlank()) Text("BeatLeader profile: $profile")
            Text("Play windows use BeatLeader's recorded start and song duration, adjusted for song speed. Older imports fall back to upload time minus duration. Pauses and device clocks can shift the match. Failed or unuploaded plays are absent from imported scores.")
            OutlinedTextField(offset, { offset = it }, label = { Text("Time adjustment (seconds)") }, supportingText = { Text("−3600 to 3600. Positive moves plays later; negative corrects upload delay.") }, isError = seconds == null, singleLine = true)
            TextButton(onClick = { refresh++ }) { Text("Reload saved scores and HR") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if(report == null && error == null && seconds != null) Text("Matching plays…")
            report?.let { rows ->
                Text("${rows.size} imported plays overlap this capture · ${rows.count { it.samples.isNotEmpty() }} with received HR")
                Text("Average and peak describe received samples. Coverage counts observed intervals up to 15 seconds apart; gaps are not filled.")
                if(rows.isEmpty()) Text("No matching plays. Refresh your BeatLeader import, check the selected profile and time adjustment, then reload here.")
            }
        }
        items(report.orEmpty(), key = { it.play.id }) { row -> DashboardCard(row.play.song) {
            Text("${row.play.difficulty} · %.2f%% accuracy".format(row.play.accuracy) + (row.play.stars?.let { " · %.2f ★".format(it) } ?: ""))
            val format = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm:ss").withZone(java.time.ZoneId.systemDefault())
            Text("Estimated: ${row.start?.let { format.format(java.time.Instant.ofEpochMilli(it)) } ?: "unknown start"} → ${format.format(java.time.Instant.ofEpochMilli(row.end))}")
            row.warning?.let { Text(it) }
            Text(row.average?.let { "Average %.1f bpm · peak %d bpm".format(it,row.peak) } ?: "No matched HR samples")
            Text("${row.samples.size} samples · %.0f%% observed coverage".format(row.coverage*100))
            if(row.samples.size > 1) {
                val color = MaterialTheme.colorScheme.primary
                val low = row.samples.minOf { it.bpm }.toFloat(); val high = row.samples.maxOf { it.bpm }.toFloat()
                Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                    fun point(s: HrReading) = Offset(((s.received-row.start!!).toDouble()/(row.end-row.start)).toFloat()*size.width,
                        size.height - (s.bpm-low+5)/(high-low+10)*size.height)
                    row.samples.zipWithNext().forEach { (a,b) ->
                        val wall = b.received-a.received; val elapsed = b.elapsed-a.elapsed
                        if(wall in 1..15000 && elapsed in 1..15000 && kotlin.math.abs(wall-elapsed)<=1000)
                            drawLine(color,point(a),point(b),3.dp.toPx())
                    }
                }
                Text("HR trace · ${low.toInt()}–${high.toInt()} bpm; blank spans indicate missing coverage")
            }
        } }
    }
}
