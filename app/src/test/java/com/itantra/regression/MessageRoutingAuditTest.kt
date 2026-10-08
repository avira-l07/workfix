package com.itantra.regression

import com.itantra.core.transport.packet.*
import com.itantra.core.translation.TranslationResult
import com.itantra.domain.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class MessageRoutingAuditTest {
    @Test fun `GPS retry preserves LOCATION packet and original coordinates rather than sending text`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify(); profile(f, "IT-AAAA-0001")
            val fix = f.location
            f.add(sampleMessage(301L, MessageState.ERROR).copy(peerId = "IT-AAAA-0001", isLocation = true,
                latitude = fix.latitude, longitude = fix.longitude, accuracyMeters = fix.accuracyMeters,
                locationTimestampMillis = fix.timestampMillis))
            assertTrue(f.coordinator.retryMessage(301L))
            f.awaitCondition { f.sent.any { it.messageId == 301L } }
            val sent = f.sent.single { it.messageId == 301L }
            assertEquals(PacketType.LOCATION, sent.type)
            val decoded = LocationPayload.fromBytes(f.remote.decrypt(sent).payload)!!
            assertEquals(fix.latitude, decoded.latitude, 0.0)
            assertEquals(fix.longitude, decoded.longitude, 0.0)
            assertEquals(fix.timestampMillis, decoded.timestampMillis)
        }
    }
    @Test fun `sub millisecond ACK counts as delivery and does not repeat SOS`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.sendAction = { TransmissionMetrics(transmissionLatencyMillis = Measurement.Measured(0L)) }
            f.coordinator.sendEmergencyCode(EmergencyCode.MEDICAL_EMERGENCY)
            f.awaitCondition { f.coordinator.messages.value.any { it.state == MessageState.DELIVERED } }
            delay(50)
            assertEquals(1, f.sent.count { it.type == PacketType.EMERGENCY_CODE })
            assertFalse(f.coordinator.messages.value.any { it.text.contains("Failed to") })
        }
    }

    @Test fun `unacknowledged SENT message may be retried only to its original verified peer`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify(); profile(f, "IT-AAAA-0001")
            f.add(sampleMessage(200L, MessageState.SENT).copy(peerId = "IT-AAAA-0001"))
            assertTrue(f.coordinator.retryMessage(200L))
            f.awaitCondition { f.sent.any { it.type == PacketType.TEXT && it.messageId == 200L } }
            f.add(sampleMessage(201L, MessageState.SENT).copy(peerId = "IT-BBBB-0002"))
            assertFalse(f.coordinator.retryMessage(201L))
        }
    }
    private suspend fun profile(f: BugfixFixture, id: String) = f.receive(ItantraPacket(
        PacketType.PROFILE_HANDSHAKE, messageId = 40,
        payload = ProfilePayload(deviceId = id, displayName = "Peer", supportedLanguages = listOf(LanguageCode.ENGLISH)).toBytes()))

    @Test fun `incoming message belongs to verified sender even when another chat is selected`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify(); profile(f, "IT-AAAA-0001")
            f.coordinator.setActiveConversation("IT-BBBB-0002")
            f.receive(ItantraPacket(PacketType.TEXT, messageId = 41, languageCode = LanguageCode.ENGLISH, payload = "hello".toByteArray()))
            f.awaitCondition { f.coordinator.messages.value.any { it.messageId == 41L } }
            assertEquals("IT-AAAA-0001", f.coordinator.messages.value.single { it.messageId == 41L }.peerId)
        }
    }

    @Test fun `typed message to disconnected old chat never reaches current peer`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify(); profile(f, "IT-AAAA-0001")
            f.coordinator.sendTextMessage("private message", "IT-BBBB-0002")
            f.awaitCondition { f.coordinator.messages.value.any { it.state == MessageState.ERROR } }
            assertFalse(f.sent.any { it.type == PacketType.TEXT })
        }
    }

    @Test fun `translation finishing after connection replacement cannot send to new session`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.repository.setTargetLanguage(LanguageCode.GUJARATI)
            delay(40)
            val began = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            f.translationAction = { text, source, target ->
                began.complete(Unit); resume.await(); TranslationResult(text, "translated", true, source, target)
            }
            f.coordinator.sendTextMessage("delayed translation")
            withTimeout(2000) { began.await() }
            (f.field("connectionGeneration") as AtomicLong).incrementAndGet()
            resume.complete(Unit)
            f.awaitCondition { f.coordinator.messages.value.any { it.state == MessageState.ERROR } }
            assertFalse(f.sent.any { it.type == PacketType.TEXT })
        }
    }

    @Test fun `GPS fix finishing after disconnect cannot send on replacement session`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            val began = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
            f.locationAction = { began.complete(Unit); resume.await(); f.location }
            f.coordinator.sendLocationMessage()
            began.await()
            (f.field("connectionGeneration") as AtomicLong).incrementAndGet()
            resume.complete(Unit)
            f.awaitCondition { f.coordinator.messages.value.any { it.state == MessageState.ERROR } }
            assertFalse(f.sent.any { it.type == PacketType.LOCATION })
        }
    }
}
