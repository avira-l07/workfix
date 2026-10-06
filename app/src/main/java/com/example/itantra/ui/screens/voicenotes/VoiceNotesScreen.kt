package com.example.itantra.ui.screens.voicenotes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.itantra.ui.theme.ITantraColors
import com.example.itantra.ui.components.SavedSpeechButton
import com.itantra.app.AppGraph
import com.itantra.data.db.VoiceNoteEntity
import com.itantra.domain.model.LanguageCatalog
import com.itantra.domain.model.LanguageCode
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun VoiceNotesScreen(
    notes: List<VoiceNoteEntity>,
    onBack: () -> Unit,
    onDelete: (VoiceNoteEntity) -> Unit,
    onOpenRecycleBin: () -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<VoiceNoteEntity?>(null) }
    var openedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { openedAt = System.currentTimeMillis(); delay(60_000L) } }
    val grouped = remember(notes, openedAt) { VoiceNoteGrouping.group(notes, { it.createdAtMillis }, openedAt) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(ITantraColors.CanvasBg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Keep the important words.", style = MaterialTheme.typography.headlineLarge, color = ITantraColors.TextHeadline)
            Text(
                "Your voice, saved as words. Right here on your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = ITantraColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            Surface(color = ITantraColors.AccentSubtle, shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Notes, contentDescription = null, tint = ITantraColors.Primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${notes.size} saved ${if (notes.size == 1) "transcript" else "transcripts"}", style = MaterialTheme.typography.labelMedium, color = ITantraColors.Primary)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Personal transcripts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenRecycleBin) {
                    Icon(Icons.Filled.RestoreFromTrash, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Recycle bin")
                }
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = ITantraColors.Primary, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                Text(
                    "Deleting a note leaves sent messages intact. Restore deleted notes from the bin within 7 days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ITantraColors.TextMuted,
                )
            }
        }
        if (notes.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, ITantraColors.BorderSubtle),
                    colors = CardDefaults.cardColors(containerColor = ITantraColors.SurfaceWhite),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(72.dp).background(ITantraColors.AccentSubtle, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Notes, contentDescription = null, tint = ITantraColors.Primary, modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("Make room for your thoughts", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Text(
                            "Use the mic on Talk to save your first private transcript.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ITantraColors.TextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                        )
                        Button(onClick = onBack, shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Open Talk")
                        }
                    }
                }
            }
        }
        grouped.forEach { (key, bucketNotes) ->
            item(key = "header-${key.bucket}-${key.olderMonth}") {
                Text(key.title(), color = ITantraColors.TextHeadline, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            }
            items(bucketNotes, key = { it.id }) { note ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, ITantraColors.BorderSubtle),
                    colors = CardDefaults.cardColors(containerColor = ITantraColors.SurfaceWhite),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = ITantraColors.AccentSubtle, shape = RoundedCornerShape(8.dp)) {
                                Text(
                                    LanguageCode.fromWireCode(note.languageWireCode)?.let { LanguageCatalog.byCode(it).displayName }
                                        ?: note.languageWireCode.uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ITantraColors.Primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { pendingDelete = note }) {
                                Icon(Icons.Filled.Delete, "Delete voice note: ${note.transcribedText.take(40)}", tint = ITantraColors.StatusDanger)
                            }
                        }
                        Text(note.transcribedText, style = MaterialTheme.typography.bodyLarge, color = ITantraColors.TextHeadline,
                            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.createdAtMillis)),
                            style = MaterialTheme.typography.labelSmall, color = ITantraColors.TextMuted)
                        SavedSpeechButton("note-${note.id}", { AppGraph.transceiverCoordinator.replayVoiceNote(note) })
                    }
                }
            }
        }
    }

    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Move voice note to recycle bin?") },
            text = { Text("Restore this local transcript within 7 days. The separate chat message and the peer's copy are kept.") },
            confirmButton = { TextButton(onClick = { onDelete(note); pendingDelete = null }) { Text("Move to bin", color = ITantraColors.StatusDanger) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}
