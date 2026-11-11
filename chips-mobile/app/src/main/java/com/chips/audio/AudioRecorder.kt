package com.chips.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.chips.voice.VoiceRecorder
import java.io.File
import java.io.IOException

class AudioRecorder(private val context: Context) : VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startedAt = 0L

    override fun start(): Result<Unit> {
        cancel()
        return try {
            // MPEG-4 container with AAC audio: extension and upload type match the container.
            val file = File.createTempFile("chips_voice_", ".m4a", context.cacheDir)
            currentFile = file
            val recording = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = recording
            recording.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            recording.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recording.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recording.setOutputFile(file.absolutePath)
            recording.prepare()
            recording.start()
            startedAt = SystemClock.elapsedRealtime()
            Result.success(Unit)
        } catch (_: Exception) {
            cancel()
            Result.failure(IOException("Could not start the microphone. Check microphone permission and whether another app is using it."))
        }
    }

    override fun finish(): Result<File> {
        val recording = recorder ?: return Result.failure(IOException("No recording is available. Hold to speak again."))
        val file = currentFile
        val duration = SystemClock.elapsedRealtime() - startedAt
        return try {
            recording.stop()
            if (duration < 400 || file == null || file.length() == 0L) {
                file?.delete()
                Result.failure(IOException("Recording was too short. Hold the button a little longer and try again."))
            } else Result.success(file)
        } catch (_: Exception) {
            file?.delete()
            Result.failure(IOException("Could not finish the recording. Hold to speak again."))
        } finally {
            releaseRecorder()
            currentFile = null
        }
    }

    override fun cancel() {
        releaseRecorder()
        currentFile?.delete()
        currentFile = null
    }

    private fun releaseRecorder() {
        try { recorder?.release() } catch (_: Exception) { /* Cleanup remains best effort. */ }
        recorder = null
    }
}
