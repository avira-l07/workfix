package com.itantra.core.transceiver

import android.content.ContextWrapper
import com.itantra.core.crypto.SecureSessionManager
import com.itantra.core.crypto.SecureSessionState
import com.itantra.core.inference.ActiveLanguageSessionManager
import com.itantra.core.inference.EngineFactory
import com.itantra.core.inference.SherpaOnnxSpeechRecognizer
import com.itantra.core.inference.SpeechRecognizerEngine
import com.itantra.core.inference.SpeechSynthesizerEngine
import com.itantra.core.metrics.InMemoryMetricsRecorder
import com.itantra.core.storage.LanguagePackStorage
import com.itantra.core.translation.TranslationEngine
import com.itantra.core.translation.TranslationResult
import com.itantra.core.translation.TranslationRouter
import com.itantra.core.transport.ConnectionState
import com.itantra.core.transport.TransportEngine
import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.core.transport.packet.PacketType
import com.itantra.data.languagepack.MockLanguagePackRepository
import com.itantra.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Day 3 Unit Tests: Generalization of STT/MT pipeline across all 10 languages,
 * Option B graceful degradation for Malayalam & Odia, and regression protection
 * for the Hindi <-> English baseline.
 */
class PipelineGeneralizationTest {

    private class DummyStorage : LanguagePackStorage {
        override fun packDirectory(code: LanguageCode): File = File("dummy/${code.wireCode}")
        override fun isInstalled(code: LanguageCode): Boolean = true
        override suspend fun verifyChecksums(code: LanguageCode, expectedChecksums: Map<String, String>): Boolean = true
        override suspend fun deletePack(code: LanguageCode) {}
        override fun totalInstalledBytes(): Long = 0L
    }

    private class DummyRecognizer(override val languageCode: LanguageCode) : SpeechRecognizerEngine {
        override var isLoaded: Boolean = true
        override suspend fun load() { isLoaded = true }
        override suspend fun feed(samples: FloatArray) {}
        override suspend fun finalizeUtterance(): SpeechRecognitionResult =
            SpeechRecognitionResult(
                languageCode = languageCode,
                text = "Dummy",
                confidence = 1.0f,
                isFinal = true,
                timestampMillis = System.currentTimeMillis(),
                pureInferenceMs = 10L
            )
        override suspend fun reset() {}
        override suspend fun unload() { isLoaded = false }
    }

    private class ConfigurableTranslationEngine(
        override val supportedSourceLanguages: Set<LanguageCode> = setOf(
            LanguageCode.HINDI, LanguageCode.ENGLISH, LanguageCode.BENGALI,
            LanguageCode.GUJARATI, LanguageCode.MARATHI, LanguageCode.KANNADA,
            LanguageCode.TAMIL, LanguageCode.TELUGU
        ),
        override val supportedTargetLanguages: Set<LanguageCode> = setOf(
            LanguageCode.HINDI, LanguageCode.ENGLISH, LanguageCode.BENGALI,
            LanguageCode.GUJARATI, LanguageCode.MARATHI, LanguageCode.KANNADA,
            LanguageCode.TAMIL, LanguageCode.TELUGU
        )
    ) : TranslationEngine {
        override val isLoaded: Boolean = true
        var callCount = 0
        var shouldFail = false
        var failError = "MT_FAILED"

        override fun init(modelsDir: File) {}
        override fun release() {}

        override suspend fun translate(text: String, sourceLang: LanguageCode, targetLang: LanguageCode): TranslationResult {
            callCount++
            if (shouldFail) {
                return TranslationResult(text, "", false, sourceLang, targetLang, error = failError)
            }
            return TranslationResult(
                originalText = text,
                translatedText = "[TRANSLATED $sourceLang->$targetLang] $text",
                isSuccessful = true,
                sourceLanguage = sourceLang,
                targetLanguage = targetLang
            )
        }
    }

    private class FakeTransportEngine : TransportEngine {
        override val isConnected: Boolean = true
        override val isServer: Boolean = true
        val sentPackets = mutableListOf<ItantraPacket>()

        override fun observeConnectionState(): Flow<ConnectionState> = flowOf(ConnectionState.CONNECTED)
        override suspend fun disconnect() {}
        override fun notifyAckReceived(messageId: Long) {}

