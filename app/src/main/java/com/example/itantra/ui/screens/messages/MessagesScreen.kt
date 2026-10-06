package com.example.itantra.ui.screens.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.itantra.ui.theme.ITantraColors
import java.text.DateFormat
import java.util.Date

@Composable
fun MessagesScreen(
    conversations: List<ConversationSummary>,
    onOpenConversation: (ConversationSummary) -> Unit,
    onFindDevices: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(ITantraColors.CanvasBg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Your conversations.", style = MaterialTheme.typography.headlineLarge, color = ITantraColors.TextHeadline)
            Text(
                "Your previous devices and saved conversations.",
                style = MaterialTheme.typography.bodyMedium,
                color = ITantraColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Conversations", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onFindDevices) {
                    Icon(Icons.Filled.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Find devices")
                }
            }
            Text(
                "Open a saved chat. Reconnect its device to send a message.",
                style = MaterialTheme.typography.bodySmall,
                color = ITantraColors.TextMuted,
            )
        }
        if (conversations.isEmpty()) {
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
                        Box(
                            modifier = Modifier.size(72.dp).background(ITantraColors.AccentSubtle, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, tint = ITantraColors.Primary, modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("No conversations yet", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Text(
                            "Connect and verify a nearby device. Its chat stays here after you disconnect.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ITantraColors.TextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                        )
                        Button(onClick = onFindDevices, shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Filled.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Find devices")
                        }
                        TextButton(onClick = onBack) { Text("Open Talk") }
                    }
                }
            }
        }
        items(conversations, key = { it.peerId }) { conversation ->
            ConversationRow(conversation, onClick = { onOpenConversation(conversation) })
        }
    }
}

@Composable
private fun ConversationRow(conversation: ConversationSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        colors = CardDefaults.cardColors(containerColor = ITantraColors.SurfaceWhite),
        border = BorderStroke(1.dp, if (conversation.connected) ITantraColors.StatusSuccess.copy(alpha = .4f) else ITantraColors.BorderSubtle),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).background(ITantraColors.AccentSubtle, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(conversation.displayName.take(2).uppercase(), color = ITantraColors.Primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(conversation.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    conversation.lastMessage ?: "No messages yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ITantraColors.TextBody,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (conversation.connected) {
                    Surface(color = ITantraColors.SuccessContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Text("Connected · verified", style = MaterialTheme.typography.labelSmall, color = ITantraColors.OnSuccessContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
                Text(
                    "${conversation.transportName} · ${if (conversation.lastActivityMillis > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(conversation.lastActivityMillis)) else "Previously connected"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ITantraColors.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ITantraColors.TextMuted)
        }
    }
}
