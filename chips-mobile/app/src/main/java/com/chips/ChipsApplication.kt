package com.chips

import android.app.Application

class ChipsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A process death cannot run controller cleanup. No capture is active at process startup.
        runCatching {
            cacheDir.listFiles()?.filter { it.name.startsWith("chips_voice_") }?.forEach { it.delete() }
        }
    }
}
