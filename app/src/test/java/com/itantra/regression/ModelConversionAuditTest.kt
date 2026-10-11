package com.itantra.regression

import com.itantra.core.transport.packet.*
import com.itantra.core.translation.TranslationResult
import com.itantra.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelConversionAuditTest {
    @Test fun `source only packet is translated from its actual payload language`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.repository.setReceiveLanguage(LanguageCode.GUJARATI)
            f.awaitCondition { (f.field("currentReceiveLanguage") as kotlinx.coroutines.flow.StateFlow<*>).value == LanguageCode.GUJARATI }
            f.translationAction = { text, source, target -> TranslationResult(text, "મદદ જોઈએ છે", true, source, target) }
            f.receive(ItantraPacket(PacketType.TEXT, messageId = 102101, sourceLanguage = LanguageCode.HINDI,
                payload = "मदद चाहिए".toByteArray()))
            f.awaitCondition { f.coordinator.messages.value.any { it.messageId == 102101L } }
            val message = f.coordinator.messages.value.first { it.messageId == 102101L }
            assertEquals(listOf(LanguageCode.HINDI to LanguageCode.GUJARATI), f.routes.toList())
            assertEquals("મદદ જોઈએ છે", message.text)
            assertEquals(LanguageCode.GUJARATI, message.displayedTextLanguage)
            assertEquals("मदद चाहिए", message.originalText)
        }
    }

    @Test fun `unknown packet language is preserved without guessed voice`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.receive(ItantraPacket(PacketType.TEXT, messageId = 102102, payload = "unlabelled text".toByteArray()))
            f.awaitCondition { f.coordinator.messages.value.any { it.messageId == 102102L } }
            val message = f.coordinator.messages.value.first { it.messageId == 102102L }
            assertNull(message.displayedTextLanguage)
            assertEquals("unlabelled text", message.text)
            assertTrue(message.statusDetail!!.contains("speech not played"))
            assertTrue(f.routes.isEmpty())
            assertEquals(0, f.synthCalls.get())
        }
    }

    @Test fun `changed received phone number retains original and failed conversion status`() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.repository.setReceiveLanguage(LanguageCode.GUJARATI)
            f.awaitCondition { (f.field("currentReceiveLanguage") as kotlinx.coroutines.flow.StateFlow<*>).value == LanguageCode.GUJARATI }
            f.translationAction = { text, source, target -> TranslationResult(text, "ફોન 987654321", true, source, target) }
            f.receive(ItantraPacket(PacketType.TEXT, messageId = 102103, languageCode = LanguageCode.ENGLISH,
                payload = "phone 9876543210".toByteArray()))
            f.awaitCondition { f.coordinator.messages.value.any { it.messageId == 102103L } }
            val message = f.coordinator.messages.value.first { it.messageId == 102103L }
            assertEquals("phone 9876543210", message.text)
            assertEquals(LanguageCode.ENGLISH, message.displayedTextLanguage)
            assertEquals(TranslationStatus.FAILED, message.translationStatus)
        }
    }
}
