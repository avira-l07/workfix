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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.ui.theme.ITantraColors
import com.itantra.core.transceiver.TransceiverCoordinator
import com.itantra.domain.model.MessageSource
import com.itantra.domain.model.MessageState
import com.itantra.domain.model.PeerProfile
import com.itantra.domain.model.TransceiverMessage
import kotlinx.coroutines.launch

/**
 * Dedicated 1-on-1 Chat Screen for an isolated Bluetooth peer.
 * Groups messages by date with WhatsApp-style date separators,
 * displays 12-hour timestamps and delivery state indicators,
 * supports typed text messages and press-to-talk voice messages via Whisper STT.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DedicatedChatScreen(
    peerProfile: PeerProfile?,
    peerId: String,
    coordinator: TransceiverCoordinator,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val allMessages by coordinator.messages.collectAsState()
    val activePeerProfile by coordinator.activePeerProfile.collectAsState()

    // Isolated messages for this peer only
    val chatMessages = remember(allMessages, peerId) {
        allMessages.filter { msg ->
            msg.peerId == peerId || (peerId.isNotBlank() && (msg.senderDeviceId == peerId || msg.receiverDeviceId == peerId))
        }
    }

    var inputText by remember { mutableStateOf("") }
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

    val effectivePeerName = activePeerProfile?.displayName?.takeIf { it.isNotBlank() }
        ?: peerProfile?.displayName?.takeIf { it.isNotBlank() }
        ?: "iTantra Peer"

    val effectiveDeviceId = activePeerProfile?.deviceId
        ?: peerProfile?.deviceId
        ?: "iTantra ID pending"

    val isConnected = activePeerProfile?.isConnected == true || peerProfile?.isConnected == true

    Scaffold(
        containerColor = ITantraColors.CanvasBg,
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
                                text = if (isConnected) "Connected" else "Not Connected",
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
                                "Recording voice (Whisper STT)... Release to send",
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
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            isRecording = true
                                            coordinator.startRecording()
                                            tryAwaitRelease()
                                            isRecording = false
                                            coordinator.stopActiveRecording()
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = "Hold to speak",
                                tint = if (isRecording) Color.White else ITantraColors.Primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        // Message Text Field
                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
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
                                if (text.isNotBlank()) {
                                    coordinator.sendTextMessage(text, targetPeerId = peerId)
                                    inputText = ""
                                }
                            },
                            enabled = inputText.isNotBlank(),
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    if (inputText.isNotBlank()) ITantraColors.Primary else ITantraColors.BorderSubtle,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (inputText.isNotBlank()) Color.White else ITantraColors.TextMuted,
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
                        "Type a text message or hold the mic icon to transmit voice.",
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
