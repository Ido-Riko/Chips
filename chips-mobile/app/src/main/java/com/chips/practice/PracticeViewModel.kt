package com.chips.practice

import android.app.Application
import android.content.ContentValues
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chips.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class PracticeState(val deck: List<KanjiCard> = emptyList(), val reviews: List<Review> = emptyList(),
    val due: List<KanjiCard> = emptyList(), val drawings: List<DrawingEntry> = emptyList(), val pendingDeletes: List<DrawingEntry> = emptyList(),
    val busy: Boolean = false, val error: String? = null)

class PracticeViewModel(private val app: Application, private val store: PracticeStore = PracticeStore(ActivityStore.get(app))) : ViewModel() {
    private val mutable = MutableStateFlow(PracticeState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    init { refresh(); DrawingSyncWorker.schedule(app) }
    suspend fun reload() {
        val started = generation
        val loaded = withContext(Dispatchers.IO) {
            val deck = if (mutable.value.deck.isEmpty()) store.deck(app) else mutable.value.deck
            val rows = store.drawings(true)
            PracticeState(deck, store.reviews(), store.queue(deck), rows.filterNot { it.deleted }, rows.filter { it.deleted })
        }
        if (generation != started) return
        mutable.update { loaded.copy(busy = it.busy, error = it.error) }
    }
    fun refresh() { viewModelScope.launch { try { reload() } catch(e: Exception) { mutable.update { it.copy(error = "Could not read saved practice.") } } } }
    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        generation++
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() }; generation++; reload() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "Could not save practice. Retry.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun rate(card: KanjiCard, recall: Recall) = action { store.rate(card.kanji, recall); ActivitySyncWorker.schedule(app) }
    fun reset() = action { store.resetReviews() }
    fun notes(id: String, notes: String) = action {
        require(notes.length <= 2000)
        store.updateDrawing(id, ContentValues().apply { put("notes", notes) })
    }
    fun send(id: String) = action {
        store.updateDrawing(id, ContentValues().apply { put("queued", 1); put("error", "") })
        DrawingSyncWorker.schedule(app, retryNow = true)
        if (!SyncCredentials(app).connected()) mutable.update { it.copy(error = "Connect to your server in Settings to send drawings. Your drawing is saved here.") }
    }
    fun remove(id: String) = action {
        store.deleteDrawing(id)
        imageFile(app, id).delete(); thumbFile(app, id).delete()
        DrawingSyncWorker.schedule(app)
    }
    fun importDrawing(uri: Uri, goal: String) = action {
        val id = UUID.randomUUID().toString()
        val raw = File.createTempFile("drawing-import-", ".image", app.cacheDir)
        try {
            app.contentResolver.openInputStream(uri)?.use { input -> raw.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 20 * 1024 * 1024) { "Choose an image smaller than 20 MB." }; output.write(buffer, 0, n) }
            } } ?: error("Could not open this image. Choose it again.")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Choose a readable drawing image." }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            val bitmap = BitmapFactory.decodeFile(raw.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Could not read this image.")
            val orientation = runCatching { ExifInterface(raw.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                    7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f)
                }
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val flat = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(rotated, 0f, 0f, null) }
            imageFile(app, id).outputStream().use { check(flat.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
            val ratio = 320f / maxOf(flat.width, flat.height)
            val thumb = Bitmap.createScaledBitmap(flat, maxOf(1, (flat.width * ratio).toInt()), maxOf(1, (flat.height * ratio).toInt()), true)
            thumbFile(app, id).outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            store.addDrawing(id, goal.trim())
            ActivitySyncWorker.schedule(app)
        } catch (e: Exception) {
            imageFile(app, id).delete(); thumbFile(app, id).delete(); throw e
        } finally { raw.delete() }
    }
}
