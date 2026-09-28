package com.itantra.core.transport.packet

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compact binary payload for device GPS coordinates (PacketType.LOCATION).
 * Total size: exactly 28 bytes.
 * - latitude: Double (8 bytes)
 * - longitude: Double (8 bytes)
 * - accuracyMeters: Float (4 bytes)
 * - timestampMillis: Long (8 bytes)
 */
data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMillis: Long
) {
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(PAYLOAD_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.putDouble(latitude)
        buffer.putDouble(longitude)
        buffer.putFloat(accuracyMeters)
        buffer.putLong(timestampMillis)
        return buffer.array()
    }

    companion object {
        const val PAYLOAD_SIZE = 28

        fun fromBytes(bytes: ByteArray): LocationPayload {
            if (bytes.size < PAYLOAD_SIZE) {
                throw IllegalArgumentException("Location payload too short: ${bytes.size} bytes (expected at least $PAYLOAD_SIZE)")
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val latitude = buffer.getDouble()
            val longitude = buffer.getDouble()
            val accuracy = buffer.getFloat()
            val timestamp = buffer.getLong()
            return LocationPayload(
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracy,
                timestampMillis = timestamp
            )
        }
    }
}
