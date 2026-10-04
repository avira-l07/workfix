package com.itantra.core.transport.packet

import com.itantra.domain.model.LanguageCode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Payload exchanged during iTantra Application Handshake (PacketType.PROFILE_HANDSHAKE).
 */
data class ProfilePayload(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val deviceId: String,
    val displayName: String,
    val supportedLanguages: List<LanguageCode>
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("v", protocolVersion)
            put("id", deviceId)
            put("name", displayName)
            val langs = JSONArray()
            supportedLanguages.forEach { langs.put(it.wireCode) }
            put("langs", langs)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val CURRENT_PROTOCOL_VERSION = 1
        private val deviceIdPattern = Regex("IT-[0-9A-F]{4}-[0-9A-F]{4}")
        private const val MAX_PROFILE_BYTES = 2048

        fun fromBytes(bytes: ByteArray): ProfilePayload? {
            if (bytes.isEmpty() || bytes.size > MAX_PROFILE_BYTES) return null
            return try {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val v = json.get("v") as? Int ?: return null
                val id = json.getString("id")
                val name = json.getString("name")
                val langArray = json.getJSONArray("langs")
                if (v != CURRENT_PROTOCOL_VERSION || !deviceIdPattern.matches(id) ||
                    name.isBlank() || name.length > 128 || name.any { it.isISOControl() } ||
                    langArray.length() !in 1..LanguageCode.entries.size) return null
                val langs = mutableListOf<LanguageCode>()
                for (i in 0 until langArray.length()) {
                    val language = LanguageCode.fromWireCode(langArray.getString(i)) ?: return null
                    if (language in langs) return null
                    langs.add(language)
                }
                ProfilePayload(
                    protocolVersion = v,
                    deviceId = id,
                    displayName = name,
                    supportedLanguages = langs
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
