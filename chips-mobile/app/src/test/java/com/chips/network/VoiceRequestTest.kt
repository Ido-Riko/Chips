package com.chips.network

import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class VoiceRequestTest {
    private fun call(code: Int, body: String): Call = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body(body.toResponseBody()).build()
        }.build().newCall(Request.Builder().url("http://localhost/voice-log").build())

    @Test fun successfulReplyRemainsPlainTextCompatible() = runBlocking {
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
}
