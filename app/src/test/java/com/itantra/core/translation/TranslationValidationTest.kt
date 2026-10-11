package com.itantra.core.translation

import android.content.ContextWrapper
import com.itantra.domain.model.LanguageCode
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TranslationValidationTest {
    private class Engine(val output: String, val wrongMetadata: Boolean = false) : TranslationEngine {
        override val isLoaded = true
        override val supportedSourceLanguages = LanguageCode.entries.toSet()
        override val supportedTargetLanguages = supportedSourceLanguages
        var calls = 0
        override fun init(modelsDir: File) {}
        override fun release() {}
        override suspend fun translate(text: String, sourceLang: LanguageCode, targetLang: LanguageCode): TranslationResult {
            calls++
            return TranslationResult(text, output, true, sourceLang,
                if (wrongMetadata) sourceLang else targetLang)
        }
    }

    @Test fun `wrong source script is rejected without guessing Hindi versus Marathi`() = runBlocking {
        val engine = Engine("hello")
        val bad = TranslationRouter(engine).routeAndTranslate("કેમ છો", LanguageCode.HINDI, LanguageCode.ENGLISH)
        assertEquals("SOURCE_SCRIPT_MISMATCH", bad.error)
        assertEquals(0, engine.calls)
        assertFalse(sourceScriptConflict("मदत हवी आहे", LanguageCode.HINDI))
        assertFalse(sourceScriptConflict("Mera naam Zubin hai", LanguageCode.HINDI))
    }

    @Test fun `echoed Indic output cannot be successful English translation`() = runBlocking {
        val result = TranslationRouter(Engine("नमस्ते")).routeAndTranslate("नमस्ते", LanguageCode.HINDI, LanguageCode.ENGLISH)
        assertFalse(result.isSuccessful)
        assertEquals("OUTPUT_SCRIPT_MISMATCH", result.error)
        assertEquals("नमस्ते", result.originalText)
        assertEquals("", result.translatedText)
    }

    @Test fun `every foreign Indic script is checked for every target language`() = runBlocking {
        val samples = mapOf(LanguageCode.HINDI to "नमस्ते", LanguageCode.ENGLISH to "Hello",
            LanguageCode.BENGALI to "সাহায্য", LanguageCode.GUJARATI to "મદદ", LanguageCode.MARATHI to "मदत",
            LanguageCode.KANNADA to "ಸಹಾಯ", LanguageCode.MALAYALAM to "സഹായം", LanguageCode.TAMIL to "உதவி",
            LanguageCode.TELUGU to "సహాయం", LanguageCode.ODIA to "ସାହାଯ୍ୟ")
        for ((source, text) in samples) for (target in LanguageCode.entries) {
            if (translationScript(source) == translationScript(target) || source == LanguageCode.ENGLISH) continue
            val result = TranslationRouter(Engine(text)).routeAndTranslate(text, source, target)
            assertEquals("$source -> $target", "OUTPUT_SCRIPT_MISMATCH", result.error)
        }
    }

    @Test fun `empty output and mismatched metadata cannot be played as a successful conversion`() = runBlocking {
        assertEquals("TRANSLATION_EMPTY", TranslationRouter(Engine(" ")).routeAndTranslate("hello", LanguageCode.ENGLISH, LanguageCode.HINDI).error)
        assertEquals("TRANSLATION_METADATA_MISMATCH", TranslationRouter(Engine("नमस्ते", true)).routeAndTranslate("hello", LanguageCode.ENGLISH, LanguageCode.HINDI).error)
    }

    @Test fun `changed added missing reordered and sign changed numbers require review`() = runBlocking {
        for ((input, output) in listOf("call 9876543210" to "कॉल 987654321", "5" to "6",
            "−5" to "5", "5" to "five", "hello" to "नमस्ते 5", "5 2" to "2 5", "1.5" to "15")) {
            val result = TranslationRouter(Engine(output)).routeAndTranslate(input, LanguageCode.ENGLISH, LanguageCode.HINDI)
            assertEquals("$input -> $output", "NUMERIC_CONTENT_REQUIRES_REVIEW", result.error)
            assertEquals(input, result.originalText)
        }
    }

    @Test fun `digit script changes preserve values and leading zeros`() = runBlocking {
        val result = TranslationRouter(Engine("કૉલ ૦૧૨૩૪૫૬૭૮૯")).routeAndTranslate("call 0123456789", LanguageCode.ENGLISH, LanguageCode.GUJARATI)
        assertTrue(result.isSuccessful)
        val bad = TranslationRouter(Engine("કૉલ ૧૨૩૪૫૬૭૮૯")).routeAndTranslate("call 0123456789", LanguageCode.ENGLISH, LanguageCode.GUJARATI)
        assertEquals("NUMERIC_CONTENT_REQUIRES_REVIEW", bad.error)
    }

    @Test fun `unseen Latin names can remain unchanged without a name whitelist`() = runBlocking {
        assertTrue(TranslationRouter(Engine("Zubin Qureshi")).routeAndTranslate("Zubin Qureshi", LanguageCode.ENGLISH, LanguageCode.GUJARATI).isSuccessful)
    }

    @Test fun `native Whisper English output does not enter Hindi to English translation again`() = runBlocking {
        val textLanguage = com.itantra.core.inference.whisperTextLanguage(LanguageCode.HINDI, true)
        val engine = Engine("incorrect second translation")
        val result = TranslationRouter(engine).routeAndTranslate("We need help", textLanguage, LanguageCode.ENGLISH)
        assertEquals("We need help", result.translatedText)
        assertEquals(LanguageCode.ENGLISH, result.sourceLanguage)
        assertEquals(0, engine.calls)
        assertEquals(LanguageCode.HINDI, com.itantra.core.inference.whisperTextLanguage(LanguageCode.HINDI, false))
    }

    @Test fun `unsupported Malayalam and Odia cannot be reported provisioned`() = runBlocking {
        val engine = MlKitOfflineTranslationEngine(ContextWrapper(null))
        for (lang in listOf(LanguageCode.MALAYALAM, LanguageCode.ODIA))
            assertEquals(TranslationModelState.NOT_INSTALLED, engine.prepareOfflineModelForLanguage(lang))
    }
}
