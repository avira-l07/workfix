package com.example.itantra.ui.screens.messages

import com.itantra.data.db.MessageEntity
import com.itantra.data.db.PeerEntity
import com.itantra.domain.model.BROADCAST_PEER_ID

data class ConversationSummary(
    val peerId: String,
    val displayName: String,
    val transportAddress: String,
    val transportName: String,
    val lastMessage: String?,
    val lastActivityMillis: Long,
    val messageCount: Int,
    val connected: Boolean,
)

fun conversationMatchesActivePeer(peerId: String, savedDeviceId: String?, activeDeviceId: String?): Boolean =
    !activeDeviceId.isNullOrBlank() &&
        (peerId == activeDeviceId || (!savedDeviceId.isNullOrBlank() && savedDeviceId == activeDeviceId))

/** Combine verified peers and saved chats without losing legacy address-keyed messages. */
fun buildConversationSummaries(
    peers: List<PeerEntity>,
    messages: List<MessageEntity>,
    connectedDeviceId: String?,
): List<ConversationSummary> {
    val byId = peers.associateBy { it.deviceId }
    val byAddress = peers.filter { it.transportAddress.isNotBlank() }.associateBy { it.transportAddress }
    val grouped = linkedMapOf<String, MutableList<MessageEntity>>()

    messages.forEach { message ->
        if (message.peerId == BROADCAST_PEER_ID) return@forEach
        val rawId = message.peerId.takeUnless { it.isBlank() || it == BROADCAST_PEER_ID }
            ?: if (message.sourceName == "REMOTE") message.senderDeviceId else message.receiverDeviceId
        if (rawId.isBlank() || rawId == BROADCAST_PEER_ID) return@forEach
        val senderIdentity = message.senderDeviceId.takeIf { message.sourceName == "REMOTE" }?.let(byId::get)
        val receiverIdentity = message.receiverDeviceId.takeIf { message.sourceName == "LOCAL" }?.let(byId::get)
        val key = (byId[rawId] ?: byAddress[rawId] ?: senderIdentity ?: receiverIdentity)?.deviceId ?: rawId
        grouped.getOrPut(key) { mutableListOf() }.add(message)
    }

    return (peers.map { it.deviceId } + grouped.keys).distinct().map { key ->
        val peer = byId[key]
        val history = grouped[key].orEmpty()
        val last = history.maxWithOrNull(compareBy<MessageEntity> { it.createdAtLocal }.thenBy { it.messageId })
        ConversationSummary(
            peerId = key,
            displayName = peer?.displayName?.takeIf { it.isNotBlank() } ?: "Device ${key.takeLast(8)}",
            transportAddress = peer?.transportAddress.orEmpty(),
            transportName = peer?.transportName ?: "Previous device",
            lastMessage = last?.let { if (it.isLocation) "Shared location" else it.text.ifBlank { "Voice message" } },
            lastActivityMillis = maxOf(peer?.lastSeenMillis ?: 0L, last?.createdAtLocal ?: 0L),
            messageCount = history.size,
            connected = connectedDeviceId != null && key == connectedDeviceId,
        )
    }.sortedWith(compareByDescending<ConversationSummary> { it.connected }.thenByDescending { it.lastActivityMillis })
}
