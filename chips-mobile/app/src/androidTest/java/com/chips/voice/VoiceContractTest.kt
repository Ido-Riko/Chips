package com.chips.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chips.dashboard.ActivityType
import com.chips.data.ActivityStore
import com.chips.data.VoiceHistoryStore
import com.chips.network.HttpVoiceService
import com.chips.network.VoiceReplyProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class VoiceContractTest {
    private fun json(id: String) = JSONObject().put("protocol_version", 1).put("request_id", id)
        .put("is_demo", false).put("transcript", "日本語で練習したい — let's practise")
        .put("answer", "今日も練習しましょう。")

    @Test fun strictContractAcceptsRealUnicodeAndRejectsLegacyDemoInvalidAndMismatchedReplies() {
        VoiceReplyProtocol.checkCapabilities("{\"protocol_version\":1,\"idempotency\":true}")
        assertTrue(runCatching { VoiceReplyProtocol.checkCapabilities("{\"protocol_version\":1,\"idempotency\":false}") }.isFailure)
        val id = UUID.randomUUID().toString()
        assertEquals("今日も練習しましょう。", VoiceReplyProtocol.decode(json(id).toString(), id).answer)
        val invalid = listOf("plain old answer", "{}", json(id).put("is_demo", true).toString(),
            json("another-request").toString(), json(id).put("transcript", JSONObject.NULL).toString(),
            json(id).put("answer", "").toString(), json(id).put("transcript", "x".repeat(10_001)).toString(),
            json(id).put("protocol_version", 2).toString(), json(id).put("is_demo", "false").toString(),
            json(id).put("protocol_version", "1").toString())
        invalid.forEach { body ->
            val failure = runCatching { VoiceReplyProtocol.decode(body, id) }.exceptionOrNull()
            assertTrue(failure is VoiceFailure)
            assertFalse((failure as VoiceFailure).retryable)
        }
    }

    @Test fun responseLossRetrySavesOnceAndSurvivesDatabaseReopenAndDeletion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "voice-contract-${UUID.randomUUID()}.db"
        var db = ActivityStore(context, name)
        var history = VoiceHistoryStore(db)
        val requests = mutableListOf<Pair<String, String>>()
        var audio: File? = null
        val recorder = object : VoiceRecorder {
            override fun start(): Result<Unit> {
                audio = File.createTempFile("contract-test", ".m4a", context.cacheDir).apply { writeText("synthetic audio fixture") }
                return Result.success(Unit)
            }
            override fun finish() = Result.success(audio!!)
            override fun cancel() { audio?.delete() }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") {
                return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body("{\"protocol_version\":1,\"idempotency\":true}".toResponseBody()).build()
            }
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val id = request.header("Idempotency-Key")!!
            synchronized(requests) { requests += id to buffer.readUtf8().lineSequence().filterNot { it.startsWith("--") }.joinToString("\n") }
            assertEquals("application/json", request.header("Accept"))
            assertEquals("Bearer fixture-token", request.header("Authorization"))
            val first = synchronized(requests) { requests.size == 1 }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (first) 503 else 200).message("fixture")
                .body((if (first) "{\"error\":{\"code\":\"stt_unavailable\"}}" else json(id).toString()).toResponseBody()).build()
        }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = VoiceController(recorder, scope, HttpVoiceService("https://fixture.invalid/api/v1", "fixture-token", client), history::save)
        try {
            val activity = withContext(Dispatchers.IO) { db.log(ActivityType.JAPANESE) }
            withContext(Dispatchers.Main) { controller.press(); controller.release() }
            withTimeout(5_000) { controller.state.first { it.phase == VoicePhase.ERROR } }
            assertTrue(controller.state.value.text!!.contains("Speech recognition is unavailable"))
            assertTrue(audio!!.exists())
            withContext(Dispatchers.Main) { controller.retry() }
            withTimeout(5_000) { controller.state.first { it.phase == VoicePhase.REPLY } }
            assertFalse(audio!!.exists())
            assertEquals(2, requests.size)
            assertEquals(requests[0], requests[1])
            assertTrue(requests[0].second.contains("audio/mp4"))
            assertTrue(requests[0].second.contains("name=\"request_id\""))
            assertEquals(1, history.records.value.size)
            withContext(Dispatchers.IO) { db.close() }
            db = ActivityStore(context, name)
            history = VoiceHistoryStore(db)
            history.refresh()
            assertEquals(1, history.records.value.size)
            val saved = history.records.value.single()
            assertEquals(requests[0].first, saved.id)
            assertEquals("日本語で練習したい — let's practise", saved.transcript)
            assertEquals("今日も練習しましょう。", saved.answer)
            history.save(saved)
            assertEquals(1, history.records.value.size)
            assertTrue(runCatching { history.save(saved.copy(answer = "conflicting reply")) }.isFailure)
            history.delete(saved.id)
            withContext(Dispatchers.IO) { db.close() }
            db = ActivityStore(context, name)
            assertTrue(withContext(Dispatchers.IO) { db.loadVoiceHistory() }.isEmpty())
            assertEquals(activity.id, withContext(Dispatchers.IO) { db.load() }.single().id)
        } finally {
            withContext(Dispatchers.Main) { controller.cancel() }
            scope.cancel()
            withContext(Dispatchers.IO) { db.close(); context.deleteDatabase(name) }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            audio?.delete()
        }
    }

    @Test fun reservedVersionOneSchemaRetainsActivityAndVoiceRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "voice-schema-${UUID.randomUUID()}.db"
        val record = VoiceConversation(UUID.randomUUID().toString(), 1234, "spoken", "reply")
        try {
            ActivityStore(context, name).use { db ->
                db.log(ActivityType.WORKOUT)
                db.saveVoiceConversation(record)
                assertEquals(1, db.readableDatabase.version)
            }
            ActivityStore(context, name).use { db ->
                assertEquals(record, db.loadVoiceHistory().single())
                assertEquals(ActivityType.WORKOUT, db.load().single().type)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun incompatibleCapabilitiesNeverReceiveAudio() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("capability-test", ".m4a", context.cacheDir)
        var posts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "POST") posts++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("{\"protocol_version\":1,\"idempotency\":false}".toResponseBody()).build()
        }.build()
        try {
            val failure = runCatching {
                HttpVoiceService("https://fixture.invalid/api/v1", "fixture-token", client)
                    .send(VoiceRequest(UUID.randomUUID().toString(), file, 1234)) {}
            }.exceptionOrNull()
            assertTrue(failure is VoiceFailure)
            assertFalse((failure as VoiceFailure).retryable)
            assertEquals(0, posts)
        } finally {
            file.delete(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    /** Isolated fixture intentionally retained for the external force-stop/relaunch acceptance check. */
    @Test fun completedConversationFixtureForProcessRestart(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityStore(context, "m3-process-acceptance.db").use { db ->
            val history = VoiceHistoryStore(db)
            val record = VoiceConversation("11111111-1111-4111-8111-111111111111", 1234,
                "Synthetic M3 process-restart fixture", "Synthetic saved reply")
            history.save(record)
            assertEquals(record, history.records.value.single())
        }
    }
}
