package com.example.itantra.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.ui.theme.ITantraColors
import com.example.itantra.data.messages.belongsToConversation
import com.example.itantra.ui.screens.messages.conversationMatchesActivePeer
import com.itantra.app.AppGraph
import com.itantra.core.transceiver.TransceiverCoordinator
import com.itantra.core.crypto.SecureSessionState
import com.itantra.domain.model.LanguageCatalog
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.MessageSource
import com.itantra.domain.model.MessageState
import com.itantra.domain.model.PeerProfile
import com.itantra.domain.model.TransceiverMessage
import kotlinx.coroutines.launch

/**
 * Dedicated 1-on-1 Chat Screen for an isolated Bluetooth peer.
 * Groups messages by date with WhatsApp-style date separators,
 * displays 12-hour timestamps and delivery state indicators,
 * supports typed text messages and press-to-talk voice messages via offline speech recognition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DedicatedChatScreen(
    peerProfile: PeerProfile?,
    peerId: String,
    coordinator: TransceiverCoordinator,
    onBack: () -> Unit,
    onReconnect: () -> Unit = {},
) {
    var showLanguagePicker by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val allMessages by coordinator.messages.collectAsState()
    val activePeerProfile by coordinator.activePeerProfile.collectAsState()
    val secureSessionState by coordinator.secureSessionManager.state.collectAsState()
    val matchingActivePeer = activePeerProfile?.takeIf { active ->
        conversationMatchesActivePeer(peerId, peerProfile?.deviceId, active.deviceId)
    }
    val conversationIds = setOf(peerId, peerProfile?.deviceId.orEmpty(), peerProfile?.bluetoothAddress.orEmpty(), matchingActivePeer?.deviceId.orEmpty())
        .filter { it.isNotBlank() }

    // Isolated messages for this peer only
    val chatMessages = remember(allMessages, conversationIds) {
        allMessages.filter { msg ->
            conversationIds.any { msg.belongsToConversation(it) }
        }
    }

    var inputText by remember { mutableStateOf("") }
    val inputTooLong = inputText.toByteArray(Charsets.UTF_8).size > com.itantra.core.transport.packet.PacketEncoder.MAX_PLAINTEXT_BYTES
    var isRecording by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new message arrives
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    // Inform coordinator of active conversation peer
    DisposableEffect(peerId) {
        coordinator.setActiveConversation(peerId)
        onDispose {
            coordinator.setActiveConversation(null)
        }
    }

    val effectivePeerName = matchingActivePeer?.displayName?.takeIf { it.isNotBlank() }
        ?: peerProfile?.displayName?.takeIf { it.isNotBlank() }
        ?: "iTantra Peer"

    val effectiveDeviceId = matchingActivePeer?.deviceId
        ?: peerProfile?.deviceId
        ?: "iTantra ID pending"

    val isConnected = matchingActivePeer?.isConnected == true && secureSessionState == SecureSessionState.SECURE_VERIFIED

    // Observe receive language to reflect current setting
    val receiveLanguage by AppGraph.languagePackRepository.observeReceiveLanguage().collectAsState(initial = null)

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            coordinator.sendLocationMessage(targetPeerId = peerId)
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Location permission required to share GPS coordinates")
            }
        }
    }

    val onShareLocation = {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            coordinator.sendLocationMessage(targetPeerId = peerId)
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Scaffold(
        containerColor = ITantraColors.CanvasBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = effectivePeerName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = ITantraColors.TextHeadline
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (isConnected) ITantraColors.StatusSuccess else ITantraColors.TextMuted,
                                        CircleShape
                                    )
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = effectiveDeviceId,
                                style = MaterialTheme.typography.labelSmall,
                                color = ITantraColors.Primary,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isConnected) "Connected" else "Offline · reconnect to send",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isConnected) ITantraColors.StatusSuccess else ITantraColors.TextMuted
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to devices",
                            tint = ITantraColors.TextHeadline
                        )
                    }
                },
                actions = {
                    if (!isConnected) {
                        TextButton(onClick = onReconnect) { Text("Reconnect") }
                    }
                    // "Receive in" language pill — tap to change
                    ReceiveLanguageChip(
                        activeLanguage = receiveLanguage,
                        onClick = { showLanguagePicker = true }
                    )
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ITantraColors.SurfaceWhite)
            )
        },
        bottomBar = {
            Surface(
                color = ITantraColors.SurfaceWhite,
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (isRecording) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ITantraColors.ErrorContainer, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(ITantraColors.StatusDanger, CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Recording voice… Release to send",
                                style = MaterialTheme.typography.bodySmall,
                                color = ITantraColors.OnErrorContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // PTT Microphone Button (Hold or tap to record)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (isRecording) ITantraColors.StatusDanger else ITantraColors.AccentSubtle)
                                .then(if (isConnected) Modifier.pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            isRecording = true
                                            coordinator.startRecording()
                                            tryAwaitRelease()
                                            isRecording = false
                                            coordinator.stopActiveRecording()
                                        }
                                    )
                                } else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = "Hold to speak",
                                tint = if (isRecording) ITantraColors.OnError else ITantraColors.Primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        // GPS Location Sharing Button
                        IconButton(
                            onClick = onShareLocation,
                            enabled = isConnected,
                            modifier = Modifier
                                .size(44.dp)
                                .background(ITantraColors.AccentSubtle, CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.LocationOn,
                                contentDescription = "Share GPS Location",
                                tint = ITantraColors.Primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        // Message Text Field
                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            isError = inputTooLong,
                            supportingText = if (inputTooLong) ({ Text("Message is too long. Shorten it before sending.") }) else null,
                            placeholder = {
                                Text(
                                    "Type message...",
                                    color = ITantraColors.TextMuted,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            modifier = Modifier.weight(1f),
                            maxLines = 4,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = ITantraColors.CanvasBg,
                                unfocusedContainerColor = ITantraColors.CanvasBg,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = ITantraColors.TextHeadline,
                                unfocusedTextColor = ITantraColors.TextBody
                            ),
                            shape = RoundedCornerShape(24.dp)
                        )

                        Spacer(Modifier.width(8.dp))

                        // Send Button
                        IconButton(
                            onClick = {
                                val text = inputText.trim()
                                if (text.isNotBlank() && isConnected && !inputTooLong) {
                                    coordinator.sendTextMessage(text, targetPeerId = peerId)
                                    inputText = ""
                                }
                            },
                            enabled = inputText.isNotBlank() && isConnected && !inputTooLong,
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    if (inputText.isNotBlank() && isConnected && !inputTooLong) ITantraColors.Primary else ITantraColors.BorderSubtle,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (inputText.isNotBlank() && isConnected && !inputTooLong) ITantraColors.OnPrimary else ITantraColors.TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (chatMessages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        "No messages yet with $effectivePeerName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ITantraColors.TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (isConnected) "Type a text message or hold the mic icon to transmit voice." else "Reconnect this device to send a message.",
                        style = MaterialTheme.typography.labelSmall,
                        color = ITantraColors.TextMuted
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Group messages by date with WhatsApp-style date separators
                var lastDateLabel = ""
                chatMessages.forEachIndexed { index, message ->
                    val dateLabel = DateUtils.getDateSeparatorLabel(message.createdAtLocal)
                    if (dateLabel != lastDateLabel) {
                        lastDateLabel = dateLabel
                        item(key = "date_header_${message.createdAtLocal}_$index") {
                            DateSeparatorHeader(label = dateLabel)
                        }
                    }

                    item(key = "msg_${message.messageId}") {
                        MessageBubble(message = message)
                    }
                }
            }
        }
    }

    // Receive-language bottom sheet
    if (showLanguagePicker) {
        LanguagePickerBottomSheet(
            currentLanguage = receiveLanguage,
            onLanguageSelected = { code ->
                AppGraph.setReceiveLanguage(code)
                showLanguagePicker = false
            },
            onDismiss = { showLanguagePicker = false }
        )
    }
}

/**
 * Small pill in the top bar showing the current receive language.
 * Tapping it opens the language picker.
 */
