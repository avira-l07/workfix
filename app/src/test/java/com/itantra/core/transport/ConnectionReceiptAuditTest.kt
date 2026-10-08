package com.itantra.core.transport

import com.itantra.core.transport.packet.*
import com.itantra.core.transport.peer.PeerTransport
import com.itantra.domain.model.Measurement
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionReceiptAuditTest {
    private class Peer : PeerTransport {
        val state = MutableStateFlow(ConnectionState.CONNECTED)
        val incoming = MutableSharedFlow<ByteArray>(replay = 1)
        override val isConnected get() = state.value == ConnectionState.CONNECTED
        override val isServer = false
        override var connectionId = 1L
        var writes = 0
        var disconnected = false
        var action: suspend () -> Unit = {}
        override fun observeConnectionState(): Flow<ConnectionState> = state
        override fun receive(): Flow<ByteArray> = incoming
        override suspend fun send(bytes: ByteArray) { writes++; action() }
        override suspend fun disconnect() { disconnected = true; connectionId++; state.value = ConnectionState.DISCONNECTED }
    }

    @Test fun `remote disconnect cancels outstanding ACK immediately`() = runBlocking {
        val peer = Peer()
        val engine = TransportCoordinator(peer)
        try {
            delay(25)
            val wrote = CompletableDeferred<Unit>()
            peer.action = { wrote.complete(Unit) }
            val waiting = async { runCatching { engine.send(ItantraPacket(PacketType.TEXT, messageId = 10)) } }
            wrote.await()
            peer.state.value = ConnectionState.DISCONNECTED
            assertTrue(withTimeout(500) { waiting.await() }.exceptionOrNull() is CancellationException)
        } finally { engine.shutdown() }
    }

    @Test fun `overlapping retry with same message ID cannot steal existing ACK waiter`() = runBlocking {
        val peer = Peer()
        val engine = TransportCoordinator(peer)
        try {
            val wrote = CompletableDeferred<Unit>()
            peer.action = { wrote.complete(Unit) }
            val waiting = async { engine.send(ItantraPacket(PacketType.TEXT, messageId = 11)) }
            wrote.await()
            assertTrue(runCatching { engine.send(ItantraPacket(PacketType.TEXT, messageId = 11)) }.isFailure)
            assertEquals(1, peer.writes)
            engine.notifyAckReceived(11)
            assertTrue(withTimeout(1000) { waiting.await() }.transmissionLatencyMillis is Measurement.Measured)
        } finally { engine.shutdown() }
    }

    @Test fun `shutdown completes socket cleanup instead of cancelling it`() = runBlocking {
        val peer = Peer()
        val engine = TransportCoordinator(peer)
        engine.shutdown()
        withTimeout(1000) { while (!peer.disconnected) delay(5) }
        assertFalse(peer.isConnected)
    }

    @Test fun `old token rejects sending after switch and same transport reconnect`() = runBlocking {
        val first = Peer()
        val second = Peer()
        val engine = TransportCoordinator(first)
        try {
            val old = engine.connectionToken
            engine.switchTransport(second)
            assertTrue(runCatching { engine.send(ItantraPacket(PacketType.CAPABILITIES, messageId = 1), old) }.isFailure)
            assertEquals(0, second.writes)
            val previousSocket = engine.connectionToken
            second.connectionId++
            assertTrue(runCatching { engine.send(ItantraPacket(PacketType.CAPABILITIES, messageId = 2), previousSocket) }.isFailure)
            assertEquals(0, second.writes)
        } finally { engine.shutdown() }
    }

    @Test fun `early decoded HELLO reaches late upper collector and clears on disconnect`() = runBlocking {
        val peer = Peer()
        val engine = TransportCoordinator(peer)
        try {
            val encoded = PacketEncoder.encode(ItantraPacket(PacketType.SECURE_HELLO, messageId = 12))
            peer.incoming.emit(encoded.copyOfRange(4, encoded.size))
            delay(50)
            val decoded = withTimeout(1000) { engine.receive().first() }
            assertEquals(12L, decoded.messageId)
            assertEquals(engine.connectionToken, decoded.receivedOn)
            engine.disconnect()
            assertNull(withTimeoutOrNull(100) { engine.receive().first() })
        } finally { engine.shutdown() }
    }
}
