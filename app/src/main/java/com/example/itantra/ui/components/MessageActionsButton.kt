package com.example.itantra.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.itantra.app.AppGraph
import com.itantra.core.transceiver.TransceiverCoordinator
import com.itantra.core.translation.TranslationResult
import com.itantra.domain.model.*
import kotlinx.coroutines.CancellationException

/** Shared message menu in chat and history. Add future message actions here. */
@Composable
fun MessageActionsButton(
    message: TransceiverMessage,
    coordinator: TransceiverCoordinator = AppGraph.transceiverCoordinator,
    onDelete: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    var expanded by remember(message.messageId) { mutableStateOf(false) }
    var showLanguages by rememberSaveable(message.messageId) { mutableStateOf(false) }
    var selectedCodes by rememberSaveable(message.messageId) { mutableStateOf(emptyList<String>()) }
    var confirmDelete by remember(message.messageId) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, "Message options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Translate & listen") }, enabled = message.translationInput != null,
                onClick = { expanded = false; showLanguages = true })
            if (!message.isLocationMessage && message.translationInput != null) {
                DropdownMenuItem(text = { Text("Play displayed text") },
                    onClick = { expanded = false; coordinator.replayMessage(message.messageId) })
            }
            DropdownMenuItem(text = { Text(if (message.isLocationMessage) "Copy coordinates" else "Copy text") },
                enabled = message.text.isNotBlank(), onClick = {
                    expanded = false
                    val text = if (message.latitude != null && message.longitude != null)
                        "${message.latitude}, ${message.longitude}" else message.text
                    clipboard.setText(AnnotatedString(text))
                })
            if (message.source == MessageSource.LOCAL && message.priority != MessagePriority.CRITICAL &&
                message.state in setOf(MessageState.ERROR, MessageState.SENT)) {
                DropdownMenuItem(text = { Text("Retry sending") }, onClick = {
                    expanded = false
                    if (onRetry != null) onRetry()
                    else if (!coordinator.retryMessage(message.messageId))
                        Toast.makeText(context, "Reconnect the original peer before retrying", Toast.LENGTH_SHORT).show()
                })
            }
            if (RecycleBinPolicy.canTrash(message)) {
                DropdownMenuItem(text = { Text("Move to recycle bin") }, onClick = {
                    expanded = false
                    if (onDelete != null) onDelete() else confirmDelete = true
                })
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Move message to recycle bin?") },
        text = { Text("You can restore it within 7 days.") },
        confirmButton = { TextButton(onClick = { coordinator.moveMessageToTrash(message.messageId); confirmDelete = false }) { Text("Move") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    if (showLanguages) MessageLanguagesSheet(message, coordinator, selectedCodes,
        onSelectionChange = { selectedCodes = it }, onDismiss = { showLanguages = false })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageLanguagesSheet(message: TransceiverMessage, coordinator: TransceiverCoordinator,
    selectedCodes: List<String>, onSelectionChange: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var results by remember(message.translationInput) { mutableStateOf(emptyMap<LanguageCode, TranslationResult>()) }
    var errors by remember(message.translationInput) { mutableStateOf(emptyMap<LanguageCode, String>()) }
    var loading by remember { mutableStateOf<LanguageCode?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val selected = selectedCodes.mapNotNull(LanguageCode::fromWireCode)
    val clipboard = LocalClipboardManager.current
    val currentMessages by coordinator.messages.collectAsState()
    val playback by coordinator.savedSpeechPlayback.collectAsState()
    val input = message.translationInput

    LaunchedEffect(currentMessages.any { it.messageId == message.messageId }) {
        if (currentMessages.none { it.messageId == message.messageId }) onDismiss()
    }
    DisposableEffect(message.messageId, coordinator) {
        onDispose { coordinator.stopMessageSpeech(message.messageId) }
    }
    LaunchedEffect(selectedCodes, input, retry) {
        try {
            // All chosen outputs use the same source, never another chosen translation.
            for (language in selected) {
                if (results.containsKey(language) || errors.containsKey(language)) continue
                loading = language
                try { results = results + (language to coordinator.translateMessage(message.messageId, language)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { errors = errors + (language to (error.message ?: "Translation failed")) }
            }
        } finally { loading = null }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Message languages", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Text("Choose one or more languages. Read each result or tap Play speech to listen again.", style = MaterialTheme.typography.bodyMedium)
            Text("Source · ${input?.second?.let { LanguageCatalog.byCode(it).displayName } ?: "Unknown language"}",
                style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(input?.first ?: message.text, style = MaterialTheme.typography.bodyMedium) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LanguageCatalog.all.forEach { language ->
                    FilterChip(selected = language.code.wireCode in selectedCodes,
                        label = { Text(language.nativeDisplayName) }, onClick = {
                            val code = language.code.wireCode
                            if (code in selectedCodes) {
                                onSelectionChange(selectedCodes - code)
                                if (playback.itemKey == "message-${message.messageId}-$code") coordinator.stopMessageSpeech(message.messageId)
                            } else onSelectionChange(selectedCodes + code)
                        })
                }
            }
            if (selected.isEmpty()) Text("Select a language above to start.", style = MaterialTheme.typography.bodySmall)
            selected.forEach { language ->
                val result = results[language]
                val successful = result?.isSuccessful == true && result.translatedText.isNotBlank()
                val error = errors[language] ?: result?.takeUnless { successful }?.let { translationErrorLabel(it.error) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(LanguageCatalog.byCode(language).displayName, style = MaterialTheme.typography.titleSmall)
                        when {
                            successful -> {
                                SelectionContainer { Text(result!!.translatedText, style = MaterialTheme.typography.bodyLarge) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SavedSpeechButton("message-${message.messageId}-${language.wireCode}",
                                        { coordinator.replayMessageTranslation(message.messageId, result!!) },
                                        enabled = coordinator.isMessageVoiceInstalled(language))
                                    TextButton(onClick = { clipboard.setText(AnnotatedString(result!!.translatedText)) }) { Text("Copy") }
                                }
                                if (!coordinator.isMessageVoiceInstalled(language))
                                    Text("Install ${LanguageCatalog.byCode(language).displayName} Receive (TTS) in Language packs to listen.", style = MaterialTheme.typography.bodySmall)
                            }
                            error != null -> {
                                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { results = results - language; errors = errors - language; retry++ }) { Text("Try again") }
                            }
                            else -> {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(if (loading == language) "Translating…" else "Waiting…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun translationErrorLabel(error: String?): String = when {
    error == "UNSUPPORTED_ROUTE" -> "Translation for this language pair is unavailable in this build."
    error?.startsWith("MODEL_") == true || error in setOf("ENGINE_NOT_LOADED", "ENGINE_NOT_INITIALIZED", "NOT_INCLUDED_IN_BUILD") ->
        "Install the offline translation models for this language pair in Language packs or Diagnostics."
    else -> "Could not translate this message. Check that its offline translation models are installed, then try again."
}
