package com.chips.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chips.ui.theme.ChipsAccent
import com.chips.ui.theme.ChipsMint
import com.chips.ui.theme.ChipsWarning

internal enum class AssistantPhase { READY, LISTENING, PROCESSING, REPLY, ERROR }

/** Shared presentation for in-app voice and Android's assistant session. */
@Composable
internal fun AssistantPanel(
    phase: AssistantPhase,
    reply: String?,
    interactionSource: MutableInteractionSource,
    onDismiss: () -> Unit,
    reserveFingerprintSpace: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val accent by animateColorAsState(
        when (phase) {
            AssistantPhase.LISTENING -> ChipsMint
            AssistantPhase.ERROR -> colors.error
            else -> ChipsAccent
        }, label = "assistant-accent"
    )
    val status = when (phase) {
        AssistantPhase.READY -> "Ready when you are"
        AssistantPhase.LISTENING -> "Listening"
        AssistantPhase.PROCESSING -> "Working on your reply"
        AssistantPhase.REPLY -> "CHIPS replied"
        AssistantPhase.ERROR -> "Let’s try that again"
    }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.background.copy(alpha = 0.55f), colors.background)))
            .systemBarsPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Reserve the sensor area for system invocation; compact windows keep controls reachable.
        val bottomSpace = if (reserveFingerprintSpace && maxHeight >= 640.dp) 120.dp else 24.dp
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp, bottom = bottomSpace)
                .widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight - bottomSpace - 16.dp),
            shape = RoundedCornerShape(32.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("CHIPS", style = MaterialTheme.typography.titleMedium, letterSpacing = 2.sp)
                        Text("YOUR ASSISTANT", style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant, letterSpacing = 1.5.sp)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Close assistant" }) {
                        ChipsIcon(ChipsSymbol.PLUS, colors.onSurfaceVariant, Modifier.rotate(45f))
                    }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(shape = CircleShape, color = ChipsWarning.copy(alpha = 0.10f)) {
                        Text("Voice preview", Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            color = ChipsWarning, style = MaterialTheme.typography.labelMedium)
                    }
                    VoiceEmblem(accent, phase == AssistantPhase.LISTENING)
                    Text(status, color = accent, style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (phase == AssistantPhase.READY) {
                        Text("What’s on your mind?", style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center)
                        Text("Hold the button, speak, then release to send.", style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    } else if (phase == AssistantPhase.LISTENING) {
                        Text("Take your time.", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Text("Release the button when you’re finished.", style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    } else if (phase == AssistantPhase.PROCESSING) {
                        Text("One moment…", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Text("You can close this window at any time.", style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    } else {
                        Surface(
                            Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                            color = if (phase == AssistantPhase.ERROR) colors.error.copy(alpha = 0.07f) else colors.surfaceVariant
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (phase == AssistantPhase.ERROR) "COULDN’T COMPLETE REQUEST" else "REPLY",
                                    style = MaterialTheme.typography.labelMedium, color = accent, letterSpacing = 1.sp)
                                Text(reply.orEmpty().removePrefix("Error: "), style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = Color.White.copy(alpha = 0.06f))
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {}, enabled = phase != AssistantPhase.PROCESSING,
                        interactionSource = interactionSource,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = colors.onPrimary),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    ) {
                        if (phase == AssistantPhase.PROCESSING) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            ChipsIcon(ChipsSymbol.VOICE, colors.onPrimary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(when (phase) {
                            AssistantPhase.LISTENING -> "Release to send"
                            AssistantPhase.PROCESSING -> "Getting your reply…"
                            else -> "Hold to speak"
                        }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Transcription uses demo text. Replies aren’t saved.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun VoiceEmblem(color: Color, listening: Boolean) {
    val emphasis by animateFloatAsState(if (listening) 1f else 0f, label = "voice-emphasis")
    Canvas(Modifier.size(128.dp)) {
        val radius = size.minDimension / 2
        drawCircle(color.copy(alpha = 0.05f + emphasis * 0.03f))
        drawCircle(color.copy(alpha = 0.12f), radius * 0.82f, style = Stroke(1.dp.toPx()))
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.06f))), radius * 0.68f)
        // Decorative waveform; heights do not represent microphone amplitude.
        listOf(0.16f, 0.32f, 0.49f, 0.65f, 0.39f, 0.27f, 0.13f).forEachIndexed { index, height ->
            val x = center.x + (index - 3) * radius * 0.17f
            val halfHeight = radius * height * (0.65f + emphasis * 0.15f)
            drawLine(color, Offset(x, center.y - halfHeight), Offset(x, center.y + halfHeight),
                strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
