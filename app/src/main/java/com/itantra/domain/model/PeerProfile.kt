package com.itantra.domain.model

data class PeerProfile(
    val deviceId: String? = null,           // Handshake-exchanged iTantra ID, e.g. "IT-31A8-55D1"
    val bluetoothAddress: String = "",       // Hardware MAC/identifier
    val displayName: String = "",            // Remote user's name
    val lastSeen: Long = System.currentTimeMillis(),
    val isConnected: Boolean = false,
    val activeLanguage: LanguageCode? = null,
    val lastMessageAt: Long? = null
) {
    val displayId: String
        get() = deviceId ?: "iTantra ID pending"
}
