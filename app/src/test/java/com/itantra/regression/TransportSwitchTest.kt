package com.itantra.regression

import com.itantra.core.transport.ConnectionState
import com.itantra.core.transport.TransportCoordinator
import com.itantra.core.transport.peer.PeerTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test

class TransportSwitchTest {

    @Test
    fun `switching between connected transports emits a session reset before the new connection`() = runBlocking {
        val first = FakeTransport()
        val second = FakeTransport(isServer = true)
        first.state.value = ConnectionState.CONNECTED
        second.state.value = ConnectionState.CONNECTED
        val coordinator = TransportCoordinator(first)
        val states = CopyOnWriteArrayList<ConnectionState>()
        val reader = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.observeConnectionState().collect { states.add(it) }
        }
        try {
            withTimeout(3000) { while (states.lastOrNull() != ConnectionState.CONNECTED) delay(5) }
            states.clear()
            coordinator.switchTransport(second)
            withTimeout(3000) { while (states.lastOrNull() != ConnectionState.CONNECTED) delay(5) }
            val observed = states.toList()
            assertEquals(ConnectionState.DISCONNECTED, observed.first())
            assertEquals(ConnectionState.CONNECTED, observed.last())
            assertTrue(observed.dropLast(1).all { it == ConnectionState.DISCONNECTED })
        } finally {
            reader.cancel()
            coordinator.shutdown()
        }
    }

    private class FakeTransport(
        override val isServer: Boolean = false
    ) : PeerTransport {
        val state = MutableStateFlow(ConnectionState.DISCONNECTED)
        val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)

        override val isConnected: Boolean get() = state.value == ConnectionState.CONNECTED
        override fun observeConnectionState(): Flow<ConnectionState> = state
        override suspend fun send(bytes: ByteArray) {}
        override fun receive(): Flow<ByteArray> = incoming
        override suspend fun disconnect() { state.value = ConnectionState.DISCONNECTED }
    }

    @Test
    fun testSwitchTransportPropagatesNewStateViaFlatMapLatest() = runBlocking {
        val transportA = FakeTransport(isServer = false)
        val transportB = FakeTransport(isServer = true)

        val coordinator = TransportCoordinator(transportA)
        val observedStates = mutableListOf<ConnectionState>()

        val collectJob = launch {
            coordinator.observeConnectionState().collect { observedStates.add(it) }
        }

        transportA.state.value = ConnectionState.CONNECTING
        transportA.state.value = ConnectionState.CONNECTED
        assertTrue(coordinator.isConnected)
        assertFalse(coordinator.isServer)

        // Switch to Transport B (e.g. BT -> Wi-Fi)
        coordinator.switchTransport(transportB)
        assertEquals(ConnectionState.DISCONNECTED, transportA.state.value)

        transportB.state.value = ConnectionState.LISTENING
        kotlinx.coroutines.delay(50)
        transportB.state.value = ConnectionState.CONNECTED
        kotlinx.coroutines.delay(50)

        assertTrue(coordinator.isConnected)
        assertTrue(coordinator.isServer)
        assertTrue(observedStates.contains(ConnectionState.LISTENING))

        // Switch back to Transport A (Wi-Fi -> BT)
        coordinator.switchTransport(transportA)
        assertEquals(ConnectionState.DISCONNECTED, transportB.state.value)

        transportA.state.value = ConnectionState.CONNECTED
        kotlinx.coroutines.delay(50)
        assertTrue(coordinator.isConnected)
        assertFalse(coordinator.isServer)

        collectJob.cancel()
    }
}
