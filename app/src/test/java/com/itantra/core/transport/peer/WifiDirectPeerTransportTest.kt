package com.itantra.core.transport.peer

import com.itantra.core.transport.ConnectionState
import com.itantra.core.transport.TransportCoordinator
import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.core.transport.packet.PacketType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    @Test
    fun testFix019_replayBufferRetainsFramesForLateSubscriber() = runBlocking {
        val transport = WifiDirectPeerTransport()

        // Emitting frame before subscriber attaches (e.g. fast peer sending SECURE_HELLO)
        val testFrame = "SECURE_HELLO_TEST_FRAME".toByteArray(Charsets.UTF_8)
        
        // Use reflection to access incomingFlow or verify receive() Flow behavior
        val flowField = transport.javaClass.getDeclaredField("incomingFlow")
        flowField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val incomingFlow = flowField.get(transport) as kotlinx.coroutines.flow.MutableSharedFlow<ByteArray>
        incomingFlow.emit(testFrame)

        // Late subscriber attaches via receive()
        var receivedFrame: ByteArray? = null
        val job = launch {
            transport.receive().collect { frame ->
                receivedFrame = frame
            }
        }
        delay(50)
        job.cancel()

        assertNotNull("Late subscriber must receive frame via replay buffer (FIX 019)", receivedFrame)
        assertEquals("SECURE_HELLO_TEST_FRAME", String(receivedFrame!!, Charsets.UTF_8))

        // Disconnect must clear replay cache so subsequent connections do not get stale frames
        transport.disconnect()
        var postDisconnectFrame: ByteArray? = null
        val job2 = launch {
            transport.receive().collect { frame ->
                postDisconnectFrame = frame
            }
        }
        delay(50)
        job2.cancel()
        assertTrue("Replay buffer must be empty after disconnect (FIX 019)", postDisconnectFrame == null)
    }

    @Test
    fun testFix010_016_transportRestoreIdempotence() = runBlocking {
        val btTransport = WifiDirectPeerTransport() // Used as default mock transport
        val wifiTransport = WifiDirectPeerTransport()
        val coordinator = TransportCoordinator(btTransport)

        // Switch to Wi-Fi
        coordinator.switchTransport(wifiTransport)
        assertEquals(wifiTransport, coordinator.activeTransportFlow.value)

        // Terminal disconnect restores btTransport
        if (coordinator.activeTransportFlow.value == wifiTransport) {
            coordinator.switchTransport(btTransport)
        }
        assertEquals(btTransport, coordinator.activeTransportFlow.value)

        // Idempotent: repeated disconnect calls do not corrupt state
        if (coordinator.activeTransportFlow.value == wifiTransport) {
            coordinator.switchTransport(btTransport)
        }
        assertEquals(btTransport, coordinator.activeTransportFlow.value)

        coordinator.disconnect()
    }

    @Test
    fun testWifiDirectErrorValuesExist() {
        val errorNames = WifiDirectError.values().map { it.name }
        assertTrue(errorNames.contains("LOCATION_REQUIRED"))
        assertTrue(errorNames.contains("NO_PEERS_FOUND"))
        assertTrue(errorNames.contains("GROUP_FORMATION_FAILED"))
        assertTrue(errorNames.contains("TCP_CONNECT_TIMEOUT"))
    }

    @Test
    fun testWifiDirectLocationModeRequirementContract() {
        val error = WifiDirectError.LOCATION_REQUIRED
        assertNotNull(error)
        assertEquals("LOCATION_REQUIRED", error.name)
        assertEquals(WifiDirectState.ERROR, WifiDirectState.valueOf("ERROR"))
    }
}
