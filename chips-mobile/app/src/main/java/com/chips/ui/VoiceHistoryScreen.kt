package com.chips.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chips.dashboard.DashboardCard
import com.chips.data.VoiceHistoryStore
import com.chips.voice.VoiceConversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun VoiceHistoryScreen(store: VoiceHistoryStore = VoiceHistoryStore.get(LocalContext.current)) {
    val records by store.records.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<VoiceConversation?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        try { store.refresh(); loaded = true; error = null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Could not read saved conversations. Try again." }
    }
    LaunchedEffect(store) { load() }
    LazyColumn(Modifier.fillMaxSize().testTag("voice-history"), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Conversations", style = MaterialTheme.typography.headlineMedium)
            Text("Transcripts and replies saved on this phone. Audio isn’t kept in history.")
            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { load() } }) { Text("Try again") }
            } else if (!loaded) Text("Loading conversations…")
            else if (records.isEmpty()) Text("No saved conversations yet", style = MaterialTheme.typography.titleLarge)
        }
        items(records, key = { it.id }) { record ->
            DashboardCard(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(record.occurredAt)), modifier = Modifier.testTag("conversation-${record.id}")) {
                Text("YOU SAID", style = MaterialTheme.typography.labelMedium)
                Text(record.transcript, maxLines = if (expanded == record.id) Int.MAX_VALUE else 3)
                if (expanded == record.id) {
                    Text("CHIPS REPLIED", style = MaterialTheme.typography.labelMedium)
                    Text(record.answer)
                }
                Row {
                    TextButton(onClick = { expanded = if (expanded == record.id) null else record.id }) {
                        Text(if (expanded == record.id) "Collapse" else "Read conversation")
                    }
                    TextButton(onClick = { deleting = record }) { Text("Delete") }
                }
            }
        }
    }
    deleting?.let { record ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete conversation?") },
            text = { Text("Removes this transcript and reply from this phone. Any copy retained by the service is managed separately.") },
            confirmButton = { TextButton(onClick = {
                deleting = null
                scope.launch {
                    try { store.delete(record.id); error = null }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Could not delete the conversation. Try again." }
                }
            }) { Text("Delete from phone") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}
