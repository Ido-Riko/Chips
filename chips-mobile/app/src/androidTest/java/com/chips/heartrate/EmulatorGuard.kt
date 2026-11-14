package com.chips.heartrate

import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.Assume.assumeTrue

/** Keep service tests off physical devices without depending on image branding. */
internal fun assumeEmulator() {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val qemu = automation.executeShellCommand("getprop ro.kernel.qemu").use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    }
    assumeTrue("Requires an Android emulator (ro.kernel.qemu=1)", qemu == "1")
}
