package com.example.itantra.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itantra.domain.model.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun messageStateLabel(state: MessageState): String = when (state) {
    MessageState.IDLE -> "Saved locally"
    MessageState.RECORDING -> "Recording"
    MessageState.STT_PROCESSING -> "Recognizing speech"
    MessageState.STT_COMPLETE -> "Transcript ready"
    MessageState.PACKET_ENCODING -> "Preparing message"
    MessageState.TRANSMITTING -> "Sending"
    MessageState.SENT -> "Sent · awaiting delivery"
    MessageState.DELIVERED -> "Delivered to device"
    MessageState.REMOTE_TTS_READY -> "Preparing speech"
    MessageState.REMOTE_PLAYING -> "Playing on peer"
    MessageState.REMOTE_PLAYBACK_CONFIRMED -> "Playback completed"
    MessageState.WAITING_ACK -> "Awaiting device acknowledgment"
    MessageState.ACKNOWLEDGED -> "Acknowledged by person"
    MessageState.WAITING_USER_CONFIRMATION -> "Awaiting your confirmation"
    MessageState.ERROR -> "Not delivered"
}

fun messageLanguageLabel(message: TransceiverMessage): String {
    val output = message.displayedTextLanguage ?: return "Message"
    val source = message.language
    return if (message.source == MessageSource.LOCAL && message.translationStatus == TranslationStatus.SUCCESS &&
        source != null && source != output) {
        "${LanguageCatalog.byCode(source).displayName} → ${LanguageCatalog.byCode(output).displayName}"
    } else LanguageCatalog.byCode(output).displayName
}

@Composable
fun HistoryMessageCard(message: TransceiverMessage, onDelete: () -> Unit, onRetry: () -> Unit, onAcknowledge: () -> Unit, compact: Boolean = false) {
    val critical = message.priority == MessagePriority.CRITICAL
    Card(shape = RoundedCornerShape(if (compact) 12.dp else 20.dp), colors = CardDefaults.cardColors(containerColor = if (critical) MaterialTheme.colorScheme.errorContainer else if (compact) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 8.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${if (message.source == MessageSource.LOCAL) "You" else "Peer"} · ${messageLanguageLabel(message)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (compact) Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.createdAtLocal)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (critical) Text("Emergency · critical priority", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            if (!compact && message.originalText != null && message.originalText != message.text) {
                Text(if (message.source == MessageSource.LOCAL && message.language != null)
                    "Original · ${LanguageCatalog.byCode(message.language).displayName}" else "Original",
                    style = MaterialTheme.typography.labelSmall)
                Text(message.originalText, style = MaterialTheme.typography.bodyLarge)
                Text("Translated · ${message.displayedTextLanguage?.let { LanguageCatalog.byCode(it).displayName } ?: "Message"}",
                    style = MaterialTheme.typography.labelSmall)
            }
            Text(message.text, style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                maxLines = if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            if (!compact) Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(message.createdAtLocal)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!compact) Text(messageStateLabel(message.state), style = MaterialTheme.typography.labelMedium,
                color = if (message.state == MessageState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            message.statusDetail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            message.measuredWireReductionVsPcmPercent?.let { reduction ->
                Text(String.format(Locale.getDefault(), "Measured frame: %d bytes · %.1f%% smaller than uncompressed PCM", message.finalFrameBytes.takeIf { it > 0 } ?: message.packetBytes, reduction),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (!compact && message.text.isNotBlank() && message.displayedTextLanguage != null &&
                (!message.isVoiceGenerated || message.speechDurationMillis > 0))
                SavedSpeechButton("message-${message.messageId}", { com.itantra.app.AppGraph.transceiverCoordinator.replayMessage(message.messageId) })
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (compact) {
                    Text(messageStateLabel(message.state), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = if (message.state == MessageState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (message.text.isNotBlank() && message.displayedTextLanguage != null && (!message.isVoiceGenerated || message.speechDurationMillis > 0))
                        SavedSpeechButton("message-${message.messageId}", { com.itantra.app.AppGraph.transceiverCoordinator.replayMessage(message.messageId) }, compact = true)
                }
                if (message.source == MessageSource.REMOTE && message.priority == MessagePriority.CRITICAL && message.state != MessageState.ACKNOWLEDGED)
                    TextButton(onClick = onAcknowledge) { Text("Acknowledge") }
                if (!compact) Spacer(Modifier.weight(1f))
                MessageActionsButton(message, onDelete = onDelete, onRetry = onRetry)
            }
        }
    }
}