        override suspend fun send(packet: ItantraPacket): TransmissionMetrics {
            sentPackets.add(packet)
            return TransmissionMetrics(
                packetBytes = Measurement.Measured(packet.payload.size),
                transmissionLatencyMillis = Measurement.Measured(5L)
            )
        }

        override fun receive(): Flow<ItantraPacket> = emptyFlow()
    }

    @org.junit.Rule
    @JvmField
    val tempFolder = org.junit.rules.TemporaryFolder()

    private fun createDummyContext(): android.content.Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = tempFolder.root
        override fun getContentResolver(): android.content.ContentResolver? = null
    }

    private fun createRecognizer(source: LanguageCode, target: LanguageCode?): SherpaOnnxSpeechRecognizer {
        return SherpaOnnxSpeechRecognizer(
            context = createDummyContext(),
            languageCode = source,
            storage = DummyStorage(),
            metricsRecorder = InMemoryMetricsRecorder(),
            autoDetect = false,
            targetLanguage = target
        )
    }

    private fun createSessionManager(initialLanguage: LanguageCode): ActiveLanguageSessionManager {
        val engineFactory = object : EngineFactory {
            override fun createRecognizer(language: LanguageCode, autoDetect: Boolean, targetLanguage: LanguageCode?): SpeechRecognizerEngine =
                DummyRecognizer(language)
            override fun createRecognizer(language: LanguageCode, autoDetect: Boolean): SpeechRecognizerEngine =
                DummyRecognizer(language)
            override fun createRecognizer(language: LanguageCode): SpeechRecognizerEngine =
                DummyRecognizer(language)
            override fun createSynthesizer(language: LanguageCode): SpeechSynthesizerEngine? = null
        }
        val sm = ActiveLanguageSessionManager(engineFactory = engineFactory)
        runBlocking { sm.ensureStt(initialLanguage) }
        return sm
    }

    private fun setupVerifiedSessionPair(): Pair<SecureSessionManager, SecureSessionManager> {
        val alice = SecureSessionManager()
        val bob = SecureSessionManager()
        val aliceHello = alice.startHandshake(isInitiator = true)
        val bobHello = bob.processSecureHello(aliceHello)!!
        alice.processSecureHello(bobHello)
        val aliceVerify = alice.confirmSasMatch()
        val bobVerify = bob.confirmSasMatch()
        alice.processSecureVerify(bobVerify)
        bob.processSecureVerify(aliceVerify)
        assertEquals(SecureSessionState.SECURE_VERIFIED, alice.state.value)
        assertEquals(SecureSessionState.SECURE_VERIFIED, bob.state.value)
        return Pair(alice, bob)
    }

    // =========================================================================
    // TASK 1: Generalized Whisper task selection (SherpaOnnxSpeechRecognizer.kt)
    // =========================================================================

    @Test
    fun `test same-language selection sets task transcribe across non-Hindi languages`() {
        val sameLangPairs = listOf(
            LanguageCode.TAMIL to LanguageCode.TAMIL,
            LanguageCode.BENGALI to LanguageCode.BENGALI,
            LanguageCode.GUJARATI to LanguageCode.GUJARATI,
            LanguageCode.MARATHI to LanguageCode.MARATHI,
            LanguageCode.KANNADA to LanguageCode.KANNADA,
            LanguageCode.MALAYALAM to LanguageCode.MALAYALAM,
            LanguageCode.TELUGU to LanguageCode.TELUGU,
            LanguageCode.ODIA to LanguageCode.ODIA,
            LanguageCode.HINDI to LanguageCode.HINDI,
            LanguageCode.ENGLISH to LanguageCode.ENGLISH
        )

        for ((src, tgt) in sameLangPairs) {
            val recognizer = createRecognizer(src, tgt)
            assertFalse(
                "Same-language $src -> $tgt must NOT be translate mode (task=transcribe)",
                recognizer.isTranslateMode
            )
        }
    }

    @Test
    fun `test English target uses text translation for specialized Indic speech models`() {
        // The language-specific fine-tunes transcribe; the MT stage handles English targets.
        val toEnglishSources = listOf(
            LanguageCode.HINDI, // Baseline
            LanguageCode.TAMIL,
            LanguageCode.TELUGU,
            LanguageCode.BENGALI,
            LanguageCode.GUJARATI,
            LanguageCode.MARATHI,
            LanguageCode.KANNADA,
            LanguageCode.MALAYALAM,
            LanguageCode.ODIA
        )

        for (src in toEnglishSources) {
            val recognizer = createRecognizer(src, LanguageCode.ENGLISH)
            assertEquals(
                "Specialized STT must stay in transcription mode",
                src !in setOf(LanguageCode.HINDI, LanguageCode.TAMIL, LanguageCode.TELUGU),
                recognizer.isTranslateMode
            )
        }
    }

    @Test
    fun `test cross-language between two non-English languages sets task transcribe`() {
        val nonEnglishPairs = listOf(
            LanguageCode.HINDI to LanguageCode.TAMIL,
            LanguageCode.TAMIL to LanguageCode.TELUGU,
            LanguageCode.BENGALI to LanguageCode.MARATHI,
            LanguageCode.MALAYALAM to LanguageCode.HINDI,
            LanguageCode.ODIA to LanguageCode.GUJARATI,
            LanguageCode.KANNADA to LanguageCode.TAMIL
        )

        for ((src, tgt) in nonEnglishPairs) {
            val recognizer = createRecognizer(src, tgt)
            assertFalse(
                "Cross-language between non-English $src -> $tgt must use task=transcribe (then MT via router)",
                recognizer.isTranslateMode
            )
        }
    }

    @Test
    fun `test null target language falls back to transcribe`() {
        val recognizer = createRecognizer(LanguageCode.TAMIL, null)
        assertFalse("Null target language must not enable translate mode", recognizer.isTranslateMode)
    }

    // =========================================================================
    // TASK 2: Generalized isWhisperNativeTranslate bypass in TransceiverCoordinator
    // =========================================================================

    @Test
    fun `test Whisper native translate bypass applies only to shared multilingual speech models`() {
        val testSources = listOf(LanguageCode.TAMIL, LanguageCode.TELUGU, LanguageCode.BENGALI)

        for (src in testSources) {
            val engine = createRecognizer(src, LanguageCode.ENGLISH)
            val targetLang = LanguageCode.ENGLISH

            // Evaluates generalized condition in TransceiverCoordinator:
            val isWhisperNativeTranslate = targetLang == LanguageCode.ENGLISH && engine.isTranslateMode
            assertEquals(
                "Dedicated Indic models must use the text translation router",
                src == LanguageCode.BENGALI,
                isWhisperNativeTranslate,
            )
        }
    }

    @Test
    fun `test Whisper native translate bypass does not apply when target is not English`() {
        val engine = createRecognizer(LanguageCode.HINDI, LanguageCode.TAMIL)
        val targetLang = LanguageCode.TAMIL

        val isWhisperNativeTranslate = targetLang == LanguageCode.ENGLISH && engine.isTranslateMode
        assertFalse("Whisper native translate must not bypass when target is not English", isWhisperNativeTranslate)
    }

    // =========================================================================
    // TASK 3: Option B Graceful Degradation for Malayalam and Odia
    // =========================================================================

    @Test
    fun `test Malayalam cross-language UNSUPPORTED_ROUTE transmits original with BYPASSED status`() = runBlocking {
        val (aliceCrypto, bobCrypto) = setupVerifiedSessionPair()
        val transport = FakeTransportEngine()
        val mtEngine = ConfigurableTranslationEngine() // does not support ml or or
        val router = TranslationRouter(mtEngine)
        val repo = MockLanguagePackRepository()
        repo.setTargetLanguage(LanguageCode.HINDI)

        val coordinator = TransceiverCoordinator(
            context = createDummyContext(),
            sessionManager = createSessionManager(LanguageCode.MALAYALAM),
            languagePackRepository = repo,
            transportEngine = transport,
            metricsRecorder = InMemoryMetricsRecorder(),
            secureSessionManager = aliceCrypto,
            translationRouter = router
        )

        // Wait for coroutine to collect targetLanguage from repository
        kotlinx.coroutines.delay(100)

        // Non-emergency Malayalam text
        val malayalamText = "ഇത് ഒരു സാധാരണ സന്ദേശമാണ്"

        // Peer is Hindi, user speaks/types Malayalam -> cross-language route
        coordinator.sendTextMessage(
            text = malayalamText,
            targetPeerId = "PEER-HINDI",
            priority = MessagePriority.NORMAL
        )

        // Allow transmission coroutine to finish
        kotlinx.coroutines.delay(100)

        val messages = coordinator.messages.value
        assertEquals("Exactly one message should be recorded", 1, messages.size)
        val msg = messages.first()
        println("DEBUG Malayalam msg: state=${msg.state}, lang=${msg.language}, targetLang=${msg.targetLanguage}, transStatus=${msg.translationStatus}, detail=${msg.statusDetail}, text=${msg.text}")

        // Verifications for Option B:
        // 1. Must NOT be MessageState.ERROR
        assertNotEquals("Option B must NOT put message in ERROR state (detail=${msg.statusDetail})", MessageState.ERROR, msg.state)
        assertTrue("Message must progress to SENT or DELIVERED", msg.state == MessageState.SENT || msg.state == MessageState.DELIVERED)

        // 2. translationStatus must be BYPASSED
        assertEquals("Translation status must be BYPASSED for Malayalam", TranslationStatus.BYPASSED, msg.translationStatus)

        // 3. statusDetail must state original sent with language name
        assertEquals("Original sent — no translation available for Malayalam", msg.statusDetail)

        // 4. Packet must be transmitted, not dropped (filtering out handshake/capabilities control packets)
        val textPackets = transport.sentPackets.filter { it.type == PacketType.TEXT }
        assertEquals("TEXT packet must be transmitted via transport", 1, textPackets.size)
        val sentEncrypted = textPackets.first()

        // Decrypt and inspect packet
        val decrypted = bobCrypto.decrypt(sentEncrypted)
        assertEquals("Packet payload language must be SOURCE (Malayalam)", LanguageCode.MALAYALAM, decrypted.languageCode)
        assertEquals("Packet source language must be Malayalam", LanguageCode.MALAYALAM, decrypted.sourceLanguage)
        assertEquals("Packet payload must contain original Malayalam text", malayalamText, String(decrypted.payload, Charsets.UTF_8))
    }

    @Test
    fun `test Odia cross-language UNSUPPORTED_ROUTE transmits original with BYPASSED status`() = runBlocking {
        val (aliceCrypto, bobCrypto) = setupVerifiedSessionPair()
        val transport = FakeTransportEngine()
        val mtEngine = ConfigurableTranslationEngine() // does not support ml or or
        val router = TranslationRouter(mtEngine)
        val repo = MockLanguagePackRepository()
        repo.setTargetLanguage(LanguageCode.HINDI)

        val coordinator = TransceiverCoordinator(
            context = createDummyContext(),
            sessionManager = createSessionManager(LanguageCode.ODIA),
            languagePackRepository = repo,
            transportEngine = transport,
            metricsRecorder = InMemoryMetricsRecorder(),
            secureSessionManager = aliceCrypto,
            translationRouter = router
        )

        kotlinx.coroutines.delay(100)

        // Non-emergency Odia text
        val odiaText = "ଏହା ଏକ ସାଧାରଣ ବାର୍ତ୍ତା"

        coordinator.sendTextMessage(
            text = odiaText,
            targetPeerId = "PEER-HINDI",
            priority = MessagePriority.NORMAL
        )

        kotlinx.coroutines.delay(100)

        val messages = coordinator.messages.value
        assertEquals(1, messages.size)
        val msg = messages.first()

        assertNotEquals("Option B must NOT put Odia message in ERROR state (detail=${msg.statusDetail})", MessageState.ERROR, msg.state)
        assertEquals(TranslationStatus.BYPASSED, msg.translationStatus)
        assertEquals("Original sent — no translation available for Odia", msg.statusDetail)

        val textPackets = transport.sentPackets.filter { it.type == PacketType.TEXT }
        assertEquals("TEXT packet must be transmitted", 1, textPackets.size)
        val decrypted = bobCrypto.decrypt(textPackets.first())
        assertEquals("Packet language must be Odia", LanguageCode.ODIA, decrypted.languageCode)
        assertEquals(odiaText, String(decrypted.payload, Charsets.UTF_8))
    }

    @Test
    fun `test Hindi to Malayalam cross-language UNSUPPORTED_ROUTE transmits original with BYPASSED status`() = runBlocking {
        val (aliceCrypto, bobCrypto) = setupVerifiedSessionPair()
        val transport = FakeTransportEngine()
        val mtEngine = ConfigurableTranslationEngine() // supports hi, en, etc., but NOT ml or or
        val router = TranslationRouter(mtEngine)
        val repo = MockLanguagePackRepository()
        repo.setTargetLanguage(LanguageCode.MALAYALAM)

        val coordinator = TransceiverCoordinator(
            context = createDummyContext(),
            sessionManager = createSessionManager(LanguageCode.HINDI),
            languagePackRepository = repo,
            transportEngine = transport,
            metricsRecorder = InMemoryMetricsRecorder(),
            secureSessionManager = aliceCrypto,
            translationRouter = router
        )

        kotlinx.coroutines.delay(100)

        // Non-emergency Hindi text
        val hindiText = "नमस्ते मित्र, सब ठीक है"

        // Source is Hindi, target is Malayalam -> ML Kit lacks Malayalam model -> UNSUPPORTED_ROUTE
        coordinator.sendTextMessage(
            text = hindiText,
            targetPeerId = "PEER-MALAYALAM",
            priority = MessagePriority.NORMAL
        )

        kotlinx.coroutines.delay(100)

        val messages = coordinator.messages.value
        assertEquals("Exactly one message should be recorded", 1, messages.size)
        val msg = messages.first()

        // Verifications for Option B (target = Malayalam):
        // 1. Must NOT be MessageState.ERROR
        assertNotEquals("Option B must NOT put message in ERROR state (detail=${msg.statusDetail})", MessageState.ERROR, msg.state)
        assertTrue("Message must progress to SENT or DELIVERED", msg.state == MessageState.SENT || msg.state == MessageState.DELIVERED)

        // 2. translationStatus must be BYPASSED
        assertEquals("Translation status must be BYPASSED when target is Malayalam", TranslationStatus.BYPASSED, msg.translationStatus)

        // 3. statusDetail must state original sent with unsupported language name (Malayalam, not Hindi)
        assertEquals("Original sent — no translation available for Malayalam", msg.statusDetail)

        // 4. Packet must be transmitted, not dropped
        val textPackets = transport.sentPackets.filter { it.type == PacketType.TEXT }
        assertEquals("TEXT packet must be transmitted via transport", 1, textPackets.size)
        val decrypted = bobCrypto.decrypt(textPackets.first())

        // 5. Packet payload language must be SOURCE (Hindi), NOT mislabeled as target (Malayalam)
        assertEquals("Packet payload language must be SOURCE (Hindi)", LanguageCode.HINDI, decrypted.languageCode)
        assertEquals("Packet source language must be Hindi", LanguageCode.HINDI, decrypted.sourceLanguage)
        assertEquals("Packet payload must contain original Hindi text", hindiText, String(decrypted.payload, Charsets.UTF_8))
    }

    @Test
    fun `test English to Odia cross-language UNSUPPORTED_ROUTE transmits original with BYPASSED status`() = runBlocking {
        val (aliceCrypto, bobCrypto) = setupVerifiedSessionPair()
        val transport = FakeTransportEngine()
        val mtEngine = ConfigurableTranslationEngine() // supports hi, en, etc., but NOT ml or or
        val router = TranslationRouter(mtEngine)
        val repo = MockLanguagePackRepository()
        repo.setTargetLanguage(LanguageCode.ODIA)

        val coordinator = TransceiverCoordinator(
            context = createDummyContext(),
            sessionManager = createSessionManager(LanguageCode.ENGLISH),
            languagePackRepository = repo,
            transportEngine = transport,
            metricsRecorder = InMemoryMetricsRecorder(),
            secureSessionManager = aliceCrypto,
            translationRouter = router
        )

        kotlinx.coroutines.delay(100)

        // Non-emergency English text
        val englishText = "The road ahead is clear"

        // Source is English, target is Odia -> ML Kit lacks Odia model -> UNSUPPORTED_ROUTE
        coordinator.sendTextMessage(
            text = englishText,
            targetPeerId = "PEER-ODIA",
            priority = MessagePriority.NORMAL
        )

        kotlinx.coroutines.delay(100)

        val messages = coordinator.messages.value
        assertEquals("Exactly one message should be recorded", 1, messages.size)
        val msg = messages.first()

        // Verifications for Option B (target = Odia):
        // 1. Must NOT be MessageState.ERROR
        assertNotEquals("Option B must NOT put message in ERROR state (detail=${msg.statusDetail})", MessageState.ERROR, msg.state)
        assertTrue("Message must progress to SENT or DELIVERED", msg.state == MessageState.SENT || msg.state == MessageState.DELIVERED)

        // 2. translationStatus must be BYPASSED
        assertEquals("Translation status must be BYPASSED when target is Odia", TranslationStatus.BYPASSED, msg.translationStatus)

        // 3. statusDetail must state original sent with unsupported language name (Odia, not English)
        assertEquals("Original sent — no translation available for Odia", msg.statusDetail)

        // 4. Packet must be transmitted, not dropped
        val textPackets = transport.sentPackets.filter { it.type == PacketType.TEXT }
        assertEquals("TEXT packet must be transmitted via transport", 1, textPackets.size)
        val decrypted = bobCrypto.decrypt(textPackets.first())

        // 5. Packet payload language must be SOURCE (English), NOT mislabeled as target (Odia)
        assertEquals("Packet payload language must be SOURCE (English)", LanguageCode.ENGLISH, decrypted.languageCode)
        assertEquals("Packet source language must be English", LanguageCode.ENGLISH, decrypted.sourceLanguage)
        assertEquals("Packet payload must contain original English text", englishText, String(decrypted.payload, Charsets.UTF_8))
    }

    @Test
    fun `test non-Malayalam non-Odia unsupported route still errors and drops message`() = runBlocking {
        val (aliceCrypto, _) = setupVerifiedSessionPair()
        val transport = FakeTransportEngine()

        // Engine that only supports Hindi & English (Bengali -> Tamil unsupported)
        val narrowEngine = ConfigurableTranslationEngine(
            supportedSourceLanguages = setOf(LanguageCode.HINDI, LanguageCode.ENGLISH),
            supportedTargetLanguages = setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)
        )
        val router = TranslationRouter(narrowEngine)
        val repo = MockLanguagePackRepository()
        repo.setTargetLanguage(LanguageCode.TAMIL)

        val coordinator = TransceiverCoordinator(
            context = createDummyContext(),
            sessionManager = createSessionManager(LanguageCode.BENGALI),
            languagePackRepository = repo,
            transportEngine = transport,
            metricsRecorder = InMemoryMetricsRecorder(),
            secureSessionManager = aliceCrypto,
            translationRouter = router
        )

        kotlinx.coroutines.delay(100)

        // Non-emergency Bengali text
        coordinator.sendTextMessage(
            text = "এটি একটি সাধারণ বার্তা",
            targetPeerId = "PEER-TAMIL",
            priority = MessagePriority.NORMAL
        )

        kotlinx.coroutines.delay(100)

        val messages = coordinator.messages.value
        assertEquals(1, messages.size)
        val msg = messages.first()

        // For non-ml/or, UNSUPPORTED_ROUTE must set ERROR and drop transmission
        assertEquals("Non-ml/or unsupported route must set MessageState.ERROR", MessageState.ERROR, msg.state)
        assertEquals(TranslationStatus.FAILED, msg.translationStatus)
        val textPackets = transport.sentPackets.filter { it.type == PacketType.TEXT }
        assertEquals("TEXT packet must NOT be transmitted when MT fails for non-ml/or", 0, textPackets.size)
    }

    // =========================================================================
    // TASK 3 & 4: Emergency Phrase Resolution for Malayalam and Odia
    // =========================================================================

    @Test
    fun `test Malayalam and Odia emergency phrases resolve correctly without neural MT`() = runBlocking {
        val mtEngine = ConfigurableTranslationEngine() // ml and or are unsupported
        val router = TranslationRouter(mtEngine)

        // Malayalam Help Required -> English
        val mlHelp = EmergencyPhraseResolver.resolve(EmergencyCode.HELP_REQUIRED, LanguageCode.MALAYALAM)
        assertNotNull("Malayalam HELP_REQUIRED must exist", mlHelp)
        val mlRes = router.routeAndTranslate(mlHelp, LanguageCode.MALAYALAM, LanguageCode.ENGLISH)
        assertTrue("Malayalam emergency phrase must resolve successfully", mlRes.isSuccessful)
        assertEquals("Help required.", mlRes.translatedText)
        assertEquals("Neural MT engine must NOT be called for emergency phrases", 0, mtEngine.callCount)

        // Malayalam Medical Emergency -> Hindi
        val mlMed = EmergencyPhraseResolver.resolve(EmergencyCode.MEDICAL_EMERGENCY, LanguageCode.MALAYALAM)
        val mlMedRes = router.routeAndTranslate(mlMed, LanguageCode.MALAYALAM, LanguageCode.HINDI)
        assertTrue(mlMedRes.isSuccessful)
        assertEquals("तुरंत चिकित्सा सहायता की आवश्यकता है।", mlMedRes.translatedText)

        // Odia Help Required -> English
        val orHelp = EmergencyPhraseResolver.resolve(EmergencyCode.HELP_REQUIRED, LanguageCode.ODIA)
        assertNotNull("Odia HELP_REQUIRED must exist", orHelp)
        val orRes = router.routeAndTranslate(orHelp, LanguageCode.ODIA, LanguageCode.ENGLISH)
        assertTrue("Odia emergency phrase must resolve successfully", orRes.isSuccessful)
        assertEquals("Help required.", orRes.translatedText)

        // Odia Fire -> Hindi
        val orFire = EmergencyPhraseResolver.resolve(EmergencyCode.FIRE, LanguageCode.ODIA)
        val orFireRes = router.routeAndTranslate(orFire, LanguageCode.ODIA, LanguageCode.HINDI)
        assertTrue(orFireRes.isSuccessful)
        assertEquals("आग लगी है।", orFireRes.translatedText)

        // Underlying engine was never called because phrasebook resolved offline
        assertEquals(0, mtEngine.callCount)
    }

    // =========================================================================
    // TASK 4: Hindi-English Baseline Regression Protection
    // =========================================================================

    @Test
    fun `test Hindi specialized transcription retains Hindi to English MT routing`() = runBlocking {
        // 1. Whisper task selection
        val hiToEnRecognizer = createRecognizer(LanguageCode.HINDI, LanguageCode.ENGLISH)
        assertFalse("Hindi fine-tune must transcribe before the MT stage", hiToEnRecognizer.isTranslateMode)

        // 2. TranslationRouter route
        val mtEngine = ConfigurableTranslationEngine()
        val router = TranslationRouter(mtEngine)
        val result = router.routeAndTranslate("नमस्ते", LanguageCode.HINDI, LanguageCode.ENGLISH)
        assertTrue("Hindi -> English MT route must succeed", result.isSuccessful)
        assertEquals(1, mtEngine.callCount)
    }

    @Test
    fun `test English to Hindi baseline routes through MT engine without translate mode`() = runBlocking {
        val enToHiRecognizer = createRecognizer(LanguageCode.ENGLISH, LanguageCode.HINDI)
        assertFalse("English -> Hindi must NOT be translate mode (transcribe only)", enToHiRecognizer.isTranslateMode)

        val mtEngine = ConfigurableTranslationEngine()
        val router = TranslationRouter(mtEngine)
        val result = router.routeAndTranslate("Hello team", LanguageCode.ENGLISH, LanguageCode.HINDI)
        assertTrue("English -> Hindi MT route must succeed", result.isSuccessful)
        assertEquals(1, mtEngine.callCount)
    }

    @Test
    fun `test cross-language between two non-English languages routes through MT engine`() = runBlocking {
        val mtEngine = ConfigurableTranslationEngine()
        val router = TranslationRouter(mtEngine)

        // Hindi mic -> Tamil target
        val hiToTaResult = router.routeAndTranslate("रास्ता साफ है", LanguageCode.HINDI, LanguageCode.TAMIL)
        assertTrue("Hindi -> Tamil cross-language MT must succeed", hiToTaResult.isSuccessful)
        assertEquals("[TRANSLATED HINDI->TAMIL] रास्ता साफ है", hiToTaResult.translatedText)
        assertEquals(1, mtEngine.callCount)

        // Tamil mic -> Telugu target
        val taToTeResult = router.routeAndTranslate("வழி தெளிவாக உள்ளது", LanguageCode.TAMIL, LanguageCode.TELUGU)
        assertTrue("Tamil -> Telugu cross-language MT must succeed", taToTeResult.isSuccessful)
        assertEquals(2, mtEngine.callCount)
    }
}
