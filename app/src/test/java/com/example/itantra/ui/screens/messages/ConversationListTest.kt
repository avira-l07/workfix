package com.example.itantra.ui.screens.messages

import com.itantra.data.db.MessageEntity
import com.itantra.data.db.PeerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationListTest {
    private fun message(id: Long, peerId: String, text: String, time: Long) = MessageEntity(
        messageId = id,
        languageWireCode = "en",
        targetLanguageWireCode = "en",
        priority = 0,
        text = text,
        originalText = null,
        translationStatusName = "NONE",
        sourceName = "LOCAL",
        createdAtLocal = time,
        stateName = "DELIVERED",
        peerId = peerId,
    )

    @Test
    fun verifiedPeerAndLegacyAddressMessagesBecomeOneChat() {
        val peers = listOf(
            PeerEntity("IT-A", "Aarav", "AA:BB", "Bluetooth", 100L),
            PeerEntity("IT-B", "Mira", lastSeenMillis = 80L),
        )
        val messages = listOf(
            message(1, "AA:BB", "Earlier", 120L),
            message(2, "IT-A", "Latest", 140L),
            message(3, "BROADCAST", "Emergency", 150L),
        )

        val list = buildConversationSummaries(peers, messages, connectedDeviceId = "IT-A")

        assertEquals(2, list.size)
        assertEquals("IT-A", list[0].peerId)
        assertEquals("Aarav", list[0].displayName)
        assertEquals("Latest", list[0].lastMessage)
        assertEquals(2, list[0].messageCount)
        assertTrue(list[0].connected)
        assertEquals("IT-B", list[1].peerId)
        assertEquals(0, list[1].messageCount)
        assertFalse(list[1].connected)
    }

    @Test
    fun unmatchedPreviousMessageRemainsAccessibleAsItsOwnChat() {
        val list = buildConversationSummaries(emptyList(), listOf(message(4, "CC:DD", "Saved text", 200L)), null)
        assertEquals(1, list.size)
        assertEquals("CC:DD", list.single().peerId)
        assertEquals("Saved text", list.single().lastMessage)
    }

    @Test
    fun stableSenderIdAssociatesOldWifiAddressWithVerifiedPeer() {
        val peer = PeerEntity("IT-A", "Aarav", transportAddress = "NEW-ADDRESS", lastSeenMillis = 300L)
        val old = message(5, "OLD-ADDRESS", "Received earlier", 200L).copy(
            sourceName = "REMOTE",
            senderDeviceId = "IT-A",
        )
        val list = buildConversationSummaries(listOf(peer), listOf(old), null)
        assertEquals(1, list.size)
        assertEquals("IT-A", list.single().peerId)
        assertEquals("Received earlier", list.single().lastMessage)
    }

    @Test
    fun previousChatDoesNotInheritAnotherPeersLiveConnection() {
        assertFalse(conversationMatchesActivePeer("IT-OLD", "IT-OLD", "IT-NEW"))
        assertTrue(conversationMatchesActivePeer("AA:BB", "IT-NEW", "IT-NEW"))
    }
}
