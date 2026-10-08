package com.itantra.core.transceiver

import android.os.SystemClock
import android.provider.Settings
import com.itantra.core.audio.SpeakerAudioSink
import com.itantra.core.crypto.SecureSessionManager
import com.itantra.core.translation.TRANSLATION_SCOPE_NOTE
import com.itantra.core.translation.TranslationResult
import com.itantra.core.crypto.SecureSessionState
import android.content.Context
import com.itantra.core.inference.ActiveLanguageSessionManager
import com.itantra.core.inference.SherpaOnnxSpeechRecognizer
import com.itantra.core.inference.ContinuousListenEngine
import com.itantra.core.inference.ContinuousListenState
import com.itantra.core.inference.MicrophoneAudioSource
import com.itantra.core.inference.TtsCapabilityProvider
import com.itantra.core.metrics.MetricsRecorder
import com.itantra.core.transport.ConnectionState
import com.itantra.core.transport.TransportEngine
import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.core.transport.packet.PacketType
import com.itantra.core.transport.packet.ProtocolLanguageMapper
import com.itantra.core.location.DefaultGpsLocationProvider
import com.itantra.core.location.LocationProvider
import com.itantra.core.location.LocationResult
import com.itantra.core.transport.packet.LocationPayload
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.MessageSource
import com.itantra.domain.model.MessageState
import com.itantra.domain.model.SpeechSynthesisRequest
import com.itantra.domain.model.TransceiverMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PeerCapabilities(
    val supportedStt: List<LanguageCode> = emptyList(),
    val supportedTts: List<LanguageCode> = emptyList()
)

