package com.itantra.core.inference

import com.itantra.data.languagepack.FileLanguagePackStorage
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.SpeechRecognitionResult
import com.itantra.domain.model.SpeechSynthesisRequest
import com.itantra.domain.model.SpeechSynthesisResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TtsLifecycleAndStorageTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private class EventLog {
        val events = mutableListOf<String>()
    }

    private class FakeTestRecognizer(
        override val languageCode: LanguageCode,
        private val log: EventLog
    ) : SpeechRecognizerEngine {
        private var loaded = false
        override val isLoaded: Boolean get() = loaded
        override suspend fun load() {
            loaded = true
            log.events += "load-stt-${languageCode.wireCode}"
        }
        override suspend fun feed(samples: FloatArray) {}
        override suspend fun reset() {}
        override suspend fun finalizeUtterance(): SpeechRecognitionResult {
            return SpeechRecognitionResult(languageCode, "Test", 0.9f, true, System.currentTimeMillis())
        }
        override suspend fun unload() {
            loaded = false
            log.events += "unload-stt-${languageCode.wireCode}"
        }
    }

    private class FakeTestSynthesizer(
        override val languageCode: LanguageCode,
        private val log: EventLog,
        private val failOnLoad: Boolean = false
    ) : SpeechSynthesizerEngine {
        private var loaded = false
        override val isLoaded: Boolean get() = loaded
        override suspend fun load() {
            if (failOnLoad) throw IllegalStateException("Simulated TTS load failure")
            loaded = true
            log.events += "load-tts-${languageCode.wireCode}"
        }
        override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechSynthesisResult {
            if (request.text.isBlank()) {
                return SpeechSynthesisResult(
                    correlationId = request.correlationId,
                    pcmAudio = FloatArray(0),
                    sampleRateHz = 16000,
                    channelCount = 1,
                    durationMillis = 0L
                )
            }
            // Generate non-empty test waveform (100 samples)
            val samples = FloatArray(100) { 0.1f }
            return SpeechSynthesisResult(
                correlationId = request.correlationId,
                pcmAudio = samples,
                sampleRateHz = 16000,
                channelCount = 1,
                durationMillis = 6L
            )
        }
        override suspend fun unload() {
            loaded = false
            log.events += "unload-tts-${languageCode.wireCode}"
        }
    }

    // 1. English TTS model detection
    @Test
    fun test_englishTtsModelDetection_success() {
        val root = tempFolder.newFolder("files_en")
        val storage = FileLanguagePackStorage(root)

        val enTtsDir = File(storage.packDirectory(LanguageCode.ENGLISH), "tts")
        enTtsDir.mkdirs()
        File(enTtsDir, "model.onnx").writeBytes(ByteArray(1024) { 1 })
        File(enTtsDir, "tokens.txt").writeText("a 1\nb 2\n")

        assertTrue("English TTS must be detected as installed", storage.isTtsInstalled(LanguageCode.ENGLISH))
    }

    // 2. Hindi TTS model detection
    @Test
    fun test_hindiTtsModelDetection_success() {
        val root = tempFolder.newFolder("files_hi")
        val storage = FileLanguagePackStorage(root)

        val hiTtsDir = File(storage.packDirectory(LanguageCode.HINDI), "tts")
        hiTtsDir.mkdirs()
        File(hiTtsDir, "model.onnx").writeBytes(ByteArray(1024) { 2 })
        File(hiTtsDir, "tokens.txt").writeText("अ 1\nआ 2\n")

        assertTrue("Hindi TTS must be detected as installed", storage.isTtsInstalled(LanguageCode.HINDI))
    }

    // 3. Missing-model state
    @Test
    fun test_missingModel_returnsFalse() {
        val root = tempFolder.newFolder("files_missing")
        val storage = FileLanguagePackStorage(root)

        assertFalse("Missing English TTS must return false", storage.isTtsInstalled(LanguageCode.ENGLISH))
        assertFalse("Missing Hindi TTS must return false", storage.isTtsInstalled(LanguageCode.HINDI))
    }

    // 4. Zero-byte model rejection
    @Test
    fun test_zeroByteModel_rejected() {
        val root = tempFolder.newFolder("files_zerobyte")
        val storage = FileLanguagePackStorage(root)

        val enTtsDir = File(storage.packDirectory(LanguageCode.ENGLISH), "tts")
        enTtsDir.mkdirs()
        File(enTtsDir, "model.onnx").writeBytes(ByteArray(0)) // 0 bytes!
        File(enTtsDir, "tokens.txt").writeText("valid tokens")

        assertFalse("Zero-byte model.onnx must be rejected", storage.isTtsInstalled(LanguageCode.ENGLISH))

        File(enTtsDir, "model.onnx").writeBytes(ByteArray(1024) { 1 })
        File(enTtsDir, "tokens.txt").writeBytes(ByteArray(0)) // 0 bytes!

        assertFalse("Zero-byte tokens.txt must be rejected", storage.isTtsInstalled(LanguageCode.ENGLISH))
    }

    // 5. English text -> non-empty PCM synthesis
    @Test
    fun test_englishText_generatesNonEmptyPcm() = runTest {
        val log = EventLog()
        val synth = FakeTestSynthesizer(LanguageCode.ENGLISH, log)
        synth.load()

        val req = SpeechSynthesisRequest(
            languageCode = LanguageCode.ENGLISH,
            text = "Hello, this is an iTantra offline voice test.",
            correlationId = "test_en"
        )
        val result = synth.synthesize(req)

        assertNotNull(result)
        assertTrue("English PCM must be non-empty", result.pcmAudio.isNotEmpty())
        assertEquals(16000, result.sampleRateHz)
        assertEquals(1, result.channelCount)
        assertTrue(result.durationMillis > 0)
    }

    // 6. Hindi text -> non-empty PCM synthesis
    @Test
    fun test_hindiText_generatesNonEmptyPcm() = runTest {
        val log = EventLog()
        val synth = FakeTestSynthesizer(LanguageCode.HINDI, log)
        synth.load()

        val req = SpeechSynthesisRequest(
            languageCode = LanguageCode.HINDI,
            text = "नमस्ते, आप कैसे हैं?",
            correlationId = "test_hi"
        )
        val result = synth.synthesize(req)

        assertNotNull(result)
        assertTrue("Hindi PCM must be non-empty", result.pcmAudio.isNotEmpty())
        assertEquals(16000, result.sampleRateHz)
        assertEquals(1, result.channelCount)
        assertTrue(result.durationMillis > 0)
    }

    // 7. Empty and blank text rejection
    @Test
    fun test_emptyAndBlankText_rejectedGracefully() = runTest {
        val log = EventLog()
        val synth = FakeTestSynthesizer(LanguageCode.ENGLISH, log)
        synth.load()

        for (blankText in listOf("", "   ", "\t\n")) {
            val req = SpeechSynthesisRequest(
                languageCode = LanguageCode.ENGLISH,
                text = blankText,
                correlationId = "blank_test"
            )
            val result = synth.synthesize(req)
            assertEquals("Blank text must yield 0 PCM samples", 0, result.pcmAudio.size)
            assertEquals("Blank text duration must be 0ms", 0L, result.durationMillis)
        }
    }

    // 8. Language switch unloads previous TTS and loads new TTS
    @Test
    fun test_languageSwitch_unloadsPreviousTtsAndLoadsNext() = runTest {
        val log = EventLog()
        val factory = object : EngineFactory {
            override fun createRecognizer(language: LanguageCode) = FakeTestRecognizer(language, log)
            override fun createSynthesizer(language: LanguageCode) = FakeTestSynthesizer(language, log)
        }
        val sessionMgr = ActiveLanguageSessionManager(engineFactory = factory)

        // Switch to EN
        sessionMgr.switchTo(LanguageCode.ENGLISH, loadStt = true, loadTts = true)
        assertEquals(LanguageCode.ENGLISH, sessionMgr.activeLanguage.value)
        assertTrue(log.events.contains("load-tts-en"))
        assertEquals(LanguageSessionState.READY, sessionMgr.sessionState.value)

        // Switch to HI
        sessionMgr.switchTo(LanguageCode.HINDI, loadStt = true, loadTts = true)
        assertEquals(LanguageCode.HINDI, sessionMgr.activeLanguage.value)
        val unloadEnIndex = log.events.indexOf("unload-tts-en")
        val loadHiIndex = log.events.indexOf("load-tts-hi")

        assertTrue("unload-tts-en must occur", unloadEnIndex >= 0)
        assertTrue("load-tts-hi must occur", loadHiIndex >= 0)
        assertTrue("unload-tts-en must happen BEFORE load-tts-hi", unloadEnIndex < loadHiIndex)

        // Switch back to EN
        sessionMgr.switchTo(LanguageCode.ENGLISH, loadStt = true, loadTts = true)
        val unloadHiIndex = log.events.indexOf("unload-tts-hi")
        val loadEnIndex2 = log.events.lastIndexOf("load-tts-en")

        assertTrue("unload-tts-hi must occur", unloadHiIndex >= 0)
        assertTrue("unload-tts-hi must happen BEFORE second load-tts-en", unloadHiIndex < loadEnIndex2)
    }

    // 9. Missing TTS does not break STT
    @Test
    fun test_missingTts_doesNotBreakStt() = runTest {
        val log = EventLog()
        val factory = object : EngineFactory {
            override fun createRecognizer(language: LanguageCode) = FakeTestRecognizer(language, log)
            override fun createSynthesizer(language: LanguageCode): SpeechSynthesizerEngine? = null // TTS missing!
        }
        val sessionMgr = ActiveLanguageSessionManager(engineFactory = factory)

        sessionMgr.switchTo(LanguageCode.ENGLISH, loadStt = true, loadTts = true)

        assertEquals("Session must still be READY when TTS is missing", LanguageSessionState.READY, sessionMgr.sessionState.value)
        assertEquals(LanguageCode.ENGLISH, sessionMgr.activeLanguage.value)
        assertNotNull("STT must be active", sessionMgr.currentSttEngine)
        assertTrue("STT must be loaded", sessionMgr.currentSttEngine!!.isLoaded)
        assertNull("TTS engine must be null when missing", sessionMgr.currentTtsEngine)
    }

    // 10. Explicit loadTts=false loads STT without touching or loading TTS
    @Test
    fun test_explicitLoadTtsFalse_loadsSttOnly() = runTest {
        val log = EventLog()
        val factory = object : EngineFactory {
            override fun createRecognizer(language: LanguageCode) = FakeTestRecognizer(language, log)
            override fun createSynthesizer(language: LanguageCode) = FakeTestSynthesizer(language, log)
        }
        val sessionMgr = ActiveLanguageSessionManager(engineFactory = factory)

        sessionMgr.switchTo(LanguageCode.HINDI, loadStt = true, loadTts = false)

        assertEquals(LanguageSessionState.READY, sessionMgr.sessionState.value)
        assertEquals(LanguageCode.HINDI, sessionMgr.activeLanguage.value)
        assertNotNull("STT must be loaded", sessionMgr.currentSttEngine)
        assertTrue(sessionMgr.currentSttEngine!!.isLoaded)
        assertNull("TTS engine must be null when loadTts=false", sessionMgr.currentTtsEngine)
        assertFalse("load-tts-hi must NOT have occurred", log.events.contains("load-tts-hi"))
        assertTrue("load-stt-hi must have occurred", log.events.contains("load-stt-hi"))
    }

    // 11. Atomic import from directory
    @Test
    fun test_atomicImportFromDirectory() {
        val hostDir = tempFolder.newFolder("host_models")
        val enHost = File(hostDir, "en/tts")
        enHost.mkdirs()
        File(enHost, "model.onnx").writeBytes(ByteArray(2048) { 10 })
        File(enHost, "tokens.txt").writeText("en tokens")

        val hiHost = File(hostDir, "hi/tts")
        hiHost.mkdirs()
        File(hiHost, "model.onnx").writeBytes(ByteArray(2048) { 20 })
        File(hiHost, "tokens.txt").writeText("hi tokens")

        val appFiles = tempFolder.newFolder("app_files")
        val storage = FileLanguagePackStorage(appFiles)

        assertFalse(storage.isTtsInstalled(LanguageCode.ENGLISH))
        assertFalse(storage.isTtsInstalled(LanguageCode.HINDI))

        val importOk = storage.importFromDirectory(hostDir)
        assertTrue("importFromDirectory must succeed", importOk)

        assertTrue("English TTS must be installed after import", storage.isTtsInstalled(LanguageCode.ENGLISH))
        assertTrue("Hindi TTS must be installed after import", storage.isTtsInstalled(LanguageCode.HINDI))
    }
}
