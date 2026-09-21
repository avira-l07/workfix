package com.itantra.core.inference

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests verifying SentenceBoundaryConfig, pause/resume lifecycle,
 * silence detection, and resource safety of ContinuousListenEngine.
 */
class ContinuousListenEngineTest {

    private class FakeTestContext : ContextWrapper(null) {
        private val tempDir = File(System.getProperty("java.io.tmpdir"), "itantra_vad_test_${System.currentTimeMillis()}").apply { mkdirs() }
        override fun getCacheDir(): File = tempDir
        override fun getFilesDir(): File = tempDir
        override fun getApplicationContext(): Context = this
        override fun getAssets(): android.content.res.AssetManager {
            throw java.io.FileNotFoundException("AssetManager not present in test")
        }
    }

    private lateinit var mockContext: Context
    private lateinit var engine: ContinuousListenEngine

    @Before
    fun setUp() {
        mockContext = FakeTestContext()
        engine = ContinuousListenEngine(mockContext)
    }

    @Test
    fun `test SentenceBoundaryConfig defaults and update`() {
        val defaultConfig = engine.sentenceBoundaryConfig
        assertEquals(0.5f, defaultConfig.speechStartThreshold, 0.001f)
        assertEquals(0.7f, defaultConfig.minSilenceDurationSec, 0.001f)
        assertEquals(0.25f, defaultConfig.minSpeechDurationSec, 0.001f)
        assertEquals(20.0f, defaultConfig.maxSpeechDurationSec, 0.001f)
        assertEquals(512, defaultConfig.windowSize)

        val customConfig = SentenceBoundaryConfig(
            speechStartThreshold = 0.6f,
            minSilenceDurationSec = 1.0f,
            minSpeechDurationSec = 0.3f,
            maxSpeechDurationSec = 15.0f,
            windowSize = 256
        )
        engine.updateConfig(customConfig)
        assertEquals(customConfig, engine.sentenceBoundaryConfig)
    }

    @Test
    fun `test pauseListening and resumeListening state transitions`() {
        engine.start()
        assertEquals(ContinuousListenState.LISTENING, engine.state.value)

        engine.pauseListening()
        assertEquals(ContinuousListenState.PAUSED, engine.state.value)

        // Audio feeding while paused must be silently discarded
        val loudChunk = FloatArray(512) { 0.5f }
        engine.feedAudio(loudChunk)
        assertEquals(ContinuousListenState.PAUSED, engine.state.value)

        engine.resumeListening()
        assertEquals(ContinuousListenState.LISTENING, engine.state.value)

        engine.stop()
        assertEquals(ContinuousListenState.OFF, engine.state.value)
    }

    @Test
    fun `test resumeListening safely restarts from OFF state without getting stuck`() {
        engine.stop()
        assertEquals(ContinuousListenState.OFF, engine.state.value)

        // Calling resumeListening when OFF must safely call start()
        engine.resumeListening()
        assertEquals(ContinuousListenState.LISTENING, engine.state.value)

        engine.stop()
    }

    @Test
    fun `test sentence boundary endpointing distinguishes short pause from sentence finalization`() = runBlocking {
        // Fast configuration for unit test: 200ms silence threshold, 100ms min speech
        val testConfig = SentenceBoundaryConfig(
            speechStartThreshold = 0.5f,
            minSilenceDurationSec = 0.2f, // 200ms
            minSpeechDurationSec = 0.1f,  // 100ms
            maxSpeechDurationSec = 5.0f
        )
        engine.updateConfig(testConfig)
        engine.start()
        assertEquals(ContinuousListenState.LISTENING, engine.state.value)

        val speechChunk = FloatArray(512) { 0.2f } // loud samples, RMS > 0.02
        val silenceChunk = FloatArray(512) { 0.0f } // silent samples

        val receivedSegments = mutableListOf<AudioSegment>()
        val collectJob = launch(Dispatchers.Unconfined) {
            engine.segmentEvents.collect {
                receivedSegments.add(it)
            }
        }

        // 1. Feed speech chunks (~300ms of speech = ~10 chunks of 512 samples at 16kHz)
        for (i in 0 until 10) {
            engine.feedAudio(speechChunk)
        }
        assertEquals(ContinuousListenState.SPEECH_DETECTED, engine.state.value)

        // 2. Feed short pause (2 chunks of silence = ~64ms < 200ms threshold)
        for (i in 0 until 2) {
            engine.feedAudio(silenceChunk)
        }
        // Utterance must NOT be finalized yet
        assertTrue("Speech should still be active during short pause", receivedSegments.isEmpty())

        // 3. Resume speech (speech continues after short hesitation)
        for (i in 0 until 5) {
            engine.feedAudio(speechChunk)
        }
        assertTrue("Speech should still be active", receivedSegments.isEmpty())

        // 4. Feed long silence (> 200ms = 7 chunks of 512 = ~224ms)
        for (i in 0 until 8) {
            engine.feedAudio(silenceChunk)
        }

        // Utterance should now be finalized and emitted!
        withTimeout(2000L) {
            while (receivedSegments.isEmpty()) {
                kotlinx.coroutines.delay(20)
            }
        }
        collectJob.cancel()

        assertEquals(1, receivedSegments.size)
        val segment = receivedSegments[0]
        assertTrue("Finalized segment duration must be >= 100ms", segment.durationMs >= 100)
        assertNotNull(engine.lastSegment.value)

        engine.stop()
    }
}
