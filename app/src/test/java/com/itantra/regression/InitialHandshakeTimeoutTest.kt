package com.itantra.regression

import com.itantra.core.transport.*
import com.itantra.core.transport.packet.*
import com.itantra.domain.model.TransmissionMetrics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class InitialHandshakeTimeoutTest {
    @Test fun `server keeps initial HELLO timeout despite initial NO SESSION emission`() = runBlocking {
        val state = MutableStateFlow(ConnectionState.CONNECTED)
        val transport = object : TransportEngine {
            override val isConnected = true
            override val isServer = true
            override fun observeConnectionState(): Flow<ConnectionState> = state
            override fun receive(): Flow<ItantraPacket> = emptyFlow()
            override suspend fun send(packet: ItantraPacket) = TransmissionMetrics()
            override suspend fun disconnect() { state.value = ConnectionState.DISCONNECTED }
            override fun notifyAckReceived(messageId: Long) {}
        }
        BugfixFixture(transportOverride = transport).use { fixture ->
            fixture.awaitCondition { fixture.field("handshakeRetryJob") is Job }
            delay(100)
            assertTrue("Server must retain a timeout while waiting for the first HELLO",
                (fixture.field("handshakeRetryJob") as? Job)?.isActive == true)
            assertFalse(fixture.secure.localSasConfirmed)
        }
    }
}
