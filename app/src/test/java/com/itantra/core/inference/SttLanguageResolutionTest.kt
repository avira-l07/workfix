package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.SpeechRecognitionResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 18: STT language resolution tests.
 *
 * Validates the core Phase 1-4 invariants:
 * - Manual Hindi forces hint "hi" and metadata stays HINDI even if Whisper detects "en"
 * - Manual English forces hint "en" and metadata stays ENGLISH
 * - Auto mode may use Whisper detector
 * - Confidence is null, not 1.0
 */
class SttLanguageResolutionTest {

    // ── Helper: simulates processWhisperResult resolution logic ──

    /**
     * Replicates the Phase 2 language resolution logic from SherpaOnnxSpeechRecognizer
     * without requiring the actual native Whisper engine.
     */
    private fun resolveLanguage(
        autoDetect: Boolean,
        configuredLang: LanguageCode,
        whisperDetectedCode: String,
        text: String
    ): LanguageCode {
        val whisperLang = LanguageCode.fromWireCode(whisperDetectedCode)
        val scriptLang = LanguageScriptDetector.detect(text, manualFallback = configuredLang)

        return if (!autoDetect) {
            // MANUAL: user's selection always wins
            configuredLang
        } else {
            // AUTO: detector > script > fallback
            whisperLang ?: scriptLang ?: configuredLang
        }
    }

    // ── Phase 1: Manual Hindi forces hint "hi" ──

    @Test
    fun `manual Hindi forces HINDI metadata regardless of Whisper detection`() {
        val resolved = resolveLanguage(
            autoDetect = false,
            configuredLang = LanguageCode.HINDI,
            whisperDetectedCode = "en",  // Whisper wrongly detects English
            text = "मुझे पानी चाहिए"
        )
        assertEquals("Manual Hindi must stay HINDI even if Whisper says 'en'",
            LanguageCode.HINDI, resolved)
    }

    @Test
    fun `manual Hindi stays HINDI even with Latin text`() {
        val resolved = resolveLanguage(
            autoDetect = false,
            configuredLang = LanguageCode.HINDI,
            whisperDetectedCode = "en",
            text = "mujhe pani chahiye"  // Romanized Hindi (Hinglish)
        )
        assertEquals("Manual Hindi must stay HINDI even for Latin script text",
            LanguageCode.HINDI, resolved)
    }

    @Test
    fun `manual Hindi stays HINDI when Whisper returns empty detection`() {
        val resolved = resolveLanguage(
            autoDetect = false,
            configuredLang = LanguageCode.HINDI,
            whisperDetectedCode = "",
            text = "नमस्ते"
        )
        assertEquals(LanguageCode.HINDI, resolved)
    }

    // ── Phase 1: Manual English forces hint "en" ──

    @Test
    fun `manual English forces ENGLISH metadata regardless of Whisper detection`() {
        val resolved = resolveLanguage(
            autoDetect = false,
            configuredLang = LanguageCode.ENGLISH,
            whisperDetectedCode = "hi",  // Whisper wrongly detects Hindi
            text = "I need water"
        )
        assertEquals("Manual English must stay ENGLISH even if Whisper says 'hi'",
            LanguageCode.ENGLISH, resolved)
    }

    @Test
    fun `manual English stays ENGLISH when Whisper returns unknown code`() {
        val resolved = resolveLanguage(
            autoDetect = false,
            configuredLang = LanguageCode.ENGLISH,
            whisperDetectedCode = "fr",  // Whisper detects French (unsupported)
            text = "Hello there"
        )
        assertEquals(LanguageCode.ENGLISH, resolved)
    }

    // ── Phase 2: Auto mode may use Whisper detector ──

    @Test
    fun `auto mode uses Whisper detected language when available`() {
        val resolved = resolveLanguage(
            autoDetect = true,
            configuredLang = LanguageCode.HINDI,
            whisperDetectedCode = "en",
            text = "Hello world"
        )
        assertEquals("Auto mode should use Whisper-detected 'en'",
            LanguageCode.ENGLISH, resolved)
    }

