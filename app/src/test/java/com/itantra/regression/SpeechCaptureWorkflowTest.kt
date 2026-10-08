package com.itantra.regression

import com.itantra.core.inference.SpeechRecognizerEngine
import com.itantra.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class SpeechCaptureWorkflowTest {
    private class Recognizer : SpeechRecognizerEngine {
        override val languageCode = LanguageCode.HINDI
        override var isLoaded = false
        val decoded = CopyOnWriteArrayList<List<Float>>()
        val buffer = mutableListOf<Float>()
        var decodeGate: CompletableDeferred<Unit>? = null
        var resultLanguage = LanguageCode.HINDI
        var transcript = "नमस्ते"
        override suspend fun load() { isLoaded = true }
        override suspend fun unload() { isLoaded = false }
        override suspend fun feed(samples: FloatArray) { buffer.add(samples.first()) }
        override suspend fun reset() { buffer.clear() }
        override suspend fun finalizeUtterance(): SpeechRecognitionResult {
            decodeGate?.await()
            decoded.add(buffer.toList())
            return SpeechRecognitionResult(resultLanguage, transcript, null, true, 0)
        }
    }

    @Test fun autoCaptureUsesDetectedLanguageWhenNoTranslationTargetWasChosen() = runBlocking {
        val engine = Recognizer().apply { resultLanguage = LanguageCode.GUJARATI; transcript = "નમસ્તે" }
        BugfixFixture(recognizerFactory = { engine }).use { f ->
            f.repository.setTargetLanguage(null)
            f.awaitCondition { (f.field("currentTargetLanguage") as StateFlow<*>).value == null }
            f.session.ensureStt(LanguageCode.HINDI, true)
            f.coordinator.processTestAudio(FloatArray(6400) { .2f })
            f.awaitCondition { f.coordinator.messages.value.any { it.text == "નમસ્તે" } }
            val message = f.coordinator.messages.value.single()
            assertEquals(LanguageCode.GUJARATI, message.language)
            assertEquals(LanguageCode.GUJARATI, message.targetLanguage)
            assertTrue(f.routes.isEmpty())
        }
    }

    @Test fun missingVoiceReportsFailureForEverySupportedLanguage() = runBlocking {
        BugfixFixture().use { f ->
            f.ttsInstalled = { false }
            f.verify()
            val inspected = mutableSetOf<Long>()
            val failures = mutableSetOf<Long>()
            for ((index, language) in LanguageCode.entries.withIndex()) {
                val id = 700L + index
                f.receive(com.itantra.core.transport.packet.ItantraPacket(
                    type = com.itantra.core.transport.packet.PacketType.TEXT, messageId = id,
                    languageCode = language, targetLanguage = language, payload = "text".toByteArray()))
                f.awaitCondition {
                    for (encrypted in f.sent) if (inspected.add(encrypted.counter)) {
                        val packet = f.remote.decrypt(encrypted)
                        if (packet.type == com.itantra.core.transport.packet.PacketType.TTS_FAILED) failures.add(packet.messageId)
                    }
                    id in failures
                }
                assertTrue(f.coordinator.messages.value.first { it.messageId == id }.statusDetail!!.contains("Voice unavailable"))
            }
        }
    }

    @Test fun microphoneErrorFinishesListeningAndNextCaptureWorks() = runBlocking {
        val engine = Recognizer()
        val source = MutableSharedFlow<FloatArray>()
        var denied = true
        val frames = flow { if (denied) throw SecurityException("Permission denied") else emitAll(source) }
        BugfixFixture(recognizerFactory = { engine }, microphoneFrames = frames).use { f ->
            f.session.ensureStt(LanguageCode.HINDI, false)
            f.coordinator.startRecording()
            f.awaitCondition { f.coordinator.messages.value.any { it.text == "Microphone unavailable" } }
            assertNull(f.field("recordingJob"))
            denied = false
            f.coordinator.startRecording()
            f.awaitCondition { source.subscriptionCount.value == 1 }
            source.emit(FloatArray(6400) { .2f })
            f.awaitCondition { (f.field("activeRecordingChunks") as List<*>).isNotEmpty() }
            f.coordinator.stopActiveRecording()
            f.awaitCondition { engine.decoded.size == 1 }
            assertEquals(listOf(.2f), engine.decoded.single())
            f.awaitCondition { f.coordinator.messages.value.any { it.originalText == "नमस्ते" } }
            assertTrue(f.coordinator.messages.value.any { it.text == "translated" && it.originalText == "नमस्ते" })
        }
    }

    @Test fun rapidCancelThenRecordNeverFeedsTheCancelledUtterance() = runBlocking {
        val engine = Recognizer()
        val source = MutableSharedFlow<FloatArray>()
        BugfixFixture(recognizerFactory = { engine }, microphoneFrames = source).use { f ->
            f.session.ensureStt(LanguageCode.HINDI, false)
            f.coordinator.startRecording()
            f.awaitCondition { source.subscriptionCount.value == 1 }
            source.emit(FloatArray(6400) { .1f })
            f.awaitCondition { (f.field("activeRecordingChunks") as List<*>).isNotEmpty() }
            f.coordinator.cancelActiveRecording()
            f.coordinator.startRecording()
            f.awaitCondition { source.subscriptionCount.value == 1 }
            source.emit(FloatArray(6400) { .5f })
            f.awaitCondition { (f.field("activeRecordingChunks") as List<*>).isNotEmpty() }
            f.coordinator.stopActiveRecording()
            f.awaitCondition { engine.decoded.size == 1 }
            assertEquals(listOf(.5f), engine.decoded.single())
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test fun newCaptureCanStartWhilePreviousDecodeFinishesWithoutMixing() = runBlocking {
        val engine = Recognizer().apply { decodeGate = CompletableDeferred() }
        val source = MutableSharedFlow<FloatArray>()
        BugfixFixture(recognizerFactory = { engine }, microphoneFrames = source).use { f ->
            f.session.ensureStt(LanguageCode.HINDI, false)
            f.coordinator.processTestAudio(FloatArray(6400) { .2f })
            f.awaitCondition { engine.buffer.isNotEmpty() }
            f.coordinator.startRecording()
            f.awaitCondition { source.subscriptionCount.value == 1 }
            source.emit(FloatArray(6400) { .7f })
            f.awaitCondition { (f.field("activeRecordingChunks") as List<*>).isNotEmpty() }
            f.coordinator.stopActiveRecording()
            engine.decodeGate!!.complete(Unit)
            f.awaitCondition { engine.decoded.size == 2 }
            assertEquals(listOf(listOf(.2f), listOf(.7f)), engine.decoded)
        }
    }

    @Test fun silentMicroTapLeavesNoErrorMessageAndDoesNotDecode() = runBlocking {
        val engine = Recognizer()
        BugfixFixture(recognizerFactory = { engine }).use { f ->
            f.session.ensureStt(LanguageCode.HINDI, false)
            f.coordinator.processTestAudio(FloatArray(800))
            assertTrue(f.coordinator.messages.value.isEmpty())
            assertTrue(engine.decoded.isEmpty())
            assertNull(f.field("recordingJob"))
            assertEquals(0L, f.field("activeRecordingMessageId"))
        }
    }

    @Test fun holdToTalkCannotCaptureReceivedSpeakerSpeech() = runBlocking {
        val engine = Recognizer()
        BugfixFixture(recognizerFactory = { engine }).use { f ->
            f.session.ensureStt(LanguageCode.HINDI, false)
            f.coordinator.javaClass.getDeclaredField("isTtsPlaying").apply { isAccessible = true }.setBoolean(f.coordinator, true)
            f.coordinator.startRecording()
            assertNull(f.field("recordingJob"))
            assertTrue(f.coordinator.messages.value.isEmpty())
        }
    }
}
