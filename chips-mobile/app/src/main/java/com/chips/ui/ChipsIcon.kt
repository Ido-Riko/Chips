package com.chips.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

enum class ChipsSymbol { TODAY, HISTORY, SETTINGS, VOICE, WORKOUT, SABER, DRAWING, JAPANESE, MOON, ARROW, CHECK, PLUS, MEAL }

/** Decorative line icons. Accessible names belong to their labeled parent controls. */
@Composable
fun ChipsIcon(symbol: ChipsSymbol, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        withTransform({ scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) }) {
            val stroke = Stroke(width = 1.7f, cap = StrokeCap.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(color, Offset(x1, y1), Offset(x2, y2), 1.7f, StrokeCap.Round)
            fun circle(x: Float, y: Float, r: Float) = drawCircle(color, r, Offset(x, y), style = stroke)
            when (symbol) {
                ChipsSymbol.TODAY -> {
                    drawRoundRect(color, Offset(4f, 4f), Size(16f, 16f), CornerRadius(4f), style = stroke)
                    line(4f, 10f, 20f, 10f); line(10f, 10f, 10f, 20f)
                }
                ChipsSymbol.HISTORY -> {
                    drawArc(color, -135f, 300f, false, Offset(4f, 4f), Size(16f, 16f), style = stroke)
                    line(3f, 4f, 3f, 9f); line(3f, 9f, 8f, 9f)
                    line(12f, 7f, 12f, 12f); line(12f, 12f, 15f, 14f)
                }
                ChipsSymbol.SETTINGS -> {
                    circle(12f, 12f, 3f)
                    val path = Path().apply {
                        moveTo(10f, 3f); lineTo(14f, 3f); lineTo(15f, 6f)
                        lineTo(18f, 7f); lineTo(21f, 10f); lineTo(19f, 13f)
                        lineTo(19f, 16f); lineTo(16f, 19f); lineTo(13f, 18f)
                        lineTo(10f, 21f); lineTo(7f, 19f); lineTo(7f, 16f)
                        lineTo(4f, 14f); lineTo(3f, 10f); lineTo(6f, 8f)
                        lineTo(7f, 5f); close()
                    }
                    drawPath(path, color, style = stroke)
                }
                ChipsSymbol.VOICE -> {
                    line(4f, 10f, 4f, 14f); line(8f, 6f, 8f, 18f)
                    line(12f, 3f, 12f, 21f); line(16f, 7f, 16f, 17f); line(20f, 10f, 20f, 14f)
                }
                ChipsSymbol.WORKOUT -> {
                    line(3f, 8f, 3f, 16f); line(7f, 6f, 7f, 18f)
                    line(7f, 12f, 17f, 12f); line(17f, 6f, 17f, 18f); line(21f, 8f, 21f, 16f)
                }
                ChipsSymbol.SABER -> {
                    line(5f, 19f, 19f, 5f); line(6f, 14f, 10f, 18f)
                    line(5f, 5f, 10f, 10f); line(14f, 14f, 19f, 19f)
                }
                ChipsSymbol.DRAWING -> {
                    val path = Path().apply {
                        moveTo(5f, 15f); lineTo(16f, 4f); lineTo(20f, 8f)
                        lineTo(9f, 19f); lineTo(4f, 20f); close()
                    }
                    drawPath(path, color, style = stroke); line(13f, 7f, 17f, 11f)
                }
                ChipsSymbol.JAPANESE -> {
                    line(4f, 6f, 17f, 6f); line(10f, 3f, 10f, 6f)
                    val path = Path().apply {
                        moveTo(6f, 9f); quadraticTo(9f, 16f, 16f, 19f)
                        moveTo(14f, 6f); quadraticTo(13f, 15f, 4f, 20f)
                    }
                    drawPath(path, color, style = stroke)
                    line(16f, 20f, 20f, 11f); line(20f, 11f, 23f, 20f); line(17.5f, 17f, 22f, 17f)
                }
                ChipsSymbol.MOON -> {
                    val path = Path().apply {
                        moveTo(18f, 16f); cubicTo(9f, 19f, 5f, 10f, 10f, 4f)
                        cubicTo(3f, 5f, 1f, 15f, 7f, 19f)
                        cubicTo(12f, 23f, 19f, 20f, 20f, 15f); close()
                    }
                    drawPath(path, color, style = stroke)
                }
                ChipsSymbol.ARROW -> {
                    line(5f, 12f, 19f, 12f); line(14f, 7f, 19f, 12f); line(19f, 12f, 14f, 17f)
                }
                ChipsSymbol.CHECK -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 19f, 7f) }
                ChipsSymbol.PLUS -> { line(6f, 12f, 18f, 12f); line(12f, 6f, 12f, 18f) }
                ChipsSymbol.MEAL -> {
                    drawArc(color, 0f, 180f, false, Offset(3f, 7f), Size(18f, 12f), style = stroke)
                    line(3f, 13f, 21f, 13f); line(8f, 21f, 16f, 21f)
                    line(9f, 4f, 9f, 8f); line(15f, 3f, 15f, 7f)
                }
            }
        }
    }
}
