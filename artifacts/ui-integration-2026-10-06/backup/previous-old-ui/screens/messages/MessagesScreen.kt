package com.example.itantra.ui.screens.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.itantra.ui.theme.ITantraColors
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
fun MessagesScreen(
    conversations: List<ConversationSummary>,
    onOpenConversation: (ConversationSummary) -> Unit,
    onFindDevices: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = ITantraColors.CanvasBg,
        topBar = {
            TopAppBar(
                title = { Text("Messages", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to talk")
                    }
                },
                actions = {
                    IconButton(onClick = onFindDevices) {
                        Icon(Icons.Filled.AddLink, contentDescription = "Find devices")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ITantraColors.SurfaceWhite),
            )
        },
    ) { padding ->
        if (conversations.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, tint = ITantraColors.Primary, modifier = Modifier.size(44.dp))
                Text("No conversations yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text("Connect and verify a nearby device. Its chat will appear here even after you disconnect.", color = ITantraColors.TextMuted, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
                Button(onClick = onFindDevices) { Text("Find devices") }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("Previous devices", style = MaterialTheme.typography.titleSmall, color = ITantraColors.TextHeadline)
                    Text("Tap a chat to read its saved messages. Reconnect to send.", style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
                }
                items(conversations, key = { it.peerId }) { conversation ->
                    ConversationRow(conversation, onClick = { onOpenConversation(conversation) })
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ConversationRow(conversation: ConversationSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = ITantraColors.SurfaceWhite),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).background(ITantraColors.AccentSubtle, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(conversation.displayName.take(2).uppercase(), color = ITantraColors.Primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(conversation.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (conversation.connected) {
                        Text("Connected", style = MaterialTheme.typography.labelSmall, color = ITantraColors.StatusSuccess)
                    }
                }
                Text(
                    conversation.lastMessage ?: "No messages yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ITantraColors.TextBody,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${conversation.transportName} · ${if (conversation.lastActivityMillis > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(conversation.lastActivityMillis)) else "Previously connected"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ITantraColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ITantraColors.TextMuted)
        }
    }
}
