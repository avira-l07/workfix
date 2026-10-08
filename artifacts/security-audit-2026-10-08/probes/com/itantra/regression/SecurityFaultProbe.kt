package com.itantra.regression

import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.core.transport.packet.PacketType
import com.itantra.core.transport.packet.ProfilePayload
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.MessageState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Audit-only reproduction of the current queue behavior, not a fixed-behavior regression. */
class SecurityFaultProbe {
    @Test fun `verified peer acknowledgement can change a different peers message`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.receive(ItantraPacket(PacketType.PROFILE_HANDSHAKE, messageId = 80_000L,
                payload = ProfilePayload(deviceId = "IT-BBBB-0002", displayName = "Peer B",
                    supportedLanguages = listOf(LanguageCode.ENGLISH)).toBytes()))
            assertEquals("IT-BBBB-0002", f.coordinator.activePeerProfile.value?.deviceId)
            listOf(PacketType.ACK to MessageState.DELIVERED,
                PacketType.HUMAN_ACK to MessageState.ACKNOWLEDGED).forEachIndexed { index, (type, state) ->
                val id = 80_001L + index
                f.add(sampleMessage(id, MessageState.SENT).copy(peerId = "IT-AAAA-0001",
                    receiverDeviceId = "IT-AAAA-0001"))
                f.receive(ItantraPacket(type, messageId = id))
                val result = f.coordinator.messages.value.single { it.messageId == id }
                assertEquals("IT-AAAA-0001", result.peerId)
                assertEquals(state, result.state)
                println("WRONG_PEER_ACK: verifiedPeer=IT-BBBB-0002 originalRecipient=${result.peerId} type=$type resultingState=${result.state}")
            }
        }
    }

    @Test fun `unverified text does not enter playback queue`() = runBlocking {
        BugfixFixture().use { f ->
            repeat(64) { i ->
                f.coordinator.handleIncomingPacket(ItantraPacket(PacketType.TEXT,
                    messageId = 50_000L + i, languageCode = LanguageCode.ENGLISH,
                    payload = ByteArray(8192) { 65 }))
            }
            delay(50)
            assertTrue((f.field("messageQueue") as List<*>).isEmpty())
            assertTrue(f.coordinator.messages.value.isEmpty())
            println("UNVERIFIED_CONTROL: 64 plaintext messages rejected; queue=0")
        }
    }

    @Test fun `verified peer can grow pending queue while translation is blocked`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.repository.setReceiveLanguage(LanguageCode.GUJARATI)
            f.awaitCondition { (f.field("currentReceiveLanguage") as kotlinx.coroutines.flow.StateFlow<*>).value == LanguageCode.GUJARATI }
            val began = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.translationAction = { _, _, _ -> began.complete(Unit); release.await(); error("Audit gate released") }
            val packet = ItantraPacket(PacketType.TEXT, messageId = 60_000L,
                languageCode = LanguageCode.ENGLISH, sourceLanguage = LanguageCode.ENGLISH,
                targetLanguage = LanguageCode.ENGLISH, payload = ByteArray(8192) { 65 })
            f.receive(packet)
            withTimeout(3000) { began.await() }
            repeat(512) { i -> f.receive(packet.copy(messageId = 60_001L + i)) }
            val mutex = f.field("queueMutex") as Mutex
            withTimeout(5000) {
                while (mutex.withLock { (f.field("messageQueue") as List<*>).size } < 512) delay(5)
            }
            val pending = mutex.withLock { (f.field("messageQueue") as List<*>).size }
            assertEquals(512, pending)
            println("AUTHENTICATED_FLOOD: pending=$pending payloadBytes=${pending * packet.payload.size}; one additional message in translation")
        }
    }
}
