package com.itantra.core.transport.peer

import android.net.wifi.p2p.WifiP2pDevice

/**
 * Wi-Fi Direct peer device representation.
 *
 * Distinct from Bluetooth MAC/device IDs to prevent cross-transport confusion.
 */
data class WifiDirectPeer(
    val deviceAddress: String,
    val deviceName: String,
    val status: Int,
    val isGroupOwner: Boolean = false
) {
    /**
     * Human-readable status mapping from [WifiP2pDevice] status codes.
     */
    val statusDisplay: String
        get() = when (status) {
            WifiP2pDevice.AVAILABLE -> "Available"
            WifiP2pDevice.INVITED -> "Invited"
            WifiP2pDevice.CONNECTED -> "Connected"
            WifiP2pDevice.FAILED -> "Failed"
            WifiP2pDevice.UNAVAILABLE -> "Unavailable"
            else -> "Unknown ($status)"
        }

    /**
     * Masked device address for privacy / safe logging.
     */
    val maskedAddress: String
        get() = if (deviceAddress.length >= 8) {
            "${deviceAddress.take(8)}***"
        } else {
            "***"
        }

    companion object {
        fun fromWifiP2pDevice(device: WifiP2pDevice): WifiDirectPeer {
            val name = if (device.deviceName.isNullOrBlank()) "Direct-Device" else device.deviceName
            val addr = device.deviceAddress ?: ""
            return WifiDirectPeer(
                deviceAddress = addr,
                deviceName = name,
                status = device.status,
                isGroupOwner = device.isGroupOwner
            )
        }
    }
}
