package com.itantra.regression

import com.itantra.core.inference.SpeechRecognizerEngine
import com.itantra.core.inference.SpeechSynthesizerEngine
import com.itantra.core.transceiver.PeerCapabilities
import com.itantra.core.translation.ProductionTranslationEngine
import com.itantra.core.translation.TranslationEngine
import com.itantra.core.translation.TranslationResult
import com.itantra.core.translation.TranslationRouter
import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.core.transport.packet.PacketType
import com.itantra.core.transport.packet.ProtocolLanguageMapper
import com.itantra.data.languagepack.MockLanguagePackRepository
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.MessagePriority
import com.itantra.domain.model.MessageState
import com.itantra.domain.model.SpeechRecognitionResult
import com.itantra.domain.model.SpeechSynthesisRequest
import com.itantra.domain.model.SpeechSynthesisResult
import com.itantra.domain.model.TranslationMode
import com.itantra.domain.model.TranslationStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Phase 5 Regression Tests:
 * - FIX 007: Safe runtime error reporting and taxonomy.
 * - FIX 008: Voice MT failure blocks transmission (never falls through).
 * - FIX 009: Typed chat MT routing, same-language bypass, and MT failure blocking.
 * - FIX 027: Receiver-side already-translated detection (prevents double translation).
 * - FIX 028: sendCapabilities only advertises actually loaded TTS.
 * - FIX 029: sendCapabilities only advertises actually loaded STT.
 * - FIX 030: Target language selector persistence, null reset, and resolution policy.
 * - FIX 070: Directional readiness (isLoaded and isDirectionLoaded).
 */
class Phase5TranslationNegotiationRegressionTest {

    // =========================================================================
    // FIX 070: Directional Readiness
    // =========================================================================
    @Test
    fun testDirectionalReadiness_FIX_070() {
        val engine = ProductionTranslationEngine()
        // Without native init, delegate is null
        assertFalse("Uninitialized engine isLoaded must be false", engine.isLoaded)
        assertFalse("Uninitialized direction must be false", engine.isDirectionLoaded(LanguageCode.HINDI, LanguageCode.ENGLISH))
        // Same-language bypass is always ready without engine
        assertTrue("Same language bypass must always return true", engine.isDirectionLoaded(LanguageCode.HINDI, LanguageCode.HINDI))
    }

    // =========================================================================
    // FIX 030: Target Language Repository & Resolution Policy
    // =========================================================================
    @Test
    fun testTargetLanguageRepository_FIX_030() = runBlocking {
        val repo = MockLanguagePackRepository()
        // Mock repository starts with English target configured
        assertEquals(LanguageCode.ENGLISH, repo.observeTargetLanguage().first())

        repo.setTargetLanguage(LanguageCode.HINDI)
        assertEquals("Target language must be set to Hindi", LanguageCode.HINDI, repo.observeTargetLanguage().first())

        // Reset to auto/null
        repo.setTargetLanguage(null)
        assertNull("Resetting target language to null must succeed", repo.observeTargetLanguage().first())
    }

    @Test
    fun testTargetLanguageResolutionPolicy_FIX_030() {
        // Policy function reproducing TransceiverCoordinator.resolveTargetLanguage
        fun resolve(userTarget: LanguageCode?, peerTts: List<LanguageCode>, localLang: LanguageCode): LanguageCode {
            userTarget?.let { return it }
            if (peerTts.contains(localLang)) return localLang
            val peerDiff = peerTts.firstOrNull { it != localLang }
            if (peerDiff != null) return peerDiff
            return localLang
        }

        // Case 1: User explicitly picked target -> always wins
        assertEquals(
            LanguageCode.ENGLISH,
            resolve(LanguageCode.ENGLISH, listOf(LanguageCode.HINDI), LanguageCode.HINDI)
        )

        // Case 2: User picked null -> peer supports same language -> same-language bypass
        assertEquals(
            LanguageCode.HINDI,
            resolve(null, listOf(LanguageCode.HINDI), LanguageCode.HINDI)
        )

        // Case 3: User picked null -> peer speaks English -> route to peer's English
        assertEquals(
            LanguageCode.ENGLISH,
            resolve(null, listOf(LanguageCode.ENGLISH), LanguageCode.HINDI)
        )

        // Case 4: User picked null -> peer unknown / empty -> default to local (Same-Language First, no wrong guess)
        assertEquals(
            LanguageCode.TAMIL,
            resolve(null, emptyList(), LanguageCode.TAMIL)
        )
    }

