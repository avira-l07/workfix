package com.itantra.regression

import com.itantra.core.transport.packet.*
import com.itantra.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test

class Phase3BugfixTest {
    private fun translatedPacket() = ItantraPacket(PacketType.TEXT, messageId = 301,
        languageCode = LanguageCode.ENGLISH, sourceLanguage = LanguageCode.HINDI,
        targetLanguage = LanguageCode.ENGLISH, translationMode = TranslationMode.DIRECT,
        payload = "The weather is pleasant today".toByteArray())

    @Test fun translatedEnglishPayloadIsTranslatedFromEnglishToMarathi() = runBlocking {
        BugfixFixture().use { f ->
            f.repository.setReceiveLanguage(LanguageCode.MARATHI)
            f.awaitCondition { (f.field("currentReceiveLanguage") as StateFlow<*>).value == LanguageCode.MARATHI }
            f.verify()
            f.receive(translatedPacket())
            f.awaitCondition { f.spoken.isNotEmpty() }
            assertEquals(listOf(LanguageCode.ENGLISH to LanguageCode.MARATHI), f.routes.toList())
            assertEquals(listOf(LanguageCode.MARATHI), f.spoken.toList())
            assertEquals(LanguageCode.MARATHI, f.coordinator.messages.value.single().language)
        }
    }

    @Test fun automaticReceiveKeepsEnglishPayloadAndEnglishTts() = runBlocking {
        BugfixFixture().use { f ->
            f.verify()
            f.receive(translatedPacket())
            f.awaitCondition { f.spoken.isNotEmpty() }
            assertTrue(f.routes.isEmpty())
            assertEquals(listOf(LanguageCode.ENGLISH), f.spoken.toList())
            val message = f.coordinator.messages.value.single()
            assertEquals(LanguageCode.ENGLISH, message.language)
            assertEquals(LanguageCode.ENGLISH, message.targetLanguage)
            assertEquals("The weather is pleasant today", message.text)
        }
    }
}
