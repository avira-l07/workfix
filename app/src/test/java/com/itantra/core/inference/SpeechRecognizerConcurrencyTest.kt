package com.itantra.core.inference

import android.content.ContextWrapper
import com.itantra.core.metrics.InMemoryMetricsRecorder
import com.itantra.core.storage.LanguagePackStorage
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.SpeechRecognitionResult
import com.itantra.domain.model.SpeechSynthesisRequest
import com.itantra.domain.model.SpeechSynthesisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Validates concurrency protection in SherpaOnnxSpeechRecognizer:
 * - Mutex protects finalizeUtterance / decodeDirect from racing against unload()
 * - Language switch initiated by ActiveLanguageSessionManager while a recognition
 *   is mid-decode safely awaits decode completion rather than releasing native memory
 * - Unloaded recognizer rejects decode with IllegalStateException instead of native crash
 */
class SpeechRecognizerConcurrencyTest {

    private class DummyStorage : LanguagePackStorage {
        override fun packDirectory(code: LanguageCode): File = File("dummy/${code.wireCode}")
        override fun isInstalled(code: LanguageCode): Boolean = true
        override suspend fun verifyChecksums(code: LanguageCode, expectedChecksums: Map<String, String>): Boolean = true
        override suspend fun deletePack(code: LanguageCode) {}
        override fun totalInstalledBytes(): Long = 0L
    }

    private fun createRecognizer(lang: LanguageCode = LanguageCode.HINDI): SherpaOnnxSpeechRecognizer {
        return SherpaOnnxSpeechRecognizer(
            context = ContextWrapper(null),
            languageCode = lang,
            storage = DummyStorage(),
            metricsRecorder = InMemoryMetricsRecorder(),
            autoDetect = false,
            targetLanguage = null
        )
    }

    @Test
    fun `test mid-decode unload is blocked by recognizerMutex until decode completes`() = runBlocking {
        val recognizer = createRecognizer()

        // 1. Simulate an in-flight decode operation holding recognizerMutex
        recognizer.recognizerMutex.lock()
        assertTrue("Mutex must be held during decode", recognizer.recognizerMutex.isLocked)

        var unloadFinished = false

        // 2. Language switch or cleanup calls unload() on another coroutine
        val unloadJob = launch(Dispatchers.Default) {
            recognizer.unload()
            unloadFinished = true
        }

        // Allow coroutine to run and attempt to acquire lock
        delay(100)

        // 3. unload() MUST be blocked waiting on the mutex, NOT tearing down recognizer mid-decode
        assertTrue("unload() must still be active / suspended waiting for lock", unloadJob.isActive)
        assertFalse("unload() must NOT complete while decode holds the mutex", unloadFinished)

        // 4. Decode finishes and releases the mutex
        recognizer.recognizerMutex.unlock()

        // 5. unload() should now be unblocked and complete cleanly
        unloadJob.join()
        assertTrue("unload() must complete after decode releases lock", unloadFinished)
        assertFalse("Recognizer isLoaded must be false after unload", recognizer.isLoaded)
    }

    @Test
    fun `test finalizeUtterance throws IllegalStateException safely when recognizer is not loaded`() = runBlocking {
        val recognizer = createRecognizer()
        // Ensure unloaded state
        recognizer.unload()

        var threw = false
        try {
            recognizer.finalizeUtterance()
        } catch (e: IllegalStateException) {
            threw = true
            assertEquals("Recognizer not loaded", e.message)
        }
        assertTrue("Must safely throw IllegalStateException without native SIGSEGV", threw)
    }

    @Test
    fun `test decodeDirect throws IllegalStateException safely when recognizer is not loaded`() = runBlocking {
        val recognizer = createRecognizer()
        recognizer.unload()

        var threw = false
        try {
            recognizer.decodeDirect(floatArrayOf(0.1f, 0.2f))
        } catch (e: IllegalStateException) {
            threw = true
            assertEquals("Recognizer not loaded", e.message)
        }
        assertTrue("Must safely throw IllegalStateException without native SIGSEGV", threw)
    }

    @Test
    fun `test language switch in ActiveLanguageSessionManager waits for mid-decode operation`() = runBlocking {
        val decodeMutex = Mutex()
        val eventLog = mutableListOf<String>()

        class ConcurrentFakeRecognizer(override val languageCode: LanguageCode) : SpeechRecognizerEngine {
            override var isLoaded: Boolean = true
            override suspend fun load() { isLoaded = true }
            override suspend fun feed(samples: FloatArray) {}
            override suspend fun reset() {}

            override suspend fun finalizeUtterance(): SpeechRecognitionResult {
                return decodeMutex.withLock {
                    eventLog.add("decode-start-${languageCode.wireCode}")
                    delay(150)
                    eventLog.add("decode-end-${languageCode.wireCode}")
                    SpeechRecognitionResult(languageCode, "Done", 1.0f, true, 0L)
                }
            }

            override suspend fun unload() {
                // Mirroring SherpaOnnxSpeechRecognizer mutex protection
                decodeMutex.withLock {
                    eventLog.add("unload-${languageCode.wireCode}")
                    isLoaded = false
                }
            }
        }

        class FakeSynthesizer(override val languageCode: LanguageCode) : SpeechSynthesizerEngine {
            override var isLoaded: Boolean = true
            override suspend fun load() { isLoaded = true }
            override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechSynthesisResult = throw NotImplementedError()
            override suspend fun unload() { isLoaded = false }
        }

        val factory = object : EngineFactory {
            override fun createRecognizer(language: LanguageCode) = ConcurrentFakeRecognizer(language)
            override fun createSynthesizer(language: LanguageCode) = FakeSynthesizer(language)
        }

        val manager = ActiveLanguageSessionManager(factory)
        manager.switchTo(LanguageCode.HINDI)

        val sttEngine = manager.currentSttEngine!!
        assertEquals(LanguageCode.HINDI, sttEngine.languageCode)

        // Launch in-flight decode for Hindi
        val decodeJob = launch(Dispatchers.Default) {
            sttEngine.finalizeUtterance()
        }

        // Wait until decode has entered its critical section
        while (!decodeMutex.isLocked) {
            delay(10)
        }

        // Concurrent language switch to Tamil triggered by user/session
        val switchJob = launch(Dispatchers.Default) {
            manager.ensureStt(LanguageCode.TAMIL)
        }

        decodeJob.join()
        switchJob.join()

        // Verify ordering: decode MUST complete before unload executes
        val decodeEndIndex = eventLog.indexOf("decode-end-hi")
        val unloadHindiIndex = eventLog.indexOf("unload-hi")

        assertTrue("Decode must finish before unload begins", decodeEndIndex != -1 && unloadHindiIndex != -1)
        assertTrue("decode-end-hi must precede unload-hi in event log: $eventLog", decodeEndIndex < unloadHindiIndex)
        assertEquals(LanguageCode.TAMIL, manager.activeSttLanguage.value)
    }
}
