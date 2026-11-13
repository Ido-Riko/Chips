package com.chips.beatsaber

/** Fractions of the full data range; independent of screen size and axis units. */
internal data class ChartViewport(val zoom: Float = 1f, val centerX: Float = 0.5f, val centerY: Float = 0.5f) {
    val left get() = centerX - 0.5f / zoom
    val bottom get() = centerY - 0.5f / zoom

    fun transform(factor: Float, focusX: Float = 0.5f, focusY: Float = 0.5f,
                  panX: Float = 0f, panY: Float = 0f): ChartViewport {
        if (!factor.isFinite() || factor <= 0 || !panX.isFinite() || !panY.isFinite()) return this
        val next = (zoom * factor).coerceIn(1f, 16f)
        val half = 0.5f / next
        return ChartViewport(next,
            (centerX + (focusX.coerceIn(0f, 1f) - 0.5f) * (1f / zoom - 1f / next) - panX / zoom).coerceIn(half, 1f - half),
            (centerY + (focusY.coerceIn(0f, 1f) - 0.5f) * (1f / zoom - 1f / next) - panY / zoom).coerceIn(half, 1f - half))
    }
}
