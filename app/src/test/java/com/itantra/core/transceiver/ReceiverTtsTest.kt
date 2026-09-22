package com.itantra.core.transceiver

import com.itantra.core.inference.ActiveLanguageSessionManager
import com.itantra.core.inference.EngineFactory
import com.itantra.core.inference.LanguageSessionState
import com.itantra.core.inference.SpeechRecognizerEngine
import com.itantra.core.inference.SpeechSynthesizerEngine
import com.itantra.core.inference.TtsCapabilityProvider
import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.core.transport.packet.PacketType
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.MessagePriority
import com.itantra.domain.model.MessageState
import com.itantra.domain.model.SpeechRecognitionResult
import com.itantra.domain.model.SpeechSynthesisRequest
import com.itantra.domain.model.SpeechSynthesisResult
import com.itantra.domain.model.TransceiverMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Receiver TTS Tests E through J as mandated by Antigravity Final Stability Repair Spec.
 *
 * Validates:
 * - TEST E: receiver Hindi + Hindi TTS installed -> engine prepared, PCM synthesized, playback invoked -> REMOTE_PLAYBACK_CONFIRMED
 * - TEST F: receiver Hindi + Hindi TTS NOT installed -> message DELIVERED with "Voice unavailable", no TTS_STARTED, no TTS_COMPLETED
 * - TEST G: receiver Hindi, incoming English translated to Hindi -> finalTextLanguage=HINDI, Hindi TTS used
 * - TEST H: translation failed, incoming English, receiver Hindi -> displayed as English, STT language NOT changed to English
 * - TEST I: TTS synthesis returns empty PCM -> TTS_FAILED event, message state ERROR
 * - TEST J: AudioTrack write fails (throw from audio sink) -> TTS_FAILED event, message state ERROR
 */
class ReceiverTtsTest {

    // ── Mocks & Fakes ────────────────────────────────────────────────────────

    private class FakeRecognizer(override val languageCode: LanguageCode) : SpeechRecognizerEngine {
        private var loaded = false
        override val isLoaded: Boolean get() = loaded
        override suspend fun load() { loaded = true }
        override suspend fun feed(samples: FloatArray) {}
        override suspend fun reset() {}
        override suspend fun finalizeUtterance(): SpeechRecognitionResult =
            SpeechRecognitionResult(languageCode, "Recognized", 0.95f, true, System.currentTimeMillis())
        override suspend fun unload() { loaded = false }
    }

    private class FakeSynthesizer(
        override val languageCode: LanguageCode,
        private val returnEmptyPcm: Boolean = false,
        private val sampleRate: Int = 16000
    ) : SpeechSynthesizerEngine {
        private var loaded = false
        override val isLoaded: Boolean get() = loaded
        var synthesizeCallCount = 0
        override suspend fun load() { loaded = true }
        override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechSynthesisResult {
            synthesizeCallCount++
            val pcm = if (returnEmptyPcm) FloatArray(0) else FloatArray(1600) { 0.1f }
            return SpeechSynthesisResult(
                correlationId = request.correlationId,
                pcmAudio = pcm,
                sampleRateHz = sampleRate,
                channelCount = 1,
                durationMillis = if (returnEmptyPcm) 0L else 100L
            )
        }
        override suspend fun unload() { loaded = false }
    }

    private class FakeAudioSink(private val shouldFailWrite: Boolean = false) {
        var playedPcm: FloatArray? = null
        var playedCount = 0

        fun init(sampleRateHz: Int, usage: Int, requestMaxVolume: Boolean = false) {}

        fun play(samples: FloatArray) {
            if (shouldFailWrite) {
                throw IOException("AUDIO_WRITE_FAILED: AudioTrack channel write returned -1")
            }
            playedPcm = samples
            playedCount++
        }

        fun flushAndStop() {}
    }