@Composable
private fun ReceiveLanguageChip(
    activeLanguage: LanguageCode?,
    onClick: () -> Unit
) {
    val label = activeLanguage?.let { LanguageCatalog.byCode(it).nativeDisplayName } ?: "Any"
    val wireCode = activeLanguage?.wireCode?.uppercase() ?: "AUTO"

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = ITantraColors.AccentSubtle,
        border = androidx.compose.foundation.BorderStroke(1.dp, ITantraColors.Primary.copy(alpha = 0.3f)),
        modifier = Modifier.height(32.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Filled.Language,
                contentDescription = "Receive language",
                tint = ITantraColors.Primary,
                modifier = Modifier.size(14.dp)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = ITantraColors.Primary,
                    fontSize = 10.sp
                )
                Text(
                    text = wireCode,
                    style = MaterialTheme.typography.labelSmall,
                    color = ITantraColors.TextMuted,
                    fontSize = 8.sp
                )
            }
        }
    }
}

/**
 * Bottom sheet showing all 10 supported languages for incoming message reception.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePickerBottomSheet(
    currentLanguage: LanguageCode?,
    onLanguageSelected: (LanguageCode) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ITantraColors.SurfaceWhite,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Icon(
                    Icons.Filled.Language,
                    contentDescription = null,
                    tint = ITantraColors.Primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Receive Messages In",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ITantraColors.TextHeadline
                )
            }
            Text(
                "Choose the language in which you want to receive and read the peer's messages.",
                style = MaterialTheme.typography.bodySmall,
                color = ITantraColors.TextMuted,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Language list
            LanguageCatalog.all.forEach { language ->
                val isSelected = language.code == currentLanguage
                Surface(
                    onClick = { onLanguageSelected(language.code) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) ITantraColors.Primary.copy(alpha = 0.08f) else Color.Transparent,
                    border = if (isSelected)
                        androidx.compose.foundation.BorderStroke(1.5.dp, ITantraColors.Primary)
                    else
                        androidx.compose.foundation.BorderStroke(1.dp, ITantraColors.BorderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Language code badge
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isSelected) ITantraColors.Primary else ITantraColors.AccentSubtle,
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = language.code.wireCode.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) ITantraColors.OnPrimary else ITantraColors.Primary,
                                fontSize = 10.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = language.nativeDisplayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) ITantraColors.Primary else ITantraColors.TextHeadline
                            )
                            Text(
                                text = language.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = ITantraColors.TextMuted
                            )
                        }
                        if (isSelected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = ITantraColors.Primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * WhatsApp-style Date Separator Header (e.g., TODAY, YESTERDAY, 21 September 2026)
 */
