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

        fun fromBytes(bytes: ByteArray): ProfilePayload? {
            return try {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val v = json.optInt("v", 1)
                val id = json.optString("id", "")
                val name = json.optString("name", "Unknown Operator")
                val langArray = json.optJSONArray("langs")
                val langs = mutableListOf<LanguageCode>()
                if (langArray != null) {
                    for (i in 0 until langArray.length()) {
                        LanguageCode.fromWireCode(langArray.getString(i))?.let { langs.add(it) }
                    }
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
