package com.example.itantra.ui.screens.voicenotes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itantra.data.db.VoiceNoteEntity
import com.itantra.domain.model.RecycleBinPolicy
import com.itantra.domain.model.TransceiverMessage
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(messages: List<TransceiverMessage>, notes: List<VoiceNoteEntity>, onBack: () -> Unit,
    onRestoreMessage: (Long) -> Unit, onRestoreNote: (Long) -> Unit,
    onDeleteMessage: (Long) -> Unit, onDeleteNote: (Long) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(60_000L) } }
    var permanentDelete by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val availableMessages = messages.filter { it.deletedAtMillis?.let { deleted -> RecycleBinPolicy.canRestore(deleted, now) } == true }
    val availableNotes = notes.filter { it.deletedAtMillis?.let { deleted -> RecycleBinPolicy.canRestore(deleted, now) } == true }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text("Recycle bin", style = MaterialTheme.typography.titleLarge) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("A second chance.", style = MaterialTheme.typography.headlineLarge) }
            item { Text("Deleted items are kept for 7 days from deletion. Restore returns them to their original date and does not resend a message.", style = MaterialTheme.typography.bodyMedium) }
            if (availableMessages.isEmpty() && availableNotes.isEmpty()) item { Text("Your recycle bin is empty.", style = MaterialTheme.typography.titleMedium) }
            if (availableMessages.isNotEmpty()) item { Text("Messages", style = MaterialTheme.typography.titleLarge) }
            items(availableMessages.sortedByDescending { it.deletedAtMillis }, key = { "message-${it.messageId}" }) { message ->
                TrashCard(message.text, message.createdAtLocal, message.deletedAtMillis!!, now,
                    { onRestoreMessage(message.messageId) }, { permanentDelete = "message" to message.messageId })
            }
            if (availableNotes.isNotEmpty()) item { Text("Private voice notes", style = MaterialTheme.typography.titleLarge) }
            items(availableNotes, key = { "note-${it.id}" }) { note -> TrashCard(note.transcribedText, note.createdAtMillis, note.deletedAtMillis!!, now,
                { onRestoreNote(note.id) }, { permanentDelete = "note" to note.id }) }
        }
    }
    permanentDelete?.let { (type, id) -> AlertDialog(onDismissRequest = { permanentDelete = null }, title = { Text("Delete permanently?") },
        text = { Text("This item cannot be restored after permanent deletion.") },
        confirmButton = { TextButton(onClick = { if (type == "message") onDeleteMessage(id) else onDeleteNote(id); permanentDelete = null }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { permanentDelete = null }) { Text("Keep in bin") } }) }
}

@Composable
private fun TrashCard(text: String, created: Long, deleted: Long, now: Long, restore: () -> Unit, delete: () -> Unit) {
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Card(shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Text("Recorded ${format.format(Date(created))}", style = MaterialTheme.typography.bodySmall)
            Text("Deleted ${format.format(Date(deleted))}", style = MaterialTheme.typography.bodySmall)
            Text("${RecycleBinPolicy.daysRemaining(deleted, now)} days left to restore", color = MaterialTheme.colorScheme.primary)
            Row { TextButton(onClick = restore) { Text("Restore") }; Spacer(Modifier.weight(1f)); TextButton(onClick = delete) { Text("Delete forever", color = MaterialTheme.colorScheme.error) } }
        }
    }
}
