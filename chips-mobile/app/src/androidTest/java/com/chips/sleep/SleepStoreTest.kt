package com.chips.sleep

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SleepStoreTest {
    @Test fun importIsIdempotentReconcilesDeletesAndRetainsOlderHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sleep-test-${UUID.randomUUID()}.db"
        try {
            SleepStore(context,name).use { store ->
                val old = SleepEntry("old","gadgetbridge",100,200,0,"[]")
                val recent = SleepEntry("recent","gadgetbridge",1000,2000,0,"[]")
                store.replaceWindow(0,3000,listOf(old,recent))
                store.replaceWindow(500,3000,listOf(recent.copy(end=2500)))
                assertEquals(2,store.entries().size)
                assertEquals(2500L,store.entries().first().end)
                store.replaceWindow(500,3000,emptyList())
                assertEquals(listOf(old),store.entries())
                try { store.replaceWindow(0,3000,listOf(old.copy(end=0))); fail("Invalid record must roll back") } catch(_: IllegalArgumentException) { }
                assertEquals(listOf(old),store.entries())
            }
            SleepStore(context,name).use { assertEquals(1,it.entries().size); it.clear(); assertTrue(it.entries().isEmpty()) }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun stagesKeepUnknownSeparateAndWakeDateUsesRecordedOffset() {
        val start = java.time.Instant.parse("2026-11-13T21:00:00Z").toEpochMilli()
        val row = SleepEntry("id","gadgetbridge",start,start+3600000,10800,
            """[{"stage":5,"start":$start,"end":${start+1800000}},{"stage":0,"start":${start+1800000},"end":${start+3600000}}]""")
        assertEquals("2026-11-14",row.wakeDate)
        assertEquals(mapOf("Deep" to 30L,"Unknown" to 30L),row.stageMinutes())
    }
}