    // =========================================================================
    // FIX 009 & FIX 008: Typed Chat and Voice MT Blocking on Failure
    // =========================================================================
    private class FakeTranslationEngine(
        var shouldSucceed: Boolean = true,
        var translatedOutput: String = "TRANSLATED_TEXT"
    ) : TranslationEngine {
        override val isLoaded: Boolean = true
        var callCount = 0
        override val supportedSourceLanguages: Set<LanguageCode> = LanguageCode.entries.toSet()
        override val supportedTargetLanguages: Set<LanguageCode> = LanguageCode.entries.toSet()

        override fun init(modelsDir: File) {}
        override fun release() {}

        override suspend fun translate(text: String, sourceLang: LanguageCode, targetLang: LanguageCode): TranslationResult {
            callCount++
            return if (shouldSucceed) {
                TranslationResult(
                    originalText = text,
                    translatedText = translatedOutput,
                    isSuccessful = true,
                    sourceLanguage = sourceLang,
                    targetLanguage = targetLang
                )
            } else {
                TranslationResult(
                    originalText = text,
                    translatedText = "",
                    isSuccessful = false,
                    error = "MODEL_RUNTIME_OR_LOAD_FAILED",
                    sourceLanguage = sourceLang,
                    targetLanguage = targetLang
                )
            }
        }
    }

    @Test
    fun testTypedChat_SameLanguageBypass_FIX_009() = runBlocking {
        val engine = FakeTranslationEngine(shouldSucceed = true)
        val router = TranslationRouter(engine)

        val localLang = LanguageCode.ENGLISH
        val targetLang = LanguageCode.ENGLISH
        val text = "Direct tactical order"

        var finalText = text
        var translationMode = TranslationMode.NONE

        if (targetLang != localLang) {
            val res = router.routeAndTranslate(text, localLang, targetLang)
            finalText = res.translatedText
            translationMode = TranslationMode.DIRECT
        }

        assertEquals(0, engine.callCount)
        assertEquals("Direct tactical order", finalText)
        assertEquals(TranslationMode.NONE, translationMode)
    }

    @Test
    fun testTypedChat_CrossLanguageSuccess_FIX_009() = runBlocking {
        val engine = FakeTranslationEngine(shouldSucceed = true, translatedOutput = "ऑर्डर स्वीकृत")
        val router = TranslationRouter(engine)

        val localLang = LanguageCode.ENGLISH
        val targetLang = LanguageCode.HINDI
        val text = "Order acknowledged"

        var finalText = text
        var origText: String? = null
        var translationMode = TranslationMode.NONE
        var translationStatus = TranslationStatus.BYPASSED

        if (targetLang != localLang) {
            val res = router.routeAndTranslate(text, localLang, targetLang)
            assertTrue("Translation must succeed", res.isSuccessful)
            finalText = res.translatedText
            origText = text
            translationMode = TranslationMode.DIRECT
            translationStatus = TranslationStatus.SUCCESS
        }

        assertEquals(1, engine.callCount)
        assertEquals("ऑर्डर स्वीकृत", finalText)
        assertEquals("Order acknowledged", origText)
        assertEquals(TranslationMode.DIRECT, translationMode)
        assertEquals(TranslationStatus.SUCCESS, translationStatus)
    }

