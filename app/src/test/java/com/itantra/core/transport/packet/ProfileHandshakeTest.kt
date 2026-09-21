package com.itantra.core.transport.packet

import com.itantra.domain.model.LanguageCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProfileHandshakeTest {

    @Test
    fun testHandshakePayloadSerialization() {
        val original = ProfilePayload(
            protocolVersion = 1,
            deviceId = "IT-7F3A-91C2",
            displayName = "Tactical Unit 1",
            supportedLanguages = listOf(LanguageCode.EN, LanguageCode.HI)
        )
        val bytes = original.toBytes()
        val restored = ProfilePayload.fromBytes(bytes)

        assertNotNull(restored)
        assertEquals(1, restored?.protocolVersion)
        assertEquals("IT-7F3A-91C2", restored?.deviceId)
        assertEquals("Tactical Unit 1", restored?.displayName)
        assertEquals(listOf(LanguageCode.EN, LanguageCode.HI), restored?.supportedLanguages)
    }
}
