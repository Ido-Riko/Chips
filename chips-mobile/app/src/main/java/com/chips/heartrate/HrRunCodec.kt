package com.chips.heartrate

import java.io.ByteArrayOutputStream

data class HrReading(val bpm: Int, val received: Long, val elapsed: Long)

/** Run-length values, with lossless reception timing (not an assumed regular cadence). */
object HrRunCodec {
    fun delta(wall: Long, elapsed: Long): ByteArray = ByteArrayOutputStream().apply {
        require(elapsed >= 0)
        fun put(input: Long) {
            var value = input
            while(value and -128L != 0L) { write((value.toInt() and 127) or 128); value = value ushr 7 }
            write(value.toInt())
        }
        put(elapsed)
        put((wall shl 1) xor (wall shr 63)) // signed wall delta also preserves clock corrections
    }.toByteArray()

    fun readings(bpm: Int, wall: Long, elapsed: Long, count: Int, deltas: ByteArray): List<HrReading> {
        require(count in 1..256)
        var offset = 0
        fun next(): Long {
            var result = 0L
            for(index in 0..9) {
                require(offset < deltas.size) { "Truncated HR timing" }
                val byte = deltas[offset++].toInt() and 255
                require(index < 9 || byte <= 1) { "Invalid HR timing" }
                result = result or ((byte and 127).toLong() shl (index * 7))
                if(byte and 128 == 0) return result
            }
            error("Invalid HR timing")
        }
        var time = wall
        var monotonic = elapsed
        return buildList {
            add(HrReading(bpm, time, monotonic))
            repeat(count - 1) {
                val distance = next()
                require(distance >= 0) { "Invalid HR elapsed delta" }
                monotonic += distance
                val signed = next()
                time += (signed ushr 1) xor -(signed and 1)
                add(HrReading(bpm, time, monotonic))
            }
            require(offset == deltas.size) { "Extra HR timing" }
        }
    }
}
