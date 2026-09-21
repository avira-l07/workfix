package com.example.itantra.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * Message category/type as specified in PROTOCOL.md §2:
 * NORMAL | URGENT | ACK | NACK | PING | PONG | SYSTEM
 */
enum class MessageType(val id: Byte) {
    NORMAL(0),
    URGENT(1),
    ACK(2),
    NACK(3),
    PING(4),
    PONG(5),
    SYSTEM(6);

    companion object {
        fun fromId(id: Byte): MessageType = entries.find { it.id == id } ?: NORMAL
    }
}

/**
 * Priority classification:
 * 0 = NORMAL, 1 = HIGH, 2 = EMERGENCY
 */
enum class PacketPriority(val value: Byte) {
    NORMAL(0),
    HIGH(1),
    EMERGENCY(2);

    companion object {
        fun fromValue(value: Byte): PacketPriority = entries.find { it.value == value } ?: NORMAL
    }
}

/**
 * Payload encoding format:
 * 0 = UTF8_TEXT, 1 = OPUS_AUDIO, 2 = RAW_BINARY
 */
enum class PayloadEncoding(val value: Byte) {
    UTF8_TEXT(0),
    OPUS_AUDIO(1),
    RAW_BINARY(2);

    companion object {
        fun fromValue(value: Byte): PayloadEncoding = entries.find { it.value == value } ?: UTF8_TEXT
    }
}

/**
 * Production-ready wire-level implementation of the 12-field iTantra protocol.
 * Follows PROTOCOL.md:
 * 1.  VERSION (1 byte)
 * 2.  MESSAGE_ID (8 bytes, Long)
 * 3.  DEVICE_ID (2 bytes length + UTF-8 string)
 * 4.  LANGUAGE (2 bytes length + UTF-8 string)
 * 5.  MESSAGE_TYPE (1 byte, enum: NORMAL, URGENT, ACK, NACK, PING, PONG, SYSTEM)
 * 6.  PRIORITY (1 byte: NORMAL=0, HIGH=1, EMERGENCY=2)
 * 7.  TIMESTAMP (8 bytes, Long epoch millis)
 * 8.  SEQUENCE (4 bytes, Int)
 * 9.  PAYLOAD_LENGTH (4 bytes, Int)
 * 10. PAYLOAD_ENCODING (1 byte, enum: UTF8_TEXT, OPUS_AUDIO, RAW_BINARY)
 * 11. PAYLOAD (byte array of PAYLOAD_LENGTH)
 * 12. CHECKSUM (8 bytes, CRC-32 computed over fields 1 through 11)
 */
