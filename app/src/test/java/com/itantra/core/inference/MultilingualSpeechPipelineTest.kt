package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.SpeechInputMode
import com.itantra.domain.model.SpeechRecognitionResult
import com.itantra.domain.model.SpeechSynthesisRequest
import com.itantra.domain.model.SpeechSynthesisResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultilingualSpeechPipelineTest {

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Script Detection Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `script detector accurately detects unambiguous Indic scripts`() {
        assertEquals(LanguageCode.TELUGU, LanguageScriptDetector.detect("నేను బాగున్నాను"))
        assertEquals(LanguageCode.TAMIL, LanguageScriptDetector.detect("வணக்கம் எப்படி இருக்கிறீர்கள்"))
        assertEquals(LanguageCode.BENGALI, LanguageScriptDetector.detect("আমি ভালো আছি"))
        assertEquals(LanguageCode.GUJARATI, LanguageScriptDetector.detect("હું મજામાં છું"))
        assertEquals(LanguageCode.KANNADA, LanguageScriptDetector.detect("ನಾನು ಚೆನ್ನಾಗಿದ್ದೇನೆ"))
        assertEquals(LanguageCode.MALAYALAM, LanguageScriptDetector.detect("എനിക്ക് സുഖമാണ്"))
        assertEquals(LanguageCode.ODIA, LanguageScriptDetector.detect("ମୁଁ ଭଲ ଅଛି"))
    }

    @Test
    fun `script detector does NOT invent distinction between Hindi and Marathi without context`() {
        val devanagariText = "मला पाणी पाहिजे"
        // Without explicit Hindi/Marathi context, it must return null (safe uncertain)
        assertNull(LanguageScriptDetector.detect(devanagariText, manualFallback = null))
        assertNull(LanguageScriptDetector.detect(devanagariText, manualFallback = LanguageCode.ENGLISH))

        // When manual context is Marathi, resolves Marathi
        assertEquals(LanguageCode.MARATHI, LanguageScriptDetector.detect(devanagariText, manualFallback = LanguageCode.MARATHI))

        // When manual context is Hindi, resolves Hindi
        assertEquals(LanguageCode.HINDI, LanguageScriptDetector.detect(devanagariText, manualFallback = LanguageCode.HINDI))
    }

    @Test
    fun `script detector does NOT automatically classify Latin text as English`() {
        val latinText = "mujhe pani chahiye"
        assertNull(LanguageScriptDetector.detect(latinText, manualFallback = null))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Transcript Post-Processor Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `post processor strips special tokens and surrounding brackets`() {
        val raw = "<|startoftranscript|><|te|><|transcribe|> [నమస్కారం] <|endoftranscript|>"
        val processed = TranscriptPostProcessor.postProcess(raw)
        assertEquals("నమస్కారం", processed)
    }

    @Test
    fun `post processor normalizes NFC and preserves technical English terms`() {
        val text = "ICU Patient needs SOS assistance with Wi-Fi and Bluetooth"
        val processed = TranscriptPostProcessor.postProcess(text)
        assertEquals(text, processed)
    }

    @Test
    fun `post processor deduplicates repeating Whisper loops`() {
        val repeating = "హలో హలో హలో హలో హలో హలో హలో"
        val processed = TranscriptPostProcessor.postProcess(repeating)
        assertEquals("హలో", processed)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Engine Lifecycle Independence Tests
    // ─────────────────────────────────────────────────────────────────────────

    private class FakeSttEngine(override val languageCode: LanguageCode, val isAuto: Boolean = false) : SpeechRecognizerEngine {
        override var isLoaded: Boolean = false
        var unloadCount = 0
        override suspend fun load() { isLoaded = true }
        override suspend fun feed(samples: FloatArray) {}
        override suspend fun finalizeUtterance(): SpeechRecognitionResult =
            SpeechRecognitionResult(text = "test", isFinal = true, languageCode = languageCode, confidence = 1f, timestampMillis = 0L)
        override suspend fun reset() {}
        override suspend fun unload() { isLoaded = false; unloadCount++ }
    }

    private class FakeTtsEngine(override val languageCode: LanguageCode) : SpeechSynthesizerEngine {
        override var isLoaded: Boolean = false
        var unloadCount = 0
        override suspend fun load() { isLoaded = true }
        override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechSynthesisResult =
            SpeechSynthesisResult(correlationId = "1", pcmAudio = FloatArray(16000), sampleRateHz = 16000, channelCount = 1, durationMillis = 1000L)
        override suspend fun unload() { isLoaded = false; unloadCount++ }
    }

    @Test
    fun `ensureTts does not unload active STT engine and ensureStt does not unload active TTS`() = runTest {
        var createdStt: FakeSttEngine? = null
        var createdTts: FakeTtsEngine? = null

        val factory = object : EngineFactory {
            override fun createRecognizer(language: LanguageCode, autoDetect: Boolean): SpeechRecognizerEngine {
                val stt = FakeSttEngine(language, autoDetect)
                createdStt = stt
                return stt
            }
            override fun createRecognizer(language: LanguageCode): SpeechRecognizerEngine =
                createRecognizer(language, false)

            override fun createSynthesizer(language: LanguageCode): SpeechSynthesizerEngine {
                val tts = FakeTtsEngine(language)
                createdTts = tts
                return tts
            }
        }

        val sessionManager = ActiveLanguageSessionManager(engineFactory = factory)

        // 1. Load STT in AUTO mode
        sessionManager.ensureStt(LanguageCode.HINDI, autoDetect = true)
        val stt1 = createdStt
        assertNotNull(stt1)
        assertTrue(stt1!!.isLoaded)
        assertEquals(0, stt1.unloadCount)

        // 2. Load TTS for Telugu
        sessionManager.ensureTts(LanguageCode.TELUGU)
        val tts1 = createdTts
        assertNotNull(tts1)
        assertTrue(tts1!!.isLoaded)
        assertEquals(0, tts1.unloadCount)

        // Verify STT was NOT unloaded!
        assertTrue(stt1.isLoaded)
        assertEquals(0, stt1.unloadCount)

        // 3. Switch TTS to Tamil
        sessionManager.ensureTts(LanguageCode.TAMIL)
        val tts2 = createdTts
        assertNotNull(tts2)
        assertTrue(tts2!!.isLoaded)
        // Previous TTS unloaded:
        assertFalse(tts1.isLoaded)
        assertEquals(1, tts1.unloadCount)
        // STT STILL loaded:
        assertTrue(stt1.isLoaded)
        assertEquals(0, stt1.unloadCount)

        // 4. Switch STT to MANUAL Telugu
        sessionManager.ensureStt(LanguageCode.TELUGU, autoDetect = false)
        val stt2 = createdStt
        assertNotNull(stt2)
        assertTrue(stt2!!.isLoaded)
        // Previous STT unloaded:
        assertFalse(stt1.isLoaded)
        assertEquals(1, stt1.unloadCount)
        // TTS STILL loaded:
        assertTrue(tts2.isLoaded)
        assertEquals(0, tts2.unloadCount)
    }

    @Test
    fun `ensureStt in AUTO mode reuses loaded recognizer without reloading`() = runTest {
        var createCount = 0
        val factory = object : EngineFactory {
            override fun createRecognizer(language: LanguageCode, autoDetect: Boolean): SpeechRecognizerEngine {
                createCount++
                return FakeSttEngine(language, autoDetect)
            }
            override fun createRecognizer(language: LanguageCode): SpeechRecognizerEngine =
                createRecognizer(language, false)
            override fun createSynthesizer(language: LanguageCode): SpeechSynthesizerEngine =
                FakeTtsEngine(language)
        }

        val sessionManager = ActiveLanguageSessionManager(engineFactory = factory)

        sessionManager.ensureStt(LanguageCode.HINDI, autoDetect = true)
        assertEquals(1, createCount)

        // Second call with Telugu in AUTO mode should reuse the shared multilingual recognizer
        sessionManager.ensureStt(LanguageCode.TELUGU, autoDetect = true)
        assertEquals(1, createCount)
    }

    @Test
    fun `speech input mode enum has AUTO and MANUAL`() {
        assertEquals(SpeechInputMode.AUTO, SpeechInputMode.valueOf("AUTO"))
        assertEquals(SpeechInputMode.MANUAL, SpeechInputMode.valueOf("MANUAL"))
    }
}
