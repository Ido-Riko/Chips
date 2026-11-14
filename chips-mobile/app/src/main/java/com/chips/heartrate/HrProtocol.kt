package com.chips.heartrate

import java.nio.ByteBuffer

/** Standard 0x2A37 measurement; transport reception time is recorded separately. */
object HrProtocol {
    fun parse(value: ByteArray): Int? {
        if (value.size < 2) return null
        val flags = value[0].toInt() and 255
        if (flags and 4 != 0 && flags and 2 == 0) return null // supported contact sensor reports no contact
        val wide = flags and 1 != 0
        if (wide && value.size < 3) return null
        val bpm = (value[1].toInt() and 255) or
            (if (wide) (value[2].toInt() and 255) shl 8 else 0)
        return bpm.takeIf { it in 1..65535 }
    }

    fun measurement(bpm: Int): ByteArray {
        require(bpm in 1..65535)
        return if (bpm <= 255) byteArrayOf(0, bpm.toByte())
        else byteArrayOf(1, bpm.toByte(), (bpm shr 8).toByte())
    }

    /** MiBand-Converter compatible OSC address and integer type, including NUL padding. */
    fun osc(bpm: Int): ByteArray {
        require(bpm in 1..65535)
        fun string(value: String): ByteArray {
            val data = value.toByteArray(Charsets.US_ASCII)
            return data + ByteArray(4 - data.size % 4)
        }
        return string("/heartBeat") + string(",i") + ByteBuffer.allocate(4).putInt(bpm).array()
    }
}
