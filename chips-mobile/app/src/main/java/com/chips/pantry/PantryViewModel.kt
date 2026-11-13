package com.chips.pantry

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chips.data.SyncCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class PantryState(val items: List<PantryItem> = emptyList(), val pending: Int = 0, val status: String = "",
    val error: String = "", val busy: Boolean = false, val receipt: String = "", val hasPhoto: Boolean = false, val duplicate: Boolean = false)
internal class PantryViewModel(app: Application, private val store: PantryStore = PantryStore(app)): AndroidViewModel(app) {
    private val actionMutex = kotlinx.coroutines.sync.Mutex()
    private val mutable = MutableStateFlow(PantryState())
    val state = mutable.asStateFlow()
    private val photo get() = File(getApplication<Application>().filesDir, "pantry-receipt.jpg")
    init { refresh(); PantrySyncWorker.schedule(app) }
    fun refresh() { viewModelScope.launch(Dispatchers.IO) { if(!mutable.value.busy) reload() } }
    private fun reload() {
        val pending = store.transactions(true).size
        val last = store.state("last_sync").toLongOrNull()
        val text = store.state("receipt")
        mutable.value = mutable.value.copy(items = store.items(), pending = pending, receipt = text, hasPhoto = photo.exists(),
            duplicate = store.duplicate(fingerprint()), status = if(pending > 0) "$pending changes waiting for server sync" else if(last == null) "Saved on this phone" else "Server synced · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(last)),
            error = store.state("error"))
    }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch(Dispatchers.IO) {
        if(!actionMutex.tryLock()) return@launch
        mutable.value = mutable.value.copy(busy = true, error = "")
        try { store.state("error", ""); block(); reload() } catch(e: CancellationException) { throw e }
        catch(e: Exception) { val message = e.message ?: "Could not save. Try again."; store.state("error", message); mutable.value = mutable.value.copy(error = message) }
        finally { mutable.value = mutable.value.copy(busy = false); actionMutex.unlock() }
    } }
    fun add(name: String, code: String, unit: String, pack: String, expiry: String, quantity: String, low: String, done: () -> Unit) = action {
        val item = pantryItem(name, code, unit, pack, expiry, quantity, low)
        store.add(listOf(item), if(code.isEmpty()) "manual" else "barcode")
        if(code.isNotEmpty()) store.rememberBarcode(code, item.name, item.pack)
        PantrySyncWorker.schedule(getApplication(), true)
        withContext(Dispatchers.Main) { done() }
    }
    fun adjust(item: PantryItem, amount: String, consume: Boolean, done: () -> Unit) = action {
        store.adjust(item, amount, consume); PantrySyncWorker.schedule(getApplication(), true)
        withContext(Dispatchers.Main) { done() }
    }
    fun sync() { PantrySyncWorker.schedule(getApplication(), true); refresh() }
    fun saveDraft(text: String) {
        require(text.length <= 12000)
        // Tiny SQLite write, serialized before any subsequent confirmation or process stop.
        store.state("receipt", text)
        mutable.value = mutable.value.copy(receipt = text, duplicate = store.duplicate(fingerprint()))
    }
    private fun fingerprint(): String = store.state("photo_hash").ifBlank {
        if(store.state("receipt").isBlank()) "" else hash(store.state("receipt").trim().lowercase(java.util.Locale.ROOT).toByteArray())
    }
    fun discard() = action { photo.delete(); store.state("receipt", ""); store.state("photo_hash", "") }
    fun confirmReceipt(allowDuplicate: Boolean) = action {
        val items = receiptLines(store.state("receipt"))
        store.add(items, "receipt", fingerprint(), allowDuplicate)
        photo.delete(); store.state("receipt", ""); store.state("photo_hash", "")
        PantrySyncWorker.schedule(getApplication(), true)
    }
    fun importPhoto(uri: Uri) = action {
        val context = getApplication<Application>()
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val result = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var count: Int
            while(input.read(buffer).also { count = it } != -1) { require(result.size() + count <= 20 * 1024 * 1024) { "Use a receipt photo under 20 MB" }; result.write(buffer, 0, count) }; result.toByteArray()
        } ?: error("Could not open receipt")
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        require(opts.outWidth > 0 && opts.outHeight > 0) { "Use a valid photo" }
        opts.inJustDecodeBounds = false; opts.inSampleSize = 1
        while(maxOf(opts.outWidth, opts.outHeight) / opts.inSampleSize > 2400) opts.inSampleSize *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: error("Could not read receipt")
        val exif = android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
        val orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
        val matrix = android.graphics.Matrix().apply { when(orientation) {
            2 -> setScale(-1f,1f); 3 -> setRotate(180f); 4 -> setScale(1f,-1f)
            5 -> { setRotate(90f); postScale(-1f,1f) }; 6 -> setRotate(90f)
            7 -> { setRotate(-90f); postScale(-1f,1f) }; 8 -> setRotate(-90f)
        } }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val opaque = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(opaque).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(rotated,0f,0f,null) }
        val temporary = File(photo.parentFile, "pantry-receipt.tmp")
        temporary.outputStream().use { opaque.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        opaque.recycle(); if(rotated !== bitmap) rotated.recycle(); bitmap.recycle()
        require(temporary.length() <= 5 * 1024 * 1024) { "Receipt is too large; use a smaller photo" }
        check(temporary.renameTo(photo)) { "Could not save receipt photo" }
        store.state("photo_hash", hash(bytes)); store.state("receipt", "")
    }
    fun readReceipt() = action {
        val credentials = SyncCredentials(getApplication())
        check(credentials.connected()) { "Connect your server in Settings to read photos, or type the receipt below." }
        val config = credentials.read() ?: error("Enroll your server in Settings first")
        check(photo.exists()) { "Choose a receipt photo first" }
        val text = PantryRemote(config).receipt(photo)
        require(text.length <= 12000) { "Receipt response too long" }; store.state("receipt", text)
    }
    fun lookup(code: String, done: (String, String) -> Unit) = action {
        require(code.length in 4..32 && code.all { it in '0'..'9' }) { "Enter a numeric barcode" }
        val saved = store.state("barcode:$code")
        val local = store.items().firstOrNull { it.barcode == code }
        val product = if(saved.isNotBlank()) JSONObject(saved) else if(local != null) JSONObject().put("name", local.name).put("package", local.pack) else {
            val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build()
            client.newCall(Request.Builder().url("https://world.openfoodfacts.org/api/v2/product/$code.json?fields=product_name,quantity")
                .header("User-Agent", "CHIPS/1.0 (https://github.com/Ido-Riko/Chips)").build()).execute().use { r ->
                check(r.isSuccessful) { "Product lookup unavailable. Enter a name manually." }
                val source = r.body?.source() ?: error("Empty product response"); source.request(128 * 1024L + 1)
                check(source.buffer.size <= 128 * 1024) { "Product response too large" }
                val reply = JSONObject(source.readUtf8()); val p = reply.optJSONObject("product")
                check(p != null && p.optString("product_name").isNotBlank()) { "Unknown product. Enter its name; it will be remembered." }
                JSONObject().put("name", p.optString("product_name").take(200)).put("package", p.optString("quantity").take(100))
            }
        }
        store.rememberBarcode(code, product.getString("name"), product.optString("package"))
        withContext(Dispatchers.Main) { done(product.getString("name"), product.optString("package")) }
    }
    override fun onCleared() { store.close() }
}
private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
