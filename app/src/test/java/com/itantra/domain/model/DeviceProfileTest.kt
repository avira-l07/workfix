package com.itantra.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    @Test
    fun testDeviceIdFormat() {
        val idRegex = Regex("^IT-[0-9A-F]{4}-[0-9A-F]{4}$")
        val sampleId = "IT-7F3A-91C2"
        assertTrue(idRegex.matches(sampleId))
    }

    @Test
    fun testProfileImmutabilityOfDeviceId() {
        val profile = DeviceProfile(
            deviceId = "IT-A1B2-C3D4",
            displayName = "User Alpha",
            activeLanguage = LanguageCode.EN
        )
        val updated = profile.copy(displayName = "User Beta")
        assertEquals(profile.deviceId, updated.deviceId)
        assertEquals("User Beta", updated.displayName)
    }

    @Test
    fun testPeerProfileDisplayIdFallback() {
        val pendingPeer = PeerProfile(
            deviceId = null,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            displayName = "Unknown Device"
        )
        assertEquals("iTantra ID pending", pendingPeer.displayId)

        val identifiedPeer = pendingPeer.copy(deviceId = "IT-1234-5678")
        assertEquals("IT-1234-5678", identifiedPeer.displayId)
    }
}
