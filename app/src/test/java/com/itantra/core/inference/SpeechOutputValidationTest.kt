package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.SpeechInputMode
import org.junit.Assert.*
import org.junit.Test

class SpeechOutputValidationTest {
    @Test fun `manual Hindi cannot relabel English or romanized output as valid Hindi`() {
        for (text in listOf("My name is Jai Hind", "Myranam kya hai", "My name is Kyathe")) {
            assertEquals("HINDI_SCRIPT_MISMATCH", speechOutputDiagnostic(
                text, "hi", LanguageCode.HINDI, false, false
            ))
        }
        assertNull(speechOutputDiagnostic("मेरा नाम क्या है", "hi", LanguageCode.HINDI, false, false))
    }
    @Test fun `startup defaults to manual but preserves explicitly saved auto`() {
        assertEquals(SpeechInputMode.MANUAL, SpeechInputMode.fromSavedValue(null))
        assertEquals(SpeechInputMode.MANUAL, SpeechInputMode.fromSavedValue("invalid"))
        assertEquals(SpeechInputMode.AUTO, SpeechInputMode.fromSavedValue("AUTO"))
    }

    @Test fun `unsupported auto language cannot fall back to Hindi`() {
        assertEquals("UNSUPPORTED_SPEECH_LANGUAGE", speechOutputDiagnostic(
            "que é ser garunho", "pt", LanguageCode.HINDI, true, false
        ))
    }

    @Test fun `auto detected Hindi with English text is rejected`() {
        assertEquals("HINDI_SCRIPT_MISMATCH", speechOutputDiagnostic(
            "My name is Abhiran", "hi", LanguageCode.HINDI, true, false
        ))
    }

    @Test fun `native Hindi and detected English pass`() {
        assertNull(speechOutputDiagnostic("मैं कौन हूँ", "hi", LanguageCode.HINDI, true, false))
        assertNull(speechOutputDiagnostic("Who am I", "en", LanguageCode.ENGLISH, true, false))
    }

    @Test fun `English translation is not rejected for Hindi source script`() {
        assertNull(speechOutputDiagnostic("Who am I", "hi", LanguageCode.HINDI, false, true))
    }
}
