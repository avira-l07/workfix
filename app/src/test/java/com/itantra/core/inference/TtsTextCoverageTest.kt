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
        requireTtsTextCoverage(LanguageCode.HINDI, "नमस्ते", emptySet())
    }
}
