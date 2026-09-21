package com.example.itantra

import com.example.itantra.protocol.MessageType
import com.example.itantra.protocol.PacketPriority
import com.example.itantra.protocol.PacketProtocol
import com.example.itantra.protocol.PayloadEncoding
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class PacketProtocolTest {

    @Test
    fun roundTripSerialization_preservesAll12Fields() {
        val payloadText = "Alert: Sector 4 perimeter secure"
        val payloadBytes = payloadText.toByteArray(StandardCharsets.UTF_8)

        val packet = PacketProtocol(
            version = 1,
            messageId = 10042L,
            deviceId = "TAC-NODE-01",
            language = "hi",
            messageType = MessageType.NORMAL,
            priority = PacketPriority.HIGH,
            timestamp = 1726700000000L,
            sequence = 42,
            payloadEncoding = PayloadEncoding.UTF8_TEXT,
            payload = payloadBytes
        )

        val serialized = packet.toByteArray()
        assertTrue("Serialized packet must have content", serialized.isNotEmpty())

        val deserialized = PacketProtocol.fromByteArray(serialized)

        assertEquals(packet.version, deserialized.version)
        assertEquals(packet.messageId, deserialized.messageId)
        assertEquals(packet.deviceId, deserialized.deviceId)
        assertEquals(packet.language, deserialized.language)
        assertEquals(packet.messageType, deserialized.messageType)
        assertEquals(packet.priority, deserialized.priority)
        assertEquals(packet.timestamp, deserialized.timestamp)
        assertEquals(packet.sequence, deserialized.sequence)
        assertEquals(packet.payloadEncoding, deserialized.payloadEncoding)
        assertArrayEquals(packet.payload, deserialized.payload)
        assertEquals(packet.payloadLength, deserialized.payloadLength)
        assertTrue("Deserialized checksum must match computed checksum", deserialized.checksum != 0L)
    }

    @Test
    fun corruptedPacket_failsChecksumValidation() {
        val payload = "Critical transmission".toByteArray(StandardCharsets.UTF_8)
        val packet = PacketProtocol(
            messageId = 555L,
            deviceId = "PEER-ALPHA",
            language = "en",
            messageType = MessageType.URGENT,
            priority = PacketPriority.EMERGENCY,
            sequence = 1,
            payload = payload
        )

        val serialized = packet.toByteArray()

        // Flip a byte in the payload
        val corrupted = serialized.clone()
        val payloadOffset = corrupted.size - 8 - 1
        corrupted[payloadOffset] = (corrupted[payloadOffset].toInt() xor 0xFF).toByte()

        try {
            PacketProtocol.fromByteArray(corrupted)
            fail("Expected IllegalArgumentException on corrupted packet")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Checksum mismatch") == true)
        }
    }

    @Test
    fun ackGeneration_matchesMessageIdentity() {
        val original = PacketProtocol(
            messageId = 999L,
            deviceId = "STATION-A",
            language = "ta",
            messageType = MessageType.NORMAL,
            sequence = 7,
            payload = "Test message".toByteArray(StandardCharsets.UTF_8)
        )

        val ack = original.createAck(responderDeviceId = "STATION-B")

        assertEquals(MessageType.ACK, ack.messageType)
        assertEquals(original.messageId, ack.messageId)
        assertEquals(original.sequence, ack.sequence)
        assertEquals("STATION-B", ack.deviceId)
        assertEquals("ACK", String(ack.payload, StandardCharsets.UTF_8))
    }

    @Test
    fun nackGeneration_containsReason() {
        val original = PacketProtocol(
            messageId = 888L,
            deviceId = "STATION-A",
            language = "bn",
            messageType = MessageType.NORMAL,
            sequence = 3,
            payload = "Bad message".toByteArray(StandardCharsets.UTF_8)
        )

        val nack = original.createNack(responderDeviceId = "STATION-B", reason = "DECODE_ERROR")

        assertEquals(MessageType.NACK, nack.messageType)
        assertEquals(original.messageId, nack.messageId)
        assertEquals(original.sequence, nack.sequence)
        assertEquals("STATION-B", nack.deviceId)
        assertEquals("DECODE_ERROR", String(nack.payload, StandardCharsets.UTF_8))
    }
}
