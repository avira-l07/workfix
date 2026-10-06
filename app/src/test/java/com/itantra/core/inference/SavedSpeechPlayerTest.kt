package com.itantra.core.inference

import com.itantra.domain.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SavedSpeechPlayerTest {
    private class Voices : EngineFactory {
        val requests = CopyOnWriteArrayList<SpeechSynthesisRequest>()
        val resident = AtomicInteger()
        val maximum = AtomicInteger()
        var loadGate: CompletableDeferred<Unit>? = null
        override fun createRecognizer(language: LanguageCode): SpeechRecognizerEngine? = null
        override fun createSynthesizer(language: LanguageCode) = object : SpeechSynthesizerEngine {
            override val languageCode = language
            override var isLoaded = false
            private var allocated = false
            override suspend fun load() {
                allocated = true
                val count = resident.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                loadGate?.await()
                isLoaded = true
            }
            override suspend fun unload() { if (allocated) resident.decrementAndGet(); allocated = false; isLoaded = false }
            override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechSynthesisResult {
                check(isLoaded && request.languageCode == languageCode)
                requests.add(request)
                return SpeechSynthesisResult(request.correlationId, floatArrayOf(.1f, -.2f), 16000, 1, 1)
            }
        }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(5) }

    @Test fun allTenLanguagesCanBePlayedTwiceWithOneResidentVoice() = runBlocking {
        val voices = Voices()
        val session = ActiveLanguageSessionManager(voices)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audio = CopyOnWriteArrayList<SpeechSynthesisResult>()
        val player = SavedSpeechPlayer(scope, session, { true }, { audio.add(it) }, {})
        try {
            LanguageCode.entries.forEach { language -> repeat(2) { number ->
                val key = "${language.wireCode}-$number"
                player.play(key, "test", language)
                await { player.state.value.itemKey == key && !player.state.value.busy }
                assertNull(player.state.value.error)
                assertEquals(language, voices.requests.last().languageCode)
            } }
            assertEquals(20, audio.size)
            assertEquals(20, voices.requests.size)
            assertEquals(1, voices.maximum.get())
        } finally { player.stop(); player.awaitStopped(); session.releaseAll(); scope.cancel() }
        assertEquals(0, voices.resident.get())
    }

    @Test fun missingPackAndUnknownLanguageNeverGenerateOrPlaySpeech() = runBlocking {
        val voices = Voices()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val player = SavedSpeechPlayer(scope, ActiveLanguageSessionManager(voices), { false }, { fail("No playback expected") }, {})
        try {
            player.play("note", "text", LanguageCode.ODIA)
            assertTrue(player.state.value.error!!.contains("Receive (TTS)"))
            player.play("note", "text", null)
            assertEquals("Text language is unknown", player.state.value.error)
            assertTrue(voices.requests.isEmpty())
            assertEquals(0, voices.resident.get())
        } finally { player.stop(); scope.cancel() }
    }

    @Test fun stopCancelsPlaybackAndTheSameContentCanBePlayedAgain() = runBlocking {
        val voices = Voices()
        val session = ActiveLanguageSessionManager(voices)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val completed = AtomicInteger()
        var hold = true
        val player = SavedSpeechPlayer(scope, session, { true }, {
            if (hold) { started.complete(Unit); awaitCancellation() }
            completed.incrementAndGet()
        }, {})
        try {
            player.play("message", "same text", LanguageCode.TAMIL)
            withTimeout(10_000) { started.await() }
            player.stop(); player.awaitStopped()
            assertFalse(player.state.value.busy)
            assertEquals(0, completed.get())
            hold = false
            player.play("message", "same text", LanguageCode.TAMIL)
            await { completed.get() == 1 && !player.state.value.busy }
            assertEquals(2, voices.requests.size)
            assertNull(player.state.value.error)
        } finally { player.stop(); player.awaitStopped(); session.releaseAll(); scope.cancel() }
    }

    @Test fun stoppingDuringModelLoadReleasesThePartiallyAllocatedVoice() = runBlocking {
        val voices = Voices().apply { loadGate = CompletableDeferred() }
        val session = ActiveLanguageSessionManager(voices)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val player = SavedSpeechPlayer(scope, session, { true }, { fail("No audio expected") }, {})
        try {
            player.play("note", "text", LanguageCode.MARATHI)
            await { voices.resident.get() == 1 }
            player.stop(); player.awaitStopped()
            assertEquals(0, voices.resident.get())
            assertTrue(voices.requests.isEmpty())
            assertFalse(player.state.value.busy)
        } finally { player.stop(); session.releaseAll(); scope.cancel() }
    }

    @Test fun replayUsesTheLanguageOfDisplayedTextIncludingTranslatedAndReceivedMessages() {
        val original = TransceiverMessage(1, LanguageCode.HINDI, LanguageCode.TAMIL, MessagePriority.NORMAL,
            "translated", "original", TranslationStatus.SUCCESS, MessageSource.LOCAL, 1, MessageState.SENT)
        assertEquals(LanguageCode.TAMIL, original.displayedTextLanguage)
        assertEquals(LanguageCode.HINDI, original.copy(translationStatus = TranslationStatus.FAILED).displayedTextLanguage)
        assertEquals(LanguageCode.HINDI, original.copy(source = MessageSource.REMOTE).displayedTextLanguage)
        assertEquals(LanguageCode.TAMIL, original.copy(deletedAtMillis = 2).displayedTextLanguage)
    }
}
