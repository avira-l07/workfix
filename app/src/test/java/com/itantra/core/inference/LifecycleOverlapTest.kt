package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.SpeechRecognitionResult
import com.itantra.domain.model.SpeechSynthesisRequest
import com.itantra.domain.model.SpeechSynthesisResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST A-D: Validates the duplicate-engine-management removal architecture.
 *
 * Rule: ONE owner (AppGraph lifecycle coordinator) may call ensureCapabilities/switchTo.
 * ViewModel and Repository must NOT independently load engines.
 */
class LifecycleOverlapTest {

    private class EventLog {
        val events = mutableListOf<String>()
    }

    private class FakeRecognizer(
        override val languageCode: LanguageCode,
        private val log: EventLog,
    ) : SpeechRecognizerEngine {
        private var loaded = false
        override val isLoaded: Boolean get() = loaded
        override suspend fun load() { loaded = true; log.events += "load-stt-${languageCode.wireCode}" }
        override suspend fun feed(samples: FloatArray) {}
        override suspend fun reset() {}
        override suspend fun finalizeUtterance(): SpeechRecognitionResult =
            SpeechRecognitionResult(languageCode, "Test", 0.9f, true, System.currentTimeMillis())
        override suspend fun unload() { loaded = false; log.events += "unload-stt-${languageCode.wireCode}" }
    }

    private class FakeSynthesizer(
        override val languageCode: LanguageCode,
        private val log: EventLog,
    ) : SpeechSynthesizerEngine {
        private var loaded = false
        override val isLoaded: Boolean get() = loaded
        override suspend fun load() { loaded = true; log.events += "load-tts-${languageCode.wireCode}" }
        override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechSynthesisResult =
            throw NotImplementedError()
        override suspend fun unload() { loaded = false; log.events += "unload-tts-${languageCode.wireCode}" }
    }

    private fun fakeFactory(log: EventLog) = object : EngineFactory {
        override fun createRecognizer(language: LanguageCode) = FakeRecognizer(language, log)
        override fun createSynthesizer(language: LanguageCode) = FakeSynthesizer(language, log)
    }

    // -------------------------------------------------------------------------
    // TEST A: repository active language changes once -> single engine transition
    // -------------------------------------------------------------------------
    @Test
    fun testA_singleLanguageChangeProducesExactlyOneEngineTransition() = runTest {
        val log = EventLog()
        val manager = ActiveLanguageSessionManager(fakeFactory(log))

        // Simulate exactly what the single AppGraph observer does for one language change
        manager.ensureCapabilities(LanguageCode.HINDI, requireStt = true, requireTts = true)

        val sttLoads = log.events.count { it == "load-stt-hi" }
        val ttsLoads = log.events.count { it == "load-tts-hi" }
        assertEquals("Expected exactly 1 STT load, got $sttLoads", 1, sttLoads)
        assertEquals("Expected exactly 1 TTS load, got $ttsLoads", 1, ttsLoads)
        assertEquals(LanguageCode.HINDI, manager.activeLanguage.value)
        assertEquals(LanguageSessionState.READY, manager.sessionState.value)
    }

    // -------------------------------------------------------------------------
    // TEST B: Hindi active, STT already loaded, Hindi TTS becomes installed later
    //         -> only TTS gets loaded, STT remains valid
    // -------------------------------------------------------------------------
    @Test
    fun testB_ttsBecomesAvailableLoadsOnlyTtsLeavingSttIntact() = runTest {
        val log = EventLog()
        val manager = ActiveLanguageSessionManager(fakeFactory(log))

        // Phase 1: Load only STT (TTS not yet installed)
        manager.ensureCapabilities(LanguageCode.HINDI, requireStt = true, requireTts = false)
        assertEquals(LanguageSessionState.READY, manager.sessionState.value)
        assertNotNull(manager.currentSttEngine)
        assertNull(manager.currentTtsEngine)
        assertTrue(manager.currentSttEngine!!.isLoaded)

        log.events.clear()

        // Phase 2: TTS now installed - only TTS should load
        manager.ensureCapabilities(LanguageCode.HINDI, requireStt = true, requireTts = true)
        assertEquals(LanguageSessionState.READY, manager.sessionState.value)
        assertNotNull(manager.currentTtsEngine)
        assertTrue(manager.currentTtsEngine!!.isLoaded)

        val sttUnloads = log.events.count { it == "unload-stt-hi" }
        val sttLoads = log.events.count { it == "load-stt-hi" }
        assertEquals("STT must not be unloaded on incremental TTS load", 0, sttUnloads)
        assertEquals("STT must not be reloaded on incremental TTS load", 0, sttLoads)
        assertTrue("TTS must have been loaded", log.events.contains("load-tts-hi"))
    }

    // -------------------------------------------------------------------------
    // TEST C: Rapid language change Hindi->English->Hindi -> final Hindi, no leak
    // -------------------------------------------------------------------------
    @Test
    fun testC_rapidLanguageChangeConvergesWithNoEngineLeak() = runTest {
        val log = EventLog()
        val manager = ActiveLanguageSessionManager(fakeFactory(log))

        // Each call is serialized by switchMutex
        manager.ensureCapabilities(LanguageCode.HINDI, requireStt = true, requireTts = true)
        manager.ensureCapabilities(LanguageCode.ENGLISH, requireStt = true, requireTts = true)
        manager.ensureCapabilities(LanguageCode.HINDI, requireStt = true, requireTts = true)

        assertEquals(LanguageCode.HINDI, manager.activeLanguage.value)
        assertEquals(LanguageCode.HINDI, manager.currentSttEngine?.languageCode)
        assertEquals(LanguageCode.HINDI, manager.currentTtsEngine?.languageCode)
        assertEquals(LanguageSessionState.READY, manager.sessionState.value)
        assertTrue(manager.currentSttEngine!!.isLoaded)
        assertTrue(manager.currentTtsEngine!!.isLoaded)

        // English engines must have been unloaded (no leak)
        assertTrue("English STT should have been unloaded", log.events.contains("unload-stt-en"))
        assertTrue("English TTS should have been unloaded", log.events.contains("unload-tts-en"))
    }

    // -------------------------------------------------------------------------
    // TEST D: ensureCapabilities is idempotent when already satisfied
    // -------------------------------------------------------------------------
    @Test
    fun testD_ensureCapabilitiesIsIdempotentWhenAlreadySatisfied() = runTest {
        val log = EventLog()
        val manager = ActiveLanguageSessionManager(fakeFactory(log))

        manager.ensureCapabilities(LanguageCode.HINDI, requireStt = true, requireTts = true)
        assertEquals(LanguageSessionState.READY, manager.sessionState.value)
        log.events.clear()

        // Calling again with same requirements must be a no-op
        manager.ensureCapabilities(LanguageCode.HINDI, requireStt = true, requireTts = true)
        assertTrue(
            "No engine events expected when requirements already satisfied, got ${log.events}",
            log.events.isEmpty()
        )
        assertEquals(LanguageSessionState.READY, manager.sessionState.value)
    }
}
