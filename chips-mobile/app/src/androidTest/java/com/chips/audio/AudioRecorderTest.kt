package com.chips.audio

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AudioRecorderTest {
    private lateinit var directory: File
    private lateinit var recorder: AudioRecorder

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        directory = File(app.cacheDir, "recorder-test-${UUID.randomUUID()}").apply { mkdirs() }
        recorder = AudioRecorder(object : ContextWrapper(app) {
            override fun getCacheDir() = directory
        })
    }

    @After fun tearDown() {
        recorder.cancel()
        directory.deleteRecursively()
    }

    @Test fun validCaptureUsesMpeg4ContainerAndCanRecordAgain() {
        assertTrue(recorder.start().isSuccess)
        Thread.sleep(600)
        val result = recorder.finish()
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        val file = result.getOrThrow()
        try {
            assertEquals("m4a", file.extension)
            assertTrue(file.length() > 0)
            val header = file.inputStream().use { stream -> ByteArray(12).also { stream.read(it) } }
            assertEquals("ftyp", String(header, 4, 4, Charsets.US_ASCII))
            assertTrue(recorder.start().isSuccess)
            recorder.cancel()
            assertEquals(listOf(file.name), directory.listFiles()!!.map { it.name })
        } finally { file.delete() }
    }

    @Test fun cancelledAndShortCapturesLeaveNoUploadableFile() {
        assertTrue(recorder.start().isSuccess)
        recorder.cancel()
        recorder.cancel()
        assertTrue(recorder.finish().isFailure)
        assertTrue(directory.listFiles()!!.isEmpty())
        assertTrue(recorder.start().isSuccess)
        assertTrue(recorder.finish().isFailure)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun restartingCaptureDiscardsPreviousTemporaryAudio() {
        assertTrue(recorder.start().isSuccess)
        val firstFile = directory.listFiles()!!.single()
        assertTrue(recorder.start().isSuccess)
        assertFalse(firstFile.exists())
        assertEquals(1, directory.listFiles()!!.size)
        recorder.cancel()
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