@Composable
fun DateSeparatorHeader(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = ITantraColors.BorderSubtle.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ITantraColors.TextMuted,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Incoming or Outgoing Message Bubble with 12-hour timestamp and delivery status.
 */
@Composable
fun MessageBubble(message: TransceiverMessage) {
    if (message.isLocationMessage) {
        LocationMessageBubble(message = message, isOutgoing = message.source == MessageSource.LOCAL)
        return
    }

    val isOutgoing = message.source == MessageSource.LOCAL
    val alignment = if (isOutgoing) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = if (isOutgoing) ITantraColors.AccentSubtle else ITantraColors.SurfaceWhite,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isOutgoing) 16.dp else 2.dp,
                bottomEnd = if (isOutgoing) 2.dp else 16.dp
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, ITantraColors.BorderSubtle),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Voice Message Badge if generated via STT
                if (message.isVoiceGenerated) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Voice STT",
                            tint = ITantraColors.Primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "VOICE TRANSCRIPT (${message.language?.wireCode?.uppercase() ?: "EN"})",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = ITantraColors.Primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Message Text
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ITantraColors.TextHeadline
                )
                com.example.itantra.ui.components.SavedSpeechButton("message-${message.messageId}",
                    { com.itantra.app.AppGraph.transceiverCoordinator.replayMessage(message.messageId) },
                    enabled = message.text.isNotBlank() && message.displayedTextLanguage != null &&
                        message.state !in listOf(MessageState.RECORDING, MessageState.STT_PROCESSING))

                Spacer(Modifier.height(4.dp))

                // Footer: Timestamp & Delivery Status
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = DateUtils.formatTime12Hour(message.createdAtLocal),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = ITantraColors.TextMuted
                    )

                    if (isOutgoing) {
                        Spacer(Modifier.width(4.dp))
                        DeliveryStatusIcon(state = message.state)
                    }
                }
            }
        }
    }
}

/**
 * Delivery status icon showing progress, sent, delivered, or error.
 */
@Composable
fun DeliveryStatusIcon(state: MessageState) {
    when (state) {
        MessageState.TRANSMITTING, MessageState.WAITING_ACK, MessageState.PACKET_ENCODING -> {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = "Pending",
                tint = ITantraColors.TextMuted,
                modifier = Modifier.size(12.dp)
            )
        }
        MessageState.SENT -> {
            Icon(
                Icons.Filled.Done,
                contentDescription = "Sent",
                tint = ITantraColors.TextMuted,
                modifier = Modifier.size(12.dp)
            )
        }
        MessageState.DELIVERED, MessageState.ACKNOWLEDGED,
        MessageState.REMOTE_PLAYING, MessageState.REMOTE_PLAYBACK_CONFIRMED -> {
            Icon(
                Icons.Filled.DoneAll,
                contentDescription = "Delivered",
                tint = ITantraColors.Primary,
                modifier = Modifier.size(14.dp)
            )
        }
        MessageState.ERROR -> {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = "Failed",
                tint = ITantraColors.StatusDanger,
                modifier = Modifier.size(12.dp)
            )
        }
        else -> {}
    }
}

