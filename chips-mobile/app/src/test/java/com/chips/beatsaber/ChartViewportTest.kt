package com.chips.beatsaber

import org.junit.Assert.*
import org.junit.Test

class ChartViewportTest {
    @Test fun zoomKeepsFocusUnderTheFingersAndClampsToData() {
        val initial = ChartViewport()
        val zoomed = initial.transform(2f, focusX = 0.75f, focusY = 0.25f)
        assertEquals(0.75f, zoomed.left + 0.75f / zoomed.zoom, 0.00001f)
        assertEquals(0.25f, zoomed.bottom + 0.25f / zoomed.zoom, 0.00001f)
        val edge = zoomed.transform(100f, panX = -100f, panY = 100f)
        assertEquals(16f, edge.zoom, 0f)
        assertEquals(1f, edge.left + 1f / edge.zoom, 0.00001f)
        assertEquals(0f, edge.bottom, 0.00001f)
        assertEquals(initial, edge.transform(0.001f))
    }

    @Test fun panUsesVisibleRangeAndInvalidGesturesDoNotCorruptView() {
        val zoomed = ChartViewport().transform(4f)
        val moved = zoomed.transform(1f, panX = 0.25f, panY = -0.25f)
        assertEquals(0.4375f, moved.centerX, 0.00001f)
        assertEquals(0.5625f, moved.centerY, 0.00001f)
        assertEquals(moved, moved.transform(Float.NaN))
        assertEquals(moved, moved.transform(-1f))
    }
}
