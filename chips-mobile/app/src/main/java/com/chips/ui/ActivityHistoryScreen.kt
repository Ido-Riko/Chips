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
import com.chips.dashboard.*
import com.chips.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ActivityHistoryScreen(state: DashboardUiState, model: DashboardViewModel, focusedId: String? = null,
    onOpenHeartRate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ActivityRecord?>(null) }
    var deleting by remember { mutableStateOf<ActivityRecord?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun change(action: suspend () -> Unit) {
        scope.launch {
            try { action(); ActivitySyncWorker.schedule(context); error = null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Could not save this change. Check the date (YYYY-MM-DD) and try again." }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("activity-history"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("History", style = MaterialTheme.typography.headlineMedium)
            Text("Saved on this phone · daily completion uses the date recorded with each session.", style = MaterialTheme.typography.bodyMedium)
            if (state.storageError != null) Text(state.storageError, color = MaterialTheme.colorScheme.error)
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            if (!state.loaded && state.storageError == null) Text("Loading saved entries…")
            else if (state.visibleRecords.isEmpty()) Text("No saved entries yet", style = MaterialTheme.typography.titleLarge)
            val pending = state.records.count { it.syncStatus != "SYNCED" }
            if (pending > 0) {
                Text("$pending changes waiting to sync (including deletions)")
                TextButton(onClick = { ActivitySyncWorker.schedule(context, retryNow = true) }) { Text("Retry sync") }
            }
        }
        items(state.visibleRecords.sortedBy { if(it.id == focusedId) 0 else 1 }, key = { it.id }) { record ->
            DashboardCard(record.type.label, modifier = Modifier.testTag("record-${record.id}"), trailing = when (record.syncStatus) {
                "SYNCED" -> "Synced"; "FAILED" -> "Sync failed"; else -> "Pending sync"
            }) {
                Text("${record.localDay} · ${record.zoneId}")
                if (record.note.isNotBlank()) Text(record.note)
                record.hrSessionId?.let { id ->
                    Text("Linked heart-rate capture")
                    TextButton(onClick = { onOpenHeartRate(id) }, modifier = Modifier.testTag("activity-open-hr-${record.id}")) { Text("Open HR capture") }
                }
                if (record.error.isNotEmpty()) Text(record.error, color = MaterialTheme.colorScheme.error)
                Row {
                    TextButton(onClick = { editing = record }) { Text("Edit") }
                    TextButton(onClick = { deleting = record }) { Text("Delete") }
                }
            }
        }
    }
    deleting?.let { record ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${record.type.label}?") },
            text = { Text("This removes the session and updates Today and streaks. Deletion will sync when connected.") },
            confirmButton = { TextButton(onClick = { deleting = null; change { model.delete(record.id) } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
    editing?.let { record ->
        var type by remember(record.id) { mutableStateOf(record.type) }
        var note by remember(record.id) { mutableStateOf(record.note) }
        var day by remember(record.id) { mutableStateOf(record.localDay) }
        var invalid by remember(record.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Edit session") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityType.entries.forEach { candidate ->
                    Row { RadioButton(selected = type == candidate, enabled = record.hrSessionId == null || candidate == ActivityType.BEAT_SABER, onClick = { type = candidate }); Text(candidate.label, Modifier.padding(top = 12.dp)) }
                }
                OutlinedTextField(value = day, onValueChange = { day = it }, label = { Text("Date (YYYY-MM-DD)") }, isError = invalid, singleLine = true)
                OutlinedTextField(value = note, onValueChange = { if (it.length <= 2000) note = it }, label = { Text("Note") })
                if (invalid) Text("Enter a valid date.")
            }
        }, confirmButton = { TextButton(onClick = {
            invalid = runCatching { java.time.LocalDate.parse(day) }.isFailure
            if (!invalid) { editing = null; change { model.edit(record.id, type, note, day) } }
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
}
