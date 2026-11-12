package com.chips.beatsaber

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Actionable diagnostics without reflecting URLs, credentials or low-level exception text. */
internal fun beatConnectionMessage(error: IOException, source: String = "the score source"): String {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
    return when {
        causes.any { it is SSLException } -> "The secure connection to $source failed. Check the phone's date and time and retry."
        causes.any { it is UnknownHostException } -> "Could not resolve $source. Check your internet connection."
        causes.any { it is ConnectException } -> "Could not connect to $source. Check your internet connection or retry later."
        causes.any { it is InterruptedIOException } -> "$source timed out. Check your internet connection and retry."
        else -> "Could not reach $source. Check your internet connection and retry."
    }
}
