package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TtsTextCoverageTest {
    private val tokens = File("src/main/assets/language_packs/mr/tts/tokens.txt")
        .readLines().map { it.substringBeforeLast(' ') }.toSet()

    @Test fun `Marathi native startup phrase is fully supported`() {
        requireTtsTextCoverage(LanguageCode.MARATHI, "मदत हवी आहे", tokens)
    }
    @Test fun `Marathi doctor vowel is rejected before native character skipping`() {
        val text = "डॉक्टरांना बोलवा"
        val error = assertThrows(TtsSynthesisException::class.java) {
            requireTtsTextCoverage(LanguageCode.MARATHI, text, tokens)
        }
        assertTrue(error.message!!.contains("ॉ"))
        assertTrue(error.message!!.contains("Text is preserved"))
        assertEquals("डॉक्टरांना बोलवा", text)
    }
    @Test fun `Marathi foreign alphabet does not silently disappear`() {
        assertThrows(TtsSynthesisException::class.java) {
            requireTtsTextCoverage(LanguageCode.MARATHI, "Hello", tokens)
        }
        assertThrows(TtsSynthesisException::class.java) {
            requireTtsTextCoverage(LanguageCode.HINDI, "नमस्ते", emptySet())
        }
    }

    private fun voiceTokens(lang: LanguageCode) = File("src/main/assets/language_packs/${lang.wireCode}/tts/tokens.txt")
        .readLines().map { it.substringBeforeLast(' ') }.toSet()

    @Test fun `every character voice supports its startup phrase`() {
        LanguageCode.entries.filter { it != LanguageCode.MARATHI }.forEach { lang ->
            requireTtsTextCoverage(lang, ttsSmokeText(lang), voiceTokens(lang))
        }
    }

    @Test fun `all MMS voices require numeric normalization instead of speaking incomplete numbers`() {
        LanguageCode.entries.filter { it != LanguageCode.MARATHI }.forEach { lang ->
            assertThrows("${lang.wireCode} must preserve all ten digits", TtsSynthesisException::class.java) {
                requireTtsTextCoverage(lang, "0123456789", voiceTokens(lang))
            }
        }
    }

    @Test fun `foreign names cannot disappear from Gujarati speech`() {
        assertThrows(TtsSynthesisException::class.java) {
            requireTtsTextCoverage(LanguageCode.GUJARATI, "મારું નામ Zubin છે", voiceTokens(LanguageCode.GUJARATI))
        }
    }

    @Test fun `English case and whitespace normalization preserves words`() {
        assertEquals("hello aviral", prepareTtsText("Hello\u00a0Aviral"))
        requireTtsTextCoverage(LanguageCode.ENGLISH, "Hello AVIRAL", voiceTokens(LanguageCode.ENGLISH))
    }

    @Test fun `normalization does not remove an unsupported accent`() {
        assertEquals("café", prepareTtsText("Cafe\u0301"))
        assertThrows(TtsSynthesisException::class.java) {
            requireTtsTextCoverage(LanguageCode.ENGLISH, "Cafe\u0301", voiceTokens(LanguageCode.ENGLISH))
        }
    }

    @Test fun `currency and minus signs are not silently removed`() {
        assertThrows(TtsSynthesisException::class.java) {
            requireTtsTextCoverage(LanguageCode.ENGLISH, "cost ₹5", voiceTokens(LanguageCode.ENGLISH))
        }
        assertThrows(TtsSynthesisException::class.java) {
            requireTtsTextCoverage(LanguageCode.ENGLISH, "−5", voiceTokens(LanguageCode.ENGLISH))
        }
    }

    @Test fun `Marathi phoneme frontend accepts native text without requiring character tokens`() {
        requireTtsTextCoverage(LanguageCode.MARATHI, "डॉक्टरांना बोलवा 9876543210", emptySet(), true)
        assertThrows(TtsSynthesisException::class.java) {
            requireTtsTextCoverage(LanguageCode.MARATHI, "मदत Aviral", emptySet(), true)
        }
    }
}
