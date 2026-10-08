package com.itantra.regression

import com.itantra.core.transport.packet.*
import com.itantra.core.translation.TranslationResult
import com.itantra.core.emergency.EmergencyPersistenceStore
import com.itantra.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import com.itantra.core.transport.ConnectionState
import com.itantra.core.transport.TransportEngine
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises hostile traffic using real session encryption and the production coordinator. */
class SecurityHardeningRegressionTest {
    private val receipts = listOf(PacketType.ACK, PacketType.HUMAN_ACK,
        PacketType.TTS_STARTED, PacketType.TTS_COMPLETED, PacketType.TTS_FAILED)

    private suspend fun profile(f: BugfixFixture, id: String, name: String = "Peer") = f.receive(
        ItantraPacket(PacketType.PROFILE_HANDSHAKE, messageId = 80_000,
            payload = ProfilePayload(deviceId = id, displayName = name,
                supportedLanguages = listOf(LanguageCode.ENGLISH)).toBytes()))

    @Test fun `verified peer cannot forge receipts for another peer or unsent history`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify(); profile(f, "IT-BBBB-0002")
            receipts.forEachIndexed { index, type ->
                val id = 80_001L + index
                f.add(sampleMessage(id, MessageState.SENT).copy(peerId = "IT-AAAA-0001",
                    receiverDeviceId = "IT-AAAA-0001"))
                f.receive(ItantraPacket(type, messageId = id))
                assertEquals(type.name, MessageState.SENT, f.coordinator.messages.value.single { it.messageId == id }.state)
            }
            f.add(sampleMessage(80_010, MessageState.SENT).copy(peerId = "IT-BBBB-0002"))
            f.receive(ItantraPacket(PacketType.HUMAN_ACK, messageId = 80_010))
            assertEquals(MessageState.SENT, f.coordinator.messages.value.last().state)
        }
    }

    @Test fun `profile identity cannot be switched inside a verified session`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify(); profile(f, "IT-BBBB-0002")
            profile(f, "IT-AAAA-0001", "Spoof")
            assertEquals("IT-BBBB-0002", f.coordinator.activePeerProfile.value?.deviceId)
            profile(f, "IT-BBBB-0002", "Renamed")
            assertEquals("Renamed", f.coordinator.activePeerProfile.value?.displayName)
        }
    }

    @Test fun `all receipt types still work for messages actually sent on this session`() = runBlocking {
        val states = listOf(MessageState.DELIVERED, MessageState.ACKNOWLEDGED,
            MessageState.REMOTE_PLAYING, MessageState.REMOTE_PLAYBACK_CONFIRMED, MessageState.ERROR)
        BugfixFixture().use { f ->
            f.verify(); profile(f, "IT-AAAA-0001")
            receipts.forEachIndexed { index, type ->
                f.sendAction = { TransmissionMetrics() }
                f.coordinator.sendTextMessage("receipt test $index", "IT-AAAA-0001")
                f.awaitCondition { f.sent.count { it.type == PacketType.TEXT } == index + 1 }
                val id = f.sent.last { it.type == PacketType.TEXT }.messageId
                f.awaitCondition { f.coordinator.messages.value.any { it.messageId == id && it.state == MessageState.SENT } }
                f.receive(ItantraPacket(type, messageId = id))
                assertEquals(type.name, states[index], f.coordinator.messages.value.single { it.messageId == id }.state)
            }
        }
    }

    @Test fun `receipts from a replacement session cannot settle the previous send`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify(); profile(f, "IT-AAAA-0001")
            f.sendAction = { TransmissionMetrics() }
            f.coordinator.sendTextMessage("original send", "IT-AAAA-0001")
            f.awaitCondition { f.coordinator.messages.value.any { it.state == MessageState.SENT } }
            val id = f.sent.single { it.type == PacketType.TEXT }.messageId
            (f.field("connectionGeneration") as AtomicLong).incrementAndGet()
            f.receive(ItantraPacket(PacketType.HUMAN_ACK, messageId = id))
            assertEquals(MessageState.SENT, f.coordinator.messages.value.single { it.messageId == id }.state)
        }
    }

    @Test fun `remote text cannot collide with a local message id`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify(); profile(f, "IT-BBBB-0002")
            f.add(sampleMessage(50_000, MessageState.SENT))
            f.receive(ItantraPacket(PacketType.TEXT, messageId = 50_000,
                languageCode = LanguageCode.ENGLISH, payload = "hostile collision".toByteArray()))
            delay(50)
            assertEquals(1, f.coordinator.messages.value.size)
            assertEquals("message 50000", f.coordinator.messages.value.single().text)
            assertFalse(f.sent.any { it.type == PacketType.ACK && it.messageId == 50_000L })
        }
    }

    @Test fun `unverified plaintext flood never enters history or speech queue`() = runBlocking {
        BugfixFixture().use { f ->
            repeat(512) { i -> f.coordinator.handleIncomingPacket(ItantraPacket(PacketType.TEXT,
                messageId = 50_000L + i, languageCode = LanguageCode.ENGLISH, payload = ByteArray(8192) { 65 })) }
            delay(50)
            assertTrue((f.field("messageQueue") as List<*>).isEmpty())
            assertTrue(f.coordinator.messages.value.isEmpty())
        }
    }

    @Test fun `authenticated flood is bounded without acknowledging rejected messages and retry succeeds`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify(); profile(f, "IT-AAAA-0001")
            f.repository.setReceiveLanguage(LanguageCode.GUJARATI)
            f.awaitCondition { (f.field("currentReceiveLanguage") as StateFlow<*>).value == LanguageCode.GUJARATI }
            val began = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.translationAction = { text, source, target ->
                began.complete(Unit); release.await(); TranslationResult(text, "translated", true, source, target)
            }
            val packet = ItantraPacket(PacketType.TEXT, messageId = 60_000,
                languageCode = LanguageCode.ENGLISH, sourceLanguage = LanguageCode.ENGLISH,
                targetLanguage = LanguageCode.ENGLISH, payload = ByteArray(8192) { 65 })
            f.receive(packet); withTimeout(3000) { began.await() }
            repeat(512) { i -> f.receive(packet.copy(messageId = 60_001L + i)) }
            synchronized(f.field("queueLock")!!) {
                val pending = (f.field("messageQueue") as List<*>).size
                val bytes = f.field("pendingPayloadBytes") as Int
                assertTrue("Pending count $pending", pending in 1..60)
                assertTrue("Pending bytes $bytes", bytes <= 448 * 1024)
            }
            delay(50)
            assertFalse("Rejected packet falsely ACKed", f.sent.any { it.type == PacketType.ACK && it.messageId == 60_512L })
            release.complete(Unit)
            f.awaitCondition { synchronized(f.field("queueLock")!!) { (f.field("messageQueue") as List<*>).isEmpty() } }
            f.receive(packet.copy(messageId = 60_512L))
            f.awaitCondition { f.sent.any { it.type == PacketType.ACK && it.messageId == 60_512L } }
            f.awaitCondition { f.coordinator.messages.value.any { it.messageId == 60_512L } }
        }
    }

    @Test fun `normal saturation preserves emergency admission`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.repository.setReceiveLanguage(LanguageCode.GUJARATI)
            f.awaitCondition { (f.field("currentReceiveLanguage") as StateFlow<*>).value == LanguageCode.GUJARATI }
            val began = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.translationAction = { text, source, target ->
                began.complete(Unit); release.await(); TranslationResult(text, "translated", true, source, target)
            }
            val packet = ItantraPacket(PacketType.TEXT, messageId = 70_000,
                languageCode = LanguageCode.ENGLISH, targetLanguage = LanguageCode.ENGLISH, payload = byteArrayOf(65))
            f.receive(packet); withTimeout(3000) { began.await() }
            repeat(100) { i -> f.receive(packet.copy(messageId = 70_001L + i)) }
            synchronized(f.field("queueLock")!!) { assertEquals(60, (f.field("messageQueue") as List<*>).size) }
            f.receive(ItantraPacket(PacketType.EMERGENCY_CODE, messageId = 71_000,
                flags = MessagePriority.CRITICAL.toByte(), payload = byteArrayOf(EmergencyCode.HELP_REQUIRED.id)))
            f.awaitCondition { f.sent.any { it.type == PacketType.ACK && it.messageId == 71_000L } }
            f.awaitCondition { f.coordinator.messages.value.any { it.messageId == 71_000L } }
        }
    }

    @Test fun `malformed emergency and location packets are not positively acknowledged`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.receive(ItantraPacket(PacketType.EMERGENCY_CODE, messageId = 90_001, payload = byteArrayOf(127)))
            f.receive(ItantraPacket(PacketType.EMERGENCY_CODE, messageId = 90_002,
                payload = byteArrayOf(EmergencyCode.ALL_CLEAR.id, 0)))
            f.receive(ItantraPacket(PacketType.LOCATION, messageId = 90_003, payload = byteArrayOf(0)))
            delay(50)
            assertFalse(f.sent.any { it.type == PacketType.ACK && it.messageId in 90_001L..90_003L })
            assertTrue(f.coordinator.messages.value.isEmpty())
        }
    }

    @Test fun `all clear resolves only senders emergencies and keeps other alerts active`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify(); profile(f, "IT-BBBB-0002")
            val store = f.field("emergencyStore") as EmergencyPersistenceStore
            val other = EmergencyRecord(91_001, EmergencyCode.HELP_REQUIRED.name, "REMOTE",
                createdAt = 1, peerId = "IT-AAAA-0001")
            val own = other.copy(messageId = 91_002, peerId = "IT-BBBB-0002")
            val local = other.copy(messageId = 91_003, source = "LOCAL", peerId = "IT-BBBB-0002")
            val legacy = other.copy(messageId = 91_004, peerId = "")
            listOf(other, own, local, legacy).forEach(store::saveRecord)
            f.add(sampleMessage(91_001, MessageState.ERROR).copy(source = MessageSource.REMOTE,
                priority = MessagePriority.CRITICAL, peerId = other.peerId))
            f.add(sampleMessage(91_002, MessageState.ERROR).copy(source = MessageSource.REMOTE,
                priority = MessagePriority.CRITICAL, peerId = own.peerId))
            @Suppress("UNCHECKED_CAST")
            (f.field("_activeEmergencyAlert") as MutableStateFlow<EmergencyRecord?>).value = other
            f.receive(ItantraPacket(PacketType.EMERGENCY_CODE, messageId = 91_005,
                payload = byteArrayOf(EmergencyCode.ALL_CLEAR.id)))
            assertTrue(store.getRecord(other.messageId)!!.isUnresolved)
            assertFalse(store.getRecord(own.messageId)!!.isUnresolved)
            assertTrue(store.getRecord(local.messageId)!!.isUnresolved)
            assertTrue(store.getRecord(legacy.messageId)!!.isUnresolved)
            assertEquals(MessageState.ERROR, f.coordinator.messages.value.first { it.messageId == other.messageId }.state)
            assertEquals(MessageState.ACKNOWLEDGED, f.coordinator.messages.value.first { it.messageId == own.messageId }.state)
            assertEquals(other.messageId, f.coordinator.activeEmergencyAlert.value?.messageId)
        }
    }

    @Test fun `all clear prevents queued sender emergencies from restarting the alarm`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify(); profile(f, "IT-BBBB-0002")
            // Hold the consumer so an emergency is accepted but not processed yet.
            val lock = f.field("queueLock")!!
            val clear = f.remote.encrypt(ItantraPacket(PacketType.EMERGENCY_CODE,
                messageId = 92_002, payload = byteArrayOf(EmergencyCode.ALL_CLEAR.id)))
            synchronized(lock) {
                f.coordinator.enqueueMessagePacketForPlayback(ItantraPacket(PacketType.EMERGENCY_CODE,
                    messageId = 92_001, flags = MessagePriority.CRITICAL.toByte(),
                    payload = byteArrayOf(EmergencyCode.HELP_REQUIRED.id)))
                f.coordinator.handleIncomingPacket(clear)
                assertTrue((f.field("messageQueue") as List<*>).isEmpty())
                assertEquals(0, f.field("pendingPayloadBytes"))
            }
            delay(50)
            assertNull(f.coordinator.activeEmergencyAlert.value)
            assertFalse(f.coordinator.messages.value.any { it.messageId == 92_001L })
        }
    }

    @Test fun `slow history storage converges to latest states including permanent deletion`() = runBlocking {
        val backing = RecordingMessageDao()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val dao = object : com.itantra.data.db.MessageDao by backing {
            override fun insert(message: com.itantra.data.db.MessageEntity): Long {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return backing.insert(message)
            }
        }
        BugfixFixture(dao).use { f ->
            try {
                f.add(sampleMessage(93_001, MessageState.SENT))
                assertTrue(started.await(3, TimeUnit.SECONDS))
                repeat(1000) { revision -> f.update(93_001) { it.copy(statusDetail = "revision $revision") } }
                f.update(93_001) { it.copy(state = MessageState.DELIVERED, statusDetail = "final") }
                f.add(sampleMessage(93_002, MessageState.SENT))
                assertTrue(f.coordinator.moveMessageToTrash(93_002))
                f.coordinator.deleteTrashedMessage(93_002)
                release.countDown()
                f.awaitCondition { backing.rows[93_001]?.stateName == "DELIVERED" && backing.rows[93_001]?.statusDetail == "final" }
                assertNull(backing.rows[93_002])
            } finally { release.countDown() }
        }
    }

    @Test fun `disconnect preserves accepted queued and translating messages under original sender`() = runBlocking {
        val states = MutableStateFlow(ConnectionState.DISCONNECTED)
        val transport = object : TransportEngine {
            override val isConnected get() = states.value == ConnectionState.CONNECTED
            override val isServer = true
            override fun observeConnectionState() = states
            override fun receive() = emptyFlow<ItantraPacket>()
            override fun notifyAckReceived(messageId: Long) {}
            override suspend fun disconnect() { states.value = ConnectionState.DISCONNECTED }
            override suspend fun send(packet: ItantraPacket) = TransmissionMetrics()
        }
        BugfixFixture(transportOverride = transport).use { f ->
            states.value = ConnectionState.CONNECTED
            f.awaitCondition { f.field("isConnected") == true }
            f.verify(); profile(f, "IT-AAAA-0001")
            f.repository.setReceiveLanguage(LanguageCode.GUJARATI)
            f.awaitCondition { (f.field("currentReceiveLanguage") as StateFlow<*>).value == LanguageCode.GUJARATI }
            val began = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            f.translationAction = { text, source, target -> began.complete(Unit); gate.await(); TranslationResult(text, "translated", true, source, target) }
            val packet = ItantraPacket(PacketType.TEXT, messageId = 94_000,
                languageCode = LanguageCode.ENGLISH, targetLanguage = LanguageCode.ENGLISH, payload = "accepted message".toByteArray())
            f.receive(packet); withTimeout(3000) { began.await() }
            f.receive(packet.copy(messageId = 94_001)); f.receive(packet.copy(messageId = 94_002))
            states.value = ConnectionState.DISCONNECTED
            f.awaitCondition { f.coordinator.messages.value.count { it.messageId in 94_000L..94_002L } == 3 }
            assertTrue(f.coordinator.messages.value.all { it.peerId == "IT-AAAA-0001" && it.text == "accepted message" })
            assertTrue((f.field("messageQueue") as List<*>).isEmpty())
            assertNull(f.coordinator.activePeerProfile.value)
        }
    }

    @Test fun `accepted text survives a session change before processing starts`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify(); profile(f, "IT-AAAA-0001")
            synchronized(f.field("queueLock")!!) {
                assertTrue(f.coordinator.enqueueMessagePacketForPlayback(ItantraPacket(PacketType.TEXT,
                    messageId = 94_010, languageCode = LanguageCode.ENGLISH,
                    payload = "accepted before session change".toByteArray())))
                (f.field("connectionGeneration") as AtomicLong).incrementAndGet()
            }
            f.awaitCondition { f.coordinator.messages.value.any { it.messageId == 94_010L } }
            val message = f.coordinator.messages.value.single { it.messageId == 94_010L }
            assertEquals("accepted before session change", message.text)
            assertEquals("IT-AAAA-0001", message.peerId)
            assertFalse(f.sent.any { it.type == PacketType.TTS_COMPLETED && it.messageId == 94_010L })
        }
    }

    @Test fun `emergency preemption preserves previously accepted translating text`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify(); profile(f, "IT-AAAA-0001")
            f.repository.setReceiveLanguage(LanguageCode.GUJARATI)
            f.awaitCondition { (f.field("currentReceiveLanguage") as StateFlow<*>).value == LanguageCode.GUJARATI }
            val began = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            f.translationAction = { text, source, target -> began.complete(Unit); gate.await(); TranslationResult(text, "translated", true, source, target) }
            f.receive(ItantraPacket(PacketType.TEXT, messageId = 95_001, languageCode = LanguageCode.ENGLISH,
                targetLanguage = LanguageCode.ENGLISH, payload = "keep this text".toByteArray()))
            withTimeout(3000) { began.await() }
            f.receive(ItantraPacket(PacketType.EMERGENCY_CODE, messageId = 95_002, flags = MessagePriority.CRITICAL.toByte(),
                payload = byteArrayOf(EmergencyCode.HELP_REQUIRED.id)))
            f.awaitCondition { f.coordinator.messages.value.any { it.messageId == 95_002L } }
            assertEquals("keep this text", f.coordinator.messages.value.single { it.messageId == 95_001L }.text)
        }
    }
}
