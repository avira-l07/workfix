package com.itantra.core.inference

import com.itantra.core.audio.awaitPlaybackDrain
import com.itantra.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechWorkflowAuditTest {
    private class Recognizer(override val languageCode: LanguageCode) : SpeechRecognizerEngine {
        override var isLoaded = false
        var unloaded = false
        val samples = mutableListOf<Float>()
        var loadAction: suspend () -> Unit = {}
        var decodeAction: suspend () -> Unit = {}
        override suspend fun load() { isLoaded = true; loadAction() }
        override suspend fun unload() { delay(1); isLoaded = false; unloaded = true }
        override suspend fun feed(samples: FloatArray) { this.samples.addAll(samples.toList()) }
        override suspend fun reset() { samples.clear() }
        override suspend fun finalizeUtterance(): SpeechRecognitionResult {
            decodeAction()
            return SpeechRecognitionResult(languageCode, samples.joinToString(), null, true, 0)
        }
    }
    private fun factory(stt: (LanguageCode) -> SpeechRecognizerEngine?) = object : EngineFactory {
        override fun createRecognizer(language: LanguageCode) = stt(language)
        override fun createSynthesizer(language: LanguageCode): SpeechSynthesizerEngine? = null
    }

    @Test fun failedSttAllocationIsReleasedAndLanguageCleared() = runTest {
        val engine = Recognizer(LanguageCode.HINDI).apply { loadAction = { error("failed after allocation") } }
        val session = ActiveLanguageSessionManager(factory { engine })
        assertTrue(runCatching { session.ensureStt(LanguageCode.HINDI) }.isFailure)
        assertTrue(engine.unloaded)
        assertNull(session.currentSttEngine)
        assertNull(session.activeSttLanguage.value)
        assertEquals(LanguageSessionState.ERROR, session.sessionState.value)
    }

    @Test fun cancelledSttAllocationStillRunsSuspendingCleanup() = runTest {
        val entered = CompletableDeferred<Unit>()
        val engine = Recognizer(LanguageCode.HINDI).apply { loadAction = { entered.complete(Unit); awaitCancellation() } }
        val session = ActiveLanguageSessionManager(factory { engine })
        val loading = launch { session.ensureStt(LanguageCode.HINDI) }
        entered.await()
        loading.cancelAndJoin()
        assertTrue(engine.unloaded)
        assertFalse(engine.isLoaded)
        assertNull(session.currentSttEngine)
    }

    @Test fun languageChangeWaitsForDecodeBeforeReleasingItsEngine() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val hindi = Recognizer(LanguageCode.HINDI).apply { decodeAction = { entered.complete(Unit); finish.await() } }
        val english = Recognizer(LanguageCode.ENGLISH)
        val session = ActiveLanguageSessionManager(factory { if (it == LanguageCode.HINDI) hindi else english })
        session.ensureStt(LanguageCode.HINDI, false)
        val decode = async { session.recognizeCapture(hindi, listOf(floatArrayOf(.2f))) }
        entered.await()
        val switching = launch { session.ensureStt(LanguageCode.ENGLISH, false) }
        runCurrent()
        assertFalse(hindi.unloaded)
        assertFalse(switching.isCompleted)
        finish.complete(Unit)
        assertEquals("0.2", decode.await().text)
        switching.join()
        assertTrue(hindi.unloaded)
        assertSame(english, session.currentSttEngine)
    }

    @Test fun obsoleteRecordingCannotDecodeUsingReplacementLanguage() = runTest {
        val hindi = Recognizer(LanguageCode.HINDI)
        val english = Recognizer(LanguageCode.ENGLISH)
        val session = ActiveLanguageSessionManager(factory { if (it == LanguageCode.HINDI) hindi else english })
        session.ensureStt(LanguageCode.HINDI, false)
        session.ensureStt(LanguageCode.ENGLISH, false)
        val failure = runCatching { session.recognizeCapture(hindi, listOf(floatArrayOf(.4f))) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("Mic language changed") == true)
        assertTrue(english.samples.isEmpty())
    }

    @Test fun capturesDoNotCarryAudioAcrossUtterances() = runTest {
        val engine = Recognizer(LanguageCode.HINDI)
        val session = ActiveLanguageSessionManager(factory { engine })
        session.ensureStt(LanguageCode.HINDI, false)
        assertEquals("0.1", session.recognizeCapture(engine, listOf(floatArrayOf(.1f))).text)
        assertEquals("0.5", session.recognizeCapture(engine, listOf(floatArrayOf(.5f))).text)
        assertTrue(engine.samples.isEmpty())
    }

    @Test fun changingVoiceWaitsForSynthesisAndLeavesTheMicEngineLoaded() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val stt = Recognizer(LanguageCode.HINDI)
        var oldVoiceReleased = false
        val session = ActiveLanguageSessionManager(object : EngineFactory {
            override fun createRecognizer(language: LanguageCode) = stt
            override fun createSynthesizer(language: LanguageCode) = object : SpeechSynthesizerEngine {
                override val languageCode = language
                override var isLoaded = false
                override suspend fun load() { isLoaded = true }
                override suspend fun unload() { isLoaded = false; if (language == LanguageCode.HINDI) oldVoiceReleased = true }
                override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechSynthesisResult {
                    assertEquals(language, request.languageCode)
                    entered.complete(Unit)
                    finish.await()
                    return SpeechSynthesisResult(request.correlationId, floatArrayOf(.2f), 16000, 1, 1)
                }
            }
        })
        session.ensureStt(LanguageCode.HINDI, false)
        val speaking = async { session.synthesizeTts(SpeechSynthesisRequest(LanguageCode.HINDI, "नमस्ते", "test")) }
        entered.await()
        val switching = launch { session.ensureTts(LanguageCode.ENGLISH) }
        runCurrent()
        assertFalse(oldVoiceReleased)
        assertFalse(switching.isCompleted)
        finish.complete(Unit)
        speaking.await()
        switching.join()
        assertTrue(oldVoiceReleased)
        assertTrue(stt.isLoaded)
        assertSame(stt, session.currentSttEngine)
    }

    @Test fun autoModeReloadsWhenConfiguredBaseLanguageChanges() = runTest {
        val session = ActiveLanguageSessionManager(factory { Recognizer(it) })
        session.ensureStt(LanguageCode.ENGLISH, true, LanguageCode.ENGLISH)
        val previous = session.currentSttEngine as Recognizer
        session.ensureStt(LanguageCode.HINDI, true, LanguageCode.ENGLISH)
        assertTrue(previous.unloaded)
        assertEquals(LanguageCode.HINDI, session.activeSttLanguage.value)
    }

    @Test fun microphoneFailureReachesBothConsumersAndCanBeRetried() = runTest {
        val fail = CompletableDeferred<Unit>()
        var starts = 0
        val shared = shareMicrophoneFrames(flow {
            if (++starts == 1) { fail.await(); throw SecurityException("microphone denied") }
            emit(floatArrayOf(.3f))
            awaitCancellation()
        }, backgroundScope)
        val one = async { runCatching { shared.first() }.exceptionOrNull() }
        val two = async { runCatching { shared.first() }.exceptionOrNull() }
        runCurrent()
        fail.complete(Unit)
        assertTrue(one.await() is SecurityException)
        assertTrue(two.await() is SecurityException)
        runCurrent()
        assertEquals(.3f, shared.first().single(), .0001f)
        assertEquals(2, starts)
    }

    @Test fun lastMicrophoneConsumerStoppingReleasesCapture() = runTest {
        var released = false
        val shared = shareMicrophoneFrames(flow {
            try { emit(floatArrayOf(.2f)); awaitCancellation() }
            finally { released = true }
        }, backgroundScope)
        shared.first()
        runCurrent()
        assertTrue(released)
    }

    @Test fun voiceRequiresFiniteAudibleMonoPcm() {
        fun audio(samples: FloatArray, rate: Int = 16000, channels: Int = 1) = SpeechSynthesisResult("test", samples, rate, channels, 1)
        for (invalid in listOf(audio(floatArrayOf()), audio(floatArrayOf(.2f), 0), audio(floatArrayOf(0f)),
            audio(floatArrayOf(Float.NaN)), audio(floatArrayOf(.2f), channels = 2))) {
            assertTrue(runCatching { requireSpeechAudio(invalid) }.isFailure)
        }
        requireSpeechAudio(audio(floatArrayOf(0f, .2f, -.2f)))
    }

    @Test fun drainWaitsForTheFinalFrames() = runTest {
        var head = 0L
        val drain = async { awaitPlaybackDrain(1600, 16000, { head }, { true }, { testScheduler.currentTime }) }
        runCurrent()
        advanceTimeBy(40)
        assertFalse(drain.isCompleted)
        head = 1600
        advanceUntilIdle()
        drain.await()
    }

    @Test fun cancellingDrainDoesNotReturnSuccessfulCompletion() = runTest {
        var completed = false
        val drain = launch { awaitPlaybackDrain(1600, 16000, { 0 }, { true }); completed = true }
        runCurrent()
        drain.cancelAndJoin()
        assertFalse(completed)
    }

    @Test fun stalledOrStoppedPlaybackIsAnError() = runTest {
        assertEquals("PLAYBACK_INTERRUPTED", runCatching {
            awaitPlaybackDrain(1600, 16000, { 0 }, { false })
        }.exceptionOrNull()?.message)
        assertEquals("PLAYBACK_DRAIN_TIMEOUT", runCatching {
            awaitPlaybackDrain(1600, 16000, { 0 }, { true }, { testScheduler.currentTime })
        }.exceptionOrNull()?.message)
    }
}
