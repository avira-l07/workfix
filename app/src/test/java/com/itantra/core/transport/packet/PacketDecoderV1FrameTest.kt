package com.itantra.core.transport.packet

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Regression tests for FIX 011: V1 short-frame min-size fix in PacketDecoder.
 */
class PacketDecoderV1FrameTest {

    companion object {
        private const val MAGIC = 0x49545031

        private fun buildV1Frame(payload: ByteArray): ByteArray {
            val headerSize = 29
            val totalSize = headerSize + payload.size + 4
            val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
            val crcStart = 0
            buf.putInt(MAGIC)
            buf.put(1.toByte())
            buf.put(PacketType.TEXT.id)
            buf.put(0.toByte())
            buf.put(0.toByte())
            buf.putLong(999L)
            buf.put(0.toByte())
            buf.putLong(0L)
            buf.putInt(payload.size)
            if (payload.isNotEmpty()) buf.put(payload)
            val crcEnd = buf.position()
            val crcData = ByteArray(crcEnd - crcStart)
            buf.position(crcStart)
            buf.get(crcData)
            val crc = CRC32()
            crc.update(crcData)
            buf.position(crcEnd)
            buf.putInt(crc.value.toInt())
            return buf.array()
        }
    }

    @Test
    fun testV1FrameZeroPayload_size33_decodes() {
        val frame = buildV1Frame(ByteArray(0))
        assertEquals(33, frame.size)
        val packet = PacketDecoder.decode(frame)
        assertEquals(PacketType.TEXT, packet.type)
        assertTrue(packet.payload.isEmpty())
    }

    @Test
    fun testV1FrameOneBytePayload_size34_decodes() {
        val frame = buildV1Frame(ByteArray(1) { 0x42 })
        assertEquals(34, frame.size)
        val packet = PacketDecoder.decode(frame)
        assertEquals(1, packet.payload.size)
        assertEquals(0x42.toByte(), packet.payload[0])
    }

    @Test
    fun testV1FrameTwoBytesPayload_size35_decodes() {
        val frame = buildV1Frame(ByteArray(2) { 0xAB.toByte() })
        assertEquals(35, frame.size)
        val packet = PacketDecoder.decode(frame)
        assertEquals(2, packet.payload.size)
    }

    @Test(expected = PacketDecodeException::class)
    fun testFrameBelow33_throws() {
        PacketDecoder.decode(ByteArray(32))
    }
}
