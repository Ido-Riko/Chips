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
import androidx.compose.ui.platform.testTag
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

internal enum class AssistantPhase { READY, LISTENING, UPLOADING, PROCESSING, SAVING, REPLY, ERROR }

/** Shared presentation for in-app voice and Android's assistant session. */
@Composable
internal fun AssistantPanel(
    phase: AssistantPhase,
    reply: String?,
    interactionSource: MutableInteractionSource,
    onDismiss: () -> Unit,
    reserveFingerprintSpace: Boolean = false,
    transcript: String? = null,
    canRetry: Boolean = false,
    retrySaving: Boolean = false,
    onRetry: () -> Unit = {},
    onDiscard: () -> Unit = {},
    onFeature: ((FeatureRoute) -> Unit)? = null,
    messages: List<com.chips.voice.ChatMessage> = emptyList(),
    onSendText: (String) -> Unit = {},
    microphoneAvailable: Boolean = true
) {
    var draft by remember { mutableStateOf("") }
    val busy = phase in setOf(AssistantPhase.UPLOADING, AssistantPhase.PROCESSING, AssistantPhase.SAVING)
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
        AssistantPhase.UPLOADING -> "Sending your message"
        AssistantPhase.PROCESSING -> "Working on your reply"
        AssistantPhase.SAVING -> "Saving your conversation"
        AssistantPhase.REPLY -> "CHIPS replied"
        AssistantPhase.ERROR -> "Let’s try that again"
    }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.background.copy(alpha = 0.55f), colors.background)))
            .systemBarsPadding().imePadding(),
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
                        Text("Chat & voice", Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            color = ChipsWarning, style = MaterialTheme.typography.labelMedium)
                    }
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(com.chips.R.drawable.chips_avatar),
                        contentDescription = "CHIPS", modifier = Modifier.size(112.dp))
                    messages.forEach { message ->
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = colors.surfaceVariant) {
                            Column(Modifier.padding(16.dp)) {
                                Text(if (message.role == "user") "YOU" else "CHIPS", style = MaterialTheme.typography.labelMedium, color = accent)
                                Text(message.content, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    Text(status, color = accent, style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (phase == AssistantPhase.READY) {
                        Text("What’s on your mind?", style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center)
                        Text(if(microphoneAvailable) "Type a message, or hold the button to speak." else "Type a message to CHIPS or choose an activity below.", style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    } else if (phase == AssistantPhase.LISTENING) {
                        Text("I’m listening.", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Text("Release the button when you’re finished.", style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    } else if (busy) {
                        Text(when (phase) {
                            AssistantPhase.UPLOADING -> "Sending…"
                            AssistantPhase.SAVING -> "Saving on this phone…"
                            else -> "One moment…"
                        }, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Text(if (phase == AssistantPhase.SAVING) "You can close this window. The completed reply will stay in History."
                            else "You can cancel or close this window at any time.", style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    } else {
                        if (messages.isEmpty() && phase == AssistantPhase.REPLY && transcript != null) {
                            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = colors.surfaceVariant) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("YOU SAID", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                    Text(transcript, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                        if (phase == AssistantPhase.ERROR || messages.isEmpty()) Surface(
                            Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                            color = if (phase == AssistantPhase.ERROR) colors.error.copy(alpha = 0.07f) else colors.surfaceVariant
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (phase == AssistantPhase.ERROR) "COULDN’T COMPLETE REQUEST" else "REPLY",
                                    style = MaterialTheme.typography.labelMedium, color = accent, letterSpacing = 1.sp)
                                Text(reply.orEmpty(), style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            }
                        }
                        if (phase == AssistantPhase.ERROR && canRetry && !retrySaving) {
                            Text("Retry resends the same message.",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = draft, onValueChange = { draft = it.take(10_000) },
                            label = { Text(if (messages.isEmpty()) "Message CHIPS" else "Ask a follow-up") },
                            enabled = !busy && phase != AssistantPhase.LISTENING,
                            modifier = Modifier.weight(1f).testTag("assistant-chat-input"), maxLines = 4)
                        Button(onClick = { onSendText(draft); draft = "" },
                            enabled = draft.isNotBlank() && !busy && phase != AssistantPhase.LISTENING,
                            modifier = Modifier.testTag("assistant-chat-send")) { Text("Send") }
                    }
                    if(onFeature != null && !busy && phase != AssistantPhase.LISTENING) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FeatureRoute.entries.chunked(2).forEach { pair ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { route ->
                                        AssistChip(onClick = { onFeature(route) }, label = { Text(route.label) }, modifier = Modifier.weight(1f).testTag("assistant-feature-${route.name}"))
                                    }
                                }
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
                        onClick = {}, enabled = !busy && microphoneAvailable,
                        interactionSource = interactionSource,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = colors.onPrimary),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            ChipsIcon(ChipsSymbol.VOICE, colors.onPrimary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(when (phase) {
                            AssistantPhase.LISTENING -> "Release to send"
                            AssistantPhase.UPLOADING -> "Sending message…"
                            AssistantPhase.PROCESSING -> "Getting your reply…"
                            AssistantPhase.SAVING -> "Saving conversation…"
                            else -> "Hold to speak"
                        }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (phase == AssistantPhase.ERROR) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (canRetry) OutlinedButton(onClick = onRetry) {
                                Text(if (retrySaving) "Retry saving" else "Retry request")
                            }
                            TextButton(onClick = onDiscard) { Text("Discard") }
                        }
                    }
                    if (busy) TextButton(onClick = onDiscard) {
                        Text(if (phase == AssistantPhase.SAVING) "Dismiss" else "Cancel request")
                    }
                    if (!busy && phase != AssistantPhase.ERROR) Text(if(microphoneAvailable) "Conversations are saved on this phone." else "Chat works without microphone access. Enable it in Settings to speak.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                }
            }
        }
    }
}
