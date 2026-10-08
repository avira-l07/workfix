package com.itantra.regression

import android.content.Context
import android.content.ContextWrapper
import com.itantra.core.inference.ContinuousListenEngine
import com.itantra.core.inference.ContinuousListenState
import com.itantra.core.inference.SentenceBoundaryConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ContinuousListenSafetyTest {
    @Test fun uninterruptedFallbackSpeechStopsAtConfiguredMaximum() {
        val engine = ContinuousListenEngine(FakeContext(), SentenceBoundaryConfig(minSpeechDurationSec = .02f, maxSpeechDurationSec = .1f))
        engine.start()
        repeat(4) { engine.feedAudio(FloatArray(512) { .5f }) }
        assertNotNull(engine.lastSegment.value)
        assertEquals(2048, engine.lastSegment.value!!.samples.size)
        engine.stop()
    }

    @Test fun changingVadConfigKeepsAPausedEnginePausedAndAppliesTheNewLimit() {
        val engine = ContinuousListenEngine(FakeContext())
        engine.start()
        engine.pauseListening()
        engine.updateConfig(SentenceBoundaryConfig(minSpeechDurationSec = .02f, maxSpeechDurationSec = .1f))
        assertEquals(ContinuousListenState.PAUSED, engine.state.value)
        engine.feedAudio(floatArrayOf())
        engine.resumeListening()
        repeat(4) { engine.feedAudio(FloatArray(512) { .5f }) }
        assertEquals(2048, engine.lastSegment.value!!.samples.size)
        engine.stop()
    }

    private class FakeContext : ContextWrapper(null) {
        private val tempDir = File(System.getProperty("java.io.tmpdir"), "vad_reg_test_${System.currentTimeMillis()}").apply { mkdirs() }
        override fun getCacheDir(): File = tempDir
        override fun getFilesDir(): File = tempDir
        override fun getApplicationContext(): Context = this
        override fun getAssets(): android.content.res.AssetManager {
            throw java.io.FileNotFoundException("AssetManager not present in test")
        }
    }

    private lateinit var engine: ContinuousListenEngine

    @Before
    fun setUp() {
        engine = ContinuousListenEngine(FakeContext())
    }

    @Test
    fun testPausePreservesVadAndResumeRestoresListening() {
        engine.start()
        assertEquals(ContinuousListenState.LISTENING, engine.state.value)

        // Pause listening during TTS
        engine.pauseListening()
        assertEquals(ContinuousListenState.PAUSED, engine.state.value)

        // Audio feeding while paused must be ignored
        engine.feedAudio(FloatArray(512) { 0.5f })
        assertEquals(ContinuousListenState.PAUSED, engine.state.value)

        // Resume restores listening
        engine.resumeListening()
        assertEquals(ContinuousListenState.LISTENING, engine.state.value)

        // Full stop releases native resources
        engine.stop()
        assertEquals(ContinuousListenState.OFF, engine.state.value)
    }

    @Test
    fun testMultiplePauseResumeCyclesWithoutReleasingVad() {
        engine.start()

        for (i in 1..5) {
            engine.resumeListening()
            assertEquals("Cycle $i resume", ContinuousListenState.LISTENING, engine.state.value)
            engine.pauseListening()
            assertEquals("Cycle $i pause", ContinuousListenState.PAUSED, engine.state.value)
        }

        engine.stop()
        assertEquals(ContinuousListenState.OFF, engine.state.value)
    }
}
