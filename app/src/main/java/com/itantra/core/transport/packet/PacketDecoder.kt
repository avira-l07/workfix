package com.itantra.core.transport.packet

import com.itantra.domain.model.TranslationMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class PacketDecodeException(message: String) : Exception(message)

/**
 * Decodes a completely framed ByteArray into an [ItantraPacket].
 * Assumes the 4-byte frame length prefix has already been consumed by the stream reader,
 * and the passed array contains exactly the `frameContentLength` bytes.
 */
object PacketDecoder {

    // Max payload constraint to prevent memory exhaustion (e.g. 16 KB)
    const val MAX_PAYLOAD_SIZE = 16 * 1024

    // Max frame content length = Header(32) + Payload + CRC(4) = Payload + 36
    const val MAX_FRAME_BODY_SIZE = MAX_PAYLOAD_SIZE + 36

    fun decode(framedData: ByteArray): ItantraPacket {
        // V2 minimum: 32-byte header + 0-byte payload + 4-byte CRC = 36 bytes.
        // V1 minimum is the same (32-byte header) + 4 CRC = 36 bytes.
        // Accepting < 36 bytes would cause a BufferUnderflowException when reading
        // the V2-only header fields (sourceLang, targetLang, translationMode).
        if (framedData.size < 36) {
            throw PacketDecodeException("Frame too small: ${framedData.size} bytes (min 36)")
        }

        val buffer = ByteBuffer.wrap(framedData).order(ByteOrder.BIG_ENDIAN)

        // 1. Verify CRC32
        val crcStart = 0
        val crcEnd = framedData.size - 4

        val crc32 = CRC32()
        crc32.update(framedData, crcStart, crcEnd)
        val expectedCrc = crc32.value.toInt()

        buffer.position(crcEnd)
        val actualCrc = buffer.getInt()
        if (expectedCrc != actualCrc) {
            throw PacketDecodeException("CRC32 mismatch! expected=${expectedCrc.toUInt().toString(16)}, actual=${actualCrc.toUInt().toString(16)}")
        }

        // Reset to read header
        buffer.position(0)

        // 2. Parse Header
        val magic = buffer.getInt()
        if (magic != PacketEncoder.MAGIC) {
            throw PacketDecodeException("Invalid MAGIC signature: ${magic.toUInt().toString(16)}")
        }

        val version = buffer.get()
        if (version != 1.toByte() && version != 2.toByte()) {
            throw PacketDecodeException("Unsupported Protocol Version: $version")
        }

        val typeId = buffer.get()
        val type = PacketType.fromId(typeId) ?: throw PacketDecodeException("Unknown Message Type: $typeId")

        val flags = buffer.get()
        val legacyLangId = buffer.get()

        var sourceLangId: Byte = legacyLangId
        var targetLangId: Byte = legacyLangId
        var translationModeId: Byte = TranslationMode.NONE.id

        if (version == 2.toByte()) {
            sourceLangId = buffer.get()
            targetLangId = buffer.get()
            translationModeId = buffer.get()
        }

        val messageId = buffer.getLong()
        val securityVersion = buffer.get()
        val counter = buffer.getLong()
        val payloadLen = buffer.getInt()

        if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_SIZE) {
            throw PacketDecodeException("Invalid payload length: $payloadLen")
        }

        // Exact size check: buffer must have exactly payloadLen bytes remaining before CRC.
        // '<' would accept frames with trailing garbage before the CRC field.
        // '!=' rejects both truncated and oversized payloads.
        if (buffer.remaining() - 4 != payloadLen) {
            throw PacketDecodeException(
                "Payload size mismatch: expected $payloadLen bytes, " +
                "got ${buffer.remaining() - 4} (frame=${framedData.size})"
            )
        }

        // 3. Extract Payload
        val payload = ByteArray(payloadLen)
        if (payloadLen > 0) {
            buffer.get(payload)
        }

        return ItantraPacket(
            type = type,
            flags = flags,
            languageCode = ProtocolLanguageMapper.fromWireId(legacyLangId),
            sourceLanguage = ProtocolLanguageMapper.fromWireId(sourceLangId),
            targetLanguage = ProtocolLanguageMapper.fromWireId(targetLangId),
            translationMode = TranslationMode.fromId(translationModeId),
            messageId = messageId,
            securityVersion = securityVersion,
            counter = counter,
            payload = payload
        )
    }
}
