package com.itantra.core.transport.peer

import com.itantra.core.transport.ConnectionState
import com.itantra.core.transport.TransportCoordinator
import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.core.transport.packet.PacketType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class WifiDirectPeerTransportTest {

    @Test
    fun testWifiDirectPeerDataModelAndMasking() {
        val peer = WifiDirectPeer(
            deviceAddress = "02:1a:2b:3c:4d:5e",
            deviceName = "Tactical Unit A",
            status = 3, // WifiP2pDevice.AVAILABLE = 3
            isGroupOwner = false
        )

        assertEquals("02:1a:2b:3c:4d:5e", peer.deviceAddress)
        assertEquals("Tactical Unit A", peer.deviceName)
        assertEquals("Available", peer.statusDisplay)
        assertEquals("02:1a:2b***", peer.maskedAddress)
        assertFalse(peer.isGroupOwner)
    }

    @Test
    fun testWifiDirectPeerTransportInitialStateAndGuards() = runBlocking {
        val transport = WifiDirectPeerTransport()

        assertFalse("Transport must start disconnected", transport.isConnected)
        assertFalse("Transport must not be server initially", transport.isServer)

        // Sending when disconnected must throw IOException
        try {
            transport.send("Test payload".toByteArray())
            fail("send() must throw IOException when disconnected")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("not connected") == true)
        }

        // Disconnect when already disconnected should be safe and idempotent
        transport.disconnect()
        assertFalse(transport.isConnected)
    }

    @Test
    fun testTransportCoordinatorSwitchTransport() = runBlocking {
        val initialTransport = WifiDirectPeerTransport()
        val coordinator = TransportCoordinator(initialTransport)

        assertFalse(coordinator.isConnected)

        val newTransport = WifiDirectPeerTransport()
        coordinator.switchTransport(newTransport)

        assertEquals(newTransport, coordinator.activeTransportFlow.value)
        assertFalse(coordinator.isConnected)

        coordinator.disconnect()
    }
}
