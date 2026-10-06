package com.example.itantra.ui.screens.hub

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.itantra.data.messages.MessageFilter
import com.example.itantra.data.messages.applyMessageFilter
import com.example.itantra.ui.components.HistoryMessageCard
import com.example.itantra.ui.components.MessageFilterChipsRow
import com.example.itantra.ui.screens.voicenotes.VoiceNoteGrouping
import com.itantra.app.AppGraph
import com.itantra.core.crypto.SecureSessionState
import com.itantra.core.inference.ActiveLanguageSessionManager
import com.itantra.core.inference.ContinuousListenState
import com.itantra.core.transceiver.TransceiverCoordinator
import com.itantra.domain.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransceiverHubScreen(
    coordinator: TransceiverCoordinator = remember { AppGraph.transceiverCoordinator },
    sessionManager: ActiveLanguageSessionManager = remember { AppGraph.activeLanguageSessionManager },
    onNavigateToConnect: () -> Unit = {}, onNavigateToLanguagePacks: () -> Unit = {},
    onNavigateToVoiceNotes: () -> Unit = {}, onNavigateToMessages: () -> Unit = {},
    onNavigateToRecycleBin: () -> Unit = {}, operatorName: String = "",
    savedNotesCount: Int = 0, deletedNotesCount: Int = 0,
) {
    val messages by coordinator.messages.collectAsState()
    val trash by coordinator.trashedMessages.collectAsState()
    val peer by coordinator.activePeerProfile.collectAsState()
    val security by coordinator.secureSessionManager.state.collectAsState()
    val mic by sessionManager.activeSttLanguage.collectAsState()
    val auto by sessionManager.isSttAutoDetect.collectAsState()
    val session by sessionManager.sessionState.collectAsState()
    val targetFlow = remember { AppGraph.languagePackRepository.observeTargetLanguage() }
    val target by targetFlow.collectAsState(initial = null)
    val packsFlow = remember { AppGraph.languagePackRepository.observePackSummaries() }
    val packs by packsFlow.collectAsState(initial = emptyList())
    val mtStates = remember { (AppGraph.translationEngine as? com.itantra.core.translation.MlKitOfflineTranslationEngine)?.pairModelStates
        ?: MutableStateFlow(emptyMap<LanguageCode, com.itantra.core.translation.TranslationModelState>()) }
    val mt by mtStates.collectAsState()
    val activeEmergency by coordinator.activeEmergencyAlert.collectAsState()
    val continuous by coordinator.continuousListenEngine.state.collectAsState()
    val voiceError by coordinator.continuousModeError.collectAsState()
    var handsFree by remember { mutableStateOf(false) }
    val recording = messages.any { it.source == MessageSource.LOCAL && it.state == MessageState.RECORDING }
    var locked by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(MessageFilter.ALL) }
    var pendingDelete by remember { mutableStateOf<TransceiverMessage?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val showDock by remember { derivedStateOf {
        listState.firstVisibleItemIndex > 3 && listState.layoutInfo.visibleItemsInfo.none { it.key == "talk-controls" }
    } }
    val verified = security == SecureSessionState.SECURE_VERIFIED && peer?.isConnected == true
    val modelReady = session != com.itantra.core.inference.LanguageSessionState.LOADING_STT && sessionManager.currentSttEngine?.isLoaded == true
    val stop = { locked = false; coordinator.stopActiveRecording() }
    val cancel = { locked = false; coordinator.cancelActiveRecording() }
    val start = { if (modelReady) coordinator.startRecording() else onNavigateToLanguagePacks() }
    val toggle = { when {
        !modelReady -> onNavigateToLanguagePacks()
        handsFree -> when (continuous) {
            ContinuousListenState.OFF, ContinuousListenState.ERROR -> coordinator.setContinuousMode(true)
            ContinuousListenState.PAUSED -> coordinator.continuousListenEngine.resumeListening()
            else -> coordinator.continuousListenEngine.pauseListening()
        }
        recording || locked -> stop()
        else -> { start(); locked = true }
    } }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, coordinator) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancel() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); cancel(); coordinator.setContinuousMode(false) }
    }
    LaunchedEffect(recording) { if (!recording) locked = false }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(60_000L) } }
    val filtered = messages.applyMessageFilter(filter, { it.source == MessageSource.LOCAL }, { it.state == MessageState.ERROR }, { it.priority == MessagePriority.CRITICAL })
    val groups = remember(filtered, now) { VoiceNoteGrouping.group(filtered, { it.createdAtLocal }, now) }
    val micName = if (auto) "Auto detect" else LanguageCatalog.all.firstOrNull { it.code == mic }?.nativeDisplayName ?: "Choose language"
    val targetName = LanguageCatalog.all.firstOrNull { it.code == target }?.nativeDisplayName ?: "Auto · peer language"
    val swapReady = !auto && !recording && !handsFree && mic != null && target != null && mic != target &&
        packs.any { it.language.code == target && it.isSttDownloaded } && packs.any { it.language.code == mic && it.isTtsDownloaded } &&
        mt[mic] == com.itantra.core.translation.TranslationModelState.READY && mt[target] == com.itantra.core.translation.TranslationModelState.READY

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = { if (showDock) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExtendedFloatingActionButton(onClick = toggle,
                icon = { Icon(Icons.Filled.Mic, null) }, text = { Text(if (recording) "Finish recording" else if (handsFree) "Pause / resume" else "Tap to talk") })
            if (recording) SmallFloatingActionButton(onClick = cancel) {
                Icon(Icons.Filled.Close, "Cancel recording")
            }
        } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = if (showDock) 100.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(if (modelReady) "Ready to talk." else "Prepare your voice.", style = MaterialTheme.typography.headlineLarge)
                if (operatorName.isNotBlank()) Text("Hello, $operatorName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { Card(onClick = onNavigateToConnect, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (verified) Icons.Filled.VerifiedUser else Icons.Filled.Link, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(if (verified) "Connected to ${peer?.displayName?.takeIf { it.isNotBlank() } ?: "verified peer"}" else "Connect a nearby device", style = MaterialTheme.typography.titleSmall)
                        Text(if (verified) "Verified peer · encrypted direct link" else "Save locally. Connect to share.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.ArrowForward, "Open connections", modifier = Modifier.size(20.dp))
                }
            } }
            activeEmergency?.let { alert -> item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Active emergency", style = MaterialTheme.typography.titleMedium)
                    Text(alert.resolvedPhrase)
                    Button(onClick = { coordinator.sendHumanAck(alert.messageId) }) { Text("Acknowledge & silence alarm") }
                }
            } } }
            item { Card(shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent messages", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onNavigateToMessages) { Text("Open chats", style = MaterialTheme.typography.labelMedium); Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.ArrowForward, null, Modifier.size(16.dp)) }
                    }
                    MessageFilterChipsRow(filter, { filter = it })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (filtered.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Your conversation starts here.", style = MaterialTheme.typography.bodyMedium)
                        Text("Saved transcripts and incoming messages appear here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    filtered.sortedByDescending { it.createdAtLocal }.take(2).forEach { message ->
                        HistoryMessageCard(message, { pendingDelete = message }, {
                            if (!coordinator.retryMessage(message.messageId)) scope.launch { snack.showSnackbar("Reconnect the original peer, or copy the transcript into a new message.") }
                        }, { coordinator.sendHumanAck(message.messageId) }, compact = true)
                    }
                }
            } }
            item(key = "talk-controls") { Card(shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .3f)),
                colors = CardDefaults.cardColors(containerColor = lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primaryContainer, .45f))) {
                Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp)); Text("Speak & translate", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp)) {
                            Text(if (modelReady) "On-device" else "Models needed", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HubLanguageChoice("Speak", micName, Icons.Filled.Mic, !recording && !handsFree, Modifier.weight(1f)) { picker = "mic" }
                        OutlinedIconButton(onClick = { val previous = mic!!; AppGraph.setMicLanguage(target!!); AppGraph.setTargetLanguage(previous) }, enabled = swapReady, shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Filled.SwapHoriz, "Swap microphone and target languages", tint = if (swapReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HubLanguageChoice("Translate to", targetName, Icons.Filled.Translate, !recording && !handsFree, Modifier.weight(1f)) { picker = "target" }
                    }
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(3.dp)) {
                            TextButton(onClick = { stop(); handsFree = false; coordinator.setContinuousMode(false) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(9.dp),
                                colors = ButtonDefaults.textButtonColors(containerColor = if (!handsFree) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent)) {
                                Icon(Icons.Filled.Mic, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Push to talk", style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(onClick = { stop(); handsFree = true; coordinator.setContinuousMode(true) }, enabled = modelReady, modifier = Modifier.weight(1f), shape = RoundedCornerShape(9.dp),
                                colors = ButtonDefaults.textButtonColors(containerColor = if (handsFree) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent)) {
                                Icon(Icons.Filled.GraphicEq, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Hands-free", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    val label = if (!modelReady) "Prepare speech models" else if (handsFree) "Pause or resume listening" else if (recording) "Finish recording" else "Start recording"
                    Box(Modifier.size(132.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .2f), CircleShape), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(118.dp).border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = .35f), CircleShape), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(104.dp).shadow(6.dp, CircleShape)
                                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, lerp(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, .15f))), CircleShape)
                                .semantics { role = Role.Button; stateDescription = if (recording) "Recording" else "Idle"; onClick(label) { toggle(); true } }
                                .onKeyEvent { event -> if (event.type == KeyEventType.KeyUp && event.key in listOf(Key.Enter, Key.Spacebar, Key.DirectionCenter)) { toggle(); true } else false }
                                .focusable().pointerInput(handsFree, locked, modelReady, continuous) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false); down.consume()
                                        if (handsFree || !modelReady || locked) { toggle(); return@awaitEachGesture }
                                        start(); var slideLocked = false; var released = false
                                        try { while (true) {
                                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) { released = true; break }
                                            if (down.position.y - change.position.y > 64.dp.toPx()) { slideLocked = true; break }
                                            change.consume()
                                        } } finally { when { slideLocked -> locked = true; released -> stop(); else -> cancel() } }
                                    }
                                }, contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(if (!modelReady) Icons.Filled.Download else if (recording || handsFree) Icons.Filled.GraphicEq else Icons.Filled.Mic,
                                        null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
                                    Text(if (!modelReady) "Get models" else if (recording) "Recording" else if (handsFree) when (continuous) {
                                        ContinuousListenState.OFF, ContinuousListenState.ERROR -> "Tap to retry"
                                        ContinuousListenState.PAUSED -> "Paused"
                                        else -> "Listening"
                                    } else "Hold to talk",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        }
                    }
                    Text(if (locked) "Recording locked" else if (!modelReady) "Prepare once. Speak offline." else if (handsFree) continuous.name.replace('_', ' ').lowercase() else "Press. Speak. Release.", style = MaterialTheme.typography.titleSmall)
                    Text(if (!modelReady) "Tap to open language packs" else if (handsFree) "Tap to start or pause listening" else "Slide up while holding to record hands-free",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (recording) Row { Button(onClick = stop) { Text("Finish") }; Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = cancel) { Text("Cancel") } }
                    voiceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                        Text(if (verified) "Processed locally · sent directly to your peer" else "No peer connected · transcripts saved locally", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HubShortcut("Voice notes", "$savedNotesCount saved transcripts", Icons.Filled.Notes, Modifier.weight(1f), onNavigateToVoiceNotes)
                HubShortcut("Language packs", "${packs.count { it.isSttDownloaded && it.isTtsDownloaded }} speech pairs installed", Icons.Filled.Language, Modifier.weight(1f), onNavigateToLanguagePacks)
            } }
            item { OutlinedButton(onClick = onNavigateToRecycleBin, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Filled.RestoreFromTrash, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text("Recycle bin · ${trash.size + deletedNotesCount} items · restore within 7 days", style = MaterialTheme.typography.labelMedium)
            } }
            if (filtered.isNotEmpty()) {
                item { Text("Saved messages", style = MaterialTheme.typography.titleMedium) }
                groups.forEach { (group, rows) ->
                    item(key = "date-${group.bucket}-${group.olderMonth}") { Text(group.title(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    items(rows, key = { it.messageId }) { message -> HistoryMessageCard(message, { pendingDelete = message }, {
                        if (!coordinator.retryMessage(message.messageId)) scope.launch { snack.showSnackbar("Reconnect the original peer, or copy the transcript into a new message.") }
                    }, { coordinator.sendHumanAck(message.messageId) }) }
                }
            }
            item { Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Phonelink, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Column { Text("A connection, even without the internet.", style = MaterialTheme.typography.titleSmall)
                        Text("Pair nearby phones. Installed speech models work locally.", style = MaterialTheme.typography.bodySmall) }
                }
            } }
        }
    }

    picker?.let { kind -> AlertDialog(onDismissRequest = { picker = null }, title = { Text(if (kind == "mic") "Microphone language" else "Translate to") }, text = { LazyColumn {
        item { TextButton(onClick = { if (kind == "target") AppGraph.setTargetLanguage(null) else scope.launch { AppGraph.languagePackRepository.setSpeechInputMode(SpeechInputMode.AUTO) }; picker = null }) { Text("Auto") } }
        items(LanguageCatalog.all) { language -> val installed = packs.any { it.language.code == language.code && it.isSttDownloaded }
            TextButton(onClick = { if (kind == "mic") AppGraph.setMicLanguage(language.code) else AppGraph.setTargetLanguage(language.code); picker = null }, enabled = kind != "mic" || installed) {
                Text(language.nativeDisplayName + if (kind == "mic" && !installed) " · download first" else "")
            }
        }
    } }, confirmButton = { TextButton(onClick = { picker = null; onNavigateToLanguagePacks() }) { Text("Language packs") } }) }
    pendingDelete?.let { message -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Move to recycle bin?") },
        text = { Text("Restore within 7 days. This deletes only your local history, not a peer's copy or the separate voice note.") },
        confirmButton = { TextButton(onClick = { pendingDelete = null; if (coordinator.moveMessageToTrash(message.messageId)) scope.launch {
            if (snack.showSnackbar("Moved to recycle bin", "Undo") == SnackbarResult.ActionPerformed) coordinator.restoreMessage(message.messageId)
        } }) { Text("Move to bin") } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep") } }) }
}

@Composable
private fun HubLanguageChoice(label: String, value: String, icon: ImageVector, enabled: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 68.dp),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ExpandMore, null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun HubShortcut(title: String, subtitle: String, icon: ImageVector, modifier: Modifier,
    onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.heightIn(min = 76.dp), shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, style = MaterialTheme.typography.labelMedium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
