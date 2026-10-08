package com.itantra.regression

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.itantra.data.settings.SettingsRepository
import com.itantra.core.location.LocationResult
import com.itantra.core.storage.MemoryKeyProvider
import com.itantra.core.storage.PrivateContent
import com.itantra.core.transport.packet.*
import com.itantra.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test

class AppFunctionsAuditTest {
    @Test fun `typed Hindi retains its selected source when only Gujarati replay voice is loaded`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.session.ensureTts(LanguageCode.GUJARATI)
            f.awaitCondition { (f.field("currentTargetLanguage") as StateFlow<*>).value == LanguageCode.ENGLISH }
            f.coordinator.sendTextMessage("मेरा नाम अविरल है")
            f.awaitCondition { f.sent.any { it.type == PacketType.TEXT } }
            val packet = f.remote.decrypt(f.sent.single { it.type == PacketType.TEXT })
            assertEquals(LanguageCode.HINDI, packet.sourceLanguage)
            assertEquals(LanguageCode.ENGLISH, packet.targetLanguage)
            assertEquals(listOf(LanguageCode.HINDI to LanguageCode.ENGLISH), f.routes.toList())
        }
    }

    @Test fun `typed English uses saved source even before an STT model is loaded`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.repository.setActiveLanguage(LanguageCode.ENGLISH)
            f.repository.setTargetLanguage(LanguageCode.HINDI)
            f.session.ensureTts(LanguageCode.GUJARATI)
            f.awaitCondition { (f.field("currentTargetLanguage") as StateFlow<*>).value == LanguageCode.HINDI }
            f.coordinator.sendTextMessage("Hello")
            f.awaitCondition { f.sent.any { it.type == PacketType.TEXT } }
            val packet = f.remote.decrypt(f.sent.single { it.type == PacketType.TEXT })
            assertEquals(LanguageCode.ENGLISH, packet.sourceLanguage)
            assertEquals(LanguageCode.HINDI, packet.targetLanguage)
        }
    }

    @Test fun `retry keeps high priority and removes obsolete failure status`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.receive(ItantraPacket(PacketType.PROFILE_HANDSHAKE, messageId = 40,
                payload = ProfilePayload(deviceId = "IT-AAAA-0001", displayName = "Peer",
                    supportedLanguages = listOf(LanguageCode.ENGLISH)).toBytes()))
            f.add(sampleMessage(300, MessageState.ERROR).copy(peerId = "IT-AAAA-0001",
                priority = MessagePriority.HIGH, statusDetail = "Peer disconnected"))
            assertTrue(f.coordinator.retryMessage(300))
            f.awaitCondition { f.coordinator.messages.value.single { it.messageId == 300L }.state == MessageState.DELIVERED }
            val packet = f.remote.decrypt(f.sent.single { it.messageId == 300L })
            assertEquals(MessagePriority.HIGH, packet.flags.toInt())
            val message = f.coordinator.messages.value.single { it.messageId == 300L }
            assertEquals(MessagePriority.HIGH, message.priority)
            assertNull(message.statusDetail)
        }
    }

    @Test fun `GPS acquisition interrupted by app exit settles and can be removed after restart`() = runBlocking {
        val dao = RecordingMessageDao()
        dao.insert(com.itantra.data.db.MessageEntity.fromDomain(sampleMessage(400, MessageState.PACKET_ENCODING)
            .copy(text = "GPS: Acquiring satellite fix...")))
        BugfixFixture(dao).use { f ->
            f.awaitCondition { f.coordinator.messages.value.isNotEmpty() }
            val message = f.coordinator.messages.value.single()
            assertEquals(MessageState.ERROR, message.state)
            assertTrue(message.statusDetail!!.startsWith("Interrupted"))
            assertTrue(f.coordinator.moveMessageToTrash(400))
            f.awaitCondition { dao.rows[400]?.deletedAtMillis != null }
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test fun `invalid outgoing GPS fix fails locally without transmitting bogus coordinates`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.location = LocationResult.Success(Double.NaN, 181.0, -1f, System.currentTimeMillis())
            f.coordinator.sendLocationMessage()
            f.awaitCondition { f.coordinator.messages.value.any { it.state == MessageState.ERROR } }
            assertTrue(f.coordinator.messages.value.single().statusDetail!!.contains("invalid coordinates"))
            assertFalse(f.sent.any { it.type == PacketType.LOCATION })
        }
    }

    @Test fun `operator edits update encrypted peer profile and clearing restores default identity`() = runBlocking {
        val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            BugfixFixture(deviceId = "IT-A1B2-0001").use { f ->
                val repository = SettingsRepository(PreferenceDataStoreFactory.create(scope = dataScope) {
                    f.dir.resolve("settings.preferences_pb")
                }, PrivateContent(MemoryKeyProvider()))
                val defaultName = f.coordinator.deviceProfileManager!!.currentDisplayName
                val originalId = f.coordinator.deviceProfileManager!!.currentDeviceId
                f.verify()
                f.awaitCondition { f.sent.any { it.type == PacketType.CAPABILITIES } }
                val initialProfiles = f.sent.count { it.type == PacketType.PROFILE_HANDSHAKE }
                f.coordinator.attachSettings(repository)
                repository.update { it.copy(operatorName = "ALPHA\nLEADER") }
                f.awaitCondition { f.sent.count { it.type == PacketType.PROFILE_HANDSHAKE } == initialProfiles + 1 }
                val renamed = ProfilePayload.fromBytes(f.remote.decrypt(f.sent.last {
                    it.type == PacketType.PROFILE_HANDSHAKE }).payload)!!
                assertEquals("ALPHALEADER", renamed.displayName)
                assertEquals(originalId, renamed.deviceId)
                assertEquals(listOf(LanguageCode.HINDI), renamed.supportedLanguages)
                repository.update { it.copy(vadSensitivity = 3) }
                delay(75)
                assertEquals(initialProfiles + 1, f.sent.count { it.type == PacketType.PROFILE_HANDSHAKE })
                repository.resetToDefault()
                f.awaitCondition { f.sent.count { it.type == PacketType.PROFILE_HANDSHAKE } == initialProfiles + 2 }
                val reset = ProfilePayload.fromBytes(f.remote.decrypt(f.sent.last {
                    it.type == PacketType.PROFILE_HANDSHAKE }).payload)!!
                assertEquals(defaultName, reset.displayName)
                assertEquals(originalId, reset.deviceId)
            }
        } finally { dataScope.cancel() }
    }
}
