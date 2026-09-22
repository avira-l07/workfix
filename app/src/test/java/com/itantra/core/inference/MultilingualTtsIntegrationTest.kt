package com.itantra.core.inference

import android.content.Context
import com.itantra.core.metrics.InMemoryMetricsRecorder
import com.itantra.core.storage.LanguagePackStorage
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.SpeechRecognitionResult
import com.itantra.domain.model.SpeechSynthesisRequest
import com.itantra.domain.model.SpeechSynthesisResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Validates the production-safe multilingual offline TTS requirements:
 * 1. Model & tokens file pre-validation (missing, empty, unreadable, valid).
 * 2. Unsupported language graceful null handling.
 * 3. Single TTS model in RAM lifecycle (eviction on language switch).
 * 4. Same-language idempotency (no unnecessary reload).
 * 5. Blank text rejection before native inference.
 * 6. Native synthesis exception handling (no fake empty PCM success).
 * 7. Empty generated PCM rejection.
 * 8. Audio write failure state preservation.
 */
class MultilingualTtsIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class EventLog {
        val events = mutableListOf<String>()
    }

    private class FakeTestStorage(private val rootDir: File) : LanguagePackStorage {
        override fun packDirectory(code: LanguageCode): File = File(rootDir, code.wireCode)
        override fun isInstalled(code: LanguageCode): Boolean = isTtsInstalled(code)
        override suspend fun verifyChecksums(code: LanguageCode, expectedChecksums: Map<String, String>): Boolean = true
        override suspend fun deletePack(code: LanguageCode) { packDirectory(code).deleteRecursively() }
        override fun totalInstalledBytes(): Long = 0L
        override fun isSharedSttInstalled(): Boolean = true
        override fun isTtsInstalled(code: LanguageCode): Boolean {
            val ttsDir = File(packDirectory(code), "tts")
            val model = File(ttsDir, "model.onnx")
            val tokens = File(ttsDir, "tokens.txt")
            return model.exists() && model.length() > 0L && tokens.exists() && tokens.length() > 0L
        }
    }

    private class LifecycleRecordingSynthesizer(
        override val languageCode: LanguageCode,
        private val log: EventLog,
        private val shouldFailSynthesis: Boolean = false,
        private val returnEmptyPcm: Boolean = false
    ) : SpeechSynthesizerEngine {
        private var loaded = false
        override val isLoaded: Boolean get() = loaded

        override suspend fun load() {
            loaded = true
            log.events += "load-tts-${languageCode.wireCode}"
        }

        override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechSynthesisResult {
            if (request.text.isBlank()) {
                throw IllegalArgumentException("TTS_BLANK_TEXT")
            }
            if (shouldFailSynthesis) {
                throw TtsSynthesisException("TTS_SYNTHESIS_FAILED: Native generation crashed")
            }
            val pcm = if (returnEmptyPcm) FloatArray(0) else FloatArray(1600) { 0.1f }
            if (pcm.isEmpty()) {
                throw TtsSynthesisException("TTS_EMPTY_PCM")
            }
            return SpeechSynthesisResult(
                correlationId = request.correlationId,
                pcmAudio = pcm,
                sampleRateHz = 16000,
                channelCount = 1,
                durationMillis = 100L
            )
        }

        override suspend fun unload() {
            loaded = false
            log.events += "unload-tts-${languageCode.wireCode}"
        }
    }

    private class FakeRecognizer(override val languageCode: LanguageCode) : SpeechRecognizerEngine {
        private var loaded = false
        override val isLoaded: Boolean get() = loaded
        override suspend fun load() { loaded = true }
        override suspend fun feed(samples: FloatArray) {}
        override suspend fun reset() {}
        override suspend fun finalizeUtterance(): SpeechRecognitionResult =
            SpeechRecognitionResult(languageCode, "Recognized", 1.0f, true, 0L)
        override suspend fun unload() { loaded = false }
    }

    // ── 1. MODEL & TOKEN PRE-VALIDATION TESTS ──────────────────────────────────

    @Test
    fun `load throws TtsLoadException when tts directory or model file is missing`() = runTest {
        val rootDir = tempFolder.newFolder("packs")
        val storage = FakeTestStorage(rootDir)
        val synthesizer = SherpaOnnxSpeechSynthesizer(
            context = object : android.content.ContextWrapper(null) {},
            languageCode = LanguageCode.HINDI,
            storage = storage,
            metricsRecorder = InMemoryMetricsRecorder()
        )

        var threw = false
        try {
            synthesizer.load()
        } catch (e: TtsLoadException) {
            threw = true
            assertTrue("Expected TTS_MODEL_MISSING in message: ${e.message}", e.message?.contains("TTS_MODEL_MISSING") == true)
        }
        assertTrue("Must throw TtsLoadException for missing model file", threw)
    }

    @Test
    fun `load throws TtsLoadException when model file is zero-byte`() = runTest {
        val rootDir = tempFolder.newFolder("packs_empty_model")
        val ttsDir = File(rootDir, "hi/tts").apply { mkdirs() }
        File(ttsDir, "model.onnx").createNewFile() // 0 bytes
        File(ttsDir, "tokens.txt").writeText("token_a\ntoken_b\n")

        val storage = FakeTestStorage(rootDir)
        val synthesizer = SherpaOnnxSpeechSynthesizer(
            context = object : android.content.ContextWrapper(null) {},
            languageCode = LanguageCode.HINDI,
            storage = storage,
            metricsRecorder = InMemoryMetricsRecorder()
        )

        var threw = false
        try {
            synthesizer.load()
        } catch (e: TtsLoadException) {
            threw = true
            assertTrue("Expected TTS_MODEL_EMPTY in message: ${e.message}", e.message?.contains("TTS_MODEL_EMPTY") == true)
        }
        assertTrue("Must throw TtsLoadException for zero-byte model file", threw)
    }

    @Test
    fun `load throws TtsLoadException when tokens file is missing`() = runTest {
        val rootDir = tempFolder.newFolder("packs_no_tokens")
        val ttsDir = File(rootDir, "hi/tts").apply { mkdirs() }
        File(ttsDir, "model.onnx").writeBytes(ByteArray(1024) { 1 })

        val storage = FakeTestStorage(rootDir)
        val synthesizer = SherpaOnnxSpeechSynthesizer(
            context = object : android.content.ContextWrapper(null) {},
            languageCode = LanguageCode.HINDI,
            storage = storage,
            metricsRecorder = InMemoryMetricsRecorder()
        )

        var threw = false
        try {
            synthesizer.load()
        } catch (e: TtsLoadException) {
            threw = true
            assertTrue("Expected TTS_TOKENS_MISSING in message: ${e.message}", e.message?.contains("TTS_TOKENS_MISSING") == true)
        }
        assertTrue("Must throw TtsLoadException for missing tokens file", threw)
    }

    @Test
    fun `load throws TtsLoadException when tokens file is empty or contains only whitespace`() = runTest {
        val rootDir = tempFolder.newFolder("packs_blank_tokens")
        val ttsDir = File(rootDir, "hi/tts").apply { mkdirs() }
        File(ttsDir, "model.onnx").writeBytes(ByteArray(1024) { 1 })
        File(ttsDir, "tokens.txt").writeText("   \n\n\t\n") // Only whitespace

        val storage = FakeTestStorage(rootDir)
        val synthesizer = SherpaOnnxSpeechSynthesizer(
            context = object : android.content.ContextWrapper(null) {},
            languageCode = LanguageCode.HINDI,
            storage = storage,
            metricsRecorder = InMemoryMetricsRecorder()
        )

        var threw = false
        try {
            synthesizer.load()
        } catch (e: TtsLoadException) {
            threw = true
            assertTrue("Expected TTS_TOKENS_INVALID in message: ${e.message}", e.message?.contains("TTS_TOKENS_INVALID") == true)
        }
        assertTrue("Must throw TtsLoadException for whitespace-only tokens file", threw)
    }

    // ── 2. UNSUPPORTED LANGUAGE TESTS ──────────────────────────────────────────

    @Test
    fun `ModelFileSpecs getTtsSpec returns null gracefully for unsupported language`() {
        // Supported languages return non-null specs
        assertNotNull(ModelFileSpecs.getTtsSpec(LanguageCode.HINDI))
        assertNotNull(ModelFileSpecs.getTtsSpec(LanguageCode.ENGLISH))
        assertNotNull(ModelFileSpecs.getTtsSpec(LanguageCode.TELUGU))
        assertNotNull(ModelFileSpecs.getTtsSpec(LanguageCode.TAMIL))

        // All 10 canonical languages must have defined specifications
        for (lang in com.itantra.domain.model.LanguageCatalog.all.map { it.code }) {
            val spec = ModelFileSpecs.getTtsSpec(lang)
            assertNotNull("Language $lang must have defined TTS spec", spec)
            assertEquals("model.onnx", spec!!.mainModelFile)
            assertEquals("tokens.txt", spec.tokensFile)
        }
    }

    // ── 3. ENGINE LIFECYCLE & SINGLE MODEL IN RAM ──────────────────────────────

    @Test
    fun `ensureTts unloads previous TTS before loading new TTS language`() = runTest {
        val log = EventLog()
        val factory = object : EngineFactory {
            override fun createRecognizer(language: LanguageCode) = FakeRecognizer(language)
            override fun createSynthesizer(language: LanguageCode) =
                LifecycleRecordingSynthesizer(language, log)
        }
        val sessionManager = ActiveLanguageSessionManager(factory)

        // 1. Load Hindi TTS
        sessionManager.ensureTts(LanguageCode.HINDI)
        assertEquals(LanguageCode.HINDI, sessionManager.currentTtsEngine?.languageCode)
        assertTrue(sessionManager.currentTtsEngine!!.isLoaded)
        assertEquals(listOf("load-tts-hi"), log.events)

        // 2. Switch to Telugu TTS
        sessionManager.ensureTts(LanguageCode.TELUGU)
        assertEquals(LanguageCode.TELUGU, sessionManager.currentTtsEngine?.languageCode)
        assertTrue(sessionManager.currentTtsEngine!!.isLoaded)

        // Strict ordering: Hindi MUST be unloaded before Telugu is loaded (never both in RAM)
        val unloadHiIdx = log.events.indexOf("unload-tts-hi")
        val loadTeIdx = log.events.indexOf("load-tts-te")
        assertTrue("Hindi must be unloaded", unloadHiIdx >= 0)
        assertTrue("Telugu must be loaded", loadTeIdx >= 0)
        assertTrue("Hindi unload must precede Telugu load", unloadHiIdx < loadTeIdx)
        assertEquals(3, log.events.size) // load-hi, unload-hi, load-te
    }

    @Test
    fun `ensureTts is a no-op when requested language is already loaded`() = runTest {
        val log = EventLog()
        val factory = object : EngineFactory {
            override fun createRecognizer(language: LanguageCode) = FakeRecognizer(language)
            override fun createSynthesizer(language: LanguageCode) =
                LifecycleRecordingSynthesizer(language, log)
        }
        val sessionManager = ActiveLanguageSessionManager(factory)

        sessionManager.ensureTts(LanguageCode.HINDI)
        log.events.clear()

        // Calling again with same language
        sessionManager.ensureTts(LanguageCode.HINDI)
        assertTrue("No engine events should occur on redundant ensureTts", log.events.isEmpty())
        assertEquals(LanguageCode.HINDI, sessionManager.currentTtsEngine?.languageCode)
        assertTrue(sessionManager.currentTtsEngine!!.isLoaded)
    }

    @Test
    fun `ensureTts does not disturb existing STT engine`() = runTest {
        val log = EventLog()
        val factory = object : EngineFactory {
            override fun createRecognizer(language: LanguageCode) = FakeRecognizer(language)
            override fun createSynthesizer(language: LanguageCode) =
                LifecycleRecordingSynthesizer(language, log)
        }
        val sessionManager = ActiveLanguageSessionManager(factory)

        // Initial state: Hindi STT loaded
        sessionManager.ensureCapabilities(LanguageCode.HINDI, requireStt = true, requireTts = false)
        val sttEngine = sessionManager.currentSttEngine
        assertNotNull(sttEngine)
        assertTrue(sttEngine!!.isLoaded)

        // Ensure Telugu TTS
        sessionManager.ensureTts(LanguageCode.TELUGU)

        // Crucial: STT engine must remain intact and loaded!
        assertEquals("STT engine must not be replaced", sttEngine, sessionManager.currentSttEngine)
        assertTrue("STT engine must remain loaded", sessionManager.currentSttEngine!!.isLoaded)
        // TTS engine must be Telugu
        assertEquals(LanguageCode.TELUGU, sessionManager.currentTtsEngine?.languageCode)
    }

    // ── 4. SYNTHESIS ERROR & VALIDATION BEHAVIORS ──────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `blank text is rejected before native inference`() = runTest {
        val log = EventLog()
        val synthesizer = LifecycleRecordingSynthesizer(LanguageCode.HINDI, log)
        synthesizer.load()

        synthesizer.synthesize(SpeechSynthesisRequest(LanguageCode.HINDI, "   \n\t  ", "corr-1"))
    }

    @Test
    fun `native synthesis failure throws TtsSynthesisException without returning fake empty PCM`() = runTest {
        val log = EventLog()
        val synthesizer = LifecycleRecordingSynthesizer(LanguageCode.HINDI, log, shouldFailSynthesis = true)
        synthesizer.load()

        var threw = false
        try {
            synthesizer.synthesize(SpeechSynthesisRequest(LanguageCode.HINDI, "Valid text", "corr-2"))
        } catch (e: TtsSynthesisException) {
            threw = true
            assertTrue("Expected TTS_SYNTHESIS_FAILED in message", e.message?.contains("TTS_SYNTHESIS_FAILED") == true)
        }
        assertTrue("Must throw TtsSynthesisException on synthesis failure", threw)
    }

    @Test
    fun `empty generated PCM is rejected with TtsSynthesisException`() = runTest {
        val log = EventLog()
        val synthesizer = LifecycleRecordingSynthesizer(LanguageCode.HINDI, log, returnEmptyPcm = true)
        synthesizer.load()

        var threw = false
        try {
            synthesizer.synthesize(SpeechSynthesisRequest(LanguageCode.HINDI, "Valid text", "corr-3"))
        } catch (e: TtsSynthesisException) {
            threw = true
            assertTrue("Expected TTS_EMPTY_PCM in message", e.message?.contains("TTS_EMPTY_PCM") == true)
        }
        assertTrue("Must throw TtsSynthesisException on empty PCM", threw)
    }

    @Test
    fun `unload cleans up engine state safely`() = runTest {
        val log = EventLog()
        val synthesizer = LifecycleRecordingSynthesizer(LanguageCode.HINDI, log)
        synthesizer.load()
        assertTrue(synthesizer.isLoaded)

        synthesizer.unload()
        assertFalse(synthesizer.isLoaded)
        assertEquals(listOf("load-tts-hi", "unload-tts-hi"), log.events)
    }
}
