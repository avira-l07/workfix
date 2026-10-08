package com.itantra.regression

import com.itantra.core.transport.*
import com.itantra.core.transport.peer.PeerTransport
import com.itantra.core.transport.packet.*
import com.itantra.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import com.example.itantra.ui.screens.chat.locationMapUri

class Phase4BugfixTest {
    @Test fun outgoingGpsKeepsExactFixUnderCommaDecimalLocale() = runBlocking {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            BugfixFixture().use { f ->
                f.verify()
                f.coordinator.sendLocationMessage()
                f.awaitCondition { f.coordinator.messages.value.any { it.state == MessageState.DELIVERED } }
                val message = f.coordinator.messages.value.single()
                assertTrue(message.isLocation)
                assertEquals(f.location.latitude, message.latitude!!, 0.0)
                assertEquals(f.location.longitude, message.longitude!!, 0.0)
                assertEquals(f.location.accuracyMeters, message.accuracyMeters!!, 0f)
                assertEquals(f.location.timestampMillis, message.locationTimestampMillis)
                assertTrue(message.text.contains("12.34568, 77.12346"))
                assertEquals("geo:12.3456789123,77.1234567891?q=12.3456789123,77.1234567891", locationMapUri(message))
            }
        } finally { Locale.setDefault(original) }
    }

    @Test fun locationTransportRegistersWaiterBeforeSend() = runBlocking {
        lateinit var coordinator: TransportCoordinator
        val peer = object : PeerTransport {
            override val isConnected = true
            override val isServer = false
            override fun observeConnectionState(): Flow<ConnectionState> = emptyFlow()
            override fun receive(): Flow<ByteArray> = emptyFlow()
            override suspend fun disconnect() {}
            override suspend fun send(bytes: ByteArray) {
                coordinator.notifyAckReceived(401)
            }
        }
        coordinator = TransportCoordinator(peer)
        try {
            val metrics = coordinator.send(ItantraPacket(PacketType.LOCATION, messageId = 401))
            assertTrue("LOCATION has no ACK waiter", metrics.transmissionLatencyMillis is Measurement.Measured)
        } finally { coordinator.shutdown() }
    }

    @Test fun sendCompletionCannotRegressEarlyLocationAck() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            val sendReturned = CompletableDeferred<Unit>()
            f.sendAction = { packet ->
                if (packet.type == PacketType.LOCATION) {
                    f.receive(ItantraPacket(PacketType.ACK, messageId = packet.messageId))
                    sendReturned.complete(Unit)
                }
                TransmissionMetrics(packetBytes = Measurement.Measured(99))
            }
            f.coordinator.sendLocationMessage()
            withTimeout(5000) { sendReturned.await() }
            f.awaitCondition { f.coordinator.messages.value.singleOrNull()?.packetBytes == 99 }
            assertEquals(MessageState.DELIVERED, f.coordinator.messages.value.single().state)
        }
    }
}
