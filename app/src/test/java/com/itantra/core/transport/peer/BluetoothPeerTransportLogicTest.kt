package com.itantra.core.transport.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression and logic tests for Phase 2: Bluetooth compatibility and error states.
 */
class BluetoothPeerTransportLogicTest {

    @Test
    fun testBluetoothErrorEnumContainsAllPhase2Errors() {
        val errors = BluetoothError.values().map { it.name }
        assertTrue("Must contain NONE", errors.contains("NONE"))
        assertTrue("Must contain BLUETOOTH_DISABLED", errors.contains("BLUETOOTH_DISABLED"))
        assertTrue("Must contain PERMISSION_DENIED", errors.contains("PERMISSION_DENIED"))
        assertTrue("Must contain PAIRING_FAILED", errors.contains("PAIRING_FAILED"))
        assertTrue("Must contain CONNECT_TIMEOUT", errors.contains("CONNECT_TIMEOUT"))
        assertTrue("Must contain RFCOMM_CONNECT_FAILED", errors.contains("RFCOMM_CONNECT_FAILED"))
        assertTrue("Must contain BOND_LOST", errors.contains("BOND_LOST"))
        assertTrue("Must contain SOCKET_DISCONNECTED", errors.contains("SOCKET_DISCONNECTED"))
        assertTrue("Must contain DISCOVERABILITY_DENIED per FIX 004", errors.contains("DISCOVERABILITY_DENIED"))
    }

    @Test
    fun testBluetoothUuidAndServiceRecord() {
        assertEquals("20f01a35-26a1-432a-bc95-021b36d0130a", BluetoothPeerTransport.ITANTRA_UUID.toString())
        assertEquals("iTantraTransceiver", BluetoothPeerTransport.NAME)
    }
}
