package com.example.itantra.ui.components

import com.itantra.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageLanguageLabelTest {
    private val translated = TransceiverMessage(
        messageId = 1, language = LanguageCode.HINDI, targetLanguage = LanguageCode.GUJARATI,
        priority = MessagePriority.NORMAL, text = "ગુજરાતી", originalText = "हिन्दी",
        translationStatus = TranslationStatus.SUCCESS, source = MessageSource.LOCAL,
        createdAtLocal = 0, state = MessageState.STT_COMPLETE,
    )

    @Test fun `local translated text shows both languages and replays target`() {
        assertEquals("Hindi → Gujarati", messageLanguageLabel(translated))
        assertEquals(LanguageCode.GUJARATI, translated.displayedTextLanguage)
        assertEquals("Gujarati → Hindi", messageLanguageLabel(translated.copy(
            language = LanguageCode.GUJARATI, targetLanguage = LanguageCode.HINDI)))
    }

    @Test fun `failed translation retains source label`() {
        assertEquals("Hindi", messageLanguageLabel(translated.copy(translationStatus = TranslationStatus.FAILED)))
    }

    @Test fun `remote message does not invent original language`() {
        assertEquals("Gujarati", messageLanguageLabel(translated.copy(source = MessageSource.REMOTE,
            language = LanguageCode.GUJARATI)))
    }
}
