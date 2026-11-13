package com.chips.pantry

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID

class PantryStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun purchasesRetriesConsumptionAndReopenPreserveExactStock() {
        val name = "pantry-${UUID.randomUUID()}.db"
        try {
            val item = pantryItem("Milk", "7290000000000", "item", "1 L", "", "2", "1")
            PantryStore(context,name).use { store ->
                val tx = store.add(listOf(item), "barcode")
                store.merge(tx, true); store.merge(JSONObject(tx.toString()), true)
                assertEquals(BigDecimal("2"), store.items().single().quantity)
                assertTrue(store.transactions(true).isEmpty())
                store.adjust(item, "1", true)
                assertEquals(BigDecimal.ONE, store.items().single().quantity)
            }
            PantryStore(context,name).use { store ->
                val current = store.items().single()
                assertEquals("1 L", current.pack)
                assertEquals(BigDecimal.ONE, current.quantity)
                store.adjust(current, "0", false)
                assertTrue(store.items().isEmpty())
                assertEquals(3, store.transactions().size)
                assertEquals(2, store.transactions(true).size)
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun receiptValidationDuplicatesAndDraftsSurviveRestart() {
        val name = "receipt-${UUID.randomUUID()}.db"
        try {
            PantryStore(context,name).use { store ->
                store.state("receipt", "אורז | 1.25 | kg")
                val items = receiptLines(store.state("receipt"))
                store.add(items, "receipt", "a".repeat(64))
                assertTrue(store.duplicate("a".repeat(64)))
                assertThrows(IllegalStateException::class.java) { store.add(items,"receipt","a".repeat(64)) }
                store.add(items,"receipt","a".repeat(64),true)
                assertEquals(BigDecimal("2.50"), store.items().single().quantity)
                assertThrows(IllegalArgumentException::class.java) { receiptLines("Milk | 4.99") }
                assertThrows(IllegalArgumentException::class.java) { receiptLines("Milk | -1 | item") }
                assertThrows(IllegalArgumentException::class.java) { receiptLines("Milk | 1 | ILS") }
            }
            PantryStore(context,name).use { assertEquals("אורז | 1.25 | kg",it.state("receipt")) }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun conflictsAndEnrollmentSwitchNeverRewriteInventory() {
        val name = "conflict-${UUID.randomUUID()}.db"
        try {
            PantryStore(context,name).use { store ->
                store.bind("server|owner|device")
                assertThrows(IllegalStateException::class.java) { store.bind("server|other|device") }
                val item = pantryItem("Rice","","kg","","","1.5","0.5")
                val tx = store.add(listOf(item),"manual")
                val other = JSONObject(tx.toString());other.getJSONArray("lines").getJSONObject(0).put("delta","100")
                assertThrows(IllegalStateException::class.java) { store.merge(other,true) }
                assertEquals(BigDecimal("1.5"),store.items().single().quantity)
                assertThrows(IllegalArgumentException::class.java) { store.adjust(item,"2",true) }
                assertEquals(1,store.transactions().size)
            }
        } finally { context.deleteDatabase(name) }
    }
}
