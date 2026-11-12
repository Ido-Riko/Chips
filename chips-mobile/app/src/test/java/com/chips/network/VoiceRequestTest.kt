package com.chips.network

import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import com.chips.voice.VoiceFailure
import org.junit.Assert.*
import org.junit.Test

class VoiceRequestTest {
    private fun call(code: Int, body: String): Call = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body(body.toResponseBody()).build()
        }.build().newCall(Request.Builder().url("http://localhost/voice-log").build())

    @Test fun transportReturnsSuccessfulBodyForProtocolValidation() = runBlocking {
        assertEquals("answer", awaitVoiceReply(call(200, " answer ")))
    }

    @Test fun unauthorizedAndEmptyResponsesAreFailures() = runBlocking {
        for ((code, body, expected) in listOf(
            Triple(401, "private server diagnostics", "Device access rejected"),
            Triple(200, "   ", "empty reply"),
            Triple(503, "private server diagnostics", "Try again shortly")
        )) {
            val error = runCatching { awaitVoiceReply(call(code, body)) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error!!.message!!.contains(expected))
            assertFalse(error.message!!.contains("private server diagnostics"))
        }
    }

    @Test fun cancellationCancelsRealHttpCallWhileReadingBody() = runBlocking {
        ServerSocket(0).use { server ->
            val headersSent = CountDownLatch(1)
            val socketClosed = CountDownLatch(1)
            val serverThread = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\npartial".toByteArray())
                        flush()
                    }
                    headersSent.countDown()
                    try { if (input.read() == -1) socketClosed.countDown() } catch (_: IOException) { socketClosed.countDown() }
                }
            }
            val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
            val request = launch(start = CoroutineStart.UNDISPATCHED) { awaitVoiceReply(call) }
            try {
                assertTrue(headersSent.await(5, TimeUnit.SECONDS))
                request.cancelAndJoin()
                assertTrue(call.isCanceled())
                assertTrue(socketClosed.await(5, TimeUnit.SECONDS))
            } finally {
                request.cancelAndJoin()
                call.cancel()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                serverThread.join(5_000)
            }
        }
    }

    @Test fun voiceRequestCarriesStableIdentityAndReportsCompletedUpload() {
        var uploaded = false
        val request = voiceHttpRequest("https://fixture.invalid/api/v1", "fixture-token", "stable-id",
            "audio fixture".toRequestBody(), { uploaded = true })
        assertEquals("stable-id", request.header("Idempotency-Key"))
        assertEquals("1", request.header("X-Chips-Voice-Protocol"))
        assertEquals("application/json", request.header("Accept"))
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        assertEquals("audio fixture", buffer.readUtf8())
        assertTrue(uploaded)
        val client = voiceHttpClient()
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertEquals(120_000, client.callTimeoutMillis)
    }

    @Test fun totalTimeoutProducesRecoverableFailure() = runBlocking {
        ServerSocket(0).use { server ->
            val release = CountDownLatch(1)
            val serverThread = thread(isDaemon = true) {
                server.accept().use { release.await(5, TimeUnit.SECONDS) }
            }
            val client = OkHttpClient.Builder().callTimeout(150, TimeUnit.MILLISECONDS).build()
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
            try {
                val failure = runCatching { awaitVoiceReply(call) }.exceptionOrNull()
                assertTrue(failure is VoiceFailure)
                assertTrue((failure as VoiceFailure).retryable)
                assertTrue(call.isCanceled())
            } finally {
                release.countDown(); call.cancel(); serverThread.join(5_000)
                client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            }
        }
    }

    @Test fun cancellationStopsActualUploadWriter() = runBlocking {
        ServerSocket(0).use { server ->
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val exited = CountDownLatch(1)
            val serverThread = thread(isDaemon = true) {
                server.accept().use { release.await(5, TimeUnit.SECONDS) }
            }
            val body = object : RequestBody() {
                override fun contentType(): MediaType? = null
                override fun writeTo(sink: BufferedSink) {
                    entered.countDown()
                    try { repeat(4096) { sink.write(ByteArray(8192)) } }
                    finally { exited.countDown() }
                }
            }
            val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").post(body).build())
            val request = launch(start = CoroutineStart.UNDISPATCHED) { awaitVoiceReply(call) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                request.cancelAndJoin()
                assertTrue(call.isCanceled())
                assertTrue(exited.await(5, TimeUnit.SECONDS))
            } finally {
                request.cancelAndJoin(); call.cancel(); release.countDown(); serverThread.join(5_000)
                client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            }
        }
    }
}
