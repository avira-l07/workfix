package com.example.itantra.ui.screens.hub

import com.itantra.core.translation.TRANSLATION_SCOPE_NOTE
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.painterResource
import com.example.itantra.R
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.data.messages.MessageFilter
import com.example.itantra.data.messages.applyMessageFilter
import com.example.itantra.ui.components.MessageFilterChipsRow
import com.example.itantra.ui.components.TacticalBatteryPill
import com.example.itantra.ui.theme.ITantraColors
import com.itantra.app.AppGraph
import com.itantra.core.crypto.SecureSessionState
import com.itantra.core.inference.ActiveLanguageSessionManager
import com.itantra.domain.model.LanguageCode
import com.itantra.core.inference.ContinuousListenState
import com.itantra.core.transceiver.TransceiverCoordinator
import com.itantra.core.transport.ConnectionState
import com.itantra.domain.model.EmergencyCode
import com.itantra.domain.model.Measurement
import com.itantra.domain.model.MessageSource
import com.itantra.domain.model.MessageState
import com.itantra.domain.model.VOICE_OUTPUT_UNAVAILABLE_NOTE
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HubMessage(
    val id: Long,
    val sender: String,
    val isSent: Boolean,
    val langPair: String,
    val text: String,
    val translation: String? = null,
    val time: String,
    val state: String,
    val isEmergency: Boolean = false,
    val isFailed: Boolean = false,
    val duration: String = "0:00s Audio",
    val statusDetail: String? = null,
    // True when statusDetail is the standing "translation not included in this build" note
    // rather than a real per-message failure (peer disconnected, silence, etc). This note is
    // a stated scope decision for the whole app, not something to repeat as a red error on
    // every single message - see the persistent header note instead.
    val isTranslationScopeNote: Boolean = false,
    val isVoiceNote: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransceiverHubScreen(
    coordinator: TransceiverCoordinator = remember { AppGraph.transceiverCoordinator },
    sessionManager: ActiveLanguageSessionManager = remember { AppGraph.activeLanguageSessionManager },
    onNavigateToConnect: () -> Unit = {},
    onNavigateToLanguagePacks: () -> Unit = {},
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onSendEmergency: (String) -> Unit = {},
    operatorName: String = "",
) {
    val liveMessages by coordinator.messages.collectAsState()
    val sessionState by coordinator.secureSessionManager.state.collectAsState()
    val activeLanguage by sessionManager.activeLanguage.collectAsState()
    val transportConnectionState by AppGraph.transportEngine.observeConnectionState().collectAsState(initial = ConnectionState.DISCONNECTED)
    val liveMetrics by AppGraph.metricsRecorder.latest.collectAsState()
    val continuousListenState by coordinator.continuousListenEngine.state.collectAsState()

    var isPttMode by remember { mutableStateOf(true) }
    var isTransmitting by remember { mutableStateOf(false) }
    var isPttLocked by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(MessageFilter.ALL) }
    var showEmergencyConfirm by remember { mutableStateOf(false) }
    var pendingEmergencyCode by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, coordinator) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                if (isTransmitting || isPttLocked) {
                    isTransmitting = false
                    isPttLocked = false
                    coordinator.stopActiveRecording()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            isTransmitting = false
            isPttLocked = false
            coordinator.stopActiveRecording()
            coordinator.setContinuousMode(false)
        }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val timeFormat = remember {
        SimpleDateFormat("HH:mm 'IST'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        }
    }

    val allMessages = liveMessages.map { msg ->
        val totalSecs = ((msg.speechDurationMillis + 500) / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        val formattedDuration = String.format(Locale.US, "%d:%02ds Audio", mins, secs)

        val isMtSuccess = msg.translationStatus == com.itantra.domain.model.TranslationStatus.SUCCESS
        val srcName = msg.language?.name ?: "HI"
        val tgtName = msg.targetLanguage?.name ?: "EN"
        val langPair = if (isMtSuccess && msg.targetLanguage != null && msg.targetLanguage != msg.language) {
            "$srcName → $tgtName"
        } else {
            srcName
        }

        HubMessage(
            id = msg.messageId,
            sender = if (msg.source == MessageSource.LOCAL) {
                if (operatorName.isNotBlank()) operatorName else "OPERATOR (Local)"
            } else "REMOTE PEER",
            isSent = msg.source == MessageSource.LOCAL,
            langPair = langPair,
            text = if (isMtSuccess && msg.originalText != null) msg.originalText!! else msg.text,
            translation = if (isMtSuccess && msg.originalText != null) msg.text else null,
            time = timeFormat.format(Date(msg.createdAtLocal)),
            state = when (msg.state) {
                MessageState.DELIVERED -> "DELIVERED"
                MessageState.WAITING_ACK -> "WAITING ACK"
                MessageState.ERROR -> "FAILED"
                MessageState.STT_PROCESSING -> "PROCESSING"
                MessageState.RECORDING -> "RECORDING"
                else -> "SENDING"
            },
            isEmergency = msg.priority == com.itantra.domain.model.MessagePriority.CRITICAL,
            isFailed = msg.state == MessageState.ERROR,
            duration = formattedDuration,
            statusDetail = msg.statusDetail,
            isTranslationScopeNote = msg.statusDetail == TRANSLATION_SCOPE_NOTE,
            isVoiceNote = msg.statusDetail == VOICE_OUTPUT_UNAVAILABLE_NOTE
        )
    }

    val filteredMessages = allMessages.applyMessageFilter(
        filter = selectedFilter,
        isSent = { it.isSent },
        isFailed = { it.isFailed },
        isEmergency = { it.isEmergency },
    )

    // Tracks message IDs already seen, so we can tell an addition from a state-only update
    // (updateMessage() mutates an existing entry in place, so it never changes .size/.id set).
    var previousMessageIds by remember { mutableStateOf(emptySet<Long>()) }
    // Set when a new LOCAL message appears while PTT is actively held - almost always our own
    // "Listening..." placeholder from starting this very hold - and cleared once the hold ends,
    // at which point we do the deferred scroll instead.
    var pendingScrollAfterHold by remember { mutableStateOf(false) }

    LaunchedEffect(allMessages) {
        if (previousMessageIds.isEmpty()) {
            previousMessageIds = allMessages.map { it.id }.toSet()
            return@LaunchedEffect
        }
        val currentIds = allMessages.map { it.id }.toSet()
        val newlyAdded = allMessages.filter { it.id !in previousMessageIds }
        previousMessageIds = currentIds
        if (newlyAdded.isEmpty()) return@LaunchedEffect

        val isOwnHoldPlaceholder = (isTransmitting || isPttLocked) && newlyAdded.all { it.isSent }
        if (isOwnHoldPlaceholder) {
            // Don't yank the viewport out from under the operator's thumb mid-press. An incoming
            // REMOTE message still scrolls immediately even while holding - newlyAdded.all above
            // only matches when every newly-added message is our own outgoing one.
            pendingScrollAfterHold = true
            return@LaunchedEffect
        }

        kotlinx.coroutines.delay(100)
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    // Catches up the scroll we deferred above once the hold actually ends (release, slide-to-lock
    // release via "TAP TO SEND", or the lifecycle-driven stop). This is a proxy for "the outgoing
    // message is done" - true completion (STT -> translate -> transmit -> ACK) only updates the
    // existing message in place and never changes allMessages' size, so there's no size-based
    // signal to hook for that later point without a larger restructure.
    LaunchedEffect(isTransmitting, isPttLocked) {
        if (!isTransmitting && !isPttLocked && pendingScrollAfterHold) {
            pendingScrollAfterHold = false
            kotlinx.coroutines.delay(100)
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) {
                listState.animateScrollToItem(total - 1)
            }
        }
    }

    Scaffold(
        containerColor = ITantraColors.CanvasBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Tactical Logo Icon
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "iTantra Logo",
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "ITANTRA",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = ITantraColors.TextHeadline,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                "TALK TRANSCEIVER",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = ITantraColors.Primary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                                fontSize = 9.5.sp
                            )
                        }
                    }
                },
                actions = {
                    // Battery Pill
                    TacticalBatteryPill()
                    Spacer(Modifier.width(8.dp))

                    // Settings Button
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ITantraColors.CanvasBg)
                            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(8.dp))
                            .clickable { onNavigateToSettings() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = ITantraColors.TextHeadline,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ITantraColors.SurfaceWhite),
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 0. Translation scope note - shown once, calmly, rather than as a repeated
            // per-message red error. See TRANSLATION_SCOPE_NOTE / UnavailableTranslationEngine.
            item { TranslationScopeBanner() }

            // 1. Hub Spoke Entry Navigation Cards (3 Columns)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SpokeCard(
                        icon = Icons.Filled.Hub,
                        title = "Connect",
                        subtitle = "Nearby Devices",
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToConnect,
                    )
                    SpokeCard(
                        icon = Icons.Filled.Translate,
                        title = "Language",
                        subtitle = "${activeLanguage?.name ?: "HINDI"} Active",
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToLanguagePacks,
                    )
                    SpokeCard(
                        icon = Icons.Filled.QueryStats,
                        title = "Diagnostics",
                        subtitle = "Benchmark · TTS",
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToDiagnostics,
                    )
                }
            }

            // 2. Dynamic Security Indicator & Real Peer State (Stacked Rows matching code.html / screen.png)
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isSecured = sessionState == SecureSessionState.SECURE_VERIFIED

                    // Card 1: Dynamic Security State Indicator
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ITantraColors.SurfaceWhite, RoundedCornerShape(12.dp))
                            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFECFDF5)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.VerifiedUser,
                                    contentDescription = null,
                                    tint = ITantraColors.StatusSuccess,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (isSecured) "ENCRYPTED (SECURE SESSION ACTIVE)" else "STANDBY (READY TO LINK)",
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.5.sp,
                                        color = ITantraColors.TextHeadline,
                                        letterSpacing = 0.2.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .scale(if (isSecured) pulseScale else 1f)
                                            .clip(CircleShape)
                                            .background(if (isSecured) ITantraColors.StatusSuccess else ITantraColors.TextMuted)
                                    )
                                }
                                Spacer(Modifier.height(1.dp))
                                Text(
                                    if (isSecured) "SecureSessionState: Established (E2EE P2P)" else "SecureSessionState: Standby (Ready to Link)",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    color = ITantraColors.TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Card 2: Real Peer Connection Status (from live transportConnectionState)
                    val peerLabel = when (transportConnectionState) {
                        ConnectionState.CONNECTED -> "PEER: CONNECTED"
                        ConnectionState.CONNECTING -> "PEER: CONNECTING…"
                        ConnectionState.LISTENING -> "PEER: LISTENING…"
                        ConnectionState.DISCONNECTED -> "PEER: DISCONNECTED"
                        ConnectionState.ERROR -> "PEER: LINK ERROR"
                    }
                    val peerBadgeLabel = when (transportConnectionState) {
                        ConnectionState.CONNECTED -> "ACTIVE"
                        ConnectionState.CONNECTING, ConnectionState.LISTENING -> "LINKING"
                        else -> "OFFLINE"
                    }
                    val peerBadgeBg = when (transportConnectionState) {
                        ConnectionState.CONNECTED -> Color(0xFFD1FAE5)
                        ConnectionState.CONNECTING, ConnectionState.LISTENING -> Color(0xFFFEF9C3)
                        else -> Color(0xFFF1F5F9)
                    }
                    val peerBadgeColor = when (transportConnectionState) {
                        ConnectionState.CONNECTED -> ITantraColors.StatusSuccess
                        ConnectionState.CONNECTING, ConnectionState.LISTENING -> ITantraColors.StatusWarning
                        else -> ITantraColors.TextMuted
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ITantraColors.SurfaceWhite, RoundedCornerShape(12.dp))
                            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ITantraColors.AccentSubtle),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Sensors,
                                    contentDescription = null,
                                    tint = ITantraColors.Primary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        peerLabel,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.5.sp,
                                        color = ITantraColors.TextHeadline,
                                        letterSpacing = 0.2.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(peerBadgeBg, RoundedCornerShape(3.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            peerBadgeLabel,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 8.sp,
                                            color = peerBadgeColor,
                                            softWrap = false
                                        )
                                    }
                                }
                                Spacer(Modifier.height(1.dp))
                                Text(
                                    if (transportConnectionState == ConnectionState.CONNECTED) "E2EE session active · Use Connect screen to switch" else "Go to Connect to pair a peer device",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    color = ITantraColors.TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // 3. Live Telemetry Instrument Cluster (real metrics from MetricsRecorder)
            item {
                // Derive real values; show null/"--" when not yet measured
                val latencyText = when (val e2e = liveMetrics.endToEndMillis) {
                    is Measurement.Measured<*> -> "${e2e.value} ms"
                    else -> when (val tx = liveMetrics.transport.transmissionLatencyMillis) {
                        is Measurement.Measured<*> -> "${tx.value} ms"
                        else -> "-- ms"
                    }
                }
                val bwSavedText = run {
                    val sem = liveMetrics.transport.semanticPayloadBytes
                    val frame = liveMetrics.transport.finalFrameBytes
                    // PCM equivalent is stored per-message in TransceiverMessage.rawPcmEquivalentBytes
                    // BW saved = 1 - (frameBytes / rawPcmEquivalentBytes)
                    // Best proxy from available metrics: semantic vs frame overhead
                    if (sem is Measurement.Measured<*> && frame is Measurement.Measured<*>) {
                        val semVal = (sem.value as Int).toLong()
                        val frameVal = (frame.value as Int).toLong()
                        if (frameVal > 0 && semVal > 0) {
                            // Use most recent message's rawPcmEquivalentBytes from live messages
                            val lastMsg = liveMessages.lastOrNull { it.rawPcmEquivalentBytes > 0 }
                            if (lastMsg != null && lastMsg.rawPcmEquivalentBytes > 0) {
                                val saved = 1.0 - (frameVal.toDouble() / lastMsg.rawPcmEquivalentBytes)
                                String.format(java.util.Locale.US, "%.1f%%", saved.coerceIn(0.0, 1.0) * 100)
                            } else {
                                "-- %"
                            }
                        } else "-- %"
                    } else "-- %"
                }
                val linkText = when (transportConnectionState) {
                    ConnectionState.CONNECTED -> "Bluetooth"
                    ConnectionState.CONNECTING -> "Linking…"
                    ConnectionState.LISTENING -> "Listening…"
                    ConnectionState.DISCONNECTED -> "No Link"
                    ConnectionState.ERROR -> "Link Error"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ITantraColors.SurfaceWhite, RoundedCornerShape(12.dp))
                        .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Stat 1: Latency
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(ITantraColors.CanvasBg, RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("LATENCY", fontFamily = FontFamily.Monospace, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = ITantraColors.TextMuted)
                                Icon(Icons.Filled.Speed, contentDescription = null, tint = ITantraColors.StatusSuccess, modifier = Modifier.size(12.dp))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(latencyText, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ITantraColors.TextHeadline)
                        }
                    }

                    // Stat 2: Bandwidth Saved
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(ITantraColors.CanvasBg, RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("BW SAVED", fontFamily = FontFamily.Monospace, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = ITantraColors.TextMuted)
                                Icon(Icons.Filled.Compress, contentDescription = null, tint = ITantraColors.Primary, modifier = Modifier.size(12.dp))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(bwSavedText, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ITantraColors.Primary)
                            Text("Tokens vs PCM", fontFamily = FontFamily.Monospace, fontSize = 7.5.sp, color = ITantraColors.TextMuted)
                        }
                    }

                    // Stat 3: Link (real transport type)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(ITantraColors.CanvasBg, RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("LINK", fontFamily = FontFamily.Monospace, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = ITantraColors.TextMuted)
                                Icon(Icons.Filled.WifiTethering, contentDescription = null, tint = ITantraColors.Primary, modifier = Modifier.size(12.dp))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(linkText, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ITantraColors.TextHeadline, maxLines = 1)
                        }
                    }
                }
            }

            // 4. Operational Tactical Channel Banner
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ITantraColors.SurfaceWhite, RoundedCornerShape(12.dp))
                        .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .scale(pulseScale)
                                .background(ITantraColors.Primary, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "CHANNEL: TAC-RELIEF-04",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFFF1F5F9), RoundedCornerShape(3.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        "P2P",
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 8.sp,
                                        color = ITantraColors.TextHeadline
                                    )
                                }
                            }
                            Text(
                                "DISASTER RELIEF COMMAND COHORTS",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.5.sp,
                                color = ITantraColors.TextMuted,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                    Text(
                        "RSSI: -- dBm",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = ITantraColors.TextMuted
                    )
                }
            }



            // 5b. Tactical Speech Language Quick Selector
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ITantraColors.SurfaceWhite, RoundedCornerShape(10.dp))
                        .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Translate,
                            contentDescription = null,
                            tint = ITantraColors.Primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "MIC LANGUAGE:",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.5.sp,
                            color = ITantraColors.TextMuted,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Hindi Chip
                        val isHindi = (activeLanguage == LanguageCode.HINDI || activeLanguage == null)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isHindi) ITantraColors.Primary else Color(0xFFF1F5F9),
                            modifier = Modifier.clickable {
                                AppGraph.switchActiveLanguage(LanguageCode.HINDI)
                            }
                        ) {
                            Text(
                                "\u0939\u093F\u0928\u094D\u0926\u0940 (HI)",
                                fontSize = 11.sp,
                                fontWeight = if (isHindi) FontWeight.Bold else FontWeight.Medium,
                                color = if (isHindi) Color.White else ITantraColors.TextHeadline,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        // English Chip
                        val isEnglish = (activeLanguage == LanguageCode.ENGLISH)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isEnglish) ITantraColors.Primary else Color(0xFFF1F5F9),
                            modifier = Modifier.clickable {
                                AppGraph.switchActiveLanguage(LanguageCode.ENGLISH)
                            }
                        ) {
                            Text(
                                "English (EN)",
                                fontSize = 11.sp,
                                fontWeight = if (isEnglish) FontWeight.Bold else FontWeight.Medium,
                                color = if (isEnglish) Color.White else ITantraColors.TextHeadline,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        // More / All Languages button
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFF1F5F9),
                            modifier = Modifier.clickable { onNavigateToLanguagePacks() }
                        ) {
                            Text(
                                "More \u25BE",
                                fontWeight = FontWeight.Medium,
                                color = ITantraColors.Primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // 6. Segmented Toggle Switch (PTT vs VAD)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
                        .padding(3.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isPttMode) Color.White else Color.Transparent)
                            .clickable {
                                if (!isPttMode) {
                                    isPttMode = true
                                    coordinator.setContinuousMode(false)
                                }
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.TouchApp,
                                contentDescription = null,
                                tint = if (isPttMode) ITantraColors.Primary else ITantraColors.TextMuted,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "PUSH-TO-TALK (PTT)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isPttMode) ITantraColors.Primary else ITantraColors.TextMuted
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isPttMode) Color.White else Color.Transparent)
                            .clickable {
                                if (isPttMode) {
                                    isPttMode = false
                                    if (isTransmitting || isPttLocked) {
                                        isTransmitting = false
                                        isPttLocked = false
                                        coordinator.stopActiveRecording()
                                    }
                                    coordinator.setContinuousMode(true)
                                }
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = if (!isPttMode) ITantraColors.StatusSuccess else ITantraColors.TextMuted,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "CONTINUOUS VAD",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (!isPttMode) ITantraColors.TextHeadline else ITantraColors.TextMuted
                            )
                            Spacer(Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (!isPttMode) Color(0xFFDCFCE7) else Color(0xFFCBD5E1),
                                        RoundedCornerShape(3.dp)
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    if (!isPttMode) "ACTIVE" else "AUTO",
                                    fontSize = 7.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (!isPttMode) Color(0xFF15803D) else Color(0xFF475569)
                                )
                            }
                        }
                    }
                }
            }

            // 7. Tactical Push-to-Talk Command Reticle & Dial
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val isRecordingActive = if (isPttMode) (isTransmitting || isPttLocked)
                            else (continuousListenState == ContinuousListenState.SPEECH_DETECTED || continuousListenState == ContinuousListenState.FINALIZING)
                        // Concentric Audio Visualizer Reticle (192dp outer)
                        Box(
                            modifier = Modifier
                                .size(192.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isRecordingActive) Color(0xFFFEE2E2).copy(alpha = 0.6f)
                                    else if (!isPttMode) Color(0xFFF0FDF4).copy(alpha = 0.7f)
                                    else Color(0xFFEFF6FF).copy(alpha = 0.4f)
                                )
                                .border(
                                    1.dp,
                                    if (isRecordingActive) Color(0xFFFECACA) else if (!isPttMode) Color(0xFF86EFAC) else Color(0x99BFDBFE),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            // Mid ring with dashed circular track
                            Box(
                                modifier = Modifier
                                    .size(160.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.7f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawCircle(
                                        color = if (isRecordingActive) Color(0xFFF87171) else if (!isPttMode) Color(0xFF4ADE80) else Color(0xFFCBD5E1),
                                        radius = size.minDimension / 2 - 2.dp.toPx(),
                                        style = Stroke(
                                            width = 1.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f), 0f)
                                        )
                                    )
                                }

                                // PTT Action Button (124dp)
                                Box(
                                    modifier = Modifier
                                        .size(124.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .border(
                                            1.5.dp,
                                            if (isRecordingActive) ITantraColors.StatusDanger else if (!isPttMode) ITantraColors.StatusSuccess else Color(0xFFCBD5E1),
                                            CircleShape
                                        )
                                        .pointerInput(coordinator, isPttMode) {
                                            if (!isPttMode) {
                                                awaitEachGesture {
                                                    val down = awaitFirstDown(requireUnconsumed = false)
                                                    down.consume()
                                                    if (continuousListenState == ContinuousListenState.PAUSED) {
                                                        coordinator.continuousListenEngine.resumeListening()
                                                    } else {
                                                        coordinator.continuousListenEngine.pauseListening()
                                                    }
                                                }
                                            } else {
                                                val lockSlidePx = 64.dp.toPx()
                                                awaitEachGesture {
                                                    val down = awaitFirstDown(requireUnconsumed = false)
                                                    down.consume()

                                                    if (isPttLocked) {
                                                        isPttLocked = false
                                                        isTransmitting = false
                                                        coordinator.stopActiveRecording()
                                                        return@awaitEachGesture
                                                    }

                                                    isTransmitting = true
                                                    coordinator.startRecording()

                                                    var locked = false
                                                    try {
                                                        while (true) {
                                                            val event = awaitPointerEvent()
                                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                            if (!change.pressed) break
                                                            change.consume()
                                                            if (down.position.y - change.position.y >= lockSlidePx) {
                                                                locked = true
                                                                break
                                                            }
                                                        }
                                                    } finally {
                                                        if (locked) {
                                                            isPttLocked = true
                                                        } else {
                                                            isTransmitting = false
                                                            coordinator.stopActiveRecording()
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (isRecordingActive) Color(0xFFFEE2E2)
                                                    else if (!isPttMode) Color(0xFFDCFCE7)
                                                    else ITantraColors.AccentSubtle
                                                )
                                                .border(
                                                    1.dp,
                                                    if (isRecordingActive) Color(0xFFFECACA)
                                                    else if (!isPttMode) Color(0xFFBBF7D0)
                                                    else Color(0xFFDBEAFE),
                                                    CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                if (isRecordingActive) Icons.Filled.GraphicEq
                                                else if (!isPttMode) Icons.Filled.Mic
                                                else Icons.Filled.Mic,
                                                contentDescription = null,
                                                tint = if (isRecordingActive) ITantraColors.StatusDanger
                                                else if (!isPttMode) ITantraColors.StatusSuccess
                                                else ITantraColors.Primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            if (!isPttMode) {
                                                when (continuousListenState) {
                                                    ContinuousListenState.SPEECH_DETECTED -> "VOICE ACTIVE"
                                                    ContinuousListenState.FINALIZING -> "PROCESSING"
                                                    ContinuousListenState.SEGMENT_READY -> "TRANSMITTING"
                                                    ContinuousListenState.PAUSED -> "PAUSED"
                                                    else -> "LISTENING..."
                                                }
                                            }
                                            else if (isPttLocked) "TAP TO SEND"
                                            else if (isTransmitting) "TRANSMITTING"
                                            else "HOLD TO TALK",
                                            fontFamily = FontFamily.SansSerif,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isRecordingActive) ITantraColors.StatusDanger else ITantraColors.TextHeadline,
                                            letterSpacing = 0.5.sp
                                        )
                                        Text(
                                            if (!isPttMode) {
                                                if (continuousListenState == ContinuousListenState.PAUSED) "TAP TO RESUME" else "HANDS-FREE VAD"
                                            }
                                            else if (isPttLocked) "RECORD LOCKED" else "AI TRANSCEIVER",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 7.5.sp,
                                            color = if (isPttLocked) ITantraColors.StatusDanger else ITantraColors.TextMuted,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!isPttMode) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "HANDS-FREE SILERO VAD · PAUSE TO TRANSMIT",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF16A34A),
                            letterSpacing = 0.5.sp
                        )
                    } else if (isTransmitting && !isPttLocked) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "↑ SLIDE UP TO LOCK",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = ITantraColors.TextMuted,
                            letterSpacing = 0.5.sp
                        )
                    }

                    if (isPttLocked) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                isPttLocked = false
                                isTransmitting = false
                                coordinator.stopActiveRecording()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.StatusDanger),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(horizontal = 24.dp)
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("RECORDING LOCKED — TAP TO SEND", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }

            // 8. Emergency Quick Codes Panel (Clean, Fixed Flex Row, No Collision!)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ITantraColors.SurfaceWhite),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFECACA)),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = ITantraColors.StatusDanger,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "EMERGENCY QUICK CODES",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = ITantraColors.StatusDanger,
                                    letterSpacing = 0.3.sp
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFFEF2F2), RoundedCornerShape(4.dp))
                                    .border(1.dp, Color(0xFFFECACA), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "REQUIRES CONFIRMATION DIALOG",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 7.5.sp,
                                    color = ITantraColors.StatusDanger,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Select critical tactical preset code or trigger priority broadcast. Transmission requires operator authorization.",
                            fontSize = 10.sp,
                            color = ITantraColors.TextMuted,
                            lineHeight = 14.sp
                        )
                        Spacer(Modifier.height(10.dp))

                        // 2x2 Grid of Emergency Preset Buttons
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TacticalEmergencyPresetButton(
                                title = "EVACUATE",
                                code = "CODE-E1",
                                icon = Icons.AutoMirrored.Filled.DirectionsRun,
                                iconColor = ITantraColors.StatusDanger,
                                modifier = Modifier.weight(1f)
                            ) {
                                pendingEmergencyCode = "EVACUATION (CODE-E1)"
                                showEmergencyConfirm = true
                            }
                            TacticalEmergencyPresetButton(
                                title = "MEDICAL SOS",
                                code = "CODE-M2",
                                icon = Icons.Filled.MedicalServices,
                                iconColor = ITantraColors.StatusDanger,
                                modifier = Modifier.weight(1f)
                            ) {
                                pendingEmergencyCode = "MEDICAL SOS (CODE-M2)"
                                showEmergencyConfirm = true
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TacticalEmergencyPresetButton(
                                title = "ROUTE BLOCKED",
                                code = "CODE-B3",
                                icon = Icons.Filled.Block,
                                iconColor = ITantraColors.StatusWarning,
                                modifier = Modifier.weight(1f)
                            ) {
                                pendingEmergencyCode = "ROUTE BLOCKED (CODE-B3)"
                                showEmergencyConfirm = true
                            }
                            TacticalEmergencyPresetButton(
                                title = "ASSISTANCE REQ.",
                                code = "CODE-A4",
                                icon = Icons.Filled.Sos,
                                iconColor = ITantraColors.Primary,
                                modifier = Modifier.weight(1f)
                            ) {
                                pendingEmergencyCode = "ASSISTANCE REQUIRED (CODE-A4)"
                                showEmergencyConfirm = true
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Full-width Critical PTT Trigger Button
                        Button(
                            onClick = {
                                pendingEmergencyCode = "CRITICAL PRIORITY BROADCAST"
                                showEmergencyConfirm = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.StatusDanger),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Filled.Campaign, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "TRIGGER CRITICAL PTT BROADCAST",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }

            // 9. Comms Message History Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ITantraColors.SurfaceWhite),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ITantraColors.BorderSubtle),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.History,
                                    contentDescription = null,
                                    tint = ITantraColors.Primary,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "COMMS MESSAGE HISTORY",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp,
                                    color = ITantraColors.TextHeadline,
                                    letterSpacing = 0.3.sp
                                )
                            }
                            Text(
                                "REV-CHRONOLOGICAL",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.5.sp,
                                color = ITantraColors.TextMuted
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        // Filter Chips
                        val filterCounts = remember(allMessages) {
                            mapOf(
                                MessageFilter.ALL to allMessages.size,
                                MessageFilter.FAILED to allMessages.count { it.isFailed },
                                MessageFilter.EMERGENCY to allMessages.count { it.isEmergency }
                            )
                        }
                        MessageFilterChipsRow(
                            selected = selectedFilter,
                            onSelect = { selectedFilter = it },
                            counts = filterCounts
                        )

                        Spacer(Modifier.height(10.dp))

                        // Message Items
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (filteredMessages.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                                        .padding(20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No radio transmissions yet. Hold PTT to transmit.",
                                        fontSize = 11.sp,
                                        color = ITantraColors.TextMuted,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            } else {
                                filteredMessages.forEach { msg ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(ITantraColors.CanvasBg, RoundedCornerShape(8.dp))
                                            .border(
                                                1.dp,
                                                if (msg.isEmergency) Color(0xFFFECACA)
                                                else if (msg.isFailed) Color(0xFFFED7AA)
                                                else ITantraColors.BorderSubtle,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f, fill = false),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    msg.sender,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    color = if (msg.isEmergency) ITantraColors.StatusDanger else ITantraColors.TextHeadline,
                                                    maxLines = 1
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .background(
                                                            if (msg.isEmergency) Color(0xFFFEE2E2)
                                                            else if (msg.isFailed) Color(0xFFFEF3C7)
                                                            else Color(0xFFEFF6FF),
                                                            RoundedCornerShape(3.dp)
                                                        )
                                                        .border(
                                                            1.dp,
                                                            if (msg.isEmergency) Color(0xFFFECACA)
                                                            else if (msg.isFailed) Color(0xFFFDE68A)
                                                            else Color(0xFFBFDBFE),
                                                            RoundedCornerShape(3.dp)
                                                        )
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        msg.langPair,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (msg.isEmergency) ITantraColors.StatusDanger
                                                        else if (msg.isFailed) ITantraColors.StatusWarning
                                                        else ITantraColors.Primary
                                                    )
                                                }
                                            }
                                            Text(
                                                msg.time,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp,
                                                color = ITantraColors.TextMuted,
                                                softWrap = false,
                                                maxLines = 1,
                                                modifier = Modifier.padding(start = 8.dp)
                                            )
                                        }

                                        // The translation-scope note is a standing fact about
                                        // this build, not a per-message failure - it's shown
                                        // once, calmly, near the top of the screen instead (see
                                        // TranslationScopeBanner) rather than as a red error
                                        // repeated on every single message card.
                                        if (msg.isVoiceNote && msg.statusDetail != null) {
                                            Spacer(Modifier.height(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0xFFF1F5F9), RoundedCornerShape(3.dp))
                                                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(3.dp))
                                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    msg.statusDetail.uppercase(),
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 7.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ITantraColors.TextMuted
                                                )
                                            }
                                        }
                                        if (msg.statusDetail != null && !msg.isTranslationScopeNote && !msg.isVoiceNote) {
                                            Spacer(Modifier.height(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0xFFFEE2E2), RoundedCornerShape(3.dp))
                                                    .border(1.dp, Color(0xFFFECACA), RoundedCornerShape(3.dp))
                                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    msg.statusDetail.uppercase(),
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 7.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ITantraColors.StatusDanger
                                                )
                                            }
                                        }

                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "“${msg.text}”",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = ITantraColors.TextHeadline,
                                            lineHeight = 16.sp
                                        )

                                        if (!msg.translation.isNullOrBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Row(verticalAlignment = Alignment.Top) {
                                                Text(
                                                    "EN: ",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ITantraColors.Primary
                                                )
                                                Text(
                                                    msg.translation,
                                                    fontSize = 11.5.sp,
                                                    color = ITantraColors.TextBody,
                                                    lineHeight = 16.sp
                                                )
                                            }
                                        }

                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Filled.PlayArrow,
                                                contentDescription = null,
                                                tint = ITantraColors.TextMuted,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(Modifier.width(3.dp))
                                            Text(
                                                msg.duration,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 8.5.sp,
                                                color = ITantraColors.TextMuted
                                            )
                                        }

                                        if (msg.isFailed) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                val failureLabel = msg.statusDetail ?: "Transmission Failed"
                                                Text(
                                                    failureLabel,
                                                    fontSize = 8.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ITantraColors.StatusDanger
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                        .clickable { }
                                                ) {
                                                    Text(
                                                        "↻ Retry",
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = ITantraColors.TextHeadline
                                                    )
                                                }
                                            }
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Filled.DoneAll,
                                                    contentDescription = null,
                                                    tint = ITantraColors.StatusSuccess,
                                                    modifier = Modifier.size(11.dp)
                                                )
                                                Spacer(Modifier.width(2.dp))
                                                Text(
                                                    msg.state,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 8.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ITantraColors.StatusSuccess
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEmergencyConfirm) {
        Dialog(onDismissRequest = { showEmergencyConfirm = false }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ITantraColors.SurfaceWhite),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCA5A5)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFEE2E2)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = ITantraColors.StatusDanger,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                "Confirm Emergency Broadcast",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = ITantraColors.TextHeadline
                            )
                            Text(
                                "PRIORITY OVERRIDE TRANSMISSION",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                color = ITantraColors.StatusDanger
                            )
                        }
                    }

                    Text(
                        "Emergency Priority Broadcast: Are you sure you want to transmit on active radio channel? (Preset: ${pendingEmergencyCode ?: "EVACUATE"})",
                        fontSize = 12.sp,
                        color = ITantraColors.TextBody,
                        lineHeight = 17.sp
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFFBEB), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFFFDE68A), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = Color(0xFFB45309),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "This priority transmission interrupts active non-critical voice streams across connected peers.",
                                fontSize = 10.sp,
                                color = Color(0xFF78350F),
                                lineHeight = 14.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showEmergencyConfirm = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Cancel", color = ITantraColors.TextHeadline, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = {
                                val emergencyCode = when {
                                    pendingEmergencyCode?.contains("EVACUATE") == true || pendingEmergencyCode?.contains("CODE-E1") == true -> EmergencyCode.EVACUATE
                                    pendingEmergencyCode?.contains("MEDICAL") == true || pendingEmergencyCode?.contains("CODE-M2") == true -> EmergencyCode.MEDICAL_EMERGENCY
                                    pendingEmergencyCode?.contains("ROUTE") == true || pendingEmergencyCode?.contains("CODE-B3") == true -> EmergencyCode.ROAD_BLOCKED
                                    else -> EmergencyCode.HELP_REQUIRED
                                }
                                coordinator.sendEmergencyCode(emergencyCode)
                                pendingEmergencyCode?.let { onSendEmergency(it) }
                                showEmergencyConfirm = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.StatusDanger),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Authorize & Send", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
}

/**
 * Standing, calm notice that translation isn't included in this build. Deliberately styled
 * neutral (TextMuted, outlined) rather than a warning/error color - this is a stated scope
 * decision, not something wrong with the app. Shown once at the top of the hub instead of
 * repeated as a red badge on every message.
 */
@Composable
private fun TranslationScopeBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ITantraColors.CanvasBg)
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            tint = ITantraColors.TextMuted,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "Voice transcripts only \u2014 translation not included in this build",
            fontSize = 9.5.sp,
            color = ITantraColors.TextMuted,
        )
    }
}

@Composable
private fun SpokeCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = ITantraColors.SurfaceWhite),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ITantraColors.BorderSubtle),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ITantraColors.AccentSubtle),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = ITantraColors.Primary, modifier = Modifier.size(15.dp))
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ITantraColors.TextMuted,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = ITantraColors.TextHeadline, maxLines = 1)
            Text(subtitle, fontSize = 8.5.sp, color = ITantraColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TacticalEmergencyPresetButton(
    title: String,
    code: String,
    icon: ImageVector,
    iconColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF8FAFC))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp,
                    color = ITantraColors.TextHeadline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                color = ITantraColors.TextMuted
            )
        }
    }
}
