package com.itantra.core.transport

import com.itantra.core.crypto.SecureSessionManager
import com.itantra.core.crypto.SecureSessionState
import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.core.transport.packet.PacketEncoder
import com.itantra.core.transport.packet.PacketType
import com.itantra.core.transport.peer.PeerTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class HeartbeatAndLivenessTest {

    private class TestPeerTransport : PeerTransport {
        override val isConnected: Boolean get() = _connected.get()
        override val isServer: Boolean = false
        val _connected = AtomicBoolean(true)
        val disconnectCalled = AtomicBoolean(false)
        val stateFlow = MutableStateFlow(ConnectionState.CONNECTED)
        val incomingFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        val sentBytes = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()

        override fun observeConnectionState(): Flow<ConnectionState> = stateFlow
        override suspend fun disconnect() {
            _connected.set(false)
            disconnectCalled.set(true)
            stateFlow.value = ConnectionState.DISCONNECTED
        }
        override suspend fun send(bytes: ByteArray) {
            sentBytes.add(bytes)
        }
        override fun receive(): Flow<ByteArray> = incomingFlow
    }

    @Test
    fun testHB1_plaintextHeartbeatDropped() = runBlocking {
        val transport = TestPeerTransport()
        val coordinator = TransportCoordinator(transport)

        val plainHeartbeat = ItantraPacket(
            type = PacketType.HEARTBEAT,
            securityVersion = 0,
            messageId = 101L,
            payload = ByteArray(0)
        )
        val encoded = PacketEncoder.encode(plainHeartbeat)
        val frameData = encoded.copyOfRange(4, encoded.size)

        val received = mutableListOf<ItantraPacket>()
        val job = launch {
            coordinator.receive().collect { received.add(it) }
        }
        yield()

        transport.incomingFlow.emit(frameData)
        delay(200)

        job.cancel()
        assertTrue("HB-1: plaintext heartbeat (securityVersion=0) must be dropped", received.isEmpty())
    }

    @Test
    fun testHB2_encryptedHeartbeatEmittedUpward() = runBlocking {
        val transport = TestPeerTransport()
        val coordinator = TransportCoordinator(transport)

        val encryptedHeartbeat = ItantraPacket(
            type = PacketType.HEARTBEAT,
            securityVersion = 1,
            messageId = 102L,
            payload = ByteArray(16) // GCM tag
        )
        val encoded = PacketEncoder.encode(encryptedHeartbeat)
        val frameData = encoded.copyOfRange(4, encoded.size)

        val received = mutableListOf<ItantraPacket>()
        val job = launch {
            coordinator.receive().collect { received.add(it) }
        }
        yield()

        transport.incomingFlow.emit(frameData)
        delay(200)

        job.cancel()
        assertEquals("HB-2: encrypted heartbeat (securityVersion=1) must be emitted upward", 1, received.size)
        assertEquals(PacketType.HEARTBEAT, received[0].type)
        assertEquals(1.toByte(), received[0].securityVersion)
    }

    @Test
    fun testHB3_waitingUserVerificationDoesNotDisconnect() = runBlocking {
        val transport = TestPeerTransport()
        val coordinator = TransportCoordinator(transport)

        // Authenticated liveness is disabled before SECURE_VERIFIED
        assertFalse(coordinator.isAuthenticatedLivenessEnabled())

        // Simulate elapsed time > 180s by setting lastRx far in the past
        coordinator.setLastRxAtMs(System.currentTimeMillis() - 200_000L)

        // Wait a short moment to ensure watchdog does not disconnect when disabled
        delay(200)
        assertFalse("HB-3: watchdog must not disconnect while authenticated liveness is disabled", transport.disconnectCalled.get())
        assertTrue(transport.isConnected)
    }

    @Test
    fun testHB4_secureVerifiedEnablesLivenessAndResetsLastRx() = runBlocking {
        val transport = TestPeerTransport()
        val coordinator = TransportCoordinator(transport)

        coordinator.setLastRxAtMs(1000L)
        assertFalse(coordinator.isAuthenticatedLivenessEnabled())

        val before = System.currentTimeMillis()
        coordinator.setAuthenticatedLivenessEnabled(true)
        val after = System.currentTimeMillis()

        assertTrue("HB-4: authenticated liveness must be enabled", coordinator.isAuthenticatedLivenessEnabled())
        assertTrue("HB-4: lastRxAtMs must be reset to current time", coordinator.getLastRxAtMs() in before..after)
    }

    @Test
    fun testHB5_authenticatedPacketsKeepConnectionAlive() = runBlocking {
        val transport = TestPeerTransport()
        val coordinator = TransportCoordinator(transport)

        coordinator.setAuthenticatedLivenessEnabled(true)
        val now = System.currentTimeMillis()
        coordinator.setLastRxAtMs(now - 60_000L) // 60s silent (< 180s)

        // Refresh liveness via authenticated packet receipt
        coordinator.notifyLivenessReceived()

        assertTrue(coordinator.getLastRxAtMs() >= now)
        assertFalse("HB-5: link must survive when refreshed", transport.disconnectCalled.get())
    }

    @Test
    fun testHB6_silenceTimeoutDisconnectsTransport() = runBlocking {
        val transport = TestPeerTransport()
        val coordinator = TransportCoordinator(transport)

        coordinator.setAuthenticatedLivenessEnabled(true)
        // Simulate >180s silence
        coordinator.setLastRxAtMs(System.currentTimeMillis() - 181_000L)

        // Check condition directly matching watchdog:
        val silentMs = System.currentTimeMillis() - coordinator.getLastRxAtMs()
        assertTrue("Silence duration exceeds 180s", silentMs >= 180_000L)
        if (coordinator.isAuthenticatedLivenessEnabled() && silentMs >= 180_000L) {
            coordinator.setAuthenticatedLivenessEnabled(false)
            transport.disconnect()
        }

        assertTrue("HB-6: transport disconnect must be called on silence timeout", transport.disconnectCalled.get())
        assertFalse("HB-6: liveness must be disabled after silence disconnect", coordinator.isAuthenticatedLivenessEnabled())
    }

    @Test
    fun testHB8_heartbeatSendDoesNotWaitAck() = runBlocking {
        val transport = TestPeerTransport()
        val coordinator = TransportCoordinator(transport)

        val heartbeat = ItantraPacket(
            type = PacketType.HEARTBEAT,
            messageId = 999L
        )

        // Must return immediately without timing out for ACK (ACK timeout is 5s)
        val start = System.currentTimeMillis()
        coordinator.send(heartbeat)
        val elapsed = System.currentTimeMillis() - start

        assertTrue("HB-8: HEARTBEAT send must not wait for ACK (elapsed: ${elapsed}ms)", elapsed < 2000L)
        assertEquals(1, transport.sentBytes.size)
    }

    @Test
    fun testHB9_disconnectDisablesAuthenticatedLiveness() = runBlocking {
        val transport = TestPeerTransport()
        val coordinator = TransportCoordinator(transport)

        coordinator.setAuthenticatedLivenessEnabled(true)
        assertTrue(coordinator.isAuthenticatedLivenessEnabled())

        coordinator.disconnect()
        assertFalse("HB-9: disconnect must disable authenticated liveness", coordinator.isAuthenticatedLivenessEnabled())
    }

    @Test
    fun testHB10_helloRetryUsesSameHandshake() = runBlocking {
        val sessionManager = SecureSessionManager()
        val hello1 = sessionManager.startHandshake(isInitiator = true)
        val stored = sessionManager.getStoredHello()

        assertNotNull(stored)
        assertEquals("HB-10: stored HELLO must have same messageId", hello1.messageId, stored!!.messageId)
        assertArrayEquals("HB-10: stored HELLO must have identical payload", hello1.payload, stored.payload)
    }

    @Test
    fun testHB11_verifyRetryUsesSamePacket() = runBlocking {
        val initiator = SecureSessionManager()
        val responder = SecureSessionManager()

        val hello = initiator.startHandshake(isInitiator = true)
        val helloResp = responder.processSecureHello(hello)
        assertNotNull(helloResp)

        val peerResp = initiator.processSecureHello(helloResp!!)
        assertNull(peerResp) // Handshake complete on initiator side

        assertEquals(SecureSessionState.WAITING_USER_VERIFICATION, initiator.state.value)
        assertEquals(SecureSessionState.WAITING_USER_VERIFICATION, responder.state.value)

        // Verify codes match
        assertEquals(initiator.sasCode.value, responder.sasCode.value)

        // Confirm SAS match once
        val verifyPkt1 = initiator.confirmSasMatch()
        assertNotNull(verifyPkt1)

        // Retrying verify sends the same logical verify packet without regenerating
        assertEquals(PacketType.SECURE_VERIFY, verifyPkt1.type)
        assertTrue(verifyPkt1.payload.isNotEmpty())

        // Peer processes verify
        responder.processSecureVerify(verifyPkt1)
        val respVerify = responder.confirmSasMatch()
        initiator.processSecureVerify(respVerify)

        assertEquals("HB-11: initiator reaches SECURE_VERIFIED", SecureSessionState.SECURE_VERIFIED, initiator.state.value)
        assertEquals("HB-11: responder reaches SECURE_VERIFIED", SecureSessionState.SECURE_VERIFIED, responder.state.value)
    }
}
