package com.itantra.domain.model

data class DeviceProfile(
    val deviceId: String,       // Persistent unique local identity, e.g. "IT-7F3A-91C2"
    val displayName: String,    // User-customizable display name, e.g. "Aviral's iTantra"
    val activeLanguage: LanguageCode
)