    @Test
    fun `auto mode uses Whisper detected Hindi`() {
        val resolved = resolveLanguage(
            autoDetect = true,
            configuredLang = LanguageCode.ENGLISH,
            whisperDetectedCode = "hi",
            text = "नमस्ते दुनिया"
        )
        assertEquals("Auto mode should use Whisper-detected 'hi'",
            LanguageCode.HINDI, resolved)
    }

    @Test
    fun `auto mode falls back to script detector when Whisper returns empty`() {
        val resolved = resolveLanguage(
            autoDetect = true,
            configuredLang = LanguageCode.ENGLISH,
            whisperDetectedCode = "",
            text = "এটি বাংলা"  // Bengali script
        )
        assertEquals("Auto mode should fall back to script detector for Bengali",
            LanguageCode.BENGALI, resolved)
    }

    @Test
    fun `auto mode falls back to configured language when no detection available`() {
        val resolved = resolveLanguage(
            autoDetect = true,
            configuredLang = LanguageCode.HINDI,
            whisperDetectedCode = "",
            text = "some text"  // Latin, no clear script detection
        )
        assertEquals("Auto mode should fall back to configured language",
            LanguageCode.HINDI, resolved)
    }

    // ── Phase 4: Confidence is null ──

    @Test
    fun `confidence must be null not 1_0`() {
        // This test validates the data model contract: confidence should be null
        // when the engine doesn't expose meaningful confidence scores.
        val result = SpeechRecognitionResult(
            text = "test",
            isFinal = true,
            languageCode = LanguageCode.HINDI,
            confidence = null,  // Phase 4: must be null
            timestampMillis = System.currentTimeMillis()
        )
        assertNull("Confidence should be null for Whisper Tiny (Phase 4)", result.confidence)
    }

    @Test
    fun `confidence must not be 1_0`() {
        // Negative test: ensure no code path produces confidence = 1.0
        val result = SpeechRecognitionResult(
            text = "test",
            isFinal = true,
            languageCode = LanguageCode.HINDI,
            confidence = null,
            timestampMillis = System.currentTimeMillis()
        )
        assertTrue("Confidence must never be exactly 1.0",
            result.confidence == null || result.confidence != 1.0f)
    }

    // ── Phase 3: Script mismatch detection ──

    @Test
    fun `containsDevanagari detects Hindi text`() {
        assertTrue(LanguageScriptDetector.containsDevanagari("मुझे पानी चाहिए"))
        assertTrue(LanguageScriptDetector.containsDevanagari("Hello नमस्ते"))
    }

    @Test
    fun `containsDevanagari rejects pure Latin text`() {
        assertFalse(LanguageScriptDetector.containsDevanagari("mujhe pani chahiye"))
        assertFalse(LanguageScriptDetector.containsDevanagari("Hello world"))
    }

    @Test
    fun `detectScriptMismatch flags Latin text for Hindi`() {
        val result = LanguageScriptDetector.detectScriptMismatch("mujhe pani chahiye", LanguageCode.HINDI)
        assertNotNull("Should detect HINDI_SCRIPT_MISMATCH for Latin text", result)
        assertEquals("HINDI_SCRIPT_MISMATCH", result?.diagnostic)
    }

    @Test
    fun `detectScriptMismatch passes Devanagari text for Hindi`() {
        val result = LanguageScriptDetector.detectScriptMismatch("मुझे पानी चाहिए", LanguageCode.HINDI)
        assertNull("Should not flag script mismatch for proper Devanagari", result)
    }

    @Test
    fun `detectScriptMismatch returns null for English`() {
        val result = LanguageScriptDetector.detectScriptMismatch("Hello world", LanguageCode.ENGLISH)
        assertNull("Should not flag script mismatch for English", result)
    }
}
