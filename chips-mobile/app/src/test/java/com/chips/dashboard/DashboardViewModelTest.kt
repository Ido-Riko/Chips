package com.chips.dashboard

import org.junit.Assert.*
import org.junit.Test

class DashboardViewModelTest {
    @Test
    fun normalModeCannotCreatePreviewCompletions() {
        val model = DashboardViewModel()
        model.previewActivity(ActivityType.WORKOUT)
        assertFalse(model.state.value.demoMode)
        assertTrue(model.state.value.previewCompleted.isEmpty())
    }

    @Test
    fun leavingPreviewDiscardsItsCompletions() {
        val model = DashboardViewModel()
        model.setDemoMode(true)
        model.previewActivity(ActivityType.WORKOUT)
        assertEquals(setOf(ActivityType.WORKOUT), model.state.value.previewCompleted)
        model.setDemoMode(false)
        model.setDemoMode(true)
        assertTrue(model.state.value.previewCompleted.isEmpty())
    }

    @Test
    fun previewCanUndoWithoutAffectingOtherActivities() {
        val model = DashboardViewModel()
        model.setDemoMode(true)
        model.previewActivity(ActivityType.WORKOUT)
        model.previewActivity(ActivityType.DRAWING)
        model.previewActivity(ActivityType.WORKOUT)
        assertEquals(setOf(ActivityType.DRAWING), model.state.value.previewCompleted)
    }
}
