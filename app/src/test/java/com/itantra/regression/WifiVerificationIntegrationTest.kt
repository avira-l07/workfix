package com.itantra.regression

import com.itantra.core.crypto.SecureSessionState
import com.itantra.core.transport.TransportCoordinator
import com.itantra.core.transport.peer.WifiDirectPeerTransport
import com.itantra.core.transport.peer.WifiDirectError
import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.core.transport.packet.PacketType
import com.itantra.domain.model.LanguageCode
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class WifiVerificationIntegrationTest {
    @Test fun `real TCP peers verify in both acceptance orders exchange messages and reconnect`() = runBlocking {
        val serverSocketTransport = WifiDirectPeerTransport()
        val clientSocketTransport = WifiDirectPeerTransport()
        val serverTransport = TransportCoordinator(serverSocketTransport)
        val clientTransport = TransportCoordinator(clientSocketTransport)
        val server = BugfixFixture(transportOverride = serverTransport, deviceId = "IT-AAAA-0001")
        val client = BugfixFixture(transportOverride = clientTransport, deviceId = "IT-BBBB-0002")
        var staleConfirmation: ItantraPacket? = null
        val serverGeneration = serverSocketTransport.javaClass.getDeclaredField("connectionGeneration")
            .apply { isAccessible = true }.get(serverSocketTransport) as java.util.concurrent.atomic.AtomicLong
        var staleGeneration = -1L
        try {
            // Reuse the same coordinator/transport objects so old readers and confirmations
            // cannot tear down or authenticate the second connection.
            for (round in 0..1) {
                val port = ServerSocket(0).use { it.localPort }
                serverSocketTransport.startServer(port)
                clientSocketTransport.connectToHost(InetAddress.getLoopbackAddress(), port)
                server.awaitCondition { server.secure.state.value == SecureSessionState.WAITING_USER_VERIFICATION &&
                    client.secure.state.value == SecureSessionState.WAITING_USER_VERIFICATION }
                assertEquals(server.secure.sasCode.value, client.secure.sasCode.value)
                assertTrue(serverTransport.isServer)
                assertFalse(clientTransport.isServer)
                if (round == 1) {
                    server.coordinator.handleIncomingPacket(staleConfirmation!!)
                    assertFalse(server.secure.peerSasConfirmed)
                    assertFalse(server.secure.localSasConfirmed)
                    // An old socket's delayed error must not disconnect the new socket.
                    serverSocketTransport.javaClass.getDeclaredMethod("failConnection",
                        Long::class.javaPrimitiveType, WifiDirectError::class.java)
                        .apply { isAccessible = true }
                        .invoke(serverSocketTransport, staleGeneration, WifiDirectError.SOCKET_CLOSED)
                    assertTrue(serverTransport.isConnected)
                }

                val first = if (round == 0) client else server
                val second = if (round == 0) server else client
                first.coordinator.confirmPeerVerification()
                second.awaitCondition { second.secure.peerSasConfirmed }
                if (round == 0) {
                    staleConfirmation = first.field("cachedVerifyPacket") as ItantraPacket
                    staleGeneration = serverGeneration.get()
                }
                assertEquals(SecureSessionState.WAITING_USER_VERIFICATION, first.secure.state.value)
                assertEquals(SecureSessionState.WAITING_USER_VERIFICATION, second.secure.state.value)
                second.coordinator.confirmPeerVerification()
                server.awaitCondition { server.secure.state.value == SecureSessionState.SECURE_VERIFIED &&
                    client.secure.state.value == SecureSessionState.SECURE_VERIFIED }
                server.awaitCondition { server.coordinator.activePeerProfile.value?.deviceId == "IT-BBBB-0002" &&
                    client.coordinator.activePeerProfile.value?.deviceId == "IT-AAAA-0001" }
                server.awaitCondition { server.field("cachedVerifyPacket") == null && client.field("cachedVerifyPacket") == null }

                for ((sender, receiver) in listOf(client to server, server to client)) {
                    val id = 100L + round * 10 + if (sender === client) 1 else 2
                    val packet = ItantraPacket(PacketType.TEXT, languageCode = LanguageCode.ENGLISH,
                        messageId = id, payload = "TCP message $id".toByteArray())
                    sender.transport.send(sender.secure.encrypt(packet))
                    receiver.awaitCondition { receiver.coordinator.messages.value.any { it.messageId == id && it.text == "TCP message $id" } }
                }

                clientSocketTransport.disconnect()
                serverSocketTransport.disconnect()
                server.awaitCondition { server.secure.state.value == SecureSessionState.NO_SESSION &&
                    client.secure.state.value == SecureSessionState.NO_SESSION &&
                    !server.secure.localSasConfirmation.value && !client.secure.localSasConfirmation.value &&
                    server.coordinator.activePeerProfile.value == null && client.coordinator.activePeerProfile.value == null }
                assertFalse(server.secure.localSasConfirmation.value)
                assertFalse(client.secure.localSasConfirmation.value)
                assertNull(server.coordinator.activePeerProfile.value)
                assertNull(client.coordinator.activePeerProfile.value)
            }
        } finally {
            clientSocketTransport.disconnect()
            serverSocketTransport.disconnect()
            client.close()
            server.close()
            clientTransport.shutdown()
            serverTransport.shutdown()
        }
    }
}
