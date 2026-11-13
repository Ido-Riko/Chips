package com.chips.practice

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chips.dashboard.DashboardCard
import com.chips.ui.theme.ChipsMint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun JapaneseScreen(state: PracticeState, onBack: () -> Unit, onRate: (KanjiCard, Recall) -> Unit, onReset: () -> Unit, onRefresh: suspend () -> Unit) {
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { while (true) { onRefresh(); delay(30_000) } }
    val card = state.due.firstOrNull()
    var reveal by rememberSaveable(card?.kanji) { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize().testTag("japanese-screen"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            TextButton(onClick = onBack) { Text("← Today") }
            Text("A little Japanese.", style = MaterialTheme.typography.headlineLarge)
            Text("${state.reviews.size} kanji in your review deck", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (card != null) item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(card.kanji, fontSize = 100.sp, lineHeight = 112.sp, color = ChipsMint, modifier = Modifier.testTag("review-kanji"))
                    if (!reveal) {
                        Text("Recall the meaning and a reading.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { reveal = true }, modifier = Modifier.testTag("review-reveal")) { Text("Reveal answer") }
                    } else {
                        Text(card.meanings, style = MaterialTheme.typography.titleLarge)
                        if (card.kun.isNotEmpty()) Text("Kun · ${card.kun}")
                        if (card.on.isNotEmpty()) Text("On · ${card.on}")
                        Text("${card.strokes} strokes", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Recall.entries.forEach { recall ->
                                OutlinedButton(onClick = { onRate(card, recall) }, enabled = !state.busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp), modifier = Modifier.weight(1f).testTag("review-${recall.name}")) {
                                    Text(when(recall) { Recall.AGAIN -> "Again"; Recall.GOOD -> "Got it"; Recall.EASY -> "Easy" })
                                }
                            }
                        }
                    }
                }
            }
        } else item {
            DashboardCard("All caught up") {
                Text("Come back for your next review. Up to three new kanji each day.")
                state.reviews.minOfOrNull { it.due }?.let { due -> Text("Next review · ${practiceDate(due)}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item {
            Text("Again returns in 10 minutes. Got it and Easy move the card to a later day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { confirmReset = true }) { Text("Reset review progress") }
            Text("Kanji data: KANJIDIC2 © EDRDG, via kanjiapi.dev. Selected fields, CC BY-SA 4.0.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { runCatching { uri.openUri("https://www.edrdg.org/edrdg/licence.html") } }) { Text("Source & licence") }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Reset Japanese reviews?") },
        text = { Text("Clears your review schedule. Your activity history is kept.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } })
}

internal fun practiceDate(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm"))

@Composable
private fun DrawingImage(id: String, full: Boolean = false) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, id, full) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile((if (full) imageFile(context,id) else thumbFile(context,id)).path) }
    }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Saved drawing", modifier = Modifier.fillMaxWidth().height(if (full) 300.dp else 180.dp), contentScale = ContentScale.Fit) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawingScreen(state: PracticeState, onBack: () -> Unit, onImport: (android.net.Uri, String) -> Unit,
    onSend: (String) -> Unit, onNotes: (String, String) -> Unit, onDelete: (String) -> Unit, onRefresh: suspend () -> Unit) {
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { while (true) { onRefresh(); delay(2000) } }
    var goal by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val selected = state.drawings.firstOrNull { it.id == selectedId }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onImport(uri, goal) }
    LazyColumn(Modifier.fillMaxSize().testTag("drawing-screen"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            TextButton(onClick = onBack) { Text("← Today") }
            Text("Make something.", style = MaterialTheme.typography.headlineLarge)
            Text("Your sketchbook, with a little guidance.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(goal, { if (it.length <= 2000) goal = it }, label = { Text("Practice goal (optional)") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !state.busy,
                modifier = Modifier.padding(top = 12.dp).testTag("add-drawing")) { Text(if (state.busy) "Saving…" else "Add drawing") }
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (state.drawings.isEmpty()) item { Text("Add your first drawing. It stays available here, even offline.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.drawings, key = { it.id }) { drawing ->
            Surface(onClick = { selectedId = drawing.id }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().testTag("drawing-${drawing.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DrawingImage(drawing.id)
                    Text(drawing.goal.ifEmpty { "Drawing practice" }, style = MaterialTheme.typography.titleMedium)
                    Text(practiceDate(drawing.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(when { drawing.feedback.isNotEmpty() -> "Feedback ready"; drawing.queued && drawing.uploaded -> "Waiting for feedback"; drawing.queued -> "Waiting to upload"; drawing.uploaded -> "Saved on your server"; else -> "Saved on this phone" }, color = ChipsMint)
                }
            }
        }
        items(state.pendingDeletes, key = { "delete-${it.id}" }) { drawing ->
            TextButton(onClick = { onSend(drawing.id) }) { Text("${if (drawing.error.isEmpty()) "Removing server copy…" else "Retry removing server copy"}") }
        }
    }
    if (selected != null) ModalBottomSheet(onDismissRequest = { selectedId = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        var notes by rememberSaveable(selected.id) { mutableStateOf(selected.notes) }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DrawingImage(selected.id, true)
            Text(selected.goal.ifEmpty { "Drawing practice" }, style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(notes, { if (it.length <= 2000) notes = it }, label = { Text("Your notes") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { onNotes(selected.id, notes) }, enabled = !state.busy && notes != selected.notes) { Text("Save notes") }
            if (selected.feedback.isNotEmpty()) {
                Text("AI feedback", style = MaterialTheme.typography.titleLarge)
                Text(selected.feedback)
                Text(selected.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Send this drawing to your server to get feedback and a next practice drill.")
                Button(onClick = { onSend(selected.id) }, enabled = !state.busy, modifier = Modifier.testTag("drawing-send")) {
                    Text(if (selected.queued) "Retry now" else if (selected.uploaded) "Retry analysis" else "Send for feedback")
                }
            }
            if (selected.error.isNotEmpty()) Text(selected.error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { confirmDelete = selected.id }) { Text("Delete drawing") }
        }
    }
    if (confirmDelete != null) AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete drawing?") },
        text = { Text("Removes the image, notes and feedback here and queues removal of any server copy. Activity history is kept.") },
        confirmButton = { TextButton(onClick = { onDelete(confirmDelete!!); confirmDelete = null; selectedId = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
}
