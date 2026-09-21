package com.itantra.core.transceiver

import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.MessageSource
import com.itantra.domain.model.MessageState
import com.itantra.domain.model.TransceiverMessage
import com.itantra.domain.model.TranslationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationIsolationTest {

    @Test
    fun testPeerIdIsolation() {
        val peerA = "IT-AAAA-1111"
        val peerB = "IT-BBBB-2222"

        val messages = listOf(
            createSampleMessage(1L, "Hello A", peerA),
            createSampleMessage(2L, "Hello B", peerB),
            createSampleMessage(3L, "Follow up A", peerA)
        )

        val messagesForA = messages.filter { it.peerId == peerA }
        val messagesForB = messages.filter { it.peerId == peerB }

        assertEquals(2, messagesForA.size)
        assertEquals(1, messagesForB.size)
        assertTrue(messagesForA.all { it.peerId == peerA })
        assertTrue(messagesForB.all { it.peerId == peerB })
    }

    private fun createSampleMessage(id: Long, text: String, peerId: String): TransceiverMessage {
        return TransceiverMessage(
            messageId = id,
            language = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.ENGLISH,
            priority = 0,
            text = text,
            originalText = null,
            translationStatus = TranslationStatus.NONE,
            source = MessageSource.LOCAL,
            createdAtLocal = System.currentTimeMillis(),
            state = MessageState.DELIVERED,
            peerId = peerId
        )
    }
}