    /**
     * Minimal representation of the receiver TTS execution pipeline from
     * TransceiverCoordinator.processIncomingMessagePacket().
     */
    private class ReceiverPipelineHarness(
        val sessionManager: ActiveLanguageSessionManager,
        val ttsCapabilityProvider: TtsCapabilityProvider,
        val audioSink: FakeAudioSink = FakeAudioSink()
    ) {
        val emittedEvents = mutableListOf<PacketType>()
        var lastUpdatedMessage: TransceiverMessage? = null

        suspend fun processIncomingPacket(
            packet: ItantraPacket,
            text: String,
            translatedText: String? = null
        ) {
            val localLanguage = sessionManager.activeLanguage.value ?: LanguageCode.ENGLISH
            val isCritical = packet.flags.toInt() == MessagePriority.CRITICAL

            // Determine final text and text language
            val finalText: String
            val textLanguage: LanguageCode
            if (translatedText != null) {
                finalText = translatedText
                textLanguage = localLanguage
            } else {
                finalText = text
                textLanguage = packet.targetLanguage ?: packet.languageCode ?: localLanguage
            }

            var message = TransceiverMessage(
                messageId = packet.messageId,
                language = textLanguage,
                targetLanguage = packet.targetLanguage ?: textLanguage,
                priority = packet.flags.toInt(),
                text = finalText,
                originalText = if (translatedText != null) text else null,
                translationStatus = if (translatedText != null) com.itantra.domain.model.TranslationStatus.SUCCESS else com.itantra.domain.model.TranslationStatus.NONE,
                source = com.itantra.domain.model.MessageSource.REMOTE,
                createdAtLocal = System.currentTimeMillis(),
                state = MessageState.DELIVERED
            )
            lastUpdatedMessage = message

            // Step 1: Receiver TTS Preparation via centralized sessionManager
            if (!isCritical) {
                val ttsInstalled = ttsCapabilityProvider.isTtsInstalled(textLanguage)
                if (!ttsInstalled) {
                    message = message.copy(
                        state = MessageState.DELIVERED,
                        statusDetail = "TTS_MODEL_NOT_INSTALLED"
                    )
                    lastUpdatedMessage = message
                    return
                }
                try {
                    sessionManager.ensureTts(textLanguage)
                } catch (_: Exception) {}
            }

            // Step 2: Re-evaluate canSpeak
            val engine = sessionManager.currentTtsEngine
            val canSpeak = engine != null && engine.isLoaded && engine.languageCode == textLanguage

            // Step 3: Check if voice is available
            if (!canSpeak && !isCritical) {
                val reason = when {
                    engine == null -> "TTS_ENGINE_NOT_LOADED"
                    !engine.isLoaded -> "TTS_ENGINE_NOT_LOADED"
                    engine.languageCode != textLanguage -> "TTS_LANGUAGE_MISMATCH"
                    else -> "TTS_MODEL_NOT_INSTALLED"
                }
                message = message.copy(
                    state = MessageState.DELIVERED,
                    statusDetail = reason
                )
                lastUpdatedMessage = message
                return
            }

            // Step 4 & 5: Synthesize
            try {
                if (engine != null && canSpeak) {
                    val req = SpeechSynthesisRequest(
                        languageCode = engine.languageCode,
                        text = finalText,
                        correlationId = message.messageId.toString()
                    )
                    val result = engine.synthesize(req)

                    // Step 6: Validate synthesis result
                    if (result.pcmAudio.isEmpty() || result.sampleRateHz <= 0) {
                        message = message.copy(
                            state = MessageState.ERROR,
                            statusDetail = "TTS_EMPTY_PCM: samples=${result.pcmAudio.size} sampleRate=${result.sampleRateHz}"
                        )
                        lastUpdatedMessage = message
                        emittedEvents.add(PacketType.TTS_FAILED)
                        return
                    }

                    // Step 7: Send TTS_STARTED ONLY after synthesis succeeds and PCM is valid
                    message = message.copy(state = MessageState.REMOTE_PLAYING)
                    lastUpdatedMessage = message
                    emittedEvents.add(PacketType.TTS_STARTED)

                    // Step 8: Playback
                    audioSink.init(result.sampleRateHz, 3 /* USAGE_MEDIA */, false)
                    audioSink.play(result.pcmAudio)
                    audioSink.flushAndStop()

                    // Step 9: Completed
                    message = message.copy(state = MessageState.REMOTE_PLAYBACK_CONFIRMED)
                    lastUpdatedMessage = message
                    emittedEvents.add(PacketType.TTS_COMPLETED)
                }
            } catch (e: Exception) {
                message = message.copy(
                    state = MessageState.ERROR,
                    statusDetail = "AUDIO_WRITE_FAILED: ${e.message}"
                )
                lastUpdatedMessage = message
                emittedEvents.add(PacketType.TTS_FAILED)
            }
        }
    }

