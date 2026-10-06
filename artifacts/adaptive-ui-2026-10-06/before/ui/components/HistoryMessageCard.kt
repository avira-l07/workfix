package com.example.itantra.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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

@Composable
fun HistoryMessageCard(message: TransceiverMessage, onDelete: () -> Unit, onRetry: () -> Unit, onAcknowledge: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val critical = message.priority == MessagePriority.CRITICAL
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = if (critical) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (message.source == MessageSource.LOCAL) "You · ${if (message.isVoiceGenerated) "Voice transcript" else "Message"}" else "Peer · Message",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (critical) Text("Emergency · critical priority", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            if (message.originalText != null && message.originalText != message.text) {
                Text("Original", style = MaterialTheme.typography.labelSmall)
                Text(message.originalText, style = MaterialTheme.typography.bodyLarge)
                Text("Translated", style = MaterialTheme.typography.labelSmall)
            }
            Text(message.text, style = MaterialTheme.typography.bodyLarge)
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(message.createdAtLocal)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(messageStateLabel(message.state), style = MaterialTheme.typography.labelMedium,
                color = if (message.state == MessageState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            message.statusDetail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            message.measuredWireReductionVsPcmPercent?.let { reduction ->
                Text(String.format(Locale.getDefault(), "Measured frame: %d bytes · %.1f%% smaller than uncompressed PCM", message.finalFrameBytes.takeIf { it > 0 } ?: message.packetBytes, reduction),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (message.text.isNotBlank() && message.displayedTextLanguage != null &&
                (!message.isVoiceGenerated || message.speechDurationMillis > 0))
                SavedSpeechButton("message-${message.messageId}", { com.itantra.app.AppGraph.transceiverCoordinator.replayMessage(message.messageId) })
            Row {
                IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) { Icon(Icons.Filled.ContentCopy, "Copy transcript") }
                if (message.state == MessageState.ERROR && message.source == MessageSource.LOCAL && message.priority != MessagePriority.CRITICAL &&
                    (!message.isVoiceGenerated || (message.payloadBytes > 0 && message.rawPcmEquivalentBytes > 0)))
                    TextButton(onClick = onRetry) { Text("Retry") }
                if (message.source == MessageSource.REMOTE && message.priority == MessagePriority.CRITICAL && message.state != MessageState.ACKNOWLEDGED)
                    TextButton(onClick = onAcknowledge) { Text("Acknowledge") }
                Spacer(Modifier.weight(1f))
                if (RecycleBinPolicy.canTrash(message)) IconButton(onClick = onDelete) { Icon(Icons.Filled.DeleteOutline, "Move message to recycle bin") }
            }
        }
    }
}
