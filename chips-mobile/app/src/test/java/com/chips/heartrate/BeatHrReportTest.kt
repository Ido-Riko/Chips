package com.chips.heartrate

import org.junit.Assert.*
import org.junit.Test

class BeatHrReportTest {
    private fun play(id: Long = 1, end: Long = 120000, duration: Double? = 60.0, modifiers: String = "", pauses: Int? = 0) =
        BeatPlay(id,"Song","Expert",end,duration,modifiers,pauses,95.0,8.0)
    @Test fun halfOpenWindowsDoNotShareBoundarySamplesAndIgnoreDuplicateScores() {
        val plays = listOf(play(),play(),play(2,180000))
        val result = correlateBeatHr(plays,listOf(HrReading(100,119000,119000),HrReading(150,120000,120000)),0,200000)
        assertEquals(2,result.size)
        assertEquals(100.0,result[0].average!!,0.01)
        assertEquals(150.0,result[1].average!!,0.01)
    }
    @Test fun recordedStartWinsOverDelayedUpload() {
        val result = correlateBeatHr(listOf(play(end=900000).copy(started=60000)),
            listOf(HrReading(110,70000,70000)),0,200000).single()
        assertEquals(60000L,result.start); assertEquals(120000L,result.end)
        assertEquals(110.0,result.average!!,0.01)
        assertNull(result.warning)
    }
    @Test fun gapsAndClockJumpsDoNotBecomeCoverage() {
        val result = correlateBeatHr(listOf(play()),listOf(HrReading(100,60000,60000),HrReading(120,70000,70000),
            HrReading(140,100000,100000),HrReading(160,110000,500)),0,200000).single()
        assertEquals(1.0/6,result.coverage,0.001)
        assertEquals(130.0,result.average!!,0.001)
        assertEquals(160,result.peak)
    }
    @Test fun missingDurationAndNoSamplesRemainUnknown() {
        val result = correlateBeatHr(listOf(play(duration=null)),listOf(HrReading(100,119000,119000)),0,200000).single()
        assertNull(result.start); assertNull(result.average); assertNotNull(result.warning)
        assertNull(correlateBeatHr(listOf(play()),emptyList(),0,200000).single().peak)
    }
    @Test fun speedOffsetAndPartialCaptureAreApplied() {
        val result = correlateBeatHr(listOf(play(duration=120.0,modifiers="FS",pauses=1)),
            listOf(HrReading(100,60000,60000)),50000,90000,-10).single()
        assertEquals(10000L,result.start); assertEquals(110000L,result.end)
        assertNotNull(result.warning); assertEquals(1,result.samples.size)
        assertTrue(correlateBeatHr(listOf(play(end=300000)),emptyList(),0,90000).isEmpty())
    }
}