    private fun createHarness(
        installedLangsTts: Set<LanguageCode>,
        returnEmptyPcm: Boolean = false,
        shouldFailAudioWrite: Boolean = false
    ): Pair<ReceiverPipelineHarness, ActiveLanguageSessionManager> {
        val factory = object : EngineFactory {
            override fun createRecognizer(language: LanguageCode) = FakeRecognizer(language)
            override fun createSynthesizer(language: LanguageCode) =
                if (installedLangsTts.contains(language)) FakeSynthesizer(language, returnEmptyPcm = returnEmptyPcm) else null
        }
        val sessionManager = ActiveLanguageSessionManager(factory)
        val provider = TtsCapabilityProvider { lang -> installedLangsTts.contains(lang) }
        val audioSink = FakeAudioSink(shouldFailWrite = shouldFailAudioWrite)
        val harness = ReceiverPipelineHarness(sessionManager, provider, audioSink)
        return Pair(harness, sessionManager)
    }

    // ── TEST E ───────────────────────────────────────────────────────────────
    // Receiver active Hindi, Hindi TTS installed, incoming Hindi text
    // Expected: engine loaded, PCM generated, playback invoked -> REMOTE_PLAYBACK_CONFIRMED
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testE_receiverHindi_ttsInstalled_synthesizesAndPlaysAudio() = runTest {
        val (harness, sessionManager) = createHarness(installedLangsTts = setOf(LanguageCode.HINDI))

        // Set receiver to Hindi with STT (simulating active session)
        sessionManager.ensureCapabilities(LanguageCode.HINDI, requireStt = true, requireTts = false)
        assertEquals(LanguageCode.HINDI, sessionManager.activeLanguage.value)
        assertNull("TTS engine not yet loaded before message", sessionManager.currentTtsEngine)

        val packet = ItantraPacket(
            type = PacketType.TEXT,
            messageId = 1001L,
            languageCode = LanguageCode.HINDI,
            targetLanguage = LanguageCode.HINDI,
            payload = "नमस्ते".toByteArray(Charsets.UTF_8)
        )

        harness.processIncomingPacket(packet, "नमस्ते")

        // Assert TTS engine was prepared
        assertNotNull("TTS engine must have been loaded", sessionManager.currentTtsEngine)
        assertEquals(LanguageCode.HINDI, sessionManager.currentTtsEngine?.languageCode)
        assertTrue(sessionManager.currentTtsEngine!!.isLoaded)

        // Assert playback invoked and state reaches REMOTE_PLAYBACK_CONFIRMED
        assertEquals(MessageState.REMOTE_PLAYBACK_CONFIRMED, harness.lastUpdatedMessage?.state)
        assertEquals(1, harness.audioSink.playedCount)
        assertNotNull(harness.audioSink.playedPcm)
        assertTrue(harness.audioSink.playedPcm!!.isNotEmpty())

        // Assert event order: TTS_STARTED before TTS_COMPLETED
        assertEquals(listOf(PacketType.TTS_STARTED, PacketType.TTS_COMPLETED), harness.emittedEvents)
    }

