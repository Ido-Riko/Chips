package com.chips.heartrate

import org.junit.Assert.*
import org.junit.Test

class HrRunCodecTest {
    @Test fun retainsIrregularReceptionTimesAndClockCorrections() {
        val expected = listOf(HrReading(70, 10000, 100), HrReading(70, 11007, 1107),
            HrReading(70, 9500, 2240), HrReading(70, 12000, 2500))
        val timing = expected.zipWithNext().fold(byteArrayOf()) { bytes, (a, b) ->
            bytes + HrRunCodec.delta(b.received - a.received, b.elapsed - a.elapsed)
        }
        assertEquals(expected, HrRunCodec.readings(70, 10000, 100, expected.size, timing))
        assertTrue(timing.size < (expected.size - 1) * 16)
    }

    @Test fun signedWallDeltasRoundTripIncludingLongBoundaries() {
        for(delta in listOf(Long.MIN_VALUE, -1000000000000L, -1L, 0L, 1L, Long.MAX_VALUE)) {
            assertEquals(delta, HrRunCodec.readings(70, 0, 0, 2, HrRunCodec.delta(delta, 15000)).last().received)
        }
    }

    @Test fun rejectsCorruptOrTruncatedTiming() {
        for(bytes in listOf(byteArrayOf(), byteArrayOf(1), ByteArray(11) { -1 }, byteArrayOf(0, 0, 1))) {
            assertThrows(IllegalArgumentException::class.java) { HrRunCodec.readings(70, 0, 0, 2, bytes) }
        }
        assertThrows(IllegalArgumentException::class.java) { HrRunCodec.delta(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { HrRunCodec.readings(70, 0, 0, 257, byteArrayOf()) }
    }
}