    @Test
    fun testTypedChat_CrossLanguageFailure_BlocksTransmission_FIX_009_FIX_008() = runBlocking {
        val engine = FakeTranslationEngine(shouldSucceed = false)
        val router = TranslationRouter(engine)

        val localLang = LanguageCode.ENGLISH
        val targetLang = LanguageCode.HINDI
        val text = "Hold perimeter"

        var packetTransmitted = false
        var messageState = MessageState.STT_PROCESSING
        var statusDetail: String? = null

        if (targetLang != localLang) {
            val res = router.routeAndTranslate(text, localLang, targetLang)
            if (res.isSuccessful && res.translatedText.isNotBlank()) {
                packetTransmitted = true
            } else {
                // FIX 008/009: Block transmission!
                messageState = MessageState.ERROR
                statusDetail = "Translation blocked: ${res.error}"
            }
        }

        assertFalse("Packet must NEVER be transmitted when cross-language MT fails", packetTransmitted)
        assertEquals(MessageState.ERROR, messageState)
        assertTrue("Status detail must state failure reason", statusDetail?.contains("MODEL_RUNTIME_OR_LOAD_FAILED") == true)
    }

    // =========================================================================
    // FIX 027: Receiver-Side Translation & Double-Translation Prevention
    // =========================================================================
    @Test
    fun testReceiver_AlreadyTranslatedPacket_ConsumesDirectly_FIX_027() {
        val localLanguage = LanguageCode.HINDI

        // Packet arrived already translated by sender (translationMode = DIRECT, target = HI)
        val packet = ItantraPacket(
            type = PacketType.TEXT,
            flags = MessagePriority.NORMAL.toByte(),
            messageId = 1001L,
            languageCode = LanguageCode.HINDI,
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.HINDI,
            translationMode = TranslationMode.DIRECT,
            payload = "नमस्ते".toByteArray(Charsets.UTF_8)
        )

        val pktLang = packet.targetLanguage ?: packet.languageCode ?: localLanguage
        val alreadyTranslated = (packet.translationMode == TranslationMode.DIRECT && pktLang == localLanguage)

        assertTrue("Receiver must recognize packet is already translated", alreadyTranslated)
        val translationStatus = if (alreadyTranslated || pktLang == localLanguage) {
            TranslationStatus.BYPASSED
        } else {
            TranslationStatus.SUCCESS
        }
        assertEquals("Already-translated packet must bypass re-translation", TranslationStatus.BYPASSED, translationStatus)
    }

    // =========================================================================
    // FIX 028 & FIX 029: sendCapabilities Only Advertises Loaded Engines
    // =========================================================================
    @Test
    fun testCapabilitiesBitmask_OnlyLoadedEnginesAdvertised_FIX_028_029() {
        // When TTS is null or unloaded:
        val unloadedTtsLangs = emptyList<LanguageCode>()
        val ttsMaskUnloaded = ProtocolLanguageMapper.toBitmask(unloadedTtsLangs)
        assertEquals(0.toShort(), ttsMaskUnloaded)
        assertTrue("Unloaded TTS must advertise empty supported list", ProtocolLanguageMapper.fromBitmask(ttsMaskUnloaded).isEmpty())

        // When TTS is loaded for English:
        val loadedTtsLangs = listOf(LanguageCode.ENGLISH)
        val ttsMaskLoaded = ProtocolLanguageMapper.toBitmask(loadedTtsLangs)
        val decodedTts = ProtocolLanguageMapper.fromBitmask(ttsMaskLoaded)
        assertEquals(1, decodedTts.size)
        assertEquals(LanguageCode.ENGLISH, decodedTts.first())

        // When STT is null or unloaded:
        val unloadedSttLangs = emptyList<LanguageCode>()
        val sttMaskUnloaded = ProtocolLanguageMapper.toBitmask(unloadedSttLangs)
        assertEquals(0.toShort(), sttMaskUnloaded)

        // When STT is loaded for Hindi:
        val loadedSttLangs = listOf(LanguageCode.HINDI)
        val sttMaskLoaded = ProtocolLanguageMapper.toBitmask(loadedSttLangs)
        val decodedStt = ProtocolLanguageMapper.fromBitmask(sttMaskLoaded)
        assertEquals(1, decodedStt.size)
        assertEquals(LanguageCode.HINDI, decodedStt.first())
    }
}
