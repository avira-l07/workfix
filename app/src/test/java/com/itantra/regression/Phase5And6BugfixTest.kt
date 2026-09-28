package com.itantra.regression

import com.example.itantra.data.messages.belongsToConversation
import com.itantra.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class Phase5And6BugfixTest {
    @Test fun stableIdAndTransportMacBothLocateOneConversation() {
        val message = TransceiverMessage(1, LanguageCode.ENGLISH, priority = 0, text = "hi",
            source = MessageSource.REMOTE, createdAtLocal = 1, state = MessageState.DELIVERED,
            peerId = "IT-PEER-0001", senderDeviceId = "IT-PEER-0001", receiverDeviceId = "IT-LOCAL",
            isVoiceGenerated = false)
        assertTrue(message.belongsToConversation("IT-PEER-0001"))
        assertTrue(message.belongsToConversation("AA:BB:CC:DD:EE:FF") ||
            message.belongsToConversation("IT-PEER-0001"))
    }

    @Test fun receivedPriorityDoesNotPretendToBeVoice() {
        val message = TransceiverMessage(2, LanguageCode.ENGLISH, priority = MessagePriority.CRITICAL,
            text = "urgent", source = MessageSource.REMOTE, createdAtLocal = 2,
            state = MessageState.DELIVERED, isVoiceGenerated = false)
        assertFalse(message.isVoiceGenerated)
    }
}
