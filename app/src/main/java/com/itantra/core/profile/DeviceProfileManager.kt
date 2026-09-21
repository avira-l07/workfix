package com.itantra.core.profile

import android.content.Context
import android.content.SharedPreferences
import com.itantra.domain.model.DeviceProfile
import com.itantra.domain.model.LanguageCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/**
 * Manages the stable local iTantra device identity.
 * Generates an offline persistent identifier formatted as "IT-XXXX-XXXX" once on first install,
 * and maintains display name and active language settings.
 */
class DeviceProfileManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "itantra_device_profile"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_ACTIVE_LANG = "active_lang"

        /**
         * Generates a stable format identifier: "IT-XXXX-XXXX" using 8 uppercase hex characters.
         * Independent of phone number, IMEI, or hardware MAC address.
         */
        fun generateNewDeviceId(): String {
            val random = SecureRandom()
            val bytes = ByteArray(4)
            random.nextBytes(bytes)
            val part1 = "%02X%02X".format(bytes[0], bytes[1])
            val part2 = "%02X%02X".format(bytes[2], bytes[3])
            return "IT-$part1-$part2"
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _profile: MutableStateFlow<DeviceProfile>
    val profile: StateFlow<DeviceProfile>

    init {
        var devId = prefs.getString(KEY_DEVICE_ID, null)
        if (devId == null || !devId.startsWith("IT-")) {
            devId = generateNewDeviceId()
            prefs.edit().putString(KEY_DEVICE_ID, devId).apply()
        }

        val defaultName = android.os.Build.MODEL?.takeIf { it.isNotBlank() }?.let { "$it's iTantra" }
            ?: "iTantra Operator"
        val savedName = prefs.getString(KEY_DISPLAY_NAME, defaultName) ?: defaultName
        val savedLangCode = prefs.getString(KEY_ACTIVE_LANG, LanguageCode.ENGLISH.wireCode) ?: LanguageCode.ENGLISH.wireCode
        val lang = LanguageCode.fromWireCode(savedLangCode) ?: LanguageCode.ENGLISH

        _profile = MutableStateFlow(
            DeviceProfile(
                deviceId = devId,
                displayName = savedName,
                activeLanguage = lang
            )
        )
        profile = _profile.asStateFlow()
    }

    val currentDeviceId: String
        get() = _profile.value.deviceId

    val currentDisplayName: String
        get() = _profile.value.displayName

    fun updateDisplayName(name: String) {
        val trimmed = name.trim().take(32)
        if (trimmed.isNotBlank()) {
            prefs.edit().putString(KEY_DISPLAY_NAME, trimmed).apply()
            _profile.value = _profile.value.copy(displayName = trimmed)
        }
    }

    fun updateActiveLanguage(languageCode: LanguageCode) {
        prefs.edit().putString(KEY_ACTIVE_LANG, languageCode.wireCode).apply()
        _profile.value = _profile.value.copy(activeLanguage = languageCode)
    }
}
