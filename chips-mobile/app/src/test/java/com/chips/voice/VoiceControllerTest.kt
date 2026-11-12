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
        var file: File? = null
        var failStart = false
        var failFinish = false
        var failure: (String) -> Unit = {}
        override fun onFailure(listener: (String) -> Unit) { failure = listener }
        override fun start(): Result<Unit> {
            starts++
            if (failStart) return Result.failure(IOException("Permission denied"))
            file = File.createTempFile("chips-test", ".m4a").apply { writeText("audio") }
            return Result.success(Unit)
        }
        override fun finish(): Result<File> = if (failFinish) {
            file?.delete(); Result.failure(IOException("Recording too short"))
        } else Result.success(file!!)
        override fun cancel() { file?.delete() }
    }
    private fun reply(request: VoiceRequest, answer: String = "answer") = VoiceReply(request.id, "actual spoken words", answer)

    @Test fun failedStartAndShortRecordingNeverUploadAndCanRetry() = runBlocking {
        val recorder = Recorder().apply { failStart = true }
        var uploads = 0
        val saved = mutableListOf<VoiceConversation>()
        val controller = VoiceController(recorder, this, VoiceService { r, _ -> uploads++; reply(r) }, { saved += it })
        controller.press(); controller.release()
        assertEquals(VoicePhase.ERROR, controller.state.value.phase)
        recorder.failStart = false; recorder.failFinish = true
        controller.press(); controller.release()
        assertEquals(0, uploads)
        assertFalse(recorder.file!!.exists())
        recorder.failFinish = false
        controller.press(); controller.release()
        assertEquals(VoicePhase.REPLY, controller.state.value.phase)
        assertEquals(1, saved.size)
        assertFalse(recorder.file!!.exists())
        controller.cancel()
    }

    @Test fun repeatedPressesAndReleasesDoNotDuplicateRequests() = runBlocking {
        val recorder = Recorder()
        val response = CompletableDeferred<Unit>()
        var uploads = 0
        val controller = VoiceController(recorder, this, VoiceService { r, uploaded ->
            uploads++; uploaded(); response.await(); reply(r)
        }, {})
        controller.press(); controller.press(); controller.release(); controller.press(); controller.release()
        assertEquals(1, recorder.starts)
        assertEquals(1, uploads)
        response.complete(Unit); yield()
        assertEquals("actual spoken words", controller.state.value.transcript)
        assertEquals("answer", controller.state.value.text)
        assertFalse(recorder.file!!.exists())
        controller.cancel()
    }

    @Test fun cancelledGestureDiscardsRecordingWithoutUpload() = runBlocking {
        val recorder = Recorder()
        var uploads = 0
        val controller = VoiceController(recorder, this, VoiceService { r, _ -> uploads++; reply(r) }, {})
        controller.press(); controller.cancelGesture(); controller.release()
        assertEquals(0, uploads)
        assertFalse(recorder.file!!.exists())
        assertEquals(VoicePhase.READY, controller.state.value.phase)
    }

    @Test fun dismissDuringRequestCancelsSenderAndDeletesAudio() = runBlocking {
        val recorder = Recorder()
        var cancelled = false
        val controller = VoiceController(recorder, this, VoiceService { _, _ ->
            try { awaitCancellation() } finally { cancelled = true }
        }, {})
        controller.press(); controller.release(); controller.cancel(); yield()
        assertTrue(cancelled)
        assertFalse(recorder.file!!.exists())
        assertEquals(VoicePhase.READY, controller.state.value.phase)
    }

    @Test fun lateReplyCannotReplaceOrSaveOverNewSession() = runBlocking {
        val recorder = Recorder()
        val oldReply = CompletableDeferred<Unit>()
        val saved = mutableListOf<VoiceConversation>()
        var uploads = 0
        val controller = VoiceController(recorder, this, VoiceService { r, _ ->
            uploads++
            if (uploads == 1) withContext(NonCancellable) { oldReply.await(); reply(r, "stale") }
            else reply(r, "new reply")
        }, { saved += it })
        controller.press(); val oldFile = recorder.file!!
        controller.release(); controller.cancel(); controller.press(); controller.release()
        oldReply.complete(Unit); yield()
        assertEquals("new reply", controller.state.value.text)
        assertEquals(listOf("new reply"), saved.map { it.answer })
        assertFalse(oldFile.exists())
        controller.cancel()
    }

    @Test fun explicitRetryKeepsSameIdAndAudioAndSavesOnce() = runBlocking {
        val recorder = Recorder()
        val ids = mutableListOf<String>()
        val files = mutableListOf<File>()
        val saved = mutableListOf<VoiceConversation>()
        val controller = VoiceController(recorder, this, VoiceService { r, _ ->
            ids += r.id; files += r.file
            if (ids.size == 1) throw VoiceFailure("Lost response")
            reply(r)
        }, { saved += it })
        controller.press(); controller.release()
        assertTrue(controller.state.value.canRetry)
        assertTrue(recorder.file!!.exists())
        controller.retry()
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
        assertEquals(files[0], files[1])
        assertEquals(1, saved.size)
        assertFalse(recorder.file!!.exists())
        controller.cancel()
    }

    @Test fun saveFailureRetriesStorageWithoutAnotherServiceRequest() = runBlocking {
        val recorder = Recorder()
        var uploads = 0
        var attempts = 0
        val controller = VoiceController(recorder, this, VoiceService { r, _ -> uploads++; reply(r) }, {
            attempts++
            if (attempts == 1) throw IOException("Disk full")
        })
        controller.press(); controller.release()
        assertTrue(controller.state.value.retrySaving)
        assertFalse(recorder.file!!.exists())
        controller.retry()
        assertEquals(1, uploads)
        assertEquals(2, attempts)
        assertEquals(VoicePhase.REPLY, controller.state.value.phase)
        controller.cancel()
    }

    @Test fun replyIsNotShownAsSavedUntilStorageCompletes() = runBlocking {
        val recorder = Recorder()
        val stored = CompletableDeferred<Unit>()
        val controller = VoiceController(recorder, this, VoiceService { r, _ -> reply(r) }, { stored.await() })
        controller.press(); controller.release()
        assertEquals(VoicePhase.SAVING, controller.state.value.phase)
        assertNull(controller.state.value.transcript)
        stored.complete(Unit); yield()
        assertEquals(VoicePhase.REPLY, controller.state.value.phase)
        controller.cancel()
    }

    @Test fun terminalOrMismatchedReplyIsNeverSavedAndDeletesAudio() = runBlocking {
        for (mismatch in listOf(false, true)) {
            val recorder = Recorder()
            var saved = false
            val controller = VoiceController(recorder, this, VoiceService { r, _ ->
                if (!mismatch) throw VoiceFailure("Service needs update", false)
                reply(r).copy(requestId = "other-request")
            }, { saved = true })
            controller.press(); controller.release(); controller.retry()
            assertEquals(VoicePhase.ERROR, controller.state.value.phase)
            assertFalse(controller.state.value.canRetry)
            assertFalse(recorder.file!!.exists())
            assertFalse(saved)
            controller.cancel()
        }
    }

    @Test fun recordingLimitReleasesRecorderWithoutSending() = runBlocking {
        val recorder = Recorder()
        var uploads = 0
        val controller = VoiceController(recorder, this, VoiceService { r, _ -> uploads++; reply(r) }, {}, recordingLimitMillis = 1)
        controller.press()
        withTimeout(2_000) { controller.state.first { it.phase == VoicePhase.ERROR } }
        controller.release()
        assertFalse(recorder.file!!.exists())
        assertEquals(0, uploads)
        controller.cancel()
    }

    @Test fun nativeRecorderFailureReleasesCaptureAndAllowsAnotherPress() = runBlocking {
        val recorder = Recorder()
        val controller = VoiceController(recorder, this, VoiceService { r, _ -> reply(r) }, {})
        controller.press()
        recorder.failure("Microphone lost")
        assertEquals(VoicePhase.ERROR, controller.state.value.phase)
        assertFalse(recorder.file!!.exists())
        controller.press(); controller.release()
        assertEquals(VoicePhase.REPLY, controller.state.value.phase)
        controller.cancel()
    }
}