class TransceiverCoordinator(
    private val context: Context,
    private val sessionManager: ActiveLanguageSessionManager,
    private val languagePackRepository: com.itantra.domain.repository.LanguagePackRepository,
    private val transportEngine: TransportEngine,
    private val metricsRecorder: MetricsRecorder,
    val secureSessionManager: SecureSessionManager,
    private val translationRouter: com.itantra.core.translation.TranslationRouter,
    private val messageDao: com.itantra.data.db.MessageDao? = null,
    val deviceProfileManager: com.itantra.core.profile.DeviceProfileManager? = null,
    private val ttsCapabilityProvider: TtsCapabilityProvider? = null,
    private val locationProvider: LocationProvider = DefaultGpsLocationProvider(context),
    private val voiceNoteDao: com.itantra.data.db.VoiceNoteDao? = null,
    private val peerDao: com.itantra.data.db.PeerDao? = null,
    private val microphoneFrames: kotlinx.coroutines.flow.Flow<FloatArray>? = null,
) {
    companion object {
        private const val ENCRYPTED_HEARTBEAT_INTERVAL_MS = 15_000L
        private const val HELLO_RETRY_INTERVAL_MS = 2_000L
        private const val HANDSHAKE_TIMEOUT_MS = 60_000L
        private const val SAS_TIMEOUT_MS = 60_000L
        private const val VERIFY_RETRY_INTERVAL_MS = 2_000L
        private const val MAX_PENDING_MESSAGES = 64
        private const val MAX_PENDING_BYTES = 512 * 1024
        private const val RESERVED_EMERGENCY_MESSAGES = 4
        private const val RESERVED_EMERGENCY_BYTES = 64 * 1024
        private const val MAX_RECEIPT_BINDINGS = 512
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val deviceIdSalt: Long by lazy {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "0"
        (androidId.hashCode().toLong() and 0xFFF)
    }

    private val lastMessageTick = java.util.concurrent.atomic.AtomicLong(0L)

    private fun nextMessageId(): Long {
        val tick = lastMessageTick.updateAndGet { previous ->
            maxOf(System.currentTimeMillis(), previous + 1)
        }
        return (tick shl 12) or deviceIdSalt
    }

    private val _messages = MutableStateFlow<List<TransceiverMessage>>(emptyList())
    val messages: StateFlow<List<TransceiverMessage>> = _messages.asStateFlow()
    private val _trashedMessages = MutableStateFlow<List<TransceiverMessage>>(emptyList())
    val trashedMessages: StateFlow<List<TransceiverMessage>> = _trashedMessages.asStateFlow()
    // Order state mutations and snapshot enqueueing together. The IO consumer never
    // takes this lock, and persistence/alert side effects stay outside update lambdas.
    private val messageMutationLock = Any()
    // Conflate notifications, not history: a slow database cannot accumulate copies
    // of every intermediate state. The IO worker reconciles the latest full history.
    private val historyChanges = MutableStateFlow(0L)

    private val _activePeerProfile = MutableStateFlow<com.itantra.domain.model.PeerProfile?>(null)
    val activePeerProfile: StateFlow<com.itantra.domain.model.PeerProfile?> = _activePeerProfile.asStateFlow()

    private val _activeConversationPeerId = MutableStateFlow<String?>(null)
    val activeConversationPeerId: StateFlow<String?> = _activeConversationPeerId.asStateFlow()

    private val currentReceiveLanguage = MutableStateFlow<LanguageCode?>(null)

    init {
        scope.launch {
            languagePackRepository.observeReceiveLanguage().collect {
                currentReceiveLanguage.value = it
            }
        }
    }

    fun setActiveConversation(peerId: String?) {
        _activeConversationPeerId.value = peerId
    }

    private var hasSentHandshake = false

    suspend fun sendProfileHandshake() {
        val generation = connectionGeneration.get()
        if (secureSessionManager.state.value != SecureSessionState.SECURE_VERIFIED) return
        val profile = deviceProfileManager?.profile?.value
        val devId = profile?.deviceId ?: "IT-0000-0000"
        val name = profile?.displayName ?: "iTantra Operator"
        val activeLang = languagePackRepository.observeActiveLanguage().first() ?: LanguageCode.ENGLISH

        val payload = com.itantra.core.transport.packet.ProfilePayload(
            protocolVersion = com.itantra.core.transport.packet.ProfilePayload.CURRENT_PROTOCOL_VERSION,
            deviceId = devId,
            displayName = name,
            supportedLanguages = listOf(activeLang)
        ).toBytes()

        val packet = ItantraPacket(
            type = PacketType.PROFILE_HANDSHAKE,
            messageId = nextMessageId(),
            payload = payload
        )
        try {
            val securePacket = encryptForConnection(generation, packet)
            sendForConnection(generation, securePacket)
            android.util.Log.i("TransceiverCoordinator", "Sent verified peer profile")
        } catch (e: Exception) {
            android.util.Log.e("TransceiverCoordinator", "Error sending PROFILE_HANDSHAKE", e)
        }
    }

    init {
        if (messageDao != null) {
            scope.launch(Dispatchers.IO) {
                val persistedById = mutableMapOf<Long, TransceiverMessage>()
                try {
                    val persistedRows = messageDao.getAllIncludingTrash().map { it.toDomain() }
                    persistedRows.associateByTo(persistedById) { it.messageId }
                    val now = System.currentTimeMillis()
                    val trashed = persistedRows.filter { it.deletedAtMillis != null }
                    synchronized(messageMutationLock) {
                        val currentIds = (_messages.value + _trashedMessages.value).map { it.messageId }.toSet()
                        _trashedMessages.value = trashed.filter {
                            it.messageId !in currentIds && com.itantra.domain.model.RecycleBinPolicy.canRestore(it.deletedAtMillis!!, now)
                        } + _trashedMessages.value
                    }
                    val persisted = persistedRows.filter { it.deletedAtMillis == null }
                    if (persisted.isNotEmpty()) {
                        val settled = persisted.map { m ->
                            when {
                                m.priority == com.itantra.domain.model.MessagePriority.CRITICAL -> m
                                m.source == MessageSource.LOCAL && m.state in setOf(
                                    MessageState.RECORDING,
                                    MessageState.STT_PROCESSING,
                                    MessageState.STT_COMPLETE,
                                    MessageState.PACKET_ENCODING,
                                    MessageState.TRANSMITTING,
                                    MessageState.WAITING_USER_CONFIRMATION
                                ) -> m.copy(
                                    state = MessageState.ERROR,
                                    statusDetail = m.statusDetail ?: "Interrupted - app closed before this was sent"
                                )
                                m.source == MessageSource.REMOTE && m.state in setOf(
                                    MessageState.REMOTE_TTS_READY,
                                    MessageState.REMOTE_PLAYING
                                ) -> m.copy(state = MessageState.DELIVERED)
                                else -> m
                            }
                        }
                        settled.filterIndexed { i, m -> m != persisted[i] }.forEach {
                            messageDao.insert(com.itantra.data.db.MessageEntity.fromDomain(it))
                        }
                        synchronized(messageMutationLock) {
                            _messages.update { current ->
                                val currentIds = (current + _trashedMessages.value).map { it.messageId }.toSet()
                                settled.filter { it.messageId !in currentIds } + current
                            }
                        }
                        updateAlertJob()
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("TransceiverCoord", "Error loading persisted messages", e)
                }
                synchronized(messageMutationLock) { requestHistoryWrite() }
                historyChanges.collect {
                    val latest = synchronized(messageMutationLock) {
                        (_messages.value + _trashedMessages.value).associateBy { it.messageId }
                    }
                    for ((id, message) in latest) {
                        if (persistedById[id] == message) continue
                        try {
                            messageDao.insert(com.itantra.data.db.MessageEntity.fromDomain(message))
                            persistedById[id] = message
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            android.util.Log.e("TransceiverCoord", "Error persisting message", e)
                        }
                    }
                    for (id in persistedById.keys - latest.keys) {
                        try {
                            messageDao.delete(id)
                            persistedById.remove(id)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            android.util.Log.e("TransceiverCoord", "Error deleting message", e)
                        }
                    }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                purgeExpiredHistory()
                try {
                    voiceNoteDao?.purgeExpired(System.currentTimeMillis() - com.itantra.domain.model.RecycleBinPolicy.RETENTION_MILLIS)
                } catch (e: Exception) {
                    android.util.Log.e("TransceiverCoord", "Could not clear expired recycle-bin notes", e)
                }
                delay(60_000L)
            }
        }
    }

    private val _peerCapabilities = MutableStateFlow(PeerCapabilities())
    val peerCapabilities: StateFlow<PeerCapabilities> = _peerCapabilities.asStateFlow()

    private data class IncomingMessage(val packet: ItantraPacket, val generation: Long, val peerId: String, val emergencyEpoch: Long)
    @Volatile private var sessionConnection = transportEngine.connectionToken
    private val messageQueue = mutableListOf<IncomingMessage>()
    private val queueLock = Any()
    private var pendingPayloadBytes = 0
    private val emergencyEpoch = java.util.concurrent.atomic.AtomicLong()
    private val queueWakeup = Channel<Unit>(Channel.CONFLATED)
    private data class PendingAck(val messageId: Long, val generation: Long)
    private val ackQueue = Channel<PendingAck>(MAX_PENDING_MESSAGES)
    private val incomingPacketLock = Any()
    private val receiptBindings = java.util.Collections.synchronizedMap(linkedMapOf<Long, Long>())

    private val sttMutex = Mutex()
    private val sttProcessingCount = java.util.concurrent.atomic.AtomicInteger()

    private var cooldownJob: Job? = null
    private val playbackGeneration = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var currentTtsJob: Job? = null
    @Volatile private var currentAudioSink: SpeakerAudioSink? = null
    private var alertJob: Job? = null
    @Volatile private var alertAudioSink: SpeakerAudioSink? = null

    // Connection generation token to protect against stale callbacks/timeouts
    private val connectionGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    private val _sasRemainingSeconds = MutableStateFlow<Int?>(null)
    val sasRemainingSeconds: StateFlow<Int?> = _sasRemainingSeconds.asStateFlow()

    // Retry jobs for handshake and verification phases
    private var handshakeRetryJob: Job? = null
    private var verifyRetryJob: Job? = null
    private var sasTimerJob: Job? = null
    @Volatile private var cachedVerifyPacket: ItantraPacket? = null
    private var encryptedHeartbeatJob: Job? = null

    private var isConnected = false
    @Volatile private var recordingJob: Job? = null
    private val audioSource = MicrophoneAudioSource(scope)
    private var recordingStartTime = 0L
    @Volatile private var activeRecordingMessageId = 0L
    private var activeRecordingEngine: com.itantra.core.inference.SpeechRecognizerEngine? = null
    private var recordingTarget: LanguageCode? = null
    private var recordingPeerVoices: List<LanguageCode> = emptyList()
    private val activeRecordingChunks = java.util.Collections.synchronizedList(mutableListOf<FloatArray>())

    val continuousListenEngine = ContinuousListenEngine(context)
    private var continuousModeJob: Job? = null
    val continuousModeError = com.itantra.core.service.OperationalForegroundService.lastStartupError
    init {
        scope.launch {
            continuousModeError.collect { error ->
                if (error != null) {
                    continuousModeJob?.cancel()
                    continuousModeJob = null
                    continuousListenEngine.stop()
                }
            }
        }
    }
    @Volatile private var isTtsPlaying = false
    @Volatile private var savedAudioSink: SpeakerAudioSink? = null
    private val savedSpeechPlayer = com.itantra.core.inference.SavedSpeechPlayer(
        scope, sessionManager, { ttsCapabilityProvider?.isTtsInstalled(it) == true },
        { audio ->
            val sink = SpeakerAudioSink(context)
            savedAudioSink = sink
            try { sink.init(audio.sampleRateHz); sink.play(audio.pcmAudio); sink.flushAndStop() }
            finally { sink.release(); if (savedAudioSink === sink) savedAudioSink = null }
        },
        { savedAudioSink?.stopImmediate() },
    )
    val savedSpeechPlayback = savedSpeechPlayer.state
    fun stopSavedSpeech() = savedSpeechPlayer.stop()

    private fun resumeContinuousIfIdle() {
        if (recordingJob == null && sttProcessingCount.get() == 0 && !isTtsPlaying && currentTtsJob?.isActive != true &&
            !savedSpeechPlayback.value.busy && continuousModeJob?.isActive == true) {
            continuousListenEngine.resetAndResume()
        }
    }

    private fun beginTtsPlayback(): Long {
        val generation = playbackGeneration.incrementAndGet()
        cooldownJob?.cancel()
        isTtsPlaying = true
        continuousListenEngine.pauseListening()
        return generation
    }

    private fun endTtsPlayback(generation: Long) {
        if (generation != playbackGeneration.get()) return
        cooldownJob?.cancel()
        cooldownJob = scope.launch {
            delay(300)
            if (generation == playbackGeneration.get()) {
                isTtsPlaying = false
                resumeContinuousIfIdle()
            }
        }
    }

    fun replayMessage(messageId: Long) {
        val message = _messages.value.firstOrNull { it.messageId == messageId } ?: return
        replaySavedText("message-$messageId", message.text, message.displayedTextLanguage)
    }

    /** Local per-message view. Does not send, overwrite history, or change receive/STT preferences. */
    suspend fun translateMessage(messageId: Long, target: LanguageCode): TranslationResult {
        val input = _messages.value.firstOrNull { it.messageId == messageId }?.translationInput
            ?: throw IllegalStateException("This message has no readable text with a known language")
        val result = translationRouter.routeAndTranslate(input.first, input.second, target)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(_messages.value.firstOrNull { it.messageId == messageId }?.translationInput == input) {
            "Message changed or was moved to the recycle bin"
        }
        return result
    }

    fun isMessageVoiceInstalled(language: LanguageCode): Boolean = ttsCapabilityProvider?.isTtsInstalled(language) == true

    fun replayMessageTranslation(messageId: Long, translation: TranslationResult) {
        synchronized(messageMutationLock) {
            val input = _messages.value.firstOrNull { it.messageId == messageId }?.translationInput ?: return
            val key = "message-$messageId-${translation.targetLanguage.wireCode}"
            if (!translation.isSuccessful || translation.translatedText.isBlank() ||
                input != (translation.originalText to translation.sourceLanguage)) {
                savedSpeechPlayer.unavailable(key, "Translate this message again before playing")
                return
            }
            replaySavedText(key, translation.translatedText, translation.targetLanguage)
        }
    }

    fun stopMessageSpeech(messageId: Long) {
        val key = savedSpeechPlayback.value.itemKey ?: return
        if (key == "message-$messageId" || key.startsWith("message-$messageId-")) stopSavedSpeech()
    }

    fun replayVoiceNote(note: com.itantra.data.db.VoiceNoteEntity) {
        if (note.deletedAtMillis != null) return
        // Older notes stored translated text with a source-language label. Recover the
        // displayed language from their corresponding message when it is still available.
        val matching = (_messages.value + _trashedMessages.value).firstOrNull {
            it.createdAtLocal == note.createdAtMillis && it.text == note.transcribedText && it.source == MessageSource.LOCAL
        }
        replaySavedText("note-${note.id}", note.transcribedText,
            matching?.displayedTextLanguage ?: LanguageCode.fromWireCode(note.languageWireCode))
    }

    private fun replaySavedText(key: String, text: String, language: LanguageCode?) {
        val unresolvedCritical = _messages.value.any { it.source == MessageSource.REMOTE && it.priority == com.itantra.domain.model.MessagePriority.CRITICAL && it.state != MessageState.ACKNOWLEDGED }
        if (recordingJob != null || currentTtsJob?.isActive == true || isTtsPlaying || _activeEmergencyAlert.value != null || unresolvedCritical) {
            savedSpeechPlayer.unavailable(key, "Finish recording or received speech, and acknowledge active emergencies before replaying")
            return
        }
        cooldownJob?.cancel()
        continuousListenEngine.pauseListening()
        savedSpeechPlayer.play(key, text, language)
    }

    init {
        scope.launch {
            savedSpeechPlayback.collect { playback ->
                if (playback.busy) continuousListenEngine.pauseListening()
                else {
                    delay(300)
                    if (currentTtsJob?.isActive != true) resumeContinuousIfIdle()
                }
            }
        }
    }

    val emergencyStore = com.itantra.core.emergency.EmergencyPersistenceStore(context.filesDir)
    private val _activeEmergencyAlert = MutableStateFlow<com.itantra.domain.model.EmergencyRecord?>(null)
    val activeEmergencyAlert: StateFlow<com.itantra.domain.model.EmergencyRecord?> = _activeEmergencyAlert.asStateFlow()

    // Bounded deduplication cache (up to 500 entries) scoped to connection/session
    private val seenMessageIds = java.util.Collections.synchronizedSet(
        object : java.util.LinkedHashSet<Long>() {
            override fun add(element: Long): Boolean {
                if (size >= 500) {
                    val it = iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
                return super.add(element)
            }
        }
    )

    private val currentTargetLanguage = MutableStateFlow<LanguageCode?>(null)

    @Volatile
    internal var debugBeepWhenNoVoice: Boolean = false

    /**
     * Resolves which language an outgoing message should be translated into.
     *
     * Previously this fell back to a hardcoded Hindi<->English binary guess
     * (`if (source == HINDI) ENGLISH else HINDI`) whenever the user hadn't
     * explicitly picked a target in settings - meaning for 8 of the 10
     * supported languages, an unconfigured session would silently default
     * to Hindi rather than the language the connected peer actually speaks.
     *
     * We now prefer, in order:
     *  1. An explicit target the user picked (`currentTargetLanguage`).
     *  2. The peer's own advertised language, learned from the CAPABILITIES
     *     handshake (`peerCapabilities`) - this is real data we already
     *     collect but weren't using for this decision.
     *  3. The old Hindi<->English guess, only as a last resort (e.g. before
     *     a handshake has completed).
     */
    private fun resolveTargetLanguage(sourceLanguage: LanguageCode,
        preference: LanguageCode? = currentTargetLanguage.value,
        peerVoices: List<LanguageCode> = _peerCapabilities.value.supportedTts,
    ): LanguageCode {
        preference?.let { return it }

        // 1. If peer advertises support for the same language, route same-language
        if (peerVoices.contains(sourceLanguage)) {
            return sourceLanguage
        }

        // 2. If peer advertises a different language, route to that peer's language
        val peerDifferent = peerVoices.firstOrNull { it != sourceLanguage }
        if (peerDifferent != null) return peerDifferent

        // 3. Pass 3 default: Same-Language Routing First
        return sourceLanguage
    }

    init {
        // Reload unresolved emergency state from durable persistence on initialization/restart.
        // Origin-aware restoration: only REMOTE emergencies trigger audio/alarm restoration on restart.
        // LOCAL unresolved emergencies (sent from this device) must NOT trigger the sender's own alarm.
        val unresolved = emergencyStore.getUnresolvedRecords()
        val remoteUnresolved = unresolved.filter { it.source == "REMOTE" }
        if (remoteUnresolved.isNotEmpty()) {
            val lastRemoteUnresolved = remoteUnresolved.last()
            _activeEmergencyAlert.value = lastRemoteUnresolved
            com.itantra.core.service.OperationalForegroundService.triggerEmergency(context, lastRemoteUnresolved.resolvedPhrase)
        }

        scope.launch {
            transportEngine.observeConnectionState().collect { state ->
                val connected = state == ConnectionState.CONNECTED
                if (connected && !isConnected) {
                    val gen = connectionGeneration.incrementAndGet()
                    sessionConnection = transportEngine.connectionToken
                    hasSentHandshake = false
                    isConnected = true
                    handshakeRetryJob?.cancel(); handshakeRetryJob = null
                    verifyRetryJob?.cancel(); verifyRetryJob = null
                    sasTimerJob?.cancel(); sasTimerJob = null
                    cachedVerifyPacket = null
                    _sasRemainingSeconds.value = null

                    // Start handshake if connected. Only the client (initiator) sends the first HELLO.
                    // Responder remains in NO_SESSION, ready to process incoming SECURE_HELLO.
                    val isInitiator = !transportEngine.isServer
                    if (!isInitiator) {
                        handshakeRetryJob = scope.launch {
                            delay(HANDSHAKE_TIMEOUT_MS)
                            if (connectionGeneration.get() == gen && secureSessionManager.state.value == SecureSessionState.NO_SESSION) {
                                secureSessionManager.setHandshakeTimeout()
                                transportEngine.disconnect()
                            }
                        }
                    }
                    if (isInitiator) {
                        handshakeRetryJob = scope.launch {
                            val hello = secureSessionManager.startHandshake(isInitiator = true)
                            try {
                                transportEngine.send(hello, sessionConnection)
                            } catch (e: Exception) {
                                android.util.Log.w("TransceiverCoordinator", "Failed to send initial HELLO: ${e.message}")
                            }
                            val deadline = android.os.SystemClock.elapsedRealtime() + HANDSHAKE_TIMEOUT_MS
                            while (isActive &&
                                connectionGeneration.get() == gen &&
                                secureSessionManager.state.value == SecureSessionState.HANDSHAKING &&
                                android.os.SystemClock.elapsedRealtime() < deadline
                            ) {
                                delay(HELLO_RETRY_INTERVAL_MS)
                                if (connectionGeneration.get() != gen) break
                                if (secureSessionManager.state.value == SecureSessionState.HANDSHAKING) {
                                    val stored = secureSessionManager.getStoredHello()
                                    if (stored != null) {
                                        android.util.Log.d("TransceiverCoordinator", "Retrying cached SECURE_HELLO")
                                        try {
                                            transportEngine.send(stored, sessionConnection)
                                        } catch (e: Exception) {
                                            android.util.Log.w("TransceiverCoordinator", "HELLO retry failed: ${e.message}")
                                        }
                                    }
                                }
                            }
                            // Bounded 60s handshake window expired
                            if (connectionGeneration.get() == gen &&
                                secureSessionManager.state.value == SecureSessionState.HANDSHAKING
                            ) {
                                android.util.Log.e(
                                    "TransceiverCoordinator",
                                    "SECURE_HELLO handshake timeout after 60 seconds"
                                )
                                secureSessionManager.setHandshakeTimeout()
                                transportEngine.setAuthenticatedLivenessEnabled(false)
                                transportEngine.disconnect()
                            }
                        }
                    }
                } else if (!connected && isConnected) {
                    isConnected = false
                    connectionGeneration.incrementAndGet()
                    handshakeRetryJob?.cancel(); handshakeRetryJob = null
                    verifyRetryJob?.cancel(); verifyRetryJob = null
                    sasTimerJob?.cancel(); sasTimerJob = null
                    encryptedHeartbeatJob?.cancel(); encryptedHeartbeatJob = null
                    cachedVerifyPacket = null
                    _sasRemainingSeconds.value = null
                    transportEngine.setAuthenticatedLivenessEnabled(false)
                    _peerCapabilities.value = PeerCapabilities()
                    _activePeerProfile.value = null
                    secureSessionManager.resetSession()
                    seenMessageIds.clear()
                    receiptBindings.clear()
                    synchronized(queueLock) {
                        messageQueue.forEach(::preserveInterruptedIncoming)
                        messageQueue.clear()
                        pendingPayloadBytes = 0
                    }
                    currentTtsJob?.cancel()
                }
            }
        }

        scope.launch {
            secureSessionManager.state.collect { state ->
                when (state) {
                    SecureSessionState.WAITING_USER_VERIFICATION -> {
                        handshakeRetryJob?.cancel()
                        handshakeRetryJob = null
                        // Start 60-second SAS verification countdown timer for this connection generation
                        val gen = connectionGeneration.get()
                        sasTimerJob?.cancel()
                        sasTimerJob = scope.launch {
                            val deadline = android.os.SystemClock.elapsedRealtime() + SAS_TIMEOUT_MS
                            while (isActive &&
                                connectionGeneration.get() == gen &&
                                secureSessionManager.state.value == SecureSessionState.WAITING_USER_VERIFICATION
                            ) {
                                val remaining = ((deadline - android.os.SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0).toInt()
                                _sasRemainingSeconds.value = remaining
                                if (remaining <= 0) break
                                delay(1000L)
                            }
                            if (connectionGeneration.get() == gen &&
                                secureSessionManager.state.value == SecureSessionState.WAITING_USER_VERIFICATION
                            ) {
                                android.util.Log.w("TransceiverCoordinator", "SAS verification window expired (60s)")
                                _sasRemainingSeconds.value = 0
                                verifyRetryJob?.cancel(); verifyRetryJob = null
                                secureSessionManager.setHandshakeTimeout()
                                transportEngine.setAuthenticatedLivenessEnabled(false)
                                transportEngine.disconnect()
                            }
                        }
                    }
                    SecureSessionState.SECURE_VERIFIED -> {
                        com.itantra.core.service.OperationalForegroundService.startPeerConnection(context)
                        handshakeRetryJob?.cancel()
                        handshakeRetryJob = null
                        sasTimerJob?.cancel()
                        sasTimerJob = null
                        _sasRemainingSeconds.value = null
                        // Both users have accepted, but our confirmation may still be in flight.
                        // Keep its retries alive until authenticated peer traffic proves receipt.
                        transportEngine.setAuthenticatedLivenessEnabled(true)
                        startEncryptedHeartbeat()
                        val generation = connectionGeneration.get()
                        scope.launch {
                            if (connectionGeneration.get() != generation) return@launch
                            sendProfileHandshake()
                            sendCapabilities()
                            retryUnresolvedEmergencies()
                        }
                    }
                    SecureSessionState.NO_SESSION,
                    SecureSessionState.HANDSHAKE_TIMEOUT,
                    SecureSessionState.FAILED -> {
                        com.itantra.core.service.OperationalForegroundService.stopPeerConnection(context)
                        // The initial NO_SESSION emission can race CONNECTED. Keep the
                        // initial HELLO send/server timeout alive until the exchange starts.
                        if (state != SecureSessionState.NO_SESSION || !isConnected) {
                            handshakeRetryJob?.cancel()
                            handshakeRetryJob = null
                        }
                        verifyRetryJob?.cancel()
                        verifyRetryJob = null
                        sasTimerJob?.cancel()
                        sasTimerJob = null
                        _sasRemainingSeconds.value = null
                        cachedVerifyPacket = null
                        encryptedHeartbeatJob?.cancel()
                        encryptedHeartbeatJob = null
                        transportEngine.setAuthenticatedLivenessEnabled(false)
                    }
                    else -> Unit
                }
                if (state == SecureSessionState.FAILED) transportEngine.disconnect()
            }
        }

        scope.launch {
            sessionManager.activeLanguage.collect { lang ->
                if (isConnected && lang != null && secureSessionManager.state.value == SecureSessionState.SECURE_VERIFIED) {
                    sendCapabilities()
                }
            }
        }

        scope.launch {
            languagePackRepository.observeTargetLanguage().collect { lang ->
                currentTargetLanguage.value = lang
            }
        }

        scope.launch {
            transportEngine.receive().collect { packet ->
                handleIncomingPacket(packet)
            }
        }

        scope.launch {
            for (ack in ackQueue) {
                try {
                    sendForConnection(ack.generation, encryptForConnection(ack.generation,
                        ItantraPacket(PacketType.ACK, messageId = ack.messageId)))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // A retry of an accepted message can request another ACK. Never
                    // create an unbounded coroutine backlog when the link is slow.
                    android.util.Log.w("TransceiverCoord", "Could not send acceptance ACK")
                }
            }
        }

        scope.launch {
            while(true) {
                val nextPacket = synchronized(queueLock) {
                    if (messageQueue.isEmpty()) null
                    else {
                        // Priority ordering (descending), then FIFO
                        messageQueue.sortByDescending { it.packet.flags.toInt() }
                        messageQueue.removeAt(0).also { pendingPayloadBytes -= it.packet.payload.size }
                    }
                }
                if (nextPacket != null) {
                    try {
                        currentTtsJob = scope.launch {
                            processIncomingMessagePacket(nextPacket.packet, nextPacket.generation, nextPacket.peerId, nextPacket.emergencyEpoch)
                        }
                        currentTtsJob?.join()
                    } finally {
                        // Also covers a disconnect after dequeue but before the child starts.
                        preserveInterruptedIncoming(nextPacket)
                        currentTtsJob = null
                    }
                } else {
                    queueWakeup.receive() // wait for signal
                }
            }
        }
    }

    private fun addMessage(msg: TransceiverMessage) {
        synchronized(messageMutationLock) {
            // Preserve one row if disconnect and processing complete concurrently.
            if (_trashedMessages.value.any { it.messageId == msg.messageId }) return
            val existing = _messages.value.find { it.messageId == msg.messageId }
            if (existing != null && (existing.source != msg.source || existing.peerId != msg.peerId)) return
            _messages.update { messages ->
                if (existing == null) messages + msg
                else messages.map { if (it.messageId == msg.messageId) transitionMessage(it, msg) else it }
            }
            requestHistoryWrite()
        }
        updateAlertJob()
    }

    private fun updateMessage(id: Long, update: (TransceiverMessage) -> TransceiverMessage) {
        synchronized(messageMutationLock) {
            _messages.update { messages ->
                messages.map { if (it.messageId == id) transitionMessage(it, update(it)) else it }
            }
            requestHistoryWrite()
        }
        updateAlertJob()
    }

    // Call while holding messageMutationLock, including during startup reconciliation.
    private fun requestHistoryWrite() {
        if (messageDao != null) historyChanges.value += 1L
    }

    private fun transitionMessage(current: TransceiverMessage, proposed: TransceiverMessage): TransceiverMessage =
        if (current.state == MessageState.ACKNOWLEDGED) proposed.copy(state = MessageState.ACKNOWLEDGED)
        else if (proposed.state == MessageState.SENT && current.state in setOf(
            MessageState.DELIVERED, MessageState.REMOTE_PLAYING, MessageState.REMOTE_PLAYBACK_CONFIRMED
        )) proposed.copy(state = current.state)
        else proposed

    private fun acknowledgeRemoteCriticalMessages(peerId: String) {
        synchronized(messageMutationLock) {
            val unresolvedIds = _messages.value.filter {
                it.source == MessageSource.REMOTE &&
                    it.peerId == peerId &&
                    it.priority == com.itantra.domain.model.MessagePriority.CRITICAL &&
                    it.state != MessageState.ACKNOWLEDGED
            }.map { it.messageId }.toSet()
            _messages.update { messages -> messages.map {
                if (it.messageId in unresolvedIds) it.copy(state = MessageState.ACKNOWLEDGED) else it
            } }
            requestHistoryWrite()
        }
    }

    fun moveMessageToTrash(id: Long, now: Long = System.currentTimeMillis()): Boolean {
        synchronized(messageMutationLock) {
            val message = _messages.value.find { it.messageId == id } ?: return false
            if (!com.itantra.domain.model.RecycleBinPolicy.canTrash(message)) return false
            stopMessageSpeech(id)
            val deleted = message.copy(deletedAtMillis = now)
            _messages.value = _messages.value.filterNot { it.messageId == id }
            _trashedMessages.value = _trashedMessages.value + deleted
            requestHistoryWrite()
        }
        return true
    }

    fun restoreMessage(id: Long, now: Long = System.currentTimeMillis()): Boolean {
        synchronized(messageMutationLock) {
            val message = _trashedMessages.value.find { it.messageId == id } ?: return false
            if (!com.itantra.domain.model.RecycleBinPolicy.canRestore(message.deletedAtMillis!!, now)) return false
            val restored = message.copy(deletedAtMillis = null)
            _trashedMessages.value = _trashedMessages.value.filterNot { it.messageId == id }
            _messages.value = (_messages.value + restored).sortedBy { it.createdAtLocal }
            requestHistoryWrite()
        }
        // Restoration only changes local history. It never sends a packet or restarts playback.
        return true
    }

    fun deleteTrashedMessage(id: Long) {
        synchronized(messageMutationLock) {
            if (_trashedMessages.value.none { it.messageId == id }) return
            _trashedMessages.value = _trashedMessages.value.filterNot { it.messageId == id }
            requestHistoryWrite()
        }
    }

    fun purgeExpiredHistory(now: Long = System.currentTimeMillis()) {
        synchronized(messageMutationLock) {
            val expired = _trashedMessages.value.filter {
                !com.itantra.domain.model.RecycleBinPolicy.canRestore(it.deletedAtMillis!!, now)
            }
            _trashedMessages.value = _trashedMessages.value - expired.toSet()
            if (expired.isNotEmpty()) requestHistoryWrite()
        }
    }

    fun retryMessage(id: Long): Boolean {
        val message = _messages.value.find { it.messageId == id } ?: return false
        val peer = _activePeerProfile.value?.deviceId ?: return false
        if (message.source != MessageSource.LOCAL || message.state !in setOf(MessageState.ERROR, MessageState.SENT) || message.text.isBlank() ||
            message.priority == com.itantra.domain.model.MessagePriority.CRITICAL ||
            message.peerId.isBlank() || message.peerId != peer ||
            secureSessionManager.state.value != SecureSessionState.SECURE_VERIFIED ||
            message.translationStatus in setOf(com.itantra.domain.model.TranslationStatus.FAILED,
                com.itantra.domain.model.TranslationStatus.MODEL_MISSING, com.itantra.domain.model.TranslationStatus.UNSUPPORTED) ||
            (message.isVoiceGenerated && (message.rawPcmEquivalentBytes <= 0 || message.payloadBytes <= 0))) return false
        if (message.isLocation && (message.latitude == null || message.longitude == null ||
                message.accuracyMeters == null || message.locationTimestampMillis == null)) return false
        updateMessage(id) { it.copy(state = MessageState.TRANSMITTING, statusDetail = null) }
        if (message.isLocation) retrySavedLocation(message) else sendVoiceMessage(id, priority = message.priority)
        return true
    }

    private fun retrySavedLocation(message: TransceiverMessage) {
        val generation = connectionGeneration.get()
        scope.launch {
            try {
                val payload = LocationPayload(checkNotNull(message.latitude), checkNotNull(message.longitude),
                    checkNotNull(message.accuracyMeters), checkNotNull(message.locationTimestampMillis)).toBytes()
                val packet = ItantraPacket(PacketType.LOCATION, flags = message.priority.toByte(),
                    messageId = message.messageId, languageCode = LanguageCode.ENGLISH,
                    sourceLanguage = LanguageCode.ENGLISH, targetLanguage = LanguageCode.ENGLISH, payload = payload)
                val metrics = sendForConnection(generation, encryptForConnection(generation, packet))
                updateMessage(message.messageId) { it.copy(
                    state = if (metrics.transmissionLatencyMillis is com.itantra.domain.model.Measurement.Measured)
                        MessageState.DELIVERED else MessageState.SENT,
                    statusDetail = null) }
            } catch (e: Exception) {
                updateMessage(message.messageId) { it.copy(state = MessageState.ERROR, statusDetail = e.message ?: "Location retry failed") }
            }
        }
    }

    private fun updateAlertJob() {
        val hasUnack = _messages.value.any { it.source == MessageSource.REMOTE && it.priority == com.itantra.domain.model.MessagePriority.CRITICAL && it.state != MessageState.ACKNOWLEDGED }
        if (hasUnack && alertJob == null) {
            alertJob = scope.launch {
                var count = 0
                while (count < 3) {
                    delay(10_000)
                    val unack = _messages.value.filter { it.source == MessageSource.REMOTE && it.priority == com.itantra.domain.model.MessagePriority.CRITICAL && it.state != MessageState.ACKNOWLEDGED }
                    if (unack.isEmpty()) break
                    if (recordingJob != null || currentTtsJob?.isActive == true || isTtsPlaying || savedSpeechPlayback.value.busy) {
                        count++
                        continue
                    }
                    val msg = unack.first()
                    val playback = beginTtsPlayback()
                    try {
                        val req = SpeechSynthesisRequest(msg.displayedTextLanguage ?: LanguageCode.ENGLISH, msg.text, "alert")
                        if (ttsCapabilityProvider?.isTtsInstalled(req.languageCode) == true) {
                            val res = sessionManager.synthesizeTts(req)
                            com.itantra.core.inference.requireSpeechAudio(res)
                            val sink = SpeakerAudioSink(context)
                            alertAudioSink = sink
                            try {
                                sink.init(res.sampleRateHz, android.media.AudioAttributes.USAGE_ALARM, requestMaxVolume = true)
                                sink.play(res.pcmAudio)
                                sink.flushAndStop()
                            } finally { sink.release(); if (alertAudioSink === sink) alertAudioSink = null }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) { android.util.Log.w("TransceiverCoord", "Alert voice unavailable", e) }
                    finally {
                        endTtsPlayback(playback)
                    }
                    count++
                }
                alertJob = null
            }
        } else if (!hasUnack && alertJob != null) {
            alertJob?.cancel()
            alertJob = null
        }
    }

    private suspend fun sendCapabilities() {
        val generation = connectionGeneration.get()
        if (secureSessionManager.state.value != SecureSessionState.SECURE_VERIFIED) return

        // FIX 028: only advertise TTS for a language when that engine is actually loaded.
        // The previous code always advertised activeLanguage regardless of engine state,
        // allowing peers to translate into languages this device cannot speak.
        val ttsEngine = sessionManager.currentTtsEngine
        val ttsLangs = if (ttsEngine != null && ttsEngine.isLoaded) {
            listOf(ttsEngine.languageCode)
        } else {
            emptyList()
        }

        // FIX 029: only advertise STT for a language when that engine is actually loaded.
        // The previous code always advertised activeLanguage regardless of STT engine state.
        val sttEngine = sessionManager.currentSttEngine
        val sttLangs = if (sttEngine != null && sttEngine.isLoaded) {
            listOf(sttEngine.languageCode)
        } else {
            emptyList()
        }

        android.util.Log.d("TransceiverCoord", "sendCapabilities: sttLangs=$sttLangs, ttsLangs=$ttsLangs (from real engine state)")

        val sttMask = ProtocolLanguageMapper.toBitmask(sttLangs)
        val ttsMask = ProtocolLanguageMapper.toBitmask(ttsLangs)

        val payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putShort(sttMask)
            .putShort(ttsMask)
            .array()

        val packet = ItantraPacket(
            type = PacketType.CAPABILITIES,
            messageId = nextMessageId(),
            payload = payload
        )
        try {
            val securePacket = encryptForConnection(generation, packet)
            sendForConnection(generation, securePacket)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    internal fun handleIncomingPacket(packet: ItantraPacket) {
        synchronized(incomingPacketLock) { handleIncomingPacketLocked(packet) }
    }

    private fun handleIncomingPacketLocked(packet: ItantraPacket) {
        if (packet.receivedOn != null && (packet.receivedOn != sessionConnection ||
            packet.receivedOn != transportEngine.connectionToken)) return
        val generation = connectionGeneration.get()
        if (packet.type == PacketType.SECURE_HELLO) {
            val response = secureSessionManager.processSecureHello(packet)
            if (response != null) {
                val connection = sessionConnection
                scope.launch {
                    if (connectionGeneration.get() == generation) {
                        try { transportEngine.send(response, connection) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { android.util.Log.w("TransceiverCoordinator", "HELLO response failed: ${e.message}") }
                    }
                }
            }
            return
        }

        if (packet.type == PacketType.SECURE_VERIFY) {
            secureSessionManager.processSecureVerify(packet)
            return
        }

        // Only process application traffic if session is secure
        if (secureSessionManager.state.value != SecureSessionState.SECURE_VERIFIED) {
            // Drop packet before both parties confirm SAS
            return
        }

        val decryptedPacket = try {
            secureSessionManager.decrypt(packet)
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        // Every successfully authenticated and decrypted packet refreshes link liveness
        transportEngine.notifyLivenessReceived()

        if (cachedVerifyPacket != null) {
            // Only an authenticated packet can acknowledge confirmation delivery. Re-send
            // the introduction because an early one may have reached a still-verifying peer.
            cachedVerifyPacket = null
            verifyRetryJob?.cancel(); verifyRetryJob = null
            val gen = connectionGeneration.get()
            scope.launch {
                if (connectionGeneration.get() == gen) {
                    sendProfileHandshake()
                    sendCapabilities()
                }
            }
        }

        if (decryptedPacket.type == PacketType.HEARTBEAT) {
            // Heartbeat authenticated; do not create chat message, notification, or TTS
            return
        }

        if (decryptedPacket.type in setOf(PacketType.ACK, PacketType.HUMAN_ACK,
                PacketType.TTS_STARTED, PacketType.TTS_COMPLETED, PacketType.TTS_FAILED) &&
            !canAcceptReceipt(decryptedPacket.messageId, generation)) return

        when (decryptedPacket.type) {
            PacketType.PROFILE_HANDSHAKE -> {
                val payload = com.itantra.core.transport.packet.ProfilePayload.fromBytes(decryptedPacket.payload)
                if (payload != null) {
                    android.util.Log.i("TransceiverCoordinator", "Received validated peer profile")
                    val currentPeer = _activePeerProfile.value
                    // A profile rename is allowed; a new identity needs a new SAS-verified
                    // session. Otherwise a peer could impersonate another chat mid-session.
                    if (currentPeer != null && currentPeer.deviceId != payload.deviceId) return
                    val updated = (currentPeer ?: com.itantra.domain.model.PeerProfile(bluetoothAddress = "", displayName = payload.displayName))
                        .copy(
                            deviceId = payload.deviceId,
                            displayName = payload.displayName,
                            isConnected = true,
                            lastSeen = System.currentTimeMillis(),
                            activeLanguage = payload.supportedLanguages.firstOrNull()
                        )
                    _activePeerProfile.value = updated
                    // Persist only the identity received inside the verified encrypted session.
                    scope.launch(Dispatchers.IO) {
                        peerDao?.rememberVerifiedPeer(
                            com.itantra.data.db.PeerEntity(
                                deviceId = payload.deviceId,
                                displayName = payload.displayName.ifBlank { payload.deviceId },
                                lastSeenMillis = updated.lastSeen,
                            )
                        )
                    }

                    if (!hasSentHandshake) {
                        hasSentHandshake = true
                        scope.launch { sendProfileHandshake() }
                    }
                }
            }
            PacketType.ACK -> {
                transportEngine.notifyAckReceived(decryptedPacket.messageId)
                updateMessage(decryptedPacket.messageId) {
                    it.copy(state = MessageState.DELIVERED)
                }
                emergencyStore.recordTransportAck(decryptedPacket.messageId)
            }
            PacketType.CAPABILITIES -> {
                if (decryptedPacket.payload.size >= 4) {
                    val buffer = ByteBuffer.wrap(decryptedPacket.payload).order(ByteOrder.BIG_ENDIAN)
                    val sttMask = buffer.short
                    val ttsMask = buffer.short
                    _peerCapabilities.value = PeerCapabilities(
                        supportedStt = ProtocolLanguageMapper.fromBitmask(sttMask),
                        supportedTts = ProtocolLanguageMapper.fromBitmask(ttsMask)
                    )
                }
            }
            PacketType.TEXT, PacketType.EMERGENCY_CODE -> {
                if (decryptedPacket.flags.toInt() !in 0..com.itantra.domain.model.MessagePriority.CRITICAL ||
                    decryptedPacket.payload.size > 16 * 1024) return
                if (decryptedPacket.type == PacketType.EMERGENCY_CODE &&
                    (decryptedPacket.payload.size != 1 ||
                        com.itantra.domain.model.EmergencyCode.fromId(decryptedPacket.payload[0]) == null)) return
                if (rejectCollisionOrAckDuplicate(decryptedPacket, generation)) return

                // Dedicated ALL_CLEAR handling:
                // Terminal control packet that resolves emergency, stops siren immediately, and returns without creating EmergencyRecord.
                if (decryptedPacket.type == PacketType.EMERGENCY_CODE &&
                    decryptedPacket.payload.isNotEmpty() &&
                    decryptedPacket.payload[0] == com.itantra.domain.model.EmergencyCode.ALL_CLEAR.id) {

                    val peerId = _activePeerProfile.value?.deviceId?.takeIf { it.isNotBlank() } ?: return
                    emergencyEpoch.incrementAndGet()
                    synchronized(queueLock) {
                        messageQueue.removeAll { queued ->
                            val remove = queued.peerId == peerId && (queued.packet.type == PacketType.EMERGENCY_CODE ||
                                queued.packet.flags.toInt() == com.itantra.domain.model.MessagePriority.CRITICAL)
                            if (remove) pendingPayloadBytes -= queued.packet.payload.size
                            remove
                        }
                    }
                    val resolvedIds = emergencyStore.resolveRemoteEmergencies(peerId).map { it.messageId }.toSet()
                    acknowledgeRemoteCriticalMessages(peerId)
                    val activeAlert = _activeEmergencyAlert.value
                    val otherAlertsRemain = _messages.value.any {
                        it.source == MessageSource.REMOTE && it.peerId != peerId &&
                            it.priority == com.itantra.domain.model.MessagePriority.CRITICAL && it.state != MessageState.ACKNOWLEDGED
                    }
                    if ((activeAlert != null && activeAlert.messageId in resolvedIds) || (activeAlert == null && !otherAlertsRemain)) {
                        alertJob?.cancel()
                        alertJob = null
                        alertAudioSink?.stopImmediate()
                        playbackGeneration.incrementAndGet()
                        cooldownJob?.cancel()
                        isTtsPlaying = false
                        currentTtsJob?.cancel()
                        currentAudioSink?.stopImmediate()
                        currentAudioSink?.release()
                        currentAudioSink = null
                        _activeEmergencyAlert.value = emergencyStore.getUnresolvedRecords().lastOrNull { it.source == "REMOTE" }
                        val remaining = _activeEmergencyAlert.value
                        if (remaining == null) {
                            com.itantra.core.service.OperationalForegroundService.resolveEmergency(context)
                            if (continuousModeJob?.isActive == true) continuousListenEngine.resetAndResume()
                        } else {
                            com.itantra.core.service.OperationalForegroundService.triggerEmergency(context, remaining.resolvedPhrase)
                        }
                    }

                    val allClearMsg = TransceiverMessage(
                        messageId = decryptedPacket.messageId,
                        language = sessionManager.activeLanguage.value ?: LanguageCode.ENGLISH,
                        priority = com.itantra.domain.model.MessagePriority.NORMAL,
                        text = "ALL CLEAR — Sender's emergency resolved",
                        source = MessageSource.REMOTE,
                        createdAtLocal = System.currentTimeMillis(),
                        state = MessageState.DELIVERED,
                        peerId = peerId,
                        senderDeviceId = peerId,
                        receiverDeviceId = deviceProfileManager?.currentDeviceId.orEmpty()
                    )
                    addMessage(allClearMsg)
                    seenMessageIds.add(decryptedPacket.messageId)
                    ackQueue.trySend(PendingAck(decryptedPacket.messageId, generation))
                    return // Terminal return: do not enqueue into messageQueue, do not build EmergencyRecord!
                }

                // Full queues leave IDs retryable and do not report rejected work as delivered.
                if (!enqueueMessagePacketForPlayback(decryptedPacket)) return
                seenMessageIds.add(decryptedPacket.messageId)
                ackQueue.trySend(PendingAck(decryptedPacket.messageId, generation))
            }
            PacketType.HUMAN_ACK -> {
                updateMessage(decryptedPacket.messageId) {
                    it.copy(state = MessageState.ACKNOWLEDGED)
                }
                emergencyStore.recordHumanAck(decryptedPacket.messageId)
                if (_activeEmergencyAlert.value?.messageId == decryptedPacket.messageId) {
                    _activeEmergencyAlert.value = null
                    com.itantra.core.service.OperationalForegroundService.resolveEmergency(context)
                }
            }
            PacketType.TTS_STARTED -> {
                updateMessage(decryptedPacket.messageId) {
                    val rasc = (System.currentTimeMillis() - it.createdAtLocal).coerceAtLeast(0L)
                    it.copy(state = MessageState.REMOTE_PLAYING, remoteAudioStartConfMillis = rasc)
                }
            }
            PacketType.TTS_COMPLETED -> {
                var ttfa = 0L
                if (decryptedPacket.payload.size == 8) {
                    ttfa = ByteBuffer.wrap(decryptedPacket.payload).order(ByteOrder.BIG_ENDIAN).long
                }
                updateMessage(decryptedPacket.messageId) {
                    val fullEstimatedE2e = it.sttLatencyMillis + it.mtLatencyMillis + it.cryptoLatencyMillis + (it.rttMillis / 2) + ttfa
                    // Pass 4: Do not substitute an estimate into MetricsRecorder when cross-device start/end events do not exist.
                    it.copy(
                        state = MessageState.REMOTE_PLAYBACK_CONFIRMED,
                        peerTtfaMillis = ttfa,
                        estimatedE2eMillis = fullEstimatedE2e
                    )
                }
            }
            PacketType.TTS_FAILED -> {
                updateMessage(decryptedPacket.messageId) {
                    it.copy(state = MessageState.ERROR, statusDetail = "Peer received text but could not play speech")
                }
            }
            PacketType.LOCATION -> {
                handleIncomingLocationPacket(decryptedPacket)
            }
            else -> {}
        }
    }

    internal fun handleIncomingLocationPacket(packet: ItantraPacket) {
        val generation = connectionGeneration.get()
        if (rejectCollisionOrAckDuplicate(packet, generation)) return

        // 2. Reject malformed payloads (wrong length, latitude outside -90..90,
        //    longitude outside -180..180, NaN/Infinity values) without crashing.
        //    Log the rejection and drop the packet; do not show a bogus location.
        if (packet.payload.size != LocationPayload.PAYLOAD_SIZE) {
            android.util.Log.w(
                "TransceiverCoordinator",
                "Dropping malformed LOCATION packet ${packet.messageId}: payload size ${packet.payload.size} != expected ${LocationPayload.PAYLOAD_SIZE}"
            )
            return
        }

        val locationPayload = try {
            LocationPayload.fromBytes(packet.payload)
        } catch (e: Exception) {
            android.util.Log.w("TransceiverCoordinator", "Failed to decode LOCATION payload for message ${packet.messageId}", e)
            return
        }

        val lat = locationPayload.latitude
        val lon = locationPayload.longitude
        val acc = locationPayload.accuracyMeters
        val time = locationPayload.timestampMillis

        if (!com.itantra.core.location.isValidGpsCoordinates(lat, lon, acc)) {
            android.util.Log.w("TransceiverCoordinator", "Dropping malformed LOCATION packet ${packet.messageId}: invalid coordinates or accuracy")
            return
        }

        // 3. Check for timestamp disagreement: future drift > 5 min, <= 0, or older than 7 days.
        //    Do NOT drop: store and display the location with a visible "Time unverified" label.
        val now = System.currentTimeMillis()
        val maxFutureDriftMs = 5 * 60 * 1000L // 5 minutes
        val maxPastAgeMs = 7 * 24 * 60 * 60 * 1000L // 7 days
        val isTimeUnverified = (time <= 0L || time > now + maxFutureDriftMs || (now - time) > maxPastAgeMs)

        if (isTimeUnverified) {
            android.util.Log.w(
                "TransceiverCoordinator",
                "LOCATION packet ${packet.messageId} has suspect/stale timestamp $time (current time: $now). Storing with time-unverified flag."
            )
        }

        // 4. Store the received location as a message in the existing chat/message model
        //    marked as a location message with lat, lon, accuracy, timestamp and sender peer ID.
        val remoteDeviceId = _activePeerProfile.value?.deviceId ?: ""
        val activePeer = remoteDeviceId
        val myDeviceId = deviceProfileManager?.currentDeviceId ?: ""

        val displayText = "📍 Location: ${"%.5f".format(java.util.Locale.US, lat)}, ${"%.5f".format(java.util.Locale.US, lon)} (±${"%.1f".format(java.util.Locale.US, acc)}m)"

        val locationMsg = TransceiverMessage(
            messageId = packet.messageId,
            language = packet.languageCode ?: LanguageCode.ENGLISH,
            priority = packet.flags.toInt(),
            text = displayText,
            source = MessageSource.REMOTE,
            createdAtLocal = now,
            state = MessageState.DELIVERED,
            peerId = activePeer,
            senderDeviceId = remoteDeviceId,
            receiverDeviceId = myDeviceId,
            isVoiceGenerated = false,
            isLocation = true,
            latitude = lat,
            longitude = lon,
            accuracyMeters = acc,
            locationTimestampMillis = time,
            isTimeUnverified = isTimeUnverified,
            statusDetail = if (isTimeUnverified) "TIME_UNVERIFIED" else null
        )
        addMessage(locationMsg)
        seenMessageIds.add(packet.messageId)
        ackQueue.trySend(PendingAck(packet.messageId, generation))
    }

    private fun canAcceptReceipt(messageId: Long, generation: Long): Boolean {
        if (receiptBindings[messageId] != generation) return false
        val message = _messages.value.find { it.messageId == messageId && it.source == MessageSource.LOCAL }
            ?: return false
        val recipient = message.receiverDeviceId.ifBlank { message.peerId }
        return recipient.isBlank() || recipient == com.itantra.domain.model.BROADCAST_PEER_ID ||
            recipient == _activePeerProfile.value?.deviceId
    }

    /** Transport acceptance survives interrupted speech; no receipt goes to a replacement peer. */
    private fun preserveInterruptedIncoming(incoming: IncomingMessage) {
        if (!scope.isActive) return // Shutdown/wipe must never recreate private data.
        val packet = incoming.packet
        val code = if (packet.type == PacketType.EMERGENCY_CODE && packet.payload.size == 1)
            com.itantra.domain.model.EmergencyCode.fromId(packet.payload[0]) else null
        if ((code != null || packet.flags.toInt() == com.itantra.domain.model.MessagePriority.CRITICAL) &&
            incoming.emergencyEpoch != emergencyEpoch.get()) return // Already cleared by its sender.
        synchronized(messageMutationLock) {
            if ((_messages.value + _trashedMessages.value).any { it.messageId == packet.messageId }) return
            val language = if (code != null) currentReceiveLanguage.value ?: LanguageCode.ENGLISH
                else packet.targetLanguage ?: packet.languageCode ?: packet.sourceLanguage ?: LanguageCode.ENGLISH
            val text = if (code != null) com.itantra.domain.model.EmergencyPhraseResolver.resolve(code, language)
                else packet.payload.toString(Charsets.UTF_8)
            addMessage(TransceiverMessage(packet.messageId, language, priority = packet.flags.toInt(), text = text,
                source = MessageSource.REMOTE, createdAtLocal = System.currentTimeMillis(), state = MessageState.DELIVERED,
                peerId = incoming.peerId, senderDeviceId = incoming.peerId,
                receiverDeviceId = deviceProfileManager?.currentDeviceId.orEmpty(),
                statusDetail = "Received; speech interrupted. Use Play speech to listen again."))
            if (code != null) {
                val record = com.itantra.domain.model.EmergencyRecord(packet.messageId, code.name, "REMOTE", "LOCAL",
                    System.currentTimeMillis(), resolvedPhrase = text, peerId = incoming.peerId)
                emergencyStore.saveRecord(record)
                _activeEmergencyAlert.value = record
                com.itantra.core.service.OperationalForegroundService.triggerEmergency(context, text)
            }
        }
    }

    /** A duplicate is ACKed only if it was accepted from this peer; IDs are global in history. */
    private fun rejectCollisionOrAckDuplicate(packet: ItantraPacket, generation: Long): Boolean {
        val existing = (_messages.value + _trashedMessages.value).find { it.messageId == packet.messageId }
        if (existing != null && (existing.source != MessageSource.REMOTE ||
                existing.peerId != _activePeerProfile.value?.deviceId.orEmpty())) return true
        if (existing != null || seenMessageIds.contains(packet.messageId)) {
            ackQueue.trySend(PendingAck(packet.messageId, generation))
            return true
        }
        return false
    }

    internal fun enqueueMessagePacketForPlayback(packet: ItantraPacket): Boolean {
        val critical = packet.flags.toInt() == com.itantra.domain.model.MessagePriority.CRITICAL ||
            packet.type == PacketType.EMERGENCY_CODE
        val generation = connectionGeneration.get()
        val peer = _activePeerProfile.value?.deviceId.orEmpty()
        synchronized(queueLock) {
            val countLimit = MAX_PENDING_MESSAGES - if (critical) 0 else RESERVED_EMERGENCY_MESSAGES
            val byteLimit = MAX_PENDING_BYTES - if (critical) 0 else RESERVED_EMERGENCY_BYTES
            if (messageQueue.size >= countLimit || packet.payload.size > byteLimit - pendingPayloadBytes) return false
            messageQueue.add(IncomingMessage(packet, generation, peer, emergencyEpoch.get()))
            pendingPayloadBytes += packet.payload.size
        }
        // Incoming speech and emergency alerts always take precedence over manual replay.
        stopSavedSpeech()
        if (critical) {
            currentTtsJob?.cancel()
            currentAudioSink?.stopImmediate()
            currentAudioSink?.release()
            currentAudioSink = null
        }
        queueWakeup.trySend(Unit)
        return true
    }

    private suspend fun processIncomingMessagePacket(packet: ItantraPacket, generation: Long, receivedPeerId: String, receivedEmergencyEpoch: Long) {
        fun obsolete(): Boolean = generation != connectionGeneration.get() ||
            ((packet.type == PacketType.EMERGENCY_CODE || packet.flags.toInt() == com.itantra.domain.model.MessagePriority.CRITICAL) &&
                receivedEmergencyEpoch != emergencyEpoch.get())
        if (obsolete()) return
        stopSavedSpeech()
        savedSpeechPlayer.awaitStopped()
        alertJob?.cancel()
        alertAudioSink?.stopImmediate()
        alertJob?.join()
        alertJob = null
        val isEmergencyCode = packet.type == PacketType.EMERGENCY_CODE

        // Extract packet language metadata first, before building text or choosing local language.
        // These are needed to compute srcLang, which feeds the null-receive fallback.
        val rawSrcLang = packet.sourceLanguage ?: packet.languageCode
        val pktTargetLang = packet.targetLanguage ?: packet.languageCode

        // Automatic receive preserves the language actually carried by the packet,
        // including a translation already completed by the sender.
        val srcLang = rawSrcLang ?: pktTargetLang ?: LanguageCode.HINDI
        val payloadLanguage = pktTargetLang ?: srcLang
        val desiredReceive = currentReceiveLanguage.value ?: payloadLanguage
        val localLanguage = desiredReceive

        var text = if (isEmergencyCode && packet.payload.isNotEmpty()) {
            val code = com.itantra.domain.model.EmergencyCode.fromId(packet.payload[0])
            // Emergency code bypasses MT and resolves directly into receiver's active local language (Section N)
            if (code != null) com.itantra.domain.model.EmergencyPhraseResolver.resolve(code, localLanguage) else "Unknown Emergency"
        } else {
            String(packet.payload, Charsets.UTF_8)
        }

        val pktLang = pktTargetLang ?: localLanguage
        var textLanguage = if (isEmergencyCode) localLanguage else pktLang
        var translationStatus = com.itantra.domain.model.TranslationStatus.NONE
        var originalText: String? = null

        if (!isEmergencyCode) {
            // FIX 027: Determine whether receiver-side translation is needed.
            // Do NOT translate twice: if sender already translated to local language, consume directly.
            val alreadyTranslated = (packet.translationMode == com.itantra.domain.model.TranslationMode.DIRECT
                    && pktLang == localLanguage)

            if (pktLang == localLanguage || alreadyTranslated) {
                // Direct TTS: Same-language or already translated packet routes directly.
                textLanguage = localLanguage
                translationStatus = com.itantra.domain.model.TranslationStatus.BYPASSED
            } else {
                // Cross-language: attempt receiver-side recovery translation (source->local).
                // This handles the case where sender's MT failed or typed text arrived untranslated.
                android.util.Log.i("TransceiverCoord", "FIX 027: Attempting receiver-side translation $pktLang -> $localLanguage")
                val translationRes = try {
                    translationRouter.routeAndTranslate(text, pktLang, localLanguage)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    android.util.Log.e("TransceiverCoord", "Receiver-side translation exception", e)
                    null
                }
                if (translationRes != null && translationRes.isSuccessful && translationRes.translatedText.isNotBlank()) {
                    originalText = text
                    text = translationRes.translatedText
                    textLanguage = localLanguage
                    translationStatus = com.itantra.domain.model.TranslationStatus.SUCCESS
                    android.util.Log.i("TransceiverCoord", "Receiver-side translation SUCCESS charsIn=${originalText.length} charsOut=${text.length}")
                } else {
                    // Translation unavailable (model not installed, EXTERNAL BLOCKER etc.) —
                    // preserve the original text so user sees what arrived; preserve error explicitly.
                    textLanguage = pktLang
                    translationStatus = com.itantra.domain.model.TranslationStatus.FAILED
                    android.util.Log.w("TransceiverCoord", "FIX 027: Receiver-side translation FAILED: ${translationRes?.error}. Preserving transcript.")
                }
            }
        }

        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (obsolete()) return
        val remoteDeviceId = receivedPeerId
        val activePeer = remoteDeviceId
        val myDeviceId = deviceProfileManager?.currentDeviceId ?: ""

        val msg = TransceiverMessage(
            messageId = packet.messageId,
            language = textLanguage,
            targetLanguage = if (textLanguage == localLanguage) localLanguage else pktLang,
            priority = packet.flags.toInt(),
            text = text,
            originalText = originalText,
            translationStatus = translationStatus,
            source = MessageSource.REMOTE,
            createdAtLocal = System.currentTimeMillis(),
            state = MessageState.DELIVERED,
            peerId = activePeer,
            senderDeviceId = remoteDeviceId,
            receiverDeviceId = myDeviceId,
            // Voice origin is not represented in ITP v1. Priority is independent
            // of whether the sender typed or spoke the message.
            isVoiceGenerated = false
        )
        addMessage(msg)


        if (isEmergencyCode) {
            val code = if (packet.payload.isNotEmpty()) com.itantra.domain.model.EmergencyCode.fromId(packet.payload[0]) else null
            val emergencyRec = com.itantra.domain.model.EmergencyRecord(
                messageId = packet.messageId,
                emergencyCode = code?.name ?: "UNKNOWN",
                source = "REMOTE",
                target = "LOCAL",
                createdAt = System.currentTimeMillis(),
                retryStatus = com.itantra.domain.model.EmergencyRetryStatus.PENDING,
                humanAckStatus = false,
                resolvedPhrase = text,
                peerId = receivedPeerId
            )
            emergencyStore.saveRecord(emergencyRec)
            _activeEmergencyAlert.value = emergencyRec
            com.itantra.core.service.OperationalForegroundService.triggerEmergency(context, text)
        } else if (_activeEmergencyAlert.value != null) {
            // Application-level non-interruptible: normal message is delivered to history,
            // but does NOT preempt active emergency audio alert or clear alert state
            return
        }

        val isCritical = packet.flags.toInt() == com.itantra.domain.model.MessagePriority.CRITICAL || isEmergencyCode

        // ── RX_TTS TRACE ────────────────────────────────────────────────────
        // Step 1: Determine whether TTS for this language is installed on disk
        // and attempt to prepare the TTS engine before we evaluate canSpeak.
        // This is the fix for Root Cause #1: we no longer return early just
        // because the engine was null/unloaded at the moment the packet arrived.
        // ────────────────────────────────────────────────────────────────────
        val finalLanguage = textLanguage
        if (!isCritical) {
            val ttsInstalled = ttsCapabilityProvider?.isTtsInstalled(finalLanguage) ?: false
            if (com.example.itantra.BuildConfig.DEBUG) {
                android.util.Log.d(
                    "RX_TTS",
                    "RECEIVE_LANGUAGE=${localLanguage.name} " +
                    "FINAL_TEXT_LANGUAGE=${finalLanguage.name} " +
                    "TTS_LANGUAGE=${sessionManager.currentTtsEngine?.languageCode?.name} " +
                    "TTS_INSTALLED=$ttsInstalled"
                )
            }
            if (!ttsInstalled) {
                updateMessage(msg.messageId) {
                    it.copy(state = MessageState.DELIVERED,
                        statusDetail = "Voice unavailable: install ${finalLanguage.name.lowercase()} Receive (TTS) pack")
                }
                sendTtsFailed(packet.messageId, generation)
                return
            }
            try {
                sessionManager.ensureTts(finalLanguage)
                android.util.Log.d(
                    "RX_TTS",
                    "ensureTts complete: engine=${sessionManager.currentTtsEngine?.languageCode} " +
                    "loaded=${sessionManager.currentTtsEngine?.isLoaded}"
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                android.util.Log.w("RX_TTS", "ensureTts failed for $finalLanguage: ${e.message}")
            }
        }

        // Step 2: Re-evaluate canSpeak after preparation attempt
        val engine = sessionManager.currentTtsEngine
        val canSpeak = engine != null && engine.isLoaded && engine.languageCode == finalLanguage

        android.util.Log.d(
            "RX_TTS",
            "canSpeak=$canSpeak isCritical=$isCritical " +
            "engineExists=${engine != null} engineLoaded=${engine?.isLoaded} engineLang=${engine?.languageCode}"
        )

        // Step 3: If TTS is not available and message is not critical, record truthful status
        if (!canSpeak && !isCritical && !debugBeepWhenNoVoice) {
            val reason = when {
                engine == null -> "TTS_ENGINE_NOT_LOADED"
                !engine.isLoaded -> "TTS_ENGINE_NOT_LOADED"
                engine.languageCode != finalLanguage -> "TTS_LANGUAGE_MISMATCH"
                else -> "TTS_MODEL_NOT_INSTALLED"
            }
            android.util.Log.w("RX_TTS", "Voice unavailable for packet=${packet.messageId}: $reason")
            updateMessage(msg.messageId) {
                it.copy(state = MessageState.DELIVERED,
                    statusDetail = "Voice unavailable: ${finalLanguage.name.lowercase()} TTS could not load ($reason)")
            }
            sendTtsFailed(packet.messageId, generation)
            return
        }

        cancelActiveRecording()

        // Step 4: TTS Suppression — mute microphone and cancel cooldown before playback
        val playback = beginTtsPlayback()

        try {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val pcmAudio: FloatArray
            val sampleRate: Int

            if (engine != null && canSpeak) {
                // Step 5: Synthesize — TTS_STARTED sent ONLY after synthesis succeeds and PCM is valid
                val req = SpeechSynthesisRequest(
                    languageCode = engine.languageCode,
                    text = text,
                    correlationId = msg.messageId.toString()
                )
                android.util.Log.d("RX_TTS", "synthesisStart packet=${packet.messageId} lang=${engine.languageCode}")
                val result = sessionManager.synthesizeTts(req)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                com.itantra.core.inference.requireSpeechAudio(result)

                pcmAudio = result.pcmAudio
                sampleRate = result.sampleRateHz
                android.util.Log.d(
                    "RX_TTS",
                    "pcmSamples=${pcmAudio.size} sampleRate=$sampleRate"
                )

                // Step 7: TTS_STARTED — sent NOW, just before actual playback begins (truthful)
                updateMessage(msg.messageId) { it.copy(state = MessageState.REMOTE_PLAYING) }
                if (secureSessionManager.state.value == SecureSessionState.SECURE_VERIFIED) {
                    try {
                        val startedPkt = encryptForConnection(generation, ItantraPacket(PacketType.TTS_STARTED, messageId = packet.messageId))
                        scope.launch { sendForConnection(generation, startedPkt) }
                    } catch (e: Exception) {
                        android.util.Log.w("TransceiverCoord", "Could not send TTS_STARTED: ${e.message}")
                    }
                }
            } else if (isCritical) {
                // Safety-critical emergency tone: high-penetration multi-tone alarm (800Hz/1000Hz warble)
                // Guaranteed audible regardless of voice pack status
                sampleRate = 16000
                val durationSeconds = 2.0
                val numSamples = (sampleRate * durationSeconds).toInt()
                pcmAudio = FloatArray(numSamples) { i ->
                    val freq = if ((i / 4000) % 2 == 0) 800.0 else 1000.0
                    (Math.sin(2.0 * Math.PI * freq * i / sampleRate) * 0.7).toFloat()
                }
                // For critical tones send TTS_STARTED immediately
                updateMessage(msg.messageId) { it.copy(state = MessageState.REMOTE_PLAYING) }
                if (secureSessionManager.state.value == SecureSessionState.SECURE_VERIFIED) {
                    try {
                        val startedPkt = encryptForConnection(generation, ItantraPacket(PacketType.TTS_STARTED, messageId = packet.messageId))
                        scope.launch { sendForConnection(generation, startedPkt) }
                    } catch (e: Exception) {
                        android.util.Log.w("TransceiverCoord", "Could not send TTS_STARTED: ${e.message}")
                    }
                }
            } else {
                // Debug-only diagnostic tone
                sampleRate = 16000
                val durationSeconds = 3.0
                val numSamples = (sampleRate * durationSeconds).toInt()
                pcmAudio = FloatArray(numSamples) { i ->
                    (Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 0.3).toFloat()
                }
                updateMessage(msg.messageId) { it.copy(state = MessageState.REMOTE_PLAYING) }
            }

            val ttfaMillis = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000

            // Step 8: Audio route — USAGE_MEDIA for normal TTS (audible through speaker),
            // USAGE_ALARM only for safety-critical emergency audio.
            val sink = SpeakerAudioSink(context)
            currentAudioSink = sink
            val usage = if (isCritical) {
                android.media.AudioAttributes.USAGE_ALARM
            } else {
                android.media.AudioAttributes.USAGE_MEDIA
            }
            android.util.Log.d("RX_TTS", "audioRoute usage=$usage isCritical=$isCritical")
            sink.init(sampleRate, usage, requestMaxVolume = isCritical)
            sink.play(pcmAudio)
            sink.flushAndStop()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            android.util.Log.d("RX_TTS", "playbackCompleted=true framesTotal=${pcmAudio.size}")

            val usedVoice = (engine != null && canSpeak)
            val fallbackLabel = if (usedVoice) null else if (isCritical) "SAFETY ALARM TONE" else "TTS UNAVAILABLE — DIAGNOSTIC TONE"
            updateMessage(msg.messageId) {
                it.copy(
                    state = MessageState.REMOTE_PLAYBACK_CONFIRMED,
                    peerTtfaMillis = ttfaMillis,
                    statusDetail = fallbackLabel ?: it.statusDetail
                )
            }

            // Step 9: TTS_COMPLETED — sent only after full playback drain
            val payloadBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(ttfaMillis).array()
            if (secureSessionManager.state.value == SecureSessionState.SECURE_VERIFIED) {
                try {
                    val compPkt = encryptForConnection(generation, ItantraPacket(PacketType.TTS_COMPLETED, messageId = packet.messageId, payload = payloadBytes))
                    scope.launch { sendForConnection(generation, compPkt) }
                } catch (e: Exception) {
                    android.util.Log.w("TransceiverCoord", "Could not send TTS_COMPLETED: ${e.message}")
                }
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            // Interrupted by emergency preemption
            updateMessage(msg.messageId) { it.copy(state = MessageState.ERROR, statusDetail = "PLAYBACK_INTERRUPTED") }
        } catch (e: Exception) {
            val detail = "TTS_SYNTHESIS_FAILED: ${e.message}"
            android.util.Log.e("RX_TTS", detail, e)
            updateMessage(msg.messageId) { it.copy(state = MessageState.ERROR, statusDetail = detail) }
            sendTtsFailed(packet.messageId, generation)
        } finally {
            currentAudioSink?.release()
            currentAudioSink = null

            // Resume VAD after TTS
            endTtsPlayback(playback)
        }
    }

    /** Sends TTS_FAILED to the sender if session is secure. */
    private fun sendTtsFailed(messageId: Long, generation: Long) {
        if (secureSessionManager.state.value == SecureSessionState.SECURE_VERIFIED) {
            scope.launch {
                try {
                    val failPkt = encryptForConnection(generation, ItantraPacket(PacketType.TTS_FAILED, messageId = messageId))
                    sendForConnection(generation, failPkt)
                } catch (ex: Exception) {
                    android.util.Log.w("TransceiverCoord", "Could not send TTS_FAILED: ${ex.message}")
                }
            }
        }
    }

    fun setContinuousMode(enabled: Boolean) {
        if (enabled) {
            if (continuousModeJob != null) return
            if (!com.itantra.core.service.OperationalForegroundService.startContinuous(context)) return
            continuousModeJob = scope.launch {
                // Audio capture starts only after Android has accepted the foreground service.
                val ready = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    com.itantra.core.service.OperationalForegroundService.continuousReady.first { it }
                } == true
                if (!ready || continuousModeError.value != null) {
                    if (continuousModeError.value == null) com.itantra.core.service.OperationalForegroundService
                        .reportContinuousFailure("Continuous listening could not start: microphone service did not become ready. Check permissions and retry.")
                    com.itantra.core.service.OperationalForegroundService.stopContinuous(context)
                    continuousModeJob = null
                    return@launch
                }
                continuousListenEngine.start()
                launch {
                    try {
                        (microphoneFrames ?: audioSource.stream).collect { samples ->
                            if (recordingJob == null && currentTtsJob?.isActive != true && !isTtsPlaying && !savedSpeechPlayback.value.busy) {
                                continuousListenEngine.feedAudio(samples)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        com.itantra.core.service.OperationalForegroundService.reportContinuousFailure(
                            "Microphone unavailable: ${e.message ?: "check microphone access and retry"}")
                        com.itantra.core.service.OperationalForegroundService.stopContinuous(context)
                    }
                }
                launch {
                    continuousListenEngine.segmentEvents.collect { seg ->
                        processContinuousSegment(seg.samples)
                    }
                }
            }
        } else {
            com.itantra.core.service.OperationalForegroundService.stopContinuous(context)
            continuousModeJob?.cancel()
            continuousModeJob = null
            continuousListenEngine.stop()
        }
    }

    private fun processContinuousSegment(audio: FloatArray) {
        if (recordingJob != null || isTtsPlaying || savedSpeechPlayback.value.busy ||
            continuousModeJob?.isActive != true || sttProcessingCount.get() != 0) return
        val durationS = audio.size / 16000.0
        metricsRecorder.recordVadSegment(durationS, audio.size)

        val engine = sessionManager.currentSttEngine
        if (engine == null || !engine.isLoaded) {
            continuousListenEngine.stop()
            val lastMsg = _messages.value.lastOrNull()
            if (lastMsg != null && lastMsg.state == MessageState.ERROR && lastMsg.text == "STT Pack Required") {
                return
            }
            val msgId = nextMessageId()
            val sttLang = sessionManager.activeSttLanguage.value ?: LanguageCode.HINDI
            val targetLang = resolveTargetLanguage(sttLang)
            val msg = TransceiverMessage(
                messageId = msgId,
                language = sttLang,
                targetLanguage = targetLang,
                priority = com.itantra.domain.model.MessagePriority.NORMAL,
                text = "STT Pack Required",
                source = MessageSource.LOCAL,
                createdAtLocal = System.currentTimeMillis(),
                state = MessageState.ERROR
            )
            addMessage(msg)
            return
        }

        continuousListenEngine.pauseListening()

        val msgId = nextMessageId()
        val sttLang = sessionManager.activeSttLanguage.value ?: LanguageCode.HINDI
        val targetLang = resolveTargetLanguage(sttLang)
        val targetPreference = currentTargetLanguage.value
        val peerVoices = _peerCapabilities.value.supportedTts.toList()
        val activePeer = _activeConversationPeerId.value ?: _activePeerProfile.value?.deviceId.orEmpty()
        val msg = TransceiverMessage(
            messageId = msgId,
            language = sttLang,
            targetLanguage = targetLang,
            priority = com.itantra.domain.model.MessagePriority.NORMAL,
            text = "Recognizing...",
            source = MessageSource.LOCAL,
            createdAtLocal = System.currentTimeMillis(),
            state = MessageState.STT_PROCESSING,
            peerId = activePeer,
            senderDeviceId = deviceProfileManager?.currentDeviceId.orEmpty(),
            receiverDeviceId = activePeer,
            isVoiceGenerated = true,
        )
        addMessage(msg)

        sttProcessingCount.incrementAndGet()
        scope.launch {
            sttMutex.withLock {
                try {
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val result = sessionManager.recognizeCapture(engine, listOf(audio))
                    val latencyMillis = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
                    val durationMillis = (audio.size.toLong() * 1000) / 16000

                    metricsRecorder.recordSttInferenceTime(latencyMillis)
                    metricsRecorder.recordSttEndpointToFinalText(latencyMillis)
                    metricsRecorder.recordSttAudioDuration(durationMillis)

                    if (result.diagnostic != null) {
                        updateMessage(msgId) {
                            it.copy(
                                state = MessageState.ERROR,
                                text = "",
                                statusDetail = "Speech did not match selected ${result.languageCode.name.lowercase()} language \u2014 please retry"
                            )
                        }
                        return@launch
                    }

                    if (result.text.isBlank()) {
                        updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = "No speech recognized \u2014 try again") }
                        return@launch
                    }


                    if (secureSessionManager.state.value != SecureSessionState.SECURE_VERIFIED) {
                        updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = result.text, statusDetail = "Secure Link Required") }
                        return@launch
                    }

                    if (!isConnected) {
                        updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = result.text, statusDetail = "Peer disconnected") }
                        return@launch
                    }

                    val srcLang = result.languageCode
                    val targetLang = resolveTargetLanguage(srcLang, targetPreference, peerVoices)
                    var finalTxt = result.text
                    var origTxt: String? = null
                    var translationStatus = com.itantra.domain.model.TranslationStatus.BYPASSED
                    var failureDetail: String? = null

                    // No "Translating..." interim flash here: translation is either genuinely
                    // fast (real engine) or immediate (UnavailableTranslationEngine's instant
                    // no-op) - either way the flash added a moment that looked like the app
                    // was struggling with something, when there's nothing to wait on.
                    if (targetLang != srcLang) {
                        val isWhisperNativeTranslate = targetLang == LanguageCode.ENGLISH && (engine as? SherpaOnnxSpeechRecognizer)?.isTranslateMode == true
                        if (isWhisperNativeTranslate) {
                            android.util.Log.i("ITANTRA_MT_CALL", "Whisper native translation completed")
                            finalTxt = result.text
                            origTxt = null
                            translationStatus = com.itantra.domain.model.TranslationStatus.SUCCESS
                        } else {
                            val translationRes = translationRouter.routeAndTranslate(result.text, srcLang, targetLang)
                            if (translationRes.isSuccessful && translationRes.translatedText.isNotBlank()) {
                                finalTxt = translationRes.translatedText
                                origTxt = result.text
                                translationStatus = com.itantra.domain.model.TranslationStatus.SUCCESS
                            } else if (translationRes.error == "UNSUPPORTED_ROUTE" &&
                                (srcLang == LanguageCode.MALAYALAM || srcLang == LanguageCode.ODIA ||
                                 targetLang == LanguageCode.MALAYALAM || targetLang == LanguageCode.ODIA)) {
                                val unsupportedLang = when {
                                    targetLang == LanguageCode.MALAYALAM || targetLang == LanguageCode.ODIA -> targetLang
                                    srcLang == LanguageCode.MALAYALAM || srcLang == LanguageCode.ODIA -> srcLang
                                    else -> targetLang
                                }
                                val langName = if (unsupportedLang == LanguageCode.MALAYALAM) "Malayalam" else "Odia"
                                finalTxt = result.text
                                origTxt = null
                                translationStatus = com.itantra.domain.model.TranslationStatus.BYPASSED
                                failureDetail = "Original sent — no translation available for $langName"
                            } else {
                                // FIX 008: Do NOT silently fall through to sending original text when
                                // cross-language translation fails. Set state=ERROR so the user knows
                                // translation could not be delivered in the peer's language.
                                // The user can retransmit with an explicit "Send original anyway" action.
                                val errorDetail = when {
                                    translationRes.error == "NOT_INCLUDED_IN_BUILD" -> TRANSLATION_SCOPE_NOTE
                                    translationRes.error?.startsWith("MODEL_") == true -> "Translation blocked: ${translationRes.error}"
                                    else -> "Translation failed: ${translationRes.error ?: "Unknown"}"
                                }
                                updateMessage(msgId) {
                                    it.copy(
                                        state = MessageState.ERROR,
                                        text = result.text,
                                        translationStatus = com.itantra.domain.model.TranslationStatus.FAILED,
                                        statusDetail = errorDetail
                                    )
                                }
                                return@launch
                            }
                        }
                    }

                    val payload = finalTxt.toByteArray(Charsets.UTF_8)
                    val rawPcmEq = (durationMillis * 16000 * 2) / 1000

                    updateMessage(msgId) {
                        it.copy(
                            language = srcLang,
                            targetLanguage = targetLang,
                            state = MessageState.STT_COMPLETE,
                            text = finalTxt,
                            originalText = origTxt,
                            translationStatus = translationStatus,
                            statusDetail = failureDetail,
                            sttLatencyMillis = latencyMillis,
                            payloadBytes = payload.size,
                            rawPcmEquivalentBytes = rawPcmEq.toInt(),
                            speechDurationMillis = durationMillis
                        )
                    }

                    voiceNoteDao?.let { dao ->
                        scope.launch(Dispatchers.IO) {
                            dao.insert(com.itantra.data.db.VoiceNoteEntity(
                                transcribedText = result.text,
                                languageWireCode = srcLang.wireCode,
                                createdAtMillis = msg.createdAtLocal
                            ))
                        }
                    }

                    sendVoiceMessage(msgId, com.itantra.domain.model.MessagePriority.NORMAL)
                } catch (e: Exception) {
                    e.printStackTrace()
                    updateMessage(msgId) { it.copy(state = MessageState.ERROR, statusDetail = e.message ?: "Speech processing failed") }
                } finally {
                    sttProcessingCount.decrementAndGet()
                    resumeContinuousIfIdle()
                }
            }
        }
    }

    @Synchronized fun startRecording(isCritical: Boolean = false) {
        stopSavedSpeech()
        if (recordingJob != null) return
        // Half duplex: do not transcribe speech coming from this phone's speaker.
        if (isTtsPlaying || currentTtsJob?.isActive == true || _activeEmergencyAlert.value != null) return
        val engine = sessionManager.currentSttEngine
        val sttLang = sessionManager.activeSttLanguage.value ?: LanguageCode.HINDI
        if (engine == null || !engine.isLoaded) {
            val unavailableText = if (sessionManager.sessionState.value ==
                com.itantra.core.inference.LanguageSessionState.LOADING_STT) {
                "Speech model is loading — wait, then hold to talk again"
            } else {
                "Microphone model unavailable — select a mic language in Language Packs"
            }
            val lastMsg = _messages.value.lastOrNull()
            if (lastMsg != null && lastMsg.state == MessageState.ERROR && lastMsg.text == unavailableText) {
                return
            }
            val msgId = nextMessageId()
            addMessage(
                TransceiverMessage(
                    messageId = msgId,
                    language = sttLang,
                    targetLanguage = currentTargetLanguage.value,
                    priority = 0,
                    text = unavailableText,
                    source = MessageSource.LOCAL,
                    createdAtLocal = System.currentTimeMillis(),
                    state = MessageState.ERROR
                )
            )
            return
        }

        activeRecordingChunks.clear()
        recordingStartTime = SystemClock.elapsedRealtime()
        val msgId = nextMessageId()
        activeRecordingMessageId = msgId
        activeRecordingEngine = engine
        recordingTarget = currentTargetLanguage.value
        recordingPeerVoices = _peerCapabilities.value.supportedTts.toList()

        if (continuousModeJob?.isActive == true) {
            continuousListenEngine.pauseListening()
        }

        val targetLang = resolveTargetLanguage(engine.languageCode)
        val activePeer = _activeConversationPeerId.value ?: _activePeerProfile.value?.deviceId ?: ""
        val myDeviceId = deviceProfileManager?.currentDeviceId ?: ""

        val msg = TransceiverMessage(
            messageId = msgId,
            language = engine.languageCode,
            targetLanguage = targetLang,
            priority = if (isCritical) com.itantra.domain.model.MessagePriority.CRITICAL else com.itantra.domain.model.MessagePriority.NORMAL,
            text = "Listening...",
            source = MessageSource.LOCAL,
            createdAtLocal = System.currentTimeMillis(),
            state = MessageState.RECORDING,
            peerId = activePeer,
            senderDeviceId = myDeviceId,
            receiverDeviceId = activePeer,
            isVoiceGenerated = true
        )
        addMessage(msg)

        recordingJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val limitJob = launch {
                delay(60_000L) // 60s max
                stopRecording(msgId)
            }
            try {
                (microphoneFrames ?: audioSource.stream).collect { samples ->
                    if (samples.isNotEmpty()) {
                        synchronized(activeRecordingChunks) {
                            if (activeRecordingMessageId == msgId) activeRecordingChunks.add(samples.clone())
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                synchronized(activeRecordingChunks) {
                    if (activeRecordingMessageId == msgId) {
                        activeRecordingChunks.clear()
                        activeRecordingMessageId = 0L
                        activeRecordingEngine = null
                        recordingJob = null
                        updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = "Microphone unavailable",
                            statusDetail = e.message ?: "Check microphone permission and try again") }
                    }
                }
                resumeContinuousIfIdle()
            } finally {
                limitJob.cancel()
            }
        }
        recordingJob?.start()
    }

    @Synchronized fun processTestAudio(samples: FloatArray) {
        if (recordingJob != null) return
        val engine = sessionManager.currentSttEngine ?: return
        val msgId = nextMessageId()
        val targetLang = resolveTargetLanguage(engine.languageCode)
        val msg = TransceiverMessage(
            messageId = msgId,
            language = engine.languageCode,
            targetLanguage = targetLang,
            priority = com.itantra.domain.model.MessagePriority.NORMAL,
            text = "Recognizing...",
            source = MessageSource.LOCAL,
            createdAtLocal = System.currentTimeMillis(),
            state = MessageState.STT_PROCESSING
        )
        addMessage(msg)

        synchronized(activeRecordingChunks) {
            activeRecordingChunks.clear()
            activeRecordingChunks.add(samples)
        }
        recordingStartTime = SystemClock.elapsedRealtime()
        activeRecordingMessageId = msgId
        activeRecordingEngine = engine
        recordingTarget = currentTargetLanguage.value
        recordingPeerVoices = _peerCapabilities.value.supportedTts.toList()
        recordingJob = Job()
        stopRecording(msgId)
    }

    fun stopActiveRecording() {
        if (activeRecordingMessageId != 0L) {
            stopRecording(activeRecordingMessageId)
        }
    }

    @Synchronized fun cancelActiveRecording() {
        if (recordingJob == null) return
        val id = activeRecordingMessageId
        recordingJob?.cancel()
        recordingJob = null
        synchronized(activeRecordingChunks) {
            activeRecordingMessageId = 0L
            activeRecordingEngine = null
            activeRecordingChunks.forEach { it.fill(0f) }
            activeRecordingChunks.clear()
        }
        updateMessage(id) { it.copy(state = MessageState.ERROR, text = "Recording cancelled", statusDetail = "Cancelled before transcription or sending") }
        // Capture buffers are isolated from the recognizer until finalization.
        resumeContinuousIfIdle()
    }

    @Synchronized fun stopRecording(msgId: Long) {
        if (recordingJob == null || activeRecordingMessageId != msgId) return
        recordingJob?.cancel()
        recordingJob = null
        val engine = activeRecordingEngine
        val targetPreference = recordingTarget
        val peerVoices = recordingPeerVoices

        val chunksSnapshot = synchronized(activeRecordingChunks) {
            activeRecordingMessageId = 0L
            activeRecordingEngine = null
            val copy = activeRecordingChunks.toList()
            activeRecordingChunks.clear()
            copy
        }
        if (engine == null) {
            updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = "Mic language changed; record again") }
            resumeContinuousIfIdle()
            return
        }

        val totalSamples = chunksSnapshot.sumOf { it.size }
        val durationMillis = if (totalSamples > 0) {
            (totalSamples * 1000L) / 16000L
        } else {
            SystemClock.elapsedRealtime() - recordingStartTime
        }

        // Bumped from 200ms to 300ms - accidental taps/brushes on the PTT button were still
        // occasionally clearing this bar and reaching STT with near-empty audio.
        if (durationMillis < 300) {
            resumeContinuousIfIdle()
            // Cleanly discard micro-tap without leaving persistent error card in UI
            synchronized(messageMutationLock) {
                _messages.update { messages -> messages.filter { it.messageId != msgId } }
            }
            if (messageDao != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        messageDao.delete(msgId)
                    } catch (e: Throwable) {
                        // ignore
                    }
                }
            }
            return
        }

        // Microphone audio remains in memory; do not persist diagnostic WAV copies.

        var sumSquares = 0.0
        var totalSamplesCount = 0
        for (chunk in chunksSnapshot) {
            for (s in chunk) {
                sumSquares += (s * s)
                totalSamplesCount++
            }
        }
        val rms = if (totalSamplesCount > 0) Math.sqrt(sumSquares / totalSamplesCount).toFloat() else 0f
        val rmsDbfs = if (rms > 1e-9f) (20.0 * Math.log10(rms.toDouble())).toFloat() else -100f
        android.util.Log.i("TransceiverCoordinator", "VAD RMS check: samples=$totalSamplesCount, rms=$rms (${rmsDbfs} dBFS)")

        if (rms < 0.003f) {
            android.util.Log.i("TransceiverCoordinator", "Silence/low-energy detected (RMS=$rms, ${rmsDbfs} dBFS < 0.003 threshold). Short-circuiting STT.")
            resumeContinuousIfIdle()
            updateMessage(msgId) {
                it.copy(
                    state = MessageState.ERROR,
                    text = "No speech detected",
                    speechDurationMillis = durationMillis,
                    statusDetail = "Silence / Energy below threshold"
                )
            }
            return
        }

        updateMessage(msgId) { it.copy(state = MessageState.STT_PROCESSING, text = "Finalizing...") }

        sttProcessingCount.incrementAndGet()
        scope.launch {
            sttMutex.withLock {
                try {
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val result = sessionManager.recognizeCapture(engine, chunksSnapshot)
                    val latencyMillis = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000

                    // Preserved: full finalizeUtterance() latency (buffering + padding + decode) for end-to-end latency
                    metricsRecorder.recordSttInferenceTime(latencyMillis)
                    metricsRecorder.recordSttEndpointToFinalText(latencyMillis)
                    metricsRecorder.recordSttAudioDuration(durationMillis)

                    // Isolated: decode-only time used exclusively for STT RTF calculation
                    metricsRecorder.recordSttPureInferenceTime(result.pureInferenceMs)
                    if (durationMillis > 0) {
                        val rtf = result.pureInferenceMs.toDouble() / durationMillis.toDouble()
                        metricsRecorder.recordSttRealTimeFactor(rtf)
                    }

                    if (result.diagnostic != null) {
                        updateMessage(msgId) {
                            it.copy(
                                state = MessageState.ERROR,
                                text = "",
                                statusDetail = "Speech did not match selected ${result.languageCode.name.lowercase()} language \u2014 please retry"
                            )
                        }
                        return@withLock
                    }

                    if (result.text.isBlank()) {
                        updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = "No speech recognized \u2014 try again") }
                        return@withLock
                    }


                    val msg = _messages.value.find { it.messageId == msgId } ?: return@withLock

                    val priorityInt = if (msg.priority == com.itantra.domain.model.MessagePriority.CRITICAL) {
                        com.itantra.domain.model.MessagePriority.CRITICAL
                    } else {
                        com.itantra.domain.model.MessagePriority.NORMAL
                    }

                    val srcLang = result.languageCode
                    val targetLang = resolveTargetLanguage(srcLang, targetPreference, peerVoices)
                    var finalTxt = result.text
                    var origTxt: String? = null
                    var translationStatus = com.itantra.domain.model.TranslationStatus.BYPASSED
                    var failureDetail: String? = null

                    var mtLatency = 0L
                    android.util.Log.i("ITANTRA_MT_CALL", "Evaluating MT condition: targetLang=$targetLang, srcLang=$srcLang, msgId=$msgId")
                    if (targetLang != srcLang) {
                        val isWhisperNativeTranslate = targetLang == LanguageCode.ENGLISH && (engine as? SherpaOnnxSpeechRecognizer)?.isTranslateMode == true
                        if (isWhisperNativeTranslate) {
                            android.util.Log.i("ITANTRA_MT_CALL", "Whisper native translation completed")
                            finalTxt = result.text
                            origTxt = null
                            translationStatus = com.itantra.domain.model.TranslationStatus.SUCCESS
                        } else {
                            // No "Translating..." interim flash: with UnavailableTranslationEngine
                            // the result is immediate, and with a real engine the STT_PROCESSING
                            // state the message is already in covers the wait without implying a
                            // distinct, currently-failing "translating" step.
                            if (com.example.itantra.BuildConfig.DEBUG) android.util.Log.i("ITANTRA_MT_CALL", "BEFORE translation call: chars=${result.text.length}, srcLang=$srcLang, targetLang=$targetLang")
                            val tMt0 = SystemClock.elapsedRealtimeNanos()
                            val translationRes = translationRouter.routeAndTranslate(result.text, srcLang, targetLang)
                            if (com.example.itantra.BuildConfig.DEBUG) android.util.Log.i("ITANTRA_MT_CALL", "AFTER translation call: isSuccessful=${translationRes.isSuccessful}, charsOut=${translationRes.translatedText?.length ?: 0}, error=${translationRes.error}")
                            mtLatency = (SystemClock.elapsedRealtimeNanos() - tMt0) / 1_000_000

                            if (translationRes.isSuccessful && translationRes.translatedText.isNotBlank()) {
                                finalTxt = translationRes.translatedText
                                origTxt = result.text
                                translationStatus = com.itantra.domain.model.TranslationStatus.SUCCESS
                            } else if (translationRes.error == "UNSUPPORTED_ROUTE" &&
                                (srcLang == LanguageCode.MALAYALAM || srcLang == LanguageCode.ODIA ||
                                 targetLang == LanguageCode.MALAYALAM || targetLang == LanguageCode.ODIA)) {
                                val unsupportedLang = when {
                                    targetLang == LanguageCode.MALAYALAM || targetLang == LanguageCode.ODIA -> targetLang
                                    srcLang == LanguageCode.MALAYALAM || srcLang == LanguageCode.ODIA -> srcLang
                                    else -> targetLang
                                }
                                val langName = if (unsupportedLang == LanguageCode.MALAYALAM) "Malayalam" else "Odia"
                                finalTxt = result.text
                                origTxt = null
                                translationStatus = com.itantra.domain.model.TranslationStatus.BYPASSED
                                failureDetail = "Original sent — no translation available for $langName"
                            } else {
                                // FIX 008: Do NOT silently fall through to sending the original text
                                // when cross-language MT fails. Block transmission and set ERROR state.
                                // The user can explicitly choose "Send original anyway" if desired.
                                val errorDetail = when {
                                    translationRes.error == "NOT_INCLUDED_IN_BUILD" || translationRes.error == "ENGINE_NOT_LOADED" ->
                                        TRANSLATION_SCOPE_NOTE
                                    translationRes.error?.startsWith("MODEL_") == true ->
                                        "Translation blocked: ${translationRes.error}"
                                    else ->
                                        "Translation failed: ${translationRes.error ?: "Unknown"}"
                                }
                                updateMessage(msgId) {
                                    it.copy(
                                        state = MessageState.ERROR,
                                        text = result.text,
                                        translationStatus = com.itantra.domain.model.TranslationStatus.FAILED,
                                        mtLatencyMillis = mtLatency,
                                        statusDetail = errorDetail
                                    )
                                }
                                return@withLock
                            }
                        }
                    }

                    val payload = finalTxt.toByteArray(Charsets.UTF_8)
                    val rawPcmEq = (durationMillis * 16000 * 2) / 1000

                    updateMessage(msgId) {
                        it.copy(
                            language = srcLang,
                            targetLanguage = targetLang,
                            state = MessageState.STT_COMPLETE,
                            text = finalTxt,
                            originalText = origTxt,
                            translationStatus = translationStatus,
                            sttLatencyMillis = latencyMillis,
                            mtLatencyMillis = mtLatency,
                            payloadBytes = payload.size,
                            rawPcmEquivalentBytes = rawPcmEq.toInt(),
                            speechDurationMillis = durationMillis,
                            statusDetail = failureDetail
                        )
                    }

                    voiceNoteDao?.let { dao ->
                        scope.launch(Dispatchers.IO) {
                            dao.insert(com.itantra.data.db.VoiceNoteEntity(
                                transcribedText = result.text,
                                languageWireCode = srcLang.wireCode,
                                createdAtMillis = msg.createdAtLocal
                            ))
                        }
                    }

                    if (com.example.itantra.BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "TX_VOICE",
                            "STT_MODE=${if (sessionManager.isSttAutoDetect.value) "AUTO" else "MANUAL"} " +
                            "WHISPER_DETECTED=${result.languageCode.wireCode} " +
                            "RESOLVED_SOURCE_LANGUAGE=${result.languageCode.name} " +
                            "PACKET_SOURCE=${srcLang.name}"
                        )
                    }

                    if (isConnected) {
                        if (priorityInt == com.itantra.domain.model.MessagePriority.CRITICAL) {
                            updateMessage(msgId) { it.copy(state = MessageState.WAITING_USER_CONFIRMATION) }
                        } else {
                            sendVoiceMessage(msgId)
                        }
                    } else {
                        updateMessage(msgId) {
                            it.copy(
                                state = MessageState.ERROR,
                                text = finalTxt,
                                statusDetail = failureDetail ?: "Peer disconnected"
                            )
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    updateMessage(msgId) { it.copy(state = MessageState.ERROR, statusDetail = e.message ?: "Speech processing failed") }
                } finally {
                    sttProcessingCount.decrementAndGet()
                    resumeContinuousIfIdle()
                }
            }
        }
    }

    fun sendTextMessage(text: String, targetPeerId: String? = null, priority: Int = com.itantra.domain.model.MessagePriority.NORMAL) {
        if (text.isBlank()) return
        val generation = connectionGeneration.get()
        val activePeer = targetPeerId ?: _activeConversationPeerId.value ?: _activePeerProfile.value?.deviceId ?: ""
        val targetPreference = currentTargetLanguage.value
        val peerVoices = _peerCapabilities.value.supportedTts.toList()
        scope.launch {
            val msgId = nextMessageId()
            // Typed input follows the selected source, even when its mic model is
            // loading or only a different saved-message TTS voice is resident.
            val localLang = sessionManager.activeSttLanguage.value
                ?: languagePackRepository.observeActiveLanguage().first() ?: LanguageCode.ENGLISH
            // FIX 009: resolve target language with the same policy as voice — from user preference,
            // peer capability, or same-language bypass. Previously targetLanguage was always localLang
            // and translationMode=NONE, so typed text was never translated.
            val targetLang = resolveTargetLanguage(localLang, targetPreference, peerVoices)
            val myDeviceId = deviceProfileManager?.currentDeviceId ?: ""

            val msg = TransceiverMessage(
                messageId = msgId,
                language = localLang,
                targetLanguage = targetLang,
                priority = priority,
                text = text,
                source = MessageSource.LOCAL,
                createdAtLocal = System.currentTimeMillis(),
                state = MessageState.STT_PROCESSING,
                peerId = activePeer,
                senderDeviceId = myDeviceId,
                receiverDeviceId = activePeer,
                isVoiceGenerated = false
            )
            addMessage(msg)

            // FIX 009: run through TranslationRouter when target differs from source.
            var finalText = text
            var origText: String? = null
            var translationStatus = com.itantra.domain.model.TranslationStatus.BYPASSED
            var translationMode = com.itantra.domain.model.TranslationMode.NONE
            var payloadLang = localLang
            var mtLatency = 0L

            if (targetLang != localLang) {
                val tMt0 = SystemClock.elapsedRealtimeNanos()
                val translationRes = translationRouter.routeAndTranslate(text, localLang, targetLang)
                mtLatency = (SystemClock.elapsedRealtimeNanos() - tMt0) / 1_000_000

                if (translationRes.isSuccessful && translationRes.translatedText.isNotBlank()) {
                    finalText = translationRes.translatedText
                    origText = text
                    translationStatus = com.itantra.domain.model.TranslationStatus.SUCCESS
                    translationMode = com.itantra.domain.model.TranslationMode.DIRECT
                    payloadLang = targetLang
                    updateMessage(msgId) {
                        it.copy(
                            text = finalText,
                            originalText = origText,
                            translationStatus = translationStatus,
                            mtLatencyMillis = mtLatency
                        )
                    }
                } else if (translationRes.error == "UNSUPPORTED_ROUTE" &&
                    (localLang == LanguageCode.MALAYALAM || localLang == LanguageCode.ODIA ||
                     targetLang == LanguageCode.MALAYALAM || targetLang == LanguageCode.ODIA)) {
                    val unsupportedLang = when {
                        targetLang == LanguageCode.MALAYALAM || targetLang == LanguageCode.ODIA -> targetLang
                        localLang == LanguageCode.MALAYALAM || localLang == LanguageCode.ODIA -> localLang
                        else -> targetLang
                    }
                    val langName = if (unsupportedLang == LanguageCode.MALAYALAM) "Malayalam" else "Odia"
                    finalText = text
                    origText = null
                    translationStatus = com.itantra.domain.model.TranslationStatus.BYPASSED
                    translationMode = com.itantra.domain.model.TranslationMode.NONE
                    payloadLang = localLang
                    updateMessage(msgId) {
                        it.copy(
                            text = finalText,
                            originalText = origText,
                            translationStatus = translationStatus,
                            mtLatencyMillis = mtLatency,
                            statusDetail = "Original sent — no translation available for $langName"
                        )
                    }
                } else {
                    // FIX 009/008: Block transmission when cross-language MT fails.
                    val errorDetail = when {
                        translationRes.error == "NOT_INCLUDED_IN_BUILD" || translationRes.error == "ENGINE_NOT_LOADED" ->
                            TRANSLATION_SCOPE_NOTE
                        translationRes.error?.startsWith("MODEL_") == true ->
                            "Translation blocked: ${translationRes.error}"
                        else ->
                            "Translation failed: ${translationRes.error ?: "Unknown"}"
                    }
                    updateMessage(msgId) {
                        it.copy(
                            state = MessageState.ERROR,
                            translationStatus = com.itantra.domain.model.TranslationStatus.FAILED,
                            mtLatencyMillis = mtLatency,
                            statusDetail = errorDetail
                        )
                    }
                    return@launch
                }
            }

            if (activePeer.isNotBlank() && _activePeerProfile.value?.deviceId != activePeer) {
                updateMessage(msgId) { it.copy(state = MessageState.ERROR, statusDetail = "Reconnect this peer before sending") }
                return@launch
            }
            updateMessage(msgId) { it.copy(state = MessageState.TRANSMITTING) }

            val payload = finalText.toByteArray(Charsets.UTF_8)
            val packet = ItantraPacket(
                type = PacketType.TEXT,
                flags = priority.toByte(),
                messageId = msgId,
                languageCode = payloadLang,
                sourceLanguage = localLang,
                targetLanguage = payloadLang,
                translationMode = translationMode,
                payload = payload
            )

            try {
                val securePacket = encryptForConnection(generation, packet)
                val txMetrics = sendForConnection(generation, securePacket)
                val acknowledged = txMetrics.transmissionLatencyMillis is com.itantra.domain.model.Measurement.Measured
                val rtt = txMetrics.transmissionLatencyMillis.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0L }
                if (acknowledged) {
                    updateMessage(msgId) {
                        it.copy(
                            state = MessageState.DELIVERED,
                            rttMillis = rtt,
                            mtLatencyMillis = mtLatency,
                            packetBytes = txMetrics.packetBytes.let { b -> if (b is com.itantra.domain.model.Measurement.Measured) b.value else 0 }
                        )
                    }
                } else {
                    updateMessage(msgId) {
                        it.copy(
                            state = MessageState.SENT,
                            mtLatencyMillis = mtLatency,
                            packetBytes = txMetrics.packetBytes.let { b -> if (b is com.itantra.domain.model.Measurement.Measured) b.value else 0 }
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TransceiverCoordinator", "Error sending text message", e)
                updateMessage(msgId) { it.copy(state = MessageState.ERROR, statusDetail = e.message ?: "Failed to send") }
            }
        }
    }

    fun sendLocationMessage(
        targetPeerId: String? = null,
        priority: Int = com.itantra.domain.model.MessagePriority.NORMAL
    ) {
        val generation = connectionGeneration.get()
        val activePeer = targetPeerId ?: _activeConversationPeerId.value ?: _activePeerProfile.value?.deviceId ?: ""
        scope.launch {
            val msgId = nextMessageId()
            val myDeviceId = deviceProfileManager?.currentDeviceId ?: ""

            val initialMsg = TransceiverMessage(
                messageId = msgId,
                language = LanguageCode.ENGLISH,
                priority = priority,
                text = "📍 GPS: Acquiring satellite fix...",
                source = MessageSource.LOCAL,
                createdAtLocal = System.currentTimeMillis(),
                state = MessageState.PACKET_ENCODING,
                peerId = activePeer,
                senderDeviceId = myDeviceId,
                receiverDeviceId = activePeer,
                isVoiceGenerated = false
            )
            addMessage(initialMsg)

            val locationResult = try {
                val fix = locationProvider.getCurrentLocation()
                if (fix is LocationResult.Success && !com.itantra.core.location.isValidGpsCoordinates(
                        fix.latitude, fix.longitude, fix.accuracyMeters))
                    LocationResult.Failure.Error("GPS returned invalid coordinates") else fix
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LocationResult.Failure.Error(e.message ?: "Failed to acquire location")
            }

            when (locationResult) {
                is LocationResult.Success -> {
                    val lat = locationResult.latitude
                    val lon = locationResult.longitude
                    val acc = locationResult.accuracyMeters
                    val time = locationResult.timestampMillis

                    val displayText = "📍 Location: ${"%.5f".format(java.util.Locale.US, lat)}, ${"%.5f".format(java.util.Locale.US, lon)} (±${"%.1f".format(java.util.Locale.US, acc)}m)"
                    val payload = LocationPayload(lat, lon, acc, time).toBytes()

                    val packet = ItantraPacket(
                        type = PacketType.LOCATION,
                        flags = priority.toByte(),
                        messageId = msgId,
                        languageCode = LanguageCode.ENGLISH,
                        sourceLanguage = LanguageCode.ENGLISH,
                        targetLanguage = LanguageCode.ENGLISH,
                        translationMode = com.itantra.domain.model.TranslationMode.NONE,
                        payload = payload
                    )

                    updateMessage(msgId) {
                        it.copy(
                            text = displayText,
                            isLocation = true,
                            latitude = lat,
                            longitude = lon,
                            accuracyMeters = acc,
                            locationTimestampMillis = time,
                            payloadBytes = payload.size,
                            state = MessageState.TRANSMITTING
                        )
                    }

                    if (activePeer.isNotBlank() && _activePeerProfile.value?.deviceId != activePeer) {
                        updateMessage(msgId) { it.copy(state = MessageState.ERROR, statusDetail = "Reconnect this peer before sharing location") }
                        return@launch
                    }
                    try {
                        val securePacket = encryptForConnection(generation, packet)
                        val txMetrics = sendForConnection(generation, securePacket)
                        val acknowledged = txMetrics.transmissionLatencyMillis is com.itantra.domain.model.Measurement.Measured
                        val rtt = txMetrics.transmissionLatencyMillis.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0L }
                        if (acknowledged) {
                            updateMessage(msgId) {
                                it.copy(
                                    state = MessageState.DELIVERED,
                                    rttMillis = rtt,
                                    packetBytes = txMetrics.packetBytes.let { b -> if (b is com.itantra.domain.model.Measurement.Measured) b.value else 0 }
                                )
                            }
                        } else {
                            updateMessage(msgId) {
                                it.copy(
                                    state = MessageState.SENT,
                                    packetBytes = txMetrics.packetBytes.let { b -> if (b is com.itantra.domain.model.Measurement.Measured) b.value else 0 }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TransceiverCoordinator", "Error sending location packet", e)
                        updateMessage(msgId) {
                            it.copy(
                                state = MessageState.ERROR,
                                text = "$displayText (Failed to send)",
                                statusDetail = e.message ?: "Send failed"
                            )
                        }
                    }
                }
                is LocationResult.Failure -> {
                    val failureDetail = when (locationResult) {
                        is LocationResult.Failure.PermissionDenied -> "Location permission denied"
                        is LocationResult.Failure.ProviderDisabled -> "GPS is turned off"
                        is LocationResult.Failure.NoFixAvailable -> "GPS fix unavailable (no satellite lock)"
                        is LocationResult.Failure.Error -> locationResult.message
                    }
                    updateMessage(msgId) {
                        it.copy(
                            state = MessageState.ERROR,
                            text = "📍 Location share failed: $failureDetail",
                            statusDetail = failureDetail
                        )
                    }
                }
            }
        }
    }


    fun cancelMessage(msgId: Long) {
        updateMessage(msgId) { it.copy(state = MessageState.ERROR, statusDetail = "Cancelled") }
    }

    fun sendVoiceMessage(msgId: Long, priority: Int = com.itantra.domain.model.MessagePriority.NORMAL) {
        val generation = connectionGeneration.get()
        scope.launch {
            val msg = _messages.value.find { it.messageId == msgId } ?: return@launch
            if (msg.text.isBlank() || msg.state == MessageState.RECORDING || msg.state == MessageState.STT_PROCESSING) return@launch

            val sendPriority = if (msg.priority == com.itantra.domain.model.MessagePriority.CRITICAL) {
                com.itantra.domain.model.MessagePriority.CRITICAL
            } else {
                priority
            }

            updateMessage(msgId) { it.copy(state = MessageState.TRANSMITTING, priority = sendPriority) }
            val payload = msg.text.toByteArray(Charsets.UTF_8)
            val wasTranslated = msg.translationStatus == com.itantra.domain.model.TranslationStatus.SUCCESS
            val payloadLanguage = if (wasTranslated) (msg.targetLanguage ?: msg.language) else msg.language
            val packet = ItantraPacket(
                type = PacketType.TEXT,
                flags = sendPriority.toByte(),
                messageId = msgId,
                languageCode = payloadLanguage,
                sourceLanguage = msg.language,
                targetLanguage = payloadLanguage,
                translationMode = if (wasTranslated && payloadLanguage != msg.language) com.itantra.domain.model.TranslationMode.DIRECT else com.itantra.domain.model.TranslationMode.NONE,
                payload = payload
            )

            val maxAttempts = if (sendPriority == com.itantra.domain.model.MessagePriority.CRITICAL) 3 else 1
            var attempt = 0
            var success = false

            while (attempt < maxAttempts && !success) {
                attempt++
                if (msg.peerId.isNotBlank() && _activePeerProfile.value?.deviceId != msg.peerId) {
                    updateMessage(msgId) { it.copy(state = MessageState.ERROR, statusDetail = "Reconnect the original peer before sending") }
                    return@launch
                }
                try {
                    val tCrypto0 = SystemClock.elapsedRealtimeNanos()
                    val securePacket = encryptForConnection(generation, packet) // Encrypt inside loop to get a fresh nonce/counter each time
                    val cryptoLatency = (SystemClock.elapsedRealtimeNanos() - tCrypto0) / 1_000_000

                    metricsRecorder.recordCryptoMetrics(
                        com.itantra.domain.model.CryptoMetrics(
                            handshakeDurationMillis = com.itantra.domain.model.Measurement.Measured(secureSessionManager.lastHandshakeDurationMillis),
                            verificationDurationMillis = com.itantra.domain.model.Measurement.Measured(secureSessionManager.lastVerificationDurationMillis),
                            avgEncryptUs = com.itantra.domain.model.Measurement.Measured(secureSessionManager.encryptDurationUs),
                            avgDecryptUs = com.itantra.domain.model.Measurement.Measured(secureSessionManager.decryptDurationUs),
                            authFailures = secureSessionManager.authFailures,
                            replayRejections = secureSessionManager.replayRejections
                        )
                    )

                    val txMetrics = sendForConnection(generation, securePacket)
                    val acknowledged = txMetrics.transmissionLatencyMillis is com.itantra.domain.model.Measurement.Measured
                    val rtt = txMetrics.transmissionLatencyMillis.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0L }
                    val pBytes = txMetrics.packetBytes.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0 }
                    val semBytes = packet.payload.size
                    val secBytes = securePacket.payload.size
                    val frameBytes = txMetrics.finalFrameBytes.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0 }

                    if (acknowledged || sendPriority != com.itantra.domain.model.MessagePriority.CRITICAL) {
                        success = true
                        val estE2e = msg.sttLatencyMillis + msg.mtLatencyMillis + cryptoLatency + (rtt / 2)
                        if (acknowledged) {
                            updateMessage(msgId) {
                                it.copy(
                                    state = MessageState.DELIVERED,
                                    cryptoLatencyMillis = cryptoLatency,
                                    packetBytes = pBytes,
                                    semanticBytes = semBytes,
                                    secureBytes = secBytes,
                                    finalFrameBytes = frameBytes,
                                    rttMillis = rtt,
                                    estimatedE2eMillis = estE2e
                                )
                            }
                        } else {
                            updateMessage(msgId) {
                                it.copy(
                                    state = MessageState.SENT,
                                    cryptoLatencyMillis = cryptoLatency,
                                    packetBytes = pBytes,
                                    semanticBytes = semBytes,
                                    secureBytes = secBytes,
                                    finalFrameBytes = frameBytes,
                                    estimatedE2eMillis = estE2e
                                )
                            }
                        }
                        metricsRecorder.recordTransmission(txMetrics)
                    } else {
                        // Failed to get ACK for CRITICAL, retry if we have attempts left
                        if (attempt < maxAttempts) {
                            delay(1000)
                        } else {
                            updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = "${msg.text} (Failed to deliver)") }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (e is com.itantra.core.transport.packet.PayloadTooLargeException) {
                        updateMessage(msgId) { it.copy(state = MessageState.ERROR, statusDetail = e.message) }
                        return@launch
                    }
                    if (attempt == maxAttempts) {
                        updateMessage(msgId) { it.copy(state = MessageState.ERROR, statusDetail = e.message ?: "Encryption failed") }
                    } else {
                        delay(1000)
                    }
                }
            }
        }
    }

    /**
     * Sends encrypted HEARTBEAT every 15s. Only active while SECURE_VERIFIED.
     * Called once after session becomes SECURE_VERIFIED; idempotent because previous job
     * is cancelled on each disconnect or state reset.
     */
    private fun startEncryptedHeartbeat() {
        val generation = connectionGeneration.get()
        encryptedHeartbeatJob?.cancel()
        encryptedHeartbeatJob = scope.launch {
            while (isActive &&
                isConnected &&
                secureSessionManager.state.value == SecureSessionState.SECURE_VERIFIED) {
                delay(ENCRYPTED_HEARTBEAT_INTERVAL_MS)
                if (!isActive ||
                    !isConnected ||
                    secureSessionManager.state.value != SecureSessionState.SECURE_VERIFIED) break
                try {
                    val heartbeat = ItantraPacket(
                        type = PacketType.HEARTBEAT,
                        messageId = nextMessageId()
                    )
                    val encrypted = encryptForConnection(generation, heartbeat)
                    sendForConnection(generation, encrypted)
                } catch (e: Exception) {
                    android.util.Log.w("TransceiverCoordinator", "Encrypted heartbeat failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Confirms SAS match, caches SECURE_VERIFY, and retries the same packet every 2s
     * until authenticated peer traffic proves that the peer received our confirmation.
     * Local SECURE_VERIFIED must not cancel the final outbound confirmation.
     */
    fun confirmPeerVerification() {
        if (secureSessionManager.localSasConfirmed) return
        val gen = connectionGeneration.get()
        verifyRetryJob?.cancel()
        verifyRetryJob = scope.launch {
            val verifyPacket = cachedVerifyPacket ?: run {
                try {
                    val pkt = secureSessionManager.confirmSasMatch()
                    cachedVerifyPacket = pkt
                    pkt
                } catch (e: IllegalStateException) {
                    android.util.Log.e("TransceiverCoordinator", "confirmPeerVerification: ${e.message}")
                    return@launch
                }
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + SAS_TIMEOUT_MS
            while (isActive &&
                connectionGeneration.get() == gen &&
                cachedVerifyPacket === verifyPacket &&
                secureSessionManager.state.value in setOf(SecureSessionState.WAITING_USER_VERIFICATION, SecureSessionState.SECURE_VERIFIED) &&
                android.os.SystemClock.elapsedRealtime() < deadline
            ) {
                try {
                    sendForConnection(gen, verifyPacket)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("TransceiverCoordinator", "SECURE_VERIFY send failed: ${e.message}")
                }
                delay(VERIFY_RETRY_INTERVAL_MS)
            }
            if (connectionGeneration.get() == gen && cachedVerifyPacket === verifyPacket &&
                secureSessionManager.state.value == SecureSessionState.SECURE_VERIFIED) {
                secureSessionManager.setHandshakeTimeout()
                transportEngine.disconnect()
            }
        }
    }

    /**
     * Rejects SAS verification, terminates timer and retry jobs, and disconnects transport link.
     */
    fun rejectPeerVerification() {
        sasTimerJob?.cancel(); sasTimerJob = null
        verifyRetryJob?.cancel(); verifyRetryJob = null
        _sasRemainingSeconds.value = null
        cachedVerifyPacket = null
        secureSessionManager.rejectSas()
        scope.launch {
            transportEngine.setAuthenticatedLivenessEnabled(false)
            transportEngine.disconnect()
        }
    }

    var emergencyPlaybackVolume: Int = 100
        private set
    var emergencyRequireConfirmation: Boolean = true
        private set
    var emergencyOverrideSilent: Boolean = true
        private set
    var emergencyTtsAnnounce: Boolean = true
        private set
    var vadSensitivity: Int = 2
        private set
    var noiseSuppressionDb: Int = 18
        private set

    fun attachSettings(repository: com.example.itantra.data.settings.SettingsRepository) {
        scope.launch {
            repository.settings.collect { settings ->
                emergencyPlaybackVolume = settings.emergencyPlaybackVolume
                emergencyRequireConfirmation = settings.emergencyRequireConfirmation
                emergencyOverrideSilent = settings.emergencyOverrideSilent
                emergencyTtsAnnounce = settings.emergencyTtsAnnounce
                vadSensitivity = settings.vadSensitivity
                noiseSuppressionDb = settings.noiseSuppressionDb
                if (deviceProfileManager?.updateDisplayName(settings.operatorName) == true) {
                    // A verified peer should see name edits without reconnecting.
                    sendProfileHandshake()
                }
            }
        }
    }

    fun sendEmergencyCode(code: com.itantra.domain.model.EmergencyCode) {
        val generation = connectionGeneration.get()
        scope.launch {
            val msgId = nextMessageId()
            val peerId = _activeConversationPeerId.value?.takeIf { it.isNotBlank() }
                ?: _activePeerProfile.value?.deviceId?.takeIf { it.isNotBlank() }
                ?: com.itantra.domain.model.BROADCAST_PEER_ID
            val localId = deviceProfileManager?.currentDeviceId ?: ""
            val text = com.itantra.domain.model.EmergencyPhraseResolver.resolve(code, sessionManager.activeLanguage.value)
            val msg = TransceiverMessage(
                messageId = msgId,
                language = sessionManager.activeLanguage.value,
                priority = com.itantra.domain.model.MessagePriority.CRITICAL,
                text = text,
                source = MessageSource.LOCAL,
                createdAtLocal = System.currentTimeMillis(),
                state = MessageState.TRANSMITTING,
                peerId = peerId,
                senderDeviceId = localId,
                receiverDeviceId = peerId
            )
            addMessage(msg)

            val record = com.itantra.domain.model.EmergencyRecord(
                messageId = msgId,
                emergencyCode = code.name,
                source = "LOCAL",
                target = "BROADCAST",
                createdAt = System.currentTimeMillis(),
                retryStatus = com.itantra.domain.model.EmergencyRetryStatus.PENDING,
                retryCount = 0,
                resolvedPhrase = text,
                peerId = peerId
            )
            emergencyStore.saveRecord(record)

            val packet = ItantraPacket(
                type = PacketType.EMERGENCY_CODE,
                flags = com.itantra.domain.model.MessagePriority.CRITICAL.toByte(),
                messageId = msgId,
                languageCode = msg.language,
                sourceLanguage = msg.language,
                targetLanguage = msg.language,
                translationMode = com.itantra.domain.model.TranslationMode.NONE,
                payload = byteArrayOf(code.id)
            )

            var attempt = 0
            var success = false

            while (attempt < 3 && !success) {
                attempt++
                try {
                    val tCrypto0 = SystemClock.elapsedRealtimeNanos()
                    val securePacket = encryptForConnection(generation, packet)
                    val cryptoLatency = (SystemClock.elapsedRealtimeNanos() - tCrypto0) / 1_000_000

                    metricsRecorder.recordCryptoMetrics(
                        com.itantra.domain.model.CryptoMetrics(
                            handshakeDurationMillis = com.itantra.domain.model.Measurement.Measured(secureSessionManager.lastHandshakeDurationMillis),
                            verificationDurationMillis = com.itantra.domain.model.Measurement.Measured(secureSessionManager.lastVerificationDurationMillis),
                            avgEncryptUs = com.itantra.domain.model.Measurement.Measured(secureSessionManager.encryptDurationUs),
                            avgDecryptUs = com.itantra.domain.model.Measurement.Measured(secureSessionManager.decryptDurationUs),
                            authFailures = secureSessionManager.authFailures,
                            replayRejections = secureSessionManager.replayRejections
                        )
                    )

                    val txMetrics = sendForConnection(generation, securePacket)
                    val acknowledged = txMetrics.transmissionLatencyMillis is com.itantra.domain.model.Measurement.Measured
                    val rtt = txMetrics.transmissionLatencyMillis.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0L }
                    val pBytes = txMetrics.packetBytes.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0 }
                    val semBytes = packet.payload.size
                    val secBytes = securePacket.payload.size
                    val frameBytes = txMetrics.finalFrameBytes.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0 }

                    if (acknowledged) {
                        success = true
                        emergencyStore.recordTransportAck(msgId)
                        val estE2e = cryptoLatency + (rtt / 2)
                        updateMessage(msgId) {
                            it.copy(
                                state = MessageState.DELIVERED,
                                cryptoLatencyMillis = cryptoLatency,
                                packetBytes = pBytes,
                                semanticBytes = semBytes,
                                secureBytes = secBytes,
                                finalFrameBytes = frameBytes,
                                rttMillis = rtt,
                                estimatedE2eMillis = estE2e
                            )
                        }
                        metricsRecorder.recordTransmission(txMetrics)
                    } else {
                        emergencyStore.recordRetryAttempt(msgId, false, System.currentTimeMillis())
                        if (attempt < 3) delay(1000)
                        else updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = "Failed to deliver SOS") }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    emergencyStore.recordRetryAttempt(msgId, false, System.currentTimeMillis())
                    if (attempt == 3) {
                        updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = "Failed to send SOS") }
                    } else {
                        delay(1000)
                    }
                }
            }
        }
    }

    fun sendHumanAck(msgId: Long) {
        val generation = connectionGeneration.get()
        scope.launch {
            updateMessage(msgId) { it.copy(state = MessageState.ACKNOWLEDGED) }
            emergencyStore.recordHumanAck(msgId)
            if (_activeEmergencyAlert.value?.messageId == msgId) {
                _activeEmergencyAlert.value = null
                com.itantra.core.service.OperationalForegroundService.resolveEmergency(context)
            }
            val packet = ItantraPacket(
                type = PacketType.HUMAN_ACK,
                messageId = msgId,
                payload = ByteArray(0)
            )
            try {
                val originalPeer = _messages.value.firstOrNull { it.messageId == msgId }?.peerId
                if (!originalPeer.isNullOrBlank() && originalPeer != _activePeerProfile.value?.deviceId) return@launch
                val securePacket = encryptForConnection(generation, packet)
                sendForConnection(generation, securePacket)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun resolveActiveEmergency() {
        val alert = _activeEmergencyAlert.value ?: return
        _activeEmergencyAlert.value = null
        emergencyStore.recordHumanAck(alert.messageId)
        com.itantra.core.service.OperationalForegroundService.resolveEmergency(context)
    }

    private suspend fun retryUnresolvedEmergencies() {
        val generation = connectionGeneration.get()
        val unresolved = emergencyStore.getUnresolvedRecords().filter { it.canRetry && it.source == "LOCAL" }
        for (rec in unresolved) {
            val code = com.itantra.domain.model.EmergencyCode.values().find { it.name == rec.emergencyCode } ?: continue
            try {
                val packet = ItantraPacket(
                    type = PacketType.EMERGENCY_CODE,
                    flags = com.itantra.domain.model.MessagePriority.CRITICAL.toByte(),
                    messageId = rec.messageId,
                    payload = byteArrayOf(code.id)
                )
                val securePacket = encryptForConnection(generation, packet)
                val txMetrics = sendForConnection(generation, securePacket)
                val acknowledged = txMetrics.transmissionLatencyMillis is com.itantra.domain.model.Measurement.Measured
                val rtt = txMetrics.transmissionLatencyMillis.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0L }
                val ok = acknowledged
                emergencyStore.recordRetryAttempt(rec.messageId, ok, System.currentTimeMillis())
                if (ok) {
                    emergencyStore.recordTransportAck(rec.messageId)
                }
            } catch (e: Exception) {
                emergencyStore.recordRetryAttempt(rec.messageId, false, System.currentTimeMillis())
            }
        }
    }

    private suspend fun encryptForConnection(generation: Long, packet: ItantraPacket): ItantraPacket {
        check(connectionGeneration.get() == generation && sessionConnection == transportEngine.connectionToken) {
            "Connection changed; retry on the original peer"
        }
        val encrypted = secureSessionManager.encrypt(packet)
        check(connectionGeneration.get() == generation && sessionConnection == transportEngine.connectionToken) {
            "Connection changed while encrypting"
        }
        return encrypted
    }

    private suspend fun sendForConnection(generation: Long, packet: ItantraPacket): com.itantra.domain.model.TransmissionMetrics {
        val connection = sessionConnection
        check(connectionGeneration.get() == generation && connection == transportEngine.connectionToken) {
            "Connection changed before sending"
        }
        if (packet.type in setOf(PacketType.TEXT, PacketType.EMERGENCY_CODE, PacketType.LOCATION)) {
            val message = _messages.value.find { it.messageId == packet.messageId && it.source == MessageSource.LOCAL }
                ?: error("Outgoing message is no longer available")
            val recipient = message.receiverDeviceId.ifBlank { message.peerId }
            check(recipient.isBlank() || recipient == com.itantra.domain.model.BROADCAST_PEER_ID ||
                recipient == _activePeerProfile.value?.deviceId) { "Connect to the original recipient before sending" }
            synchronized(receiptBindings) {
                receiptBindings[packet.messageId] = generation
                while (receiptBindings.size > MAX_RECEIPT_BINDINGS) receiptBindings.remove(receiptBindings.keys.first())
            }
        }
        return transportEngine.send(packet, connection)
    }

    fun shutdown() {
        stopSavedSpeech()
        currentAudioSink?.stopImmediate()
        alertAudioSink?.stopImmediate()
        continuousListenEngine.stop()
        scope.cancel()
    }

    suspend fun shutdownForWipe() {
        stopSavedSpeech()
        val job = scope.coroutineContext[kotlinx.coroutines.Job]
        job?.cancel()
        job?.join()
        ackQueue.cancel()
        receiptBindings.clear()
        synchronized(queueLock) {
            messageQueue.clear()
            pendingPayloadBytes = 0
        }
        continuousListenEngine.stop()
        synchronized(activeRecordingChunks) {
            activeRecordingChunks.forEach { it.fill(0f) }
            activeRecordingChunks.clear()
        }
        _messages.value = emptyList()
        _trashedMessages.value = emptyList()
        _activePeerProfile.value = null
        emergencyStore.clearMemoryForWipe()
        secureSessionManager.resetSession()
    }
}
