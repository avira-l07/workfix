package com.itantra.regression

import com.itantra.core.crypto.SecureSessionState
import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.core.transport.packet.PacketType
import com.itantra.domain.model.TransmissionMetrics
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class VerificationDeliveryTest {
    private fun preparePeerConfirmed(f: BugfixFixture) {
        val hello = f.secure.startHandshake(true)
        f.secure.processSecureHello(f.remote.processSecureHello(hello)!!)
        f.coordinator.handleIncomingPacket(f.remote.confirmSasMatch())
        assertEquals(SecureSessionState.WAITING_USER_VERIFICATION, f.secure.state.value)
    }

    @Test fun `second acceptance cannot cancel a suspended final confirmation send`() = runBlocking {
        BugfixFixture().use { f ->
            preparePeerConfirmed(f)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delivered = CompletableDeferred<Unit>()
            f.sendAction = { packet ->
                if (packet.type == PacketType.SECURE_VERIFY) {
                    started.complete(Unit)
                    release.await()
                    f.remote.processSecureVerify(packet)
                    delivered.complete(Unit)
                }
                TransmissionMetrics()
            }
            f.coordinator.confirmPeerVerification()
            withTimeout(3000) { started.await() }
            f.awaitCondition { f.secure.state.value == SecureSessionState.SECURE_VERIFIED &&
                f.field("sasTimerJob") == null }
            // Allow the SECURE_VERIFIED observer to finish while the socket write is suspended.
            delay(100)
            release.complete(Unit)
            withTimeout(3000) { delivered.await() }
            assertEquals(SecureSessionState.SECURE_VERIFIED, f.remote.state.value)
            assertTrue(f.secure.localSasConfirmation.value)
            assertNotNull(f.field("cachedVerifyPacket"))
            f.coordinator.handleIncomingPacket(f.remote.encrypt(ItantraPacket(PacketType.HEARTBEAT, messageId = 7)))
            f.awaitCondition { f.field("cachedVerifyPacket") == null }
            f.secure.resetSession()
            assertFalse(f.secure.localSasConfirmation.value)
        }
    }

    @Test fun `failed final send retries after local verification and only authenticated traffic stops retries`() = runBlocking {
        BugfixFixture().use { f ->
            preparePeerConfirmed(f)
            val attempts = AtomicInteger()
            val delivered = CompletableDeferred<Unit>()
            f.sendAction = { packet ->
                if (packet.type == PacketType.SECURE_VERIFY) {
                    if (attempts.incrementAndGet() == 1) throw IOException("Simulated socket write failure")
                    f.remote.processSecureVerify(packet)
                    delivered.complete(Unit)
                }
                TransmissionMetrics()
            }
            f.coordinator.confirmPeerVerification()
            f.awaitCondition { attempts.get() == 1 }
            assertEquals(SecureSessionState.SECURE_VERIFIED, f.secure.state.value)
            val pending = f.field("cachedVerifyPacket")
            f.coordinator.handleIncomingPacket(ItantraPacket(PacketType.HEARTBEAT, messageId = 8))
            f.coordinator.handleIncomingPacket(ItantraPacket(PacketType.SECURE_VERIFY, messageId = 9, payload = ByteArray(32)))
            assertSame(pending, f.field("cachedVerifyPacket"))
            withTimeout(5000) { delivered.await() }
            assertEquals(SecureSessionState.SECURE_VERIFIED, f.remote.state.value)
            assertTrue(attempts.get() >= 2)
            f.coordinator.handleIncomingPacket(f.remote.encrypt(ItantraPacket(PacketType.HEARTBEAT, messageId = 10)))
            f.awaitCondition { f.field("cachedVerifyPacket") == null }
        }
    }
}
