package com.chips

import android.app.Application

class ChipsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { com.chips.heartrate.HrStore(this).use { it.recoverInterrupted() } }
        // Voice recording cannot survive process death; discard its unfinished temporary files.
        runCatching {
            cacheDir.listFiles()?.filter { it.name.startsWith("chips_voice_") }?.forEach { it.delete() }
        }
    }
}
