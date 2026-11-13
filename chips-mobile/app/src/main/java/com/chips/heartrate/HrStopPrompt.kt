package com.chips.heartrate

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.chips.data.ActivityStore
import com.chips.data.ActivitySyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun HrStopPrompt() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val capture by HrCaptureService.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<HrSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            HrStore.changes.collect {
                pending = withContext(Dispatchers.IO) {
                    runCatching { HrStore(context).use { HrSessionLinker(it, ActivityStore.get(context)).pending() } }.getOrNull()
                }
            }
        }
    }
    fun decide(save: Boolean) {
        val id = pending?.id ?: return
        if(busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    HrStore(context).use { hr ->
                        if(save) HrSessionLinker(hr, ActivityStore.get(context)).save(id) else hr.decide(id, save = false)
                    }
                }
                error = null
                runCatching { ActivitySyncWorker.schedule(context) }
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { error = "Could not save the Beat Saber choice. Your HR capture is safe. Try again." }
            finally { busy = false }
        }
    }
    val session = pending
    if(session != null && !capture.stopping) AlertDialog(
        modifier = Modifier.testTag("hr-beat-saber-prompt"),
        onDismissRequest = { if(!busy) decide(false) },
        title = { Text("Save a Beat Saber session too?") },
        text = { Column {
            Text("HR capture is saved on this phone: ${session.samples} received readings, starting ${DateFormat.getDateTimeInstance().format(Date(session.started))}.")
            Text("Save a completed Beat Saber session for this capture and link the two records. They save locally first, then sync when your server is available.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = { decide(true) }, modifier = Modifier.testTag("hr-save-beat-saber")) { Text(if(busy) "Saving…" else "Save Beat Saber session") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { decide(false) }, modifier = Modifier.testTag("hr-only")) { Text("HR only") } }
    )
}
