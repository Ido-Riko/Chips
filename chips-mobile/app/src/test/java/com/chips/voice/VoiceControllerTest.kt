package com.chips.voice

import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class VoiceControllerTest {
    private class Recorder : VoiceRecorder {
        var starts = 0
        var cancels = 0
        var file: File? = null
        var failStart = false
        var failFinish = false
        override fun start(): Result<Unit> {
            starts++
            if (failStart) return Result.failure(IOException("Permission denied"))
            file = File.createTempFile("chips-test", ".m4a").apply { writeText("audio") }
            return Result.success(Unit)
        }
        override fun finish(): Result<File> = if (failFinish) {
            file?.delete()
            Result.failure(IOException("Recording too short"))
        } else Result.success(file!!)
        override fun cancel() { cancels++; file?.delete() }
    }

    @Test fun failedStartAndShortRecordingNeverUploadAndCanRetry() = runBlocking {
        val recorder = Recorder().apply { failStart = true }
        var uploads = 0
        val controller = VoiceController(recorder, this, { uploads++; "reply" })
        controller.press()
        assertEquals(VoicePhase.ERROR, controller.state.value.phase)
        controller.release()
        recorder.failStart = false
        recorder.failFinish = true
        controller.press()
        controller.release()
        assertEquals(VoicePhase.ERROR, controller.state.value.phase)
        assertEquals(0, uploads)
        assertFalse(recorder.file!!.exists())
        recorder.failFinish = false
        controller.press()
        controller.release()
        assertEquals(VoicePhase.REPLY, controller.state.value.phase)
        assertEquals(1, uploads)
        assertFalse(recorder.file!!.exists())
        controller.cancel()
    }

    @Test fun repeatedPressesAndReleasesDoNotDuplicateRequests() = runBlocking {
        val recorder = Recorder()
        val reply = CompletableDeferred<String>()
        var uploads = 0
        val controller = VoiceController(recorder, this, { uploads++; reply.await() })
        controller.press()
        controller.press()
        assertEquals(1, recorder.starts)
        controller.release()
        controller.press()
        controller.release()
        assertEquals(1, uploads)
        assertEquals(VoicePhase.PROCESSING, controller.state.value.phase)
        reply.complete("answer")
        yield()
        assertEquals(VoiceState(VoicePhase.REPLY, "answer"), controller.state.value)
        assertFalse(recorder.file!!.exists())
        controller.cancel()
    }

    @Test fun cancelledGestureDiscardsRecordingWithoutUpload() = runBlocking {
        val recorder = Recorder()
        var uploads = 0
        val controller = VoiceController(recorder, this, { uploads++; "reply" })
        controller.press()
        controller.cancelGesture()
        controller.release()
        assertEquals(0, uploads)
        assertFalse(recorder.file!!.exists())
        assertEquals(VoicePhase.READY, controller.state.value.phase)
    }

    @Test fun dismissDuringRequestCancelsSenderAndDeletesAudio() = runBlocking {
        val recorder = Recorder()
        var cancelled = false
        val controller = VoiceController(recorder, this, {
            try { awaitCancellation() } finally { cancelled = true }
        })
        controller.press()
        controller.release()
        controller.cancel()
        yield()
        assertTrue(cancelled)
        assertFalse(recorder.file!!.exists())
        assertEquals(VoicePhase.READY, controller.state.value.phase)
    }

    @Test fun lateReplyCannotReplaceNewSession() = runBlocking {
        val recorder = Recorder()
        val oldReply = CompletableDeferred<String>()
        var uploads = 0
        val controller = VoiceController(recorder, this, {
            uploads++
            if (uploads == 1) withContext(NonCancellable) { oldReply.await() } else "new reply"
        })
        controller.press()
        val oldFile = recorder.file!!
        controller.release()
        controller.cancel()
        controller.press()
        controller.release()
        assertEquals("new reply", controller.state.value.text)
        oldReply.complete("stale reply")
        yield()
        assertEquals("new reply", controller.state.value.text)
        assertFalse(oldFile.exists())
        controller.cancel()
    }

    @Test fun failedRequestDeletesAudioAndNextPressCanRecover() = runBlocking {
        val recorder = Recorder()
        var fail = true
        val controller = VoiceController(recorder, this, {
            if (fail) throw IOException("Service unavailable")
            "Error: is just part of this valid answer"
        })
        controller.press()
        controller.release()
        assertEquals(VoicePhase.ERROR, controller.state.value.phase)
        assertFalse(recorder.file!!.exists())
        fail = false
        controller.press()
        controller.release()
        assertEquals(VoicePhase.REPLY, controller.state.value.phase)
        controller.cancel()
    }

    @Test fun recordingLimitReleasesRecorderWithoutSending() = runBlocking {
        val recorder = Recorder()
        var uploads = 0
        val controller = VoiceController(recorder, this, { uploads++; "reply" }, recordingLimitMillis = 1)
        controller.press()
        withTimeout(2_000) { controller.state.first { it.phase == VoicePhase.ERROR } }
        controller.release()
        assertFalse(recorder.file!!.exists())
        assertEquals(0, uploads)
        controller.cancel()
    }
}