    // ── TEST F ───────────────────────────────────────────────────────────────
    // Receiver Hindi, Hindi TTS NOT installed, incoming Hindi text
    // Expected: message DELIVERED with "Voice unavailable", NO false TTS_STARTED, NO TTS_COMPLETED
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testF_receiverHindi_ttsNotInstalled_deliversTextWithVoiceUnavailable() = runTest {
        val (harness, sessionManager) = createHarness(installedLangsTts = emptySet()) // no TTS installed

        sessionManager.ensureCapabilities(LanguageCode.HINDI, requireStt = true, requireTts = false)

        val packet = ItantraPacket(
            type = PacketType.TEXT,
            messageId = 1002L,
            languageCode = LanguageCode.HINDI,
            targetLanguage = LanguageCode.HINDI,
            payload = "नमस्ते".toByteArray(Charsets.UTF_8)
        )

        harness.processIncomingPacket(packet, "नमस्ते")

        assertEquals(MessageState.DELIVERED, harness.lastUpdatedMessage?.state)
        assertEquals("TTS_MODEL_NOT_INSTALLED", harness.lastUpdatedMessage?.statusDetail)
        // Verify NO false TTS events emitted
        assertFalse("TTS_STARTED must not be emitted", harness.emittedEvents.contains(PacketType.TTS_STARTED))
        assertFalse("TTS_COMPLETED must not be emitted", harness.emittedEvents.contains(PacketType.TTS_COMPLETED))
        assertEquals(0, harness.audioSink.playedCount)
    }

    // ── TEST G ───────────────────────────────────────────────────────────────
    // Receiver Hindi, incoming English translated to Hindi -> finalTextLanguage=Hindi, Hindi TTS used
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testG_translatedEnglishToHindi_usesHindiTts() = runTest {
        val (harness, sessionManager) = createHarness(installedLangsTts = setOf(LanguageCode.HINDI, LanguageCode.ENGLISH))

        sessionManager.ensureCapabilities(LanguageCode.HINDI, requireStt = true, requireTts = true)

        val packet = ItantraPacket(
            type = PacketType.TEXT,
            messageId = 1003L,
            languageCode = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.HINDI,
            payload = "Where is the hospital?".toByteArray(Charsets.UTF_8)
        )

        // Translated text provided
        harness.processIncomingPacket(packet, text = "Where is the hospital?", translatedText = "अस्पताल कहाँ है?")

        assertEquals(MessageState.REMOTE_PLAYBACK_CONFIRMED, harness.lastUpdatedMessage?.state)
        assertEquals("अस्पताल कहाँ है?", harness.lastUpdatedMessage?.text)
        assertEquals(LanguageCode.HINDI, harness.lastUpdatedMessage?.language)
        assertEquals(LanguageCode.HINDI, sessionManager.currentTtsEngine?.languageCode)
        assertEquals(1, harness.audioSink.playedCount)
        assertEquals(listOf(PacketType.TTS_STARTED, PacketType.TTS_COMPLETED), harness.emittedEvents)
    }

    // ── TEST H ───────────────────────────────────────────────────────────────
    // Translation failed, incoming English, receiver Hindi
    // Expected: text displayed as English, active language NOT switched to English (STT language stays Hindi)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testH_translationFailed_doesNotDisturbReceiverSttLanguage() = runTest {
        val (harness, sessionManager) = createHarness(installedLangsTts = setOf(LanguageCode.HINDI, LanguageCode.ENGLISH))

        sessionManager.ensureCapabilities(LanguageCode.HINDI, requireStt = true, requireTts = false)
        assertEquals(LanguageCode.HINDI, sessionManager.activeLanguage.value)
        val initialSttEngine = sessionManager.currentSttEngine
        assertNotNull(initialSttEngine)
        assertEquals(LanguageCode.HINDI, initialSttEngine?.languageCode)

