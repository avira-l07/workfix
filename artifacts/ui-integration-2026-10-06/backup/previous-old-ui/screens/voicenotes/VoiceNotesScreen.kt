package com.example.itantra.ui.screens.voicenotes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.itantra.ui.theme.ITantraColors
import com.itantra.data.db.VoiceNoteEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceNotesScreen(
    notes: List<VoiceNoteEntity>,
    onBack: () -> Unit,
    onDelete: (VoiceNoteEntity) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<VoiceNoteEntity?>(null) }
    // Recompute when the screen opens or the list changes; the list need not move live at midnight.
    val openedAt = remember { System.currentTimeMillis() }
    val grouped = remember(notes, openedAt) { VoiceNoteGrouping.group(notes, { it.createdAtMillis }, openedAt) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Scaffold(
        containerColor = ITantraColors.CanvasBg,
        topBar = {
            TopAppBar(
                title = { Text("Voice notes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ITantraColors.SurfaceWhite)
            )
        }
    ) { padding ->
        if (notes.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No private voice transcripts yet.", color = ITantraColors.TextMuted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                grouped.forEach { (key, bucketNotes) ->
                    item(key = "header-${key.bucket}-${key.olderMonth}") {
                        Text(key.title().uppercase(), color = ITantraColors.Primary,
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    }
                    items(bucketNotes, key = { it.id }) { note ->
                        Row(
                            Modifier.fillMaxWidth().background(ITantraColors.SurfaceWhite, RoundedCornerShape(10.dp))
                                .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(10.dp)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(note.transcribedText, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium, color = ITantraColors.TextHeadline)
                                Spacer(Modifier.height(4.dp))
                                Text("${note.languageWireCode.uppercase()} · ${timeFormat.format(Date(note.createdAtMillis))}",
                                    style = MaterialTheme.typography.labelSmall, color = ITantraColors.TextMuted)
                            }
                            IconButton(onClick = { pendingDelete = note }) {
                                Icon(Icons.Filled.Delete, "Delete voice note", tint = ITantraColors.StatusDanger)
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete voice note?") },
            text = { Text("This removes only the local transcript. It will not remove the sent chat message.") },
            confirmButton = { TextButton(onClick = { onDelete(note); pendingDelete = null }) { Text("Delete", color = ITantraColors.StatusDanger) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}