data class PacketProtocol(
    val version: Byte = CURRENT_VERSION,
    val messageId: Long,
    val deviceId: String,
    val language: String,
    val messageType: MessageType,
    val priority: PacketPriority = PacketPriority.NORMAL,
    val timestamp: Long = System.currentTimeMillis(),
    val sequence: Int,
    val payloadEncoding: PayloadEncoding = PayloadEncoding.UTF8_TEXT,
    val payload: ByteArray,
    val checksum: Long = 0L
) {
    val payloadLength: Int get() = payload.size

    /**
     * Serializes this packet into a network-ready big-endian byte array including the CRC32 checksum.
     */
    fun toByteArray(): ByteArray {
        val deviceIdBytes = deviceId.toByteArray(StandardCharsets.UTF_8)
        val languageBytes = language.toByteArray(StandardCharsets.UTF_8)

        val totalSizeWithoutChecksum = 1 + // version
                8 + // messageId
                2 + deviceIdBytes.size + // deviceId length prefix + bytes
                2 + languageBytes.size + // language length prefix + bytes
                1 + // messageType
                1 + // priority
                8 + // timestamp
                4 + // sequence
                4 + // payloadLength
                1 + // payloadEncoding
                payload.size // payload

        val totalSize = totalSizeWithoutChecksum + 8 // 8 bytes for checksum

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(version)
        buffer.putLong(messageId)
        buffer.putShort(deviceIdBytes.size.toShort())
        buffer.put(deviceIdBytes)
        buffer.putShort(languageBytes.size.toShort())
        buffer.put(languageBytes)
        buffer.put(messageType.id)
        buffer.put(priority.value)
        buffer.putLong(timestamp)
        buffer.putInt(sequence)
        buffer.putInt(payload.size)
        buffer.put(payloadEncoding.value)
        buffer.put(payload)

        // Compute CRC-32 over fields 1..11
        val crc = CRC32()
        crc.update(buffer.array(), 0, totalSizeWithoutChecksum)
        val computedChecksum = crc.value

        buffer.putLong(computedChecksum)
        return buffer.array()
    }

    /**
     * Creates an ACK response packet referencing this message's messageId and sequence.
     */
    fun createAck(responderDeviceId: String): PacketProtocol {
        return PacketProtocol(
            version = this.version,
            messageId = this.messageId,
            deviceId = responderDeviceId,
            language = this.language,
            messageType = MessageType.ACK,
            priority = this.priority,
            timestamp = System.currentTimeMillis(),
            sequence = this.sequence,
            payloadEncoding = PayloadEncoding.UTF8_TEXT,
            payload = "ACK".toByteArray(StandardCharsets.UTF_8)
        )
    }

    /**
     * Creates a NACK response packet referencing this message's messageId and sequence.
     */
    fun createNack(responderDeviceId: String, reason: String = "REJECT"): PacketProtocol {
        return PacketProtocol(
            version = this.version,
            messageId = this.messageId,
            deviceId = responderDeviceId,
            language = this.language,
            messageType = MessageType.NACK,
            priority = this.priority,
            timestamp = System.currentTimeMillis(),
            sequence = this.sequence,
            payloadEncoding = PayloadEncoding.UTF8_TEXT,
            payload = reason.toByteArray(StandardCharsets.UTF_8)
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PacketProtocol

        if (version != other.version) return false
        if (messageId != other.messageId) return false
        if (deviceId != other.deviceId) return false
        if (language != other.language) return false
        if (messageType != other.messageType) return false
        if (priority != other.priority) return false
        if (timestamp != other.timestamp) return false
        if (sequence != other.sequence) return false
        if (payloadEncoding != other.payloadEncoding) return false
        if (!payload.contentEquals(other.payload)) return false
        if (checksum != other.checksum) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + language.hashCode()
        result = 31 * result + messageType.hashCode()
        result = 31 * result + priority.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + sequence
        result = 31 * result + payloadEncoding.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + checksum.hashCode()
        return result
    }

    companion object {
        const val CURRENT_VERSION: Byte = 1
        const val MAX_PACKET_SIZE = 65536 // 64 KB safety limit
        const val HEADER_MIN_SIZE = 1 + 8 + 2 + 2 + 1 + 1 + 8 + 4 + 4 + 1 + 8 // 39 bytes minimum

        /**
         * Deserializes a byte array into a PacketProtocol instance with strict validation and CRC-32 verification.
         * Throws IllegalArgumentException on corruption, truncation, or checksum failure.
         */
        @Throws(IllegalArgumentException::class)
        fun fromByteArray(data: ByteArray): PacketProtocol {
            require(data.size >= HEADER_MIN_SIZE) {
                "Packet too short: size=${data.size}, minimum=$HEADER_MIN_SIZE"
            }
            require(data.size <= MAX_PACKET_SIZE) {
                "Packet exceeds max allowable size: size=${data.size}, max=$MAX_PACKET_SIZE"
            }

            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val version = buffer.get()
            val messageId = buffer.getLong()

            val deviceIdLen = buffer.getShort().toInt() and 0xFFFF
            require(buffer.remaining() >= deviceIdLen) { "Malformed deviceId length: $deviceIdLen" }
            val deviceIdBytes = ByteArray(deviceIdLen)
            buffer.get(deviceIdBytes)
            val deviceId = String(deviceIdBytes, StandardCharsets.UTF_8)

            val languageLen = buffer.getShort().toInt() and 0xFFFF
            require(buffer.remaining() >= languageLen) { "Malformed language length: $languageLen" }
            val languageBytes = ByteArray(languageLen)
            buffer.get(languageBytes)
            val language = String(languageBytes, StandardCharsets.UTF_8)

            val messageType = MessageType.fromId(buffer.get())
            val priority = PacketPriority.fromValue(buffer.get())
            val timestamp = buffer.getLong()
            val sequence = buffer.getInt()

            val payloadLength = buffer.getInt()
            require(payloadLength >= 0) { "Negative payload length: $payloadLength" }
            require(buffer.remaining() >= payloadLength + 8) {
                "Buffer underflow for payload: required=${payloadLength + 8}, available=${buffer.remaining()}"
            }

            val payloadEncoding = PayloadEncoding.fromValue(buffer.get())
            val payload = ByteArray(payloadLength)
            buffer.get(payload)

            val expectedChecksum = buffer.getLong()

            // Verify CRC-32
            val totalSizeWithoutChecksum = data.size - 8
            val crc = CRC32()
            crc.update(data, 0, totalSizeWithoutChecksum)
            val computedChecksum = crc.value

            require(computedChecksum == expectedChecksum) {
                "Checksum mismatch: computed=$computedChecksum, packet=$expectedChecksum"
            }

            return PacketProtocol(
                version = version,
                messageId = messageId,
                deviceId = deviceId,
                language = language,
                messageType = messageType,
                priority = priority,
                timestamp = timestamp,
                sequence = sequence,
                payloadEncoding = payloadEncoding,
                payload = payload,
                checksum = expectedChecksum
            )
        }
    }
}