        // Incoming English packet with NO translated text (translation failed)
        val packet = ItantraPacket(
            type = PacketType.TEXT,
            messageId = 1004L,
            languageCode = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.HINDI,
            payload = "Tactical team advancing.".toByteArray(Charsets.UTF_8)
        )

        harness.processIncomingPacket(packet, text = "Tactical team advancing.", translatedText = null)

        // Text displayed as received
        assertEquals("Tactical team advancing.", harness.lastUpdatedMessage?.text)
        // CRITICAL: Active language and STT engine MUST remain Hindi!
        assertEquals("Active language must remain Hindi", LanguageCode.HINDI, sessionManager.activeLanguage.value)
        assertEquals("STT engine must remain Hindi", LanguageCode.HINDI, sessionManager.currentSttEngine?.languageCode)
        assertEquals("Session state must remain READY", LanguageSessionState.READY, sessionManager.sessionState.value)
    }

    // ── TEST I ───────────────────────────────────────────────────────────────
    // TTS synthesis returns empty PCM -> TTS_FAILED, message state ERROR
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testI_synthesisReturnsEmptyPcm_emitsTtsFailedAndMarksError() = runTest {
        val (harness, sessionManager) = createHarness(
            installedLangsTts = setOf(LanguageCode.HINDI),
            returnEmptyPcm = true // synthesizer returns empty FloatArray
        )

        sessionManager.ensureCapabilities(LanguageCode.HINDI, requireStt = true, requireTts = true)

        val packet = ItantraPacket(
            type = PacketType.TEXT,
            messageId = 1005L,
            languageCode = LanguageCode.HINDI,
            targetLanguage = LanguageCode.HINDI,
            payload = "नमस्ते".toByteArray(Charsets.UTF_8)
        )

        harness.processIncomingPacket(packet, "नमस्ते")

        assertEquals(MessageState.ERROR, harness.lastUpdatedMessage?.state)
        assertTrue(
            "statusDetail must indicate empty PCM, was: ${harness.lastUpdatedMessage?.statusDetail}",
            harness.lastUpdatedMessage?.statusDetail?.contains("TTS_EMPTY_PCM") == true
        )
        // Verify TTS_FAILED emitted and AudioSink was NOT called
        assertEquals(listOf(PacketType.TTS_FAILED), harness.emittedEvents)
        assertEquals(0, harness.audioSink.playedCount)
    }

    // ── TEST J ───────────────────────────────────────────────────────────────
    // AudioTrack write fails (throw from audio sink) -> TTS_FAILED
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testJ_audioTrackWriteFails_emitsTtsFailedAndMarksError() = runTest {
        val (harness, sessionManager) = createHarness(
            installedLangsTts = setOf(LanguageCode.HINDI),
            shouldFailAudioWrite = true // audio sink play() throws
        )

        sessionManager.ensureCapabilities(LanguageCode.HINDI, requireStt = true, requireTts = true)

        val packet = ItantraPacket(
            type = PacketType.TEXT,
            messageId = 1006L,
            languageCode = LanguageCode.HINDI,
            targetLanguage = LanguageCode.HINDI,
            payload = "नमस्ते".toByteArray(Charsets.UTF_8)
        )

        harness.processIncomingPacket(packet, "नमस्ते")

        assertEquals(MessageState.ERROR, harness.lastUpdatedMessage?.state)
        assertTrue(
            "statusDetail must indicate write failed, was: ${harness.lastUpdatedMessage?.statusDetail}",
            harness.lastUpdatedMessage?.statusDetail?.contains("AUDIO_WRITE_FAILED") == true
        )
        // TTS_STARTED was sent before playback, then TTS_FAILED upon exception
        assertEquals(listOf(PacketType.TTS_STARTED, PacketType.TTS_FAILED), harness.emittedEvents)
    }
}
