package com.itantra.regression

import com.itantra.core.emergency.EmergencyPersistenceStore
import com.itantra.core.transport.packet.*
import com.itantra.domain.model.*
import com.example.itantra.data.messages.belongsToConversation
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class Phase2BugfixTest {
    @Test fun acknowledgedMessageSurvivesPlaybackAndRemoteCompletion() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.add(sampleMessage(71, MessageState.ACKNOWLEDGED))
            f.update(71) { it.copy(state = MessageState.REMOTE_PLAYBACK_CONFIRMED) }
            assertEquals(MessageState.ACKNOWLEDGED, f.coordinator.messages.value.single().state)
            f.receive(ItantraPacket(PacketType.TTS_COMPLETED, messageId = 71))
            assertEquals(MessageState.ACKNOWLEDGED, f.coordinator.messages.value.single().state)
        }
    }

    @Test fun allClearResolvesMessagesBeforeReminderEvaluation() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.session.ensureTts(LanguageCode.ENGLISH)
            f.receive(ItantraPacket(PacketType.EMERGENCY_CODE, messageId = 61,
                flags = MessagePriority.CRITICAL.toByte(), languageCode = LanguageCode.ENGLISH,
                payload = byteArrayOf(EmergencyCode.HELP_REQUIRED.id)))
            f.awaitCondition { f.coordinator.messages.value.any { it.messageId == 61L && it.state == MessageState.ERROR } }
            f.receive(ItantraPacket(PacketType.EMERGENCY_CODE, messageId = 62,
                payload = byteArrayOf(EmergencyCode.ALL_CLEAR.id)))
            assertNull("ALL_CLEAR restarted the reminder", f.field("alertJob"))
            assertEquals(MessageState.ACKNOWLEDGED, f.coordinator.messages.value.first { it.messageId == 61L }.state)
            val store = f.field("emergencyStore") as EmergencyPersistenceStore
            assertTrue(store.getUnresolvedRecords().isEmpty())
            val calls = f.synthCalls.get()
            delay(10_200)
            assertEquals("A reminder fired after ALL_CLEAR", calls, f.synthCalls.get())
            assertNull(f.field("alertJob"))
        }
    }

    @Test fun failedOutgoingSosKeepsConversationAndLocalIdentity() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.coordinator.setActiveConversation("AA:BB:CC:DD:EE:FF")
            f.sendAction = { packet ->
                if (packet.type == PacketType.EMERGENCY_CODE) throw java.io.IOException("link lost")
                TransmissionMetrics()
            }
            f.coordinator.sendEmergencyCode(EmergencyCode.HELP_REQUIRED)
            f.awaitCondition { f.coordinator.messages.value.any { it.state == MessageState.ERROR } }
            val message = f.coordinator.messages.value.single()
            assertEquals("AA:BB:CC:DD:EE:FF", message.peerId)
            assertEquals("IT-LOCAL-0001", message.senderDeviceId)
            assertEquals("AA:BB:CC:DD:EE:FF", message.receiverDeviceId)
            assertTrue(message.belongsToConversation("AA:BB:CC:DD:EE:FF"))
            assertFalse(message.belongsToConversation("OTHER-PEER"))
        }
    }

    @Test fun unaddressedSosHasExplicitBroadcastIdentity() = runBlocking {
        BugfixFixture().use { f ->
            f.coordinator.sendEmergencyCode(EmergencyCode.HELP_REQUIRED)
            f.awaitCondition { f.coordinator.messages.value.isNotEmpty() }
            assertEquals("BROADCAST", f.coordinator.messages.value.single().peerId)
            assertTrue(f.coordinator.messages.value.single().belongsToConversation("PEER-A"))
            assertTrue(f.coordinator.messages.value.single().belongsToConversation("PEER-B"))
        }
    }
}
