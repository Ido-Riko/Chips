package com.chips.assistant

import android.content.Context

/** Owner-requested lockscreen access; disabling it restores the unlock requirement. */
class AssistantAccessSettings(context: Context, name: String = "assistant_access") {
    val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun allowLocked() = preferences.getBoolean(KEY, true)
    fun setAllowLocked(value: Boolean) { preferences.edit().putBoolean(KEY, value).apply() }
    fun allowed(deviceLocked: Boolean) = assistantAccessAllowed(deviceLocked, allowLocked())
    companion object { const val KEY = "allow_locked" }
}

fun assistantAccessAllowed(deviceLocked: Boolean, allowLocked: Boolean) = !deviceLocked || allowLocked