/**
 * Distinct bubble for GPS location sharing messages.
 * Displays coordinates (e.g. "12.34567, 76.54321 ±4.5m"), selectable coordinates,
 * the time the fix was taken, and how old it is ("2 min ago").
 * Provides "Open in Maps" (geo: intent) falling back to "Copy coordinates",
 * plus a direct copy action. Fully offline.
 */
@Composable
fun LocationMessageBubble(message: TransceiverMessage, isOutgoing: Boolean) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val alignment = if (isOutgoing) Alignment.End else Alignment.Start

    // Coordinates come only from the fix, never locale-dependent display text.
    val lat = message.latitude ?: return
    val lon = message.longitude ?: return
    val mapUri = locationMapUri(message) ?: return
    val acc = message.accuracyMeters ?: 0.0f
    val fixTime = message.locationTimestampMillis ?: message.createdAtLocal

    val coordinatesDisplay = "${"%.5f".format(java.util.Locale.US, lat)}, ${"%.5f".format(java.util.Locale.US, lon)} ±${"%.1f".format(java.util.Locale.US, acc)}m"
    val plainCoords = "$lat, $lon"
    val relativeAge = DateUtils.formatRelativeAge(fixTime)
    val fixTimeStr = DateUtils.formatTime12Hour(fixTime)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = if (isOutgoing) ITantraColors.AccentSubtle else ITantraColors.SurfaceWhite,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isOutgoing) 16.dp else 2.dp,
                bottomEnd = if (isOutgoing) 2.dp else 16.dp
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, ITantraColors.BorderSubtle),
            modifier = Modifier.widthIn(min = 240.dp, max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                // Location Header Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Icon(
                        Icons.Filled.LocationOn,
                        contentDescription = "GPS Location",
                        tint = ITantraColors.Primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isOutgoing) "GPS LOCATION SHARED" else "PEER GPS LOCATION",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = ITantraColors.Primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Selectable Coordinates
                SelectionContainer {
                    Text(
                        text = coordinatesDisplay,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ITantraColors.TextHeadline
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Fix Time & Relative Age / Time unverified label
                val bubbleTimeText = DateUtils.formatLocationBubbleTime(
                    timestampMillis = fixTime,
                    isTimeUnverified = message.isTimeUnverified
                )
                Text(
                    text = bubbleTimeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = if (message.isTimeUnverified) ITantraColors.StatusWarning else ITantraColors.TextMuted,
                    fontWeight = if (message.isTimeUnverified) FontWeight.Medium else FontWeight.Normal
                )

                Spacer(Modifier.height(8.dp))

                // Action Buttons: Open in Maps & Copy Coordinates
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = {
                            val uri = Uri.parse(mapUri)
                            val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            try {
                                context.startActivity(mapIntent)
                            } catch (e: ActivityNotFoundException) {
                                clipboardManager.setText(AnnotatedString(plainCoords))
                                Toast.makeText(
                                    context,
                                    "No map app installed. Coordinates copied to clipboard.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                clipboardManager.setText(AnnotatedString(plainCoords))
                                Toast.makeText(
                                    context,
                                    "Could not open map. Coordinates copied to clipboard.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = ITantraColors.Primary.copy(alpha = 0.12f),
                            contentColor = ITantraColors.Primary
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Icon(
                            Icons.Filled.Map,
                            contentDescription = "Open in Maps",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Open in Maps",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(plainCoords))
                            Toast.makeText(
                                context,
                                "Coordinates copied: $plainCoords",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = ITantraColors.TextHeadline
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ITantraColors.BorderSubtle),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy Coordinates",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Footer: Message Timestamp & Delivery Status
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = DateUtils.formatTime12Hour(message.createdAtLocal),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = ITantraColors.TextMuted
                    )

                    if (isOutgoing) {
                        Spacer(Modifier.width(4.dp))
                        DeliveryStatusIcon(state = message.state)
                    }
                }
            }
        }
    }
}

internal fun locationMapUri(message: TransceiverMessage): String? {
    val lat = message.latitude ?: return null
    val lon = message.longitude ?: return null
    return "geo:$lat,$lon?q=$lat,$lon"
}
