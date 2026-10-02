package com.itantra.domain.model

import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.core.transport.packet.PacketEncoder
import com.itantra.core.transport.packet.PacketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransceiverMessageWireMetricsTest {
    private fun message(voice: Boolean, frameBytes: Int = 0, rawPcmBytes: Int = 64_000) =
        TransceiverMessage(
            messageId = 1L,
            language = LanguageCode.HINDI,
            priority = MessagePriority.NORMAL,
            text = "नमस्ते",
            source = MessageSource.LOCAL,
            createdAtLocal = 1L,
            state = MessageState.SENT,
            isVoiceGenerated = voice,
            finalFrameBytes = frameBytes,
            rawPcmEquivalentBytes = rawPcmBytes,
        )

    @Test
    fun unmeasuredOrNonVoiceMessageNeverClaimsSavings() {
        assertNull(message(voice = true).measuredWireReductionVsPcmPercent)
        assertNull(message(voice = false, frameBytes = 80).measuredWireReductionVsPcmPercent)
        assertNull(message(voice = true, frameBytes = 80, rawPcmBytes = 0).measuredWireReductionVsPcmPercent)
    }

    @Test
    fun comparesTheSameMeasuredFrameWithRawPcmWithoutClamping() {
        assertEquals(99.85, message(voice = true, frameBytes = 96).measuredWireReductionVsPcmPercent!!, 0.001)
        assertEquals(-50.0, message(voice = true, frameBytes = 120, rawPcmBytes = 80).measuredWireReductionVsPcmPercent!!, 0.001)
    }

    @Test
    fun wireFrameIncludesProtocolOverheadAndTextByteLengthIsVariable() {
        val shortText = "Help".toByteArray(Charsets.UTF_8)
        val longText = "मुझे तुरंत सहायता चाहिए".toByteArray(Charsets.UTF_8)
        val shortFrame = PacketEncoder.encode(ItantraPacket(type = PacketType.TEXT, messageId = 1L, payload = shortText))
        val longFrame = PacketEncoder.encode(ItantraPacket(type = PacketType.TEXT, messageId = 2L, payload = longText))
        assertEquals(40 + shortText.size, shortFrame.size)
        assertEquals(40 + longText.size, longFrame.size)
        assertTrue(longFrame.size > shortFrame.size)
    }
}
