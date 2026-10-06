package com.example.itantra.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itantra.app.AppGraph

@Composable
fun SavedSpeechButton(itemKey: String, onPlay: () -> Unit, enabled: Boolean = true) {
    val coordinator = remember { AppGraph.transceiverCoordinator }
    val playback by coordinator.savedSpeechPlayback.collectAsState()
    val selected = playback.itemKey == itemKey
    val active = selected && playback.busy
    Column {
        TextButton(onClick = { if (active) coordinator.stopSavedSpeech() else onPlay() },
            enabled = enabled && (!playback.busy || active)) {
            Icon(if (active) Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text(if (active && playback.preparing) "Stop · preparing speech" else if (active) "Stop speaking" else "Play speech")
        }
        if (selected) playback.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
