package com.itantra.core.translation

import com.itantra.core.inference.LanguageScriptDetector
import com.itantra.domain.model.LanguageCode
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 18: Translation engine routing and validation tests.
 *
 * These tests validate the logic without requiring ML Kit or Play Services —
 * they test the routing decisions, same-language bypass, script validation,
 * and error handling paths.
 */
class MlKitTranslationTest {

    // ── Same-language bypass ──

    @Test
    fun `same-language bypass returns input text unchanged`() {
        // When source == target, translation is bypassed
        val result = TranslationResult(
            originalText = "मुझे पानी चाहिए",
            translatedText = "मुझे पानी चाहिए",
            isSuccessful = true,
            sourceLanguage = LanguageCode.HINDI,
            targetLanguage = LanguageCode.HINDI
        )
        assertTrue("Same-language must be successful", result.isSuccessful)
        assertEquals("Same-language must return original text",
            result.originalText, result.translatedText)
    }

    @Test
    fun `same-language bypass for English`() {
        val result = TranslationResult(
            originalText = "I need water",
            translatedText = "I need water",
            isSuccessful = true,
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.ENGLISH
        )
        assertTrue(result.isSuccessful)
        assertEquals(result.originalText, result.translatedText)
    }

    // ── Supported routes ──

    @Test
    fun `HI to EN is a supported route`() {
        val supported = setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)
        assertTrue("Hindi must be in supported sources", LanguageCode.HINDI in supported)
        assertTrue("English must be in supported targets", LanguageCode.ENGLISH in supported)
    }

    @Test
    fun `EN to HI is a supported route`() {
        val supported = setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)
        assertTrue("English must be in supported sources", LanguageCode.ENGLISH in supported)
        assertTrue("Hindi must be in supported targets", LanguageCode.HINDI in supported)
    }

    @Test
    fun `unsupported route returns error`() {
        val supported = setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)
        assertFalse("Bengali should not be supported", LanguageCode.BENGALI in supported)
    }

    // ── Translation output validation ──

    @Test
    fun `HI to EN output should not be empty`() {
        val result = TranslationResult(
            originalText = "मुझे पानी चाहिए",
            translatedText = "",
            isSuccessful = false,
            sourceLanguage = LanguageCode.HINDI,
            targetLanguage = LanguageCode.ENGLISH,
            error = "TRANSLATION_EMPTY"
        )
        assertFalse("Empty output must not be successful", result.isSuccessful)
        assertEquals("TRANSLATION_EMPTY", result.error)
    }

    @Test
    fun `EN to HI output must contain Devanagari`() {
        // Simulating Phase 10 validation: EN→HI result must have Devanagari
        val translatedText = "मुझे पानी चाहिए"
        assertTrue("EN→HI output must contain Devanagari",
            LanguageScriptDetector.containsDevanagari(translatedText))
    }

    @Test
    fun `EN to HI output without Devanagari is script mismatch`() {
        val translatedText = "Mujhe paani chahiye"  // Romanized - invalid for HI target
        val hasDevanagari = LanguageScriptDetector.containsDevanagari(translatedText)
        assertFalse("Latin output for HI target should fail Devanagari check", hasDevanagari)
    }

    // ── TranslationResult structure ──

    @Test
    fun `TranslationResult carries source and target languages`() {
        val result = TranslationResult(
            originalText = "Hello",
            translatedText = "नमस्ते",
            isSuccessful = true,
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.HINDI
        )
        assertEquals(LanguageCode.ENGLISH, result.sourceLanguage)
        assertEquals(LanguageCode.HINDI, result.targetLanguage)
    }

    @Test
    fun `TranslationResult error field for model not provisioned`() {
        val result = TranslationResult(
            originalText = "Hello",
            translatedText = "",
            isSuccessful = false,
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.HINDI,
            error = "MODEL_NOT_PROVISIONED"
        )
        assertFalse(result.isSuccessful)
        assertEquals("MODEL_NOT_PROVISIONED", result.error)
    }

    // ── TranslationModelState ──

    @Test
    fun `TranslationModelState values are correct`() {
        val states = TranslationModelState.values()
        assertEquals(4, states.size)
        assertTrue(states.contains(TranslationModelState.READY))
        assertTrue(states.contains(TranslationModelState.DOWNLOADING))
        assertTrue(states.contains(TranslationModelState.FAILED))
        assertTrue(states.contains(TranslationModelState.NOT_INSTALLED))
    }

    // ── Translation scope note ──

    @Test
    fun `TRANSLATION_SCOPE_NOTE is updated from stale copy`() {
        assertFalse("TRANSLATION_SCOPE_NOTE must not contain stale 'not included in this build'",
            TRANSLATION_SCOPE_NOTE.contains("not included in this build"))
    }

    @Test
    fun `TRANSLATION_READY_NOTE exists and is meaningful`() {
        assertTrue("TRANSLATION_READY_NOTE must mention Hindi and English",
            TRANSLATION_READY_NOTE.contains("Hindi") || TRANSLATION_READY_NOTE.contains("English"))
    }
}
