package com.example.itantra.ui.screens.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
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
    onNavigateToDiagnostics: () -> Unit = {}, onNavigateToSettings: () -> Unit = {},
    onNavigateToVoiceNotes: () -> Unit = {}, onNavigateToMessages: () -> Unit = {},
    onNavigateToRecycleBin: () -> Unit = {}, operatorName: String = "",
) {
    val messages by coordinator.messages.collectAsState()
    val trash by coordinator.trashedMessages.collectAsState()
    val peer by coordinator.activePeerProfile.collectAsState()
    val security by coordinator.secureSessionManager.state.collectAsState()
    val mic by sessionManager.activeSttLanguage.collectAsState()
    val auto by sessionManager.isSttAutoDetect.collectAsState()
    val session by sessionManager.sessionState.collectAsState()
    val target by AppGraph.languagePackRepository.observeTargetLanguage().collectAsState(initial = null)
    val packs by AppGraph.languagePackRepository.observePackSummaries().collectAsState(initial = emptyList())
    val mtStates = remember { (AppGraph.translationEngine as? com.itantra.core.translation.MlKitOfflineTranslationEngine)?.pairModelStates
        ?: MutableStateFlow(emptyMap<LanguageCode, com.itantra.core.translation.TranslationModelState>()) }
    val mt by mtStates.collectAsState()
    val activeEmergency by coordinator.activeEmergencyAlert.collectAsState()
    val continuous by coordinator.continuousListenEngine.state.collectAsState()
    val voiceError by coordinator.continuousModeError.collectAsState()
    var handsFree by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(MessageFilter.ALL) }
    var showSos by remember { mutableStateOf(false) }
    var emergency by remember { mutableStateOf<EmergencyCode?>(null) }
    var pendingDelete by remember { mutableStateOf<TransceiverMessage?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val showDock by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
    val verified = security == SecureSessionState.SECURE_VERIFIED && peer?.isConnected == true
    val modelReady = session != com.itantra.core.inference.LanguageSessionState.LOADING_STT && sessionManager.currentSttEngine?.isLoaded == true
    val stop = { recording = false; locked = false; coordinator.stopActiveRecording() }
    val start = { if (modelReady) { recording = true; coordinator.startRecording() } else onNavigateToLanguagePacks() }
    val toggle = { when {
        !modelReady -> onNavigateToLanguagePacks()
        handsFree -> if (continuous == ContinuousListenState.PAUSED) coordinator.continuousListenEngine.resumeListening() else coordinator.continuousListenEngine.pauseListening()
        recording || locked -> stop()
        else -> { start(); locked = true }
    } }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, coordinator) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); stop(); coordinator.setContinuousMode(false) }
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(60_000L) } }
    val filtered = messages.applyMessageFilter(filter, { it.source == MessageSource.LOCAL }, { it.state == MessageState.ERROR }, { it.priority == MessagePriority.CRITICAL })
    val groups = remember(filtered, now) { VoiceNoteGrouping.group(filtered, { it.createdAtLocal }, now) }
    val micName = if (auto) "Auto detect" else LanguageCatalog.all.firstOrNull { it.code == mic }?.nativeDisplayName ?: "Choose language"
    val targetName = LanguageCatalog.all.firstOrNull { it.code == target }?.nativeDisplayName ?: "Auto · peer language"
    val swapReady = !auto && !recording && !handsFree && mic != null && target != null && mic != target &&
        packs.any { it.language.code == target && it.isSttDownloaded } && packs.any { it.language.code == mic && it.isTtsDownloaded } &&
        mt[mic] == com.itantra.core.translation.TranslationModelState.READY && mt[target] == com.itantra.core.translation.TranslationModelState.READY

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = { if (showDock) ExtendedFloatingActionButton(onClick = toggle,
            icon = { Icon(Icons.Filled.Mic, null) }, text = { Text(if (recording) "Finish recording" else if (handsFree) "Pause / resume" else "Tap to talk") }) }, topBar = {
        TopAppBar(title = { Text("iTantra", style = MaterialTheme.typography.titleLarge) }, actions = {
            TextButton(onClick = { showSos = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Filled.Sos, null); Spacer(Modifier.width(4.dp)); Text("SOS")
            }
            IconButton(onClick = onNavigateToSettings) { Icon(Icons.Filled.Settings, "Settings and appearance") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (showDock) 88.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(if (modelReady) "Ready to talk." else "Prepare your voice.", style = MaterialTheme.typography.headlineMedium)
                Text(if (operatorName.isBlank()) "Speak locally. Connect to share." else "Hello, $operatorName", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Card(onClick = onNavigateToConnect, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (verified) Icons.Filled.VerifiedUser else Icons.Filled.Link, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(if (verified) "Connected to ${peer?.displayName?.takeIf { it.isNotBlank() } ?: "verified peer"}" else "Connect a nearby device")
                        Text(if (verified) "Verified · encrypted direct link" else "Recordings can be saved locally", style = MaterialTheme.typography.bodySmall)
                    }; Icon(Icons.Filled.ChevronRight, null)
                }
            } }
            item { Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Speak & translate", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { picker = "mic" }, enabled = !recording && !handsFree, modifier = Modifier.weight(1f)) { Text(micName) }
                        IconButton(onClick = { val previous = mic!!; AppGraph.setMicLanguage(target!!); AppGraph.setTargetLanguage(previous) }, enabled = swapReady) { Icon(Icons.Filled.SwapHoriz, "Swap languages") }
                        OutlinedButton(onClick = { picker = "target" }, enabled = !recording && !handsFree, modifier = Modifier.weight(1f)) { Text(targetName) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!handsFree, { stop(); handsFree = false; coordinator.setContinuousMode(false) }, { Text("Push to talk") })
                        FilterChip(handsFree, { stop(); handsFree = true; coordinator.setContinuousMode(true) }, { Text("Hands-free") }, enabled = modelReady)
                    }
                    val label = if (!modelReady) "Prepare speech models" else if (handsFree) "Pause or resume listening" else if (recording) "Finish recording" else "Start recording"
                    Box(Modifier.size(124.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                        .semantics { role = Role.Button; stateDescription = if (recording) "Recording" else "Idle"; onClick(label) { toggle(); true } }
                        .onKeyEvent { event -> if (event.type == KeyEventType.KeyUp && event.key in listOf(Key.Enter, Key.Spacebar, Key.DirectionCenter)) { toggle(); true } else false }
                        .focusable()
                        .pointerInput(handsFree, locked, modelReady, continuous) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false); down.consume()
                                if (handsFree || !modelReady || locked) { toggle(); return@awaitEachGesture }
                                start(); var slideLocked = false
                                try { while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    if (down.position.y - change.position.y > 64.dp.toPx()) { slideLocked = true; break }
                                    change.consume()
                                } } finally { if (slideLocked) locked = true else stop() }
                            }
                        }, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if (recording || handsFree) Icons.Filled.GraphicEq else Icons.Filled.Mic, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(36.dp))
                            Text(if (recording) "Recording" else if (handsFree) "Listening" else "Hold to talk", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Text(if (locked) "Recording locked" else if (handsFree) continuous.name.replace('_', ' ').lowercase() else "Press. Speak. Release. Slide up to lock.", style = MaterialTheme.typography.bodySmall)
                    if (recording) Row { Button(onClick = stop) { Text("Finish") }; Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { coordinator.cancelActiveRecording(); recording = false; locked = false }) { Text("Cancel") } }
                    TextButton(onClick = onNavigateToLanguagePacks) { Text("Manage offline languages") }
                    voiceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            } }
            activeEmergency?.let { alert -> item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Active emergency", style = MaterialTheme.typography.titleMedium)
                    Text(alert.resolvedPhrase)
                    Button(onClick = { coordinator.sendHumanAck(alert.messageId) }) { Text("Acknowledge & silence alarm") }
                }
            } } }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { AssistChip(onNavigateToVoiceNotes, { Text("Voice notes") }) }
                item { AssistChip(onNavigateToRecycleBin, { Text("Recycle bin${if (trash.isNotEmpty()) " · ${trash.size}" else ""}") }) }
                item { AssistChip(onNavigateToDiagnostics, { Text("Diagnostics") }) }
            } }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Recent messages", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); TextButton(onNavigateToMessages) { Text("Open chats") } }
                MessageFilterChipsRow(filter, { filter = it }) }
            if (filtered.isEmpty()) item { Text("Your voice transcripts and messages will appear here.") }
            groups.forEach { (group, rows) ->
                item(key = "date-${group.bucket}-${group.olderMonth}") { Text(group.title(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(rows, key = { it.messageId }) { message -> HistoryMessageCard(message, { pendingDelete = message }, {
                    if (!coordinator.retryMessage(message.messageId)) scope.launch { snack.showSnackbar("Reconnect the original peer, or copy the transcript into a new message.") }
                }, { coordinator.sendHumanAck(message.messageId) }) }
            }
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
    if (showSos) AlertDialog(onDismissRequest = { showSos = false }, title = { Text("Emergency message") }, text = { Column {
        Text("Send to your connected peer. Confirm before sending.")
        listOf(EmergencyCode.HELP_REQUIRED to "Need help", EmergencyCode.MEDICAL_EMERGENCY to "Medical SOS", EmergencyCode.ROAD_BLOCKED to "Road blocked", EmergencyCode.EVACUATE to "Evacuate").forEach { (code, name) ->
            TextButton(onClick = { emergency = code; showSos = false }) { Text(name) }
        }
        TextButton(onClick = { emergency = EmergencyCode.HELP_REQUIRED; showSos = false }) { Text("Critical priority alert") }
    } }, confirmButton = { TextButton(onClick = { showSos = false }) { Text("Close") } })
    emergency?.let { code -> AlertDialog(onDismissRequest = { emergency = null }, title = { Text("Send ${code.name.lowercase().replace('_', ' ')}?") },
        text = { Text(if (verified) "Send a critical encrypted alert to your connected peer." else "Connect and verify a peer before sending.") },
        confirmButton = { TextButton(onClick = { coordinator.sendEmergencyCode(code); emergency = null }, enabled = verified) { Text("Send alert") } }, dismissButton = { TextButton(onClick = { emergency = null }) { Text("Cancel") } }) }
    pendingDelete?.let { message -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Move to recycle bin?") },
        text = { Text("Restore within 7 days. This deletes only your local history, not a peer's copy or the separate voice note.") },
        confirmButton = { TextButton(onClick = { pendingDelete = null; if (coordinator.moveMessageToTrash(message.messageId)) scope.launch {
            if (snack.showSnackbar("Moved to recycle bin", "Undo") == SnackbarResult.ActionPerformed) coordinator.restoreMessage(message.messageId)
        } }) { Text("Move to bin") } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep") } }) }
}
