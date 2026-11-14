package com.chips.heartrate

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class HrProtocolTest {
    @Test fun parsesBothWidthsWithoutInventingMeasurements() {
        assertEquals(200, HrProtocol.parse(byteArrayOf(0, 200.toByte())))
        assertEquals(300, HrProtocol.parse(byteArrayOf(1, 44, 1)))
        assertEquals(75, HrProtocol.parse(byteArrayOf(6, 75)))
        assertNull(HrProtocol.parse(byteArrayOf(4, 75))) // reported no contact
        listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(1, 44), byteArrayOf(0, 0)).forEach { assertNull(HrProtocol.parse(it)) }
        assertEquals(65535, HrProtocol.parse(byteArrayOf(1, -1, -1)))
    }
    @Test fun relayPreservesWideMeasurementsAndOscWireContract() {
        for(bpm in listOf(1, 80, 255, 256, 300, 65535)) assertEquals(bpm, HrProtocol.parse(HrProtocol.measurement(bpm)))
        val packet = HrProtocol.osc(123)
        assertEquals(20, packet.size)
        assertEquals("/heartBeat\u0000\u0000", String(packet.copyOfRange(0, 12), Charsets.US_ASCII))
        assertArrayEquals(byteArrayOf(44, 105, 0, 0), packet.copyOfRange(12, 16))
        assertEquals(123, ByteBuffer.wrap(packet, 16, 4).int)
    }
}
