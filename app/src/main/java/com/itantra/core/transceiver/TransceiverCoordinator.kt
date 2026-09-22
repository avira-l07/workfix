package com.itantra.core.transceiver

import android.os.SystemClock
import android.provider.Settings
import com.itantra.core.audio.SpeakerAudioSink
import com.itantra.core.crypto.SecureSessionManager
import com.itantra.core.translation.TRANSLATION_SCOPE_NOTE
import com.itantra.core.crypto.SecureSessionState
import android.content.Context
import com.itantra.core.inference.ActiveLanguageSessionManager
import com.itantra.core.inference.ContinuousListenEngine
import com.itantra.core.inference.ContinuousListenState
import com.itantra.core.inference.MicrophoneAudioSource
import com.itantra.core.metrics.MetricsRecorder
import com.itantra.core.transport.ConnectionState
import com.itantra.core.transport.TransportEngine
import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.core.transport.packet.PacketType
import com.itantra.core.transport.packet.ProtocolLanguageMapper
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.MessageSource
import com.itantra.domain.model.MessageState
import com.itantra.domain.model.SpeechSynthesisRequest
import com.itantra.domain.model.TransceiverMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
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
    val deviceProfileManager: com.itantra.core.profile.DeviceProfileManager? = null
) {
    companion object {
        private const val ENCRYPTED_HEARTBEAT_INTERVAL_MS = 15_000L
        private const val HELLO_RETRY_INTERVAL_MS = 1000L
        private const val HELLO_FINAL_GRACE_MS = 1500L
        private const val VERIFY_RETRY_INTERVAL_MS = 1000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val deviceIdSalt: Long by lazy {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "0"
        (androidId.hashCode().toLong() and 0xFFF)
    }

    private fun nextMessageId(): Long =
        (System.currentTimeMillis() shl 12) or deviceIdSalt

    private val _messages = MutableStateFlow<List<TransceiverMessage>>(emptyList())
    val messages: StateFlow<List<TransceiverMessage>> = _messages.asStateFlow()

    private val _activePeerProfile = MutableStateFlow<com.itantra.domain.model.PeerProfile?>(null)
    val activePeerProfile: StateFlow<com.itantra.domain.model.PeerProfile?> = _activePeerProfile.asStateFlow()

    private val _activeConversationPeerId = MutableStateFlow<String?>(null)
    val activeConversationPeerId: StateFlow<String?> = _activeConversationPeerId.asStateFlow()

    fun setActiveConversation(peerId: String?) {
        _activeConversationPeerId.value = peerId
    }

    private var hasSentHandshake = false

    suspend fun sendProfileHandshake() {
        if (secureSessionManager.state.value != SecureSessionState.SECURE_VERIFIED) return
        val profile = deviceProfileManager?.profile?.value
        val devId = profile?.deviceId ?: "IT-0000-0000"
        val name = profile?.displayName ?: "iTantra Operator"
        val activeLang = sessionManager.activeLanguage.value ?: LanguageCode.ENGLISH

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
            val securePacket = secureSessionManager.encrypt(packet)
            transportEngine.send(securePacket)
            android.util.Log.i("TransceiverCoordinator", "Sent PROFILE_HANDSHAKE: id=$devId, name=$name")
        } catch (e: Exception) {
            android.util.Log.e("TransceiverCoordinator", "Error sending PROFILE_HANDSHAKE", e)
        }
    }

    init {
        if (messageDao != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val persisted = messageDao.getAll().map { it.toDomain() }
                    if (persisted.isNotEmpty()) {
                        val settled = persisted.map { m ->
                            when {
                                m.priority == com.itantra.domain.model.MessagePriority.CRITICAL -> m
                                m.source == MessageSource.LOCAL && m.state in setOf(
                                    MessageState.RECORDING,
                                    MessageState.STT_PROCESSING,
                                    MessageState.STT_COMPLETE,
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
                            dbWriteMutex.withLock {
                                messageDao.insert(com.itantra.data.db.MessageEntity.fromDomain(it))
                            }
                        }
                        val loadedIds = settled.map { it.messageId }.toSet()
                        _messages.value = settled + _messages.value.filter { it.messageId !in loadedIds }
                        updateAlertJob()
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("TransceiverCoord", "Error loading persisted messages", e)
                }
            }
        }
    }

    private val _peerCapabilities = MutableStateFlow(PeerCapabilities())
    val peerCapabilities: StateFlow<PeerCapabilities> = _peerCapabilities.asStateFlow()

    private val messageQueue = mutableListOf<ItantraPacket>()
    private val queueMutex = Mutex()
    private val queueWakeup = Channel<Unit>(Channel.CONFLATED)

    private val sttMutex = Mutex()

    private val dbWriteMutex = Mutex()

    private var cooldownJob: Job? = null
    private var currentTtsJob: Job? = null
    private var currentAudioSink: SpeakerAudioSink? = null
    private var alertJob: Job? = null

    // FIX 012: Retry jobs for handshake and verification phases
    private var handshakeRetryJob: Job? = null
    private var verifyRetryJob: Job? = null
    private var encryptedHeartbeatJob: Job? = null

    private var isConnected = false
    private var recordingJob: Job? = null
    private val audioSource = MicrophoneAudioSource(scope)
    private var recordingStartTime = 0L
    private var activeRecordingMessageId = 0L
    private val activeRecordingChunks = java.util.Collections.synchronizedList(mutableListOf<FloatArray>())

    val continuousListenEngine = ContinuousListenEngine(context)
    private var continuousModeJob: Job? = null
    private var isTtsPlaying = false

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
    private fun resolveTargetLanguage(sourceLanguage: LanguageCode): LanguageCode {
        currentTargetLanguage.value?.let { return it }

        // 1. If peer advertises support for the same language, route same-language
        if (_peerCapabilities.value.supportedTts.contains(sourceLanguage)) {
            return sourceLanguage
        }

        // 2. If peer advertises a different language, route to that peer's language
        val peerDifferent = _peerCapabilities.value.supportedTts.firstOrNull { it != sourceLanguage }
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
                    isConnected = true
                    // Start handshake if connected. Only the client (initiator) sends the first HELLO.
                    // Responder remains in NO_SESSION, ready to process incoming SECURE_HELLO.
                    val isInitiator = !transportEngine.isServer
                    if (isInitiator) {
                        // Send SECURE_HELLO immediately and retry up to 3 times (1s apart)
                        // if still in HANDSHAKING state. On exhaustion mark HANDSHAKE_TIMEOUT.
                        handshakeRetryJob?.cancel()
                        handshakeRetryJob = scope.launch {
                            val hello = secureSessionManager.startHandshake(isInitiator = true)
                            transportEngine.send(hello)
                            var attempts = 1
                            while (attempts < 3 &&
                                secureSessionManager.state.value == SecureSessionState.HANDSHAKING) {
                                delay(HELLO_RETRY_INTERVAL_MS)
                                if (secureSessionManager.state.value == SecureSessionState.HANDSHAKING) {
                                    android.util.Log.w(
                                        "TransceiverCoordinator",
                                        "SECURE_HELLO retry attempt $attempts"
                                    )
                                    val stored = secureSessionManager.getStoredHello()
                                    if (stored != null) transportEngine.send(stored)
                                    attempts++
                                }
                            }
                            if (secureSessionManager.state.value == SecureSessionState.HANDSHAKING) {
                                delay(HELLO_FINAL_GRACE_MS)
                            }
                            // Exhausted retries — mark timeout so UI can show an error
                            if (secureSessionManager.state.value == SecureSessionState.HANDSHAKING) {
                                android.util.Log.e(
                                    "TransceiverCoordinator",
                                    "SECURE_HELLO handshake timeout after $attempts attempts"
                                )
                                secureSessionManager.setHandshakeTimeout()
                                transportEngine.setAuthenticatedLivenessEnabled(false)
                                transportEngine.disconnect()
                            }
                        }
                    }
                } else if (!connected && isConnected) {
                    isConnected = false
                    handshakeRetryJob?.cancel(); handshakeRetryJob = null
                    verifyRetryJob?.cancel(); verifyRetryJob = null
                    encryptedHeartbeatJob?.cancel(); encryptedHeartbeatJob = null
                    transportEngine.setAuthenticatedLivenessEnabled(false)
                    _peerCapabilities.value = PeerCapabilities()
                    _activePeerProfile.value = null
                    secureSessionManager.resetSession()
                    seenMessageIds.clear()
                }
            }
        }

        scope.launch {
            secureSessionManager.state.collect { state ->
                when (state) {
                    SecureSessionState.WAITING_USER_VERIFICATION -> {
                        handshakeRetryJob?.cancel()
                        handshakeRetryJob = null
                    }
                    SecureSessionState.SECURE_VERIFIED -> {
                        handshakeRetryJob?.cancel()
                        handshakeRetryJob = null
                        verifyRetryJob?.cancel()
                        verifyRetryJob = null
                        transportEngine.setAuthenticatedLivenessEnabled(true)
                        startEncryptedHeartbeat()
                    }
                    SecureSessionState.NO_SESSION,
                    SecureSessionState.HANDSHAKE_TIMEOUT,
                    SecureSessionState.FAILED -> {
                        encryptedHeartbeatJob?.cancel()
                        encryptedHeartbeatJob = null
                        transportEngine.setAuthenticatedLivenessEnabled(false)
                    }
                    else -> Unit
                }
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
            while(true) {
                val nextPacket = queueMutex.withLock {
                    if (messageQueue.isEmpty()) null
                    else {
                        // Priority ordering (descending), then FIFO
                        messageQueue.sortByDescending { it.flags.toInt() }
                        messageQueue.removeAt(0)
                    }
                }
                if (nextPacket != null) {
                    currentTtsJob = scope.launch {
                        processIncomingMessagePacket(nextPacket)
                    }
                    currentTtsJob?.join()
                    currentTtsJob = null
                } else {
                    queueWakeup.receive() // wait for signal
                }
            }
        }
    }

    private fun addMessage(msg: TransceiverMessage) {
        _messages.value = _messages.value + msg
        updateAlertJob()
        if (messageDao != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    dbWriteMutex.withLock {
                        messageDao.insert(com.itantra.data.db.MessageEntity.fromDomain(msg))
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("TransceiverCoord", "Error persisting message", e)
                }
            }
        }
    }

    private fun updateMessage(id: Long, update: (TransceiverMessage) -> TransceiverMessage) {
        var updatedMsg: TransceiverMessage? = null
        _messages.value = _messages.value.map {
            if (it.messageId == id) {
                val u = update(it)
                updatedMsg = u
                u
            } else it
        }
        updateAlertJob()
        if (messageDao != null && updatedMsg != null) {
            val u = updatedMsg!!
            scope.launch(Dispatchers.IO) {
                try {
                    dbWriteMutex.withLock {
                        messageDao.insert(com.itantra.data.db.MessageEntity.fromDomain(u))
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("TransceiverCoord", "Error updating persisted message", e)
                }
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
                    val msg = unack.first()
                    try {
                        cooldownJob?.cancel()
                        isTtsPlaying = true
                        continuousListenEngine.pauseListening()

                        val text = "Attention. " + msg.text
                        val req = SpeechSynthesisRequest(msg.language ?: LanguageCode.ENGLISH, text, "alert")
                        val res = sessionManager.currentTtsEngine?.synthesize(req)
                        if (res != null) {
                            val sink = SpeakerAudioSink(context)
                            sink.init(res.sampleRateHz, android.media.AudioAttributes.USAGE_ALARM, requestMaxVolume = true)
                            sink.play(res.pcmAudio)
                            sink.flushAndStop()
                            sink.release()
                        }
                    } catch (e: Exception) {}
                    finally {
                        cooldownJob = scope.launch {
                            delay(300)
                            isTtsPlaying = false
                            if (continuousModeJob?.isActive == true) {
                                continuousListenEngine.resetAndResume()
                            }
                        }
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
            val securePacket = secureSessionManager.encrypt(packet)
            transportEngine.send(securePacket)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    internal fun handleIncomingPacket(packet: ItantraPacket) {
        if (packet.type == PacketType.SECURE_HELLO) {
            val response = secureSessionManager.processSecureHello(packet)
            if (response != null) {
                scope.launch { transportEngine.send(response) }
            }
            return
        }

        if (packet.type == PacketType.SECURE_VERIFY) {
            secureSessionManager.processSecureVerify(packet)
            if (secureSessionManager.state.value == SecureSessionState.SECURE_VERIFIED) {
                // Cancel any ongoing verify retry — we're done
                verifyRetryJob?.cancel(); verifyRetryJob = null
                handshakeRetryJob?.cancel(); handshakeRetryJob = null
                transportEngine.setAuthenticatedLivenessEnabled(true)
                startEncryptedHeartbeat()
                scope.launch {
                    sendProfileHandshake()
                    sendCapabilities()
                    retryUnresolvedEmergencies()
                }
            }
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

        if (decryptedPacket.type == PacketType.HEARTBEAT) {
            // Heartbeat authenticated; do not create chat message, notification, or TTS
            return
        }

        when (decryptedPacket.type) {
            PacketType.PROFILE_HANDSHAKE -> {
                val payload = com.itantra.core.transport.packet.ProfilePayload.fromBytes(decryptedPacket.payload)
                if (payload != null) {
                    android.util.Log.i("TransceiverCoordinator", "Received PROFILE_HANDSHAKE: id=${payload.deviceId}, name=${payload.displayName}, v=${payload.protocolVersion}")
                    val currentPeer = _activePeerProfile.value
                    val updated = (currentPeer ?: com.itantra.domain.model.PeerProfile(bluetoothAddress = "", displayName = payload.displayName))
                        .copy(
                            deviceId = payload.deviceId,
                            displayName = payload.displayName,
                            isConnected = true,
                            lastSeen = System.currentTimeMillis(),
                            activeLanguage = payload.supportedLanguages.firstOrNull()
                        )
                    _activePeerProfile.value = updated

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
                // Immediate ACK through established secure session
                scope.launch {
                    try {
                        val ackPkt = secureSessionManager.encrypt(ItantraPacket(PacketType.ACK, messageId = decryptedPacket.messageId))
                        transportEngine.send(ackPkt)
                    } catch (e: Exception) {
                        android.util.Log.w("TransceiverCoord", "Could not send immediate ACK: ${e.message}")
                    }
                }

                val isDuplicate = seenMessageIds.contains(decryptedPacket.messageId) ||
                        _messages.value.any { it.messageId == decryptedPacket.messageId && it.source == MessageSource.REMOTE }

                if (isDuplicate) {
                    // Re-ACK has already been transmitted above. Drop duplicate without processing twice.
                    return
                }
                seenMessageIds.add(decryptedPacket.messageId)

                // Dedicated ALL_CLEAR handling:
                // Terminal control packet that resolves emergency, stops siren immediately, and returns without creating EmergencyRecord.
                if (decryptedPacket.type == PacketType.EMERGENCY_CODE &&
                    decryptedPacket.payload.isNotEmpty() &&
                    decryptedPacket.payload[0] == com.itantra.domain.model.EmergencyCode.ALL_CLEAR.id) {

                    alertJob?.cancel()
                    alertJob = null
                    isTtsPlaying = false
                    currentTtsJob?.cancel()
                    currentAudioSink?.stopImmediate()
                    currentAudioSink?.release()
                    currentAudioSink = null

                    _activeEmergencyAlert.value = null
                    com.itantra.core.service.OperationalForegroundService.resolveEmergency(context)
                    emergencyStore.resolveAllEmergencies()

                    if (continuousModeJob?.isActive == true) {
                        continuousListenEngine.resetAndResume()
                    }

                    val allClearMsg = TransceiverMessage(
                        messageId = decryptedPacket.messageId,
                        language = sessionManager.activeLanguage.value ?: LanguageCode.ENGLISH,
                        priority = com.itantra.domain.model.MessagePriority.NORMAL,
                        text = "ALL CLEAR — Emergency Resolved",
                        source = MessageSource.REMOTE,
                        createdAtLocal = System.currentTimeMillis(),
                        state = MessageState.DELIVERED
                    )
                    addMessage(allClearMsg)
                    return // Terminal return: do not enqueue into messageQueue, do not build EmergencyRecord!
                }

                enqueueMessagePacketForPlayback(decryptedPacket)
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
                    it.copy(state = MessageState.ERROR)
                }
            }
            else -> {}
        }
    }

    internal fun enqueueMessagePacketForPlayback(packet: ItantraPacket) {
        scope.launch {
            queueMutex.withLock {
                messageQueue.add(packet)
            }
            if (packet.flags.toInt() == com.itantra.domain.model.MessagePriority.CRITICAL || packet.type == PacketType.EMERGENCY_CODE) {
                currentTtsJob?.cancel()
                currentAudioSink?.stopImmediate()
                currentAudioSink?.release()
                currentAudioSink = null
            }
            queueWakeup.trySend(Unit)
        }
    }

    private suspend fun processIncomingMessagePacket(packet: ItantraPacket) {
        val isEmergencyCode = packet.type == PacketType.EMERGENCY_CODE
        val localLanguage = sessionManager.activeLanguage.value ?: LanguageCode.ENGLISH
        var text = if (isEmergencyCode && packet.payload.isNotEmpty()) {
            val code = com.itantra.domain.model.EmergencyCode.fromId(packet.payload[0])
            // Emergency code bypasses MT and resolves directly into receiver's active local language (Section N)
            if (code != null) com.itantra.domain.model.EmergencyPhraseResolver.resolve(code, localLanguage) else "Unknown Emergency"
        } else {
            String(packet.payload, Charsets.UTF_8)
        }

        val pktLang = packet.targetLanguage ?: packet.languageCode ?: localLanguage
        val srcLang = packet.sourceLanguage ?: packet.languageCode ?: pktLang
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
                android.util.Log.i("TransceiverCoord", "FIX 027: Attempting receiver-side translation $srcLang -> $localLanguage for packet $pktLang")
                val translationRes = try {
                    translationRouter.routeAndTranslate(text, srcLang, localLanguage)
                } catch (e: Exception) {
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

        val remoteDeviceId = _activePeerProfile.value?.deviceId ?: ""
        val activePeer = _activeConversationPeerId.value?.takeIf { it.isNotBlank() } ?: remoteDeviceId
        val myDeviceId = deviceProfileManager?.currentDeviceId ?: ""

        val msg = TransceiverMessage(
            messageId = packet.messageId,
            language = packet.languageCode,
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
            isVoiceGenerated = isEmergencyCode || packet.flags.toInt() != 0
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
                resolvedPhrase = text
            )
            emergencyStore.saveRecord(emergencyRec)
            _activeEmergencyAlert.value = emergencyRec
            com.itantra.core.service.OperationalForegroundService.triggerEmergency(context, text)
        } else if (_activeEmergencyAlert.value != null) {
            // Application-level non-interruptible: normal message is delivered to history,
            // but does NOT preempt active emergency audio alert or clear alert state
            return
        }

        val engine = sessionManager.currentTtsEngine
        val isCritical = packet.flags.toInt() == com.itantra.domain.model.MessagePriority.CRITICAL || isEmergencyCode

        val canSpeak = engine != null && engine.isLoaded && engine.languageCode == textLanguage

        if (!canSpeak && !isCritical && !debugBeepWhenNoVoice) {
            updateMessage(msg.messageId) {
                it.copy(state = MessageState.DELIVERED, statusDetail = com.itantra.domain.model.VOICE_OUTPUT_UNAVAILABLE_NOTE)
            }
            return
        }

        if (secureSessionManager.state.value == SecureSessionState.SECURE_VERIFIED) {
            try {
                val startedPkt = secureSessionManager.encrypt(ItantraPacket(PacketType.TTS_STARTED, messageId = packet.messageId))
                scope.launch { transportEngine.send(startedPkt) }
            } catch (e: Exception) {
                android.util.Log.w("TransceiverCoord", "Could not send TTS_STARTED: ${e.message}")
            }
        }
        updateMessage(msg.messageId) { it.copy(state = MessageState.REMOTE_PLAYING) }

        // TTS Suppression Rule
        cooldownJob?.cancel()
        isTtsPlaying = true
        continuousListenEngine.pauseListening()

        try {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val pcmAudio: FloatArray
            val sampleRate: Int

            if (engine != null && canSpeak) {
                val req = SpeechSynthesisRequest(
                    languageCode = engine.languageCode,
                    text = text,
                    correlationId = msg.messageId.toString()
                )
                val result = engine.synthesize(req)
                pcmAudio = result.pcmAudio
                sampleRate = result.sampleRateHz
            } else if (isCritical) {
                // Safety-critical emergency tone: generate high-penetration multi-tone alarm PCM (800Hz / 1000Hz alternating warble)
                // Guaranteed audible on device speaker regardless of voice pack status
                sampleRate = 16000
                val durationSeconds = 2.0
                val numSamples = (sampleRate * durationSeconds).toInt()
                pcmAudio = FloatArray(numSamples) { i ->
                    val freq = if ((i / 4000) % 2 == 0) 800.0 else 1000.0
                    (Math.sin(2.0 * Math.PI * freq * i / sampleRate) * 0.7).toFloat()
                }
            } else {
                // Debug-only placeholder tone
                sampleRate = 16000
                val durationSeconds = 3.0
                val numSamples = (sampleRate * durationSeconds).toInt()
                pcmAudio = FloatArray(numSamples) { i ->
                    (Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 0.3).toFloat()
                }
            }

            val ttfaMillis = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000

            val sink = SpeakerAudioSink(context)
            currentAudioSink = sink
            val usage = if (isCritical) {
                android.media.AudioAttributes.USAGE_ALARM
            } else {
                android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
            }
            sink.init(sampleRate, usage, requestMaxVolume = isCritical)
            sink.play(pcmAudio)
            sink.flushAndStop()

            val usedVoice = (engine != null && canSpeak)
            val fallbackLabel = if (usedVoice) null else if (isCritical) "SAFETY ALARM TONE" else "TTS UNAVAILABLE — DIAGNOSTIC TONE"
            updateMessage(msg.messageId) {
                it.copy(
                    state = MessageState.REMOTE_PLAYBACK_CONFIRMED,
                    peerTtfaMillis = ttfaMillis,
                    statusDetail = fallbackLabel ?: it.statusDetail
                )
            }

            val payloadBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(ttfaMillis).array()
            if (secureSessionManager.state.value == SecureSessionState.SECURE_VERIFIED) {
                try {
                    val compPkt = secureSessionManager.encrypt(ItantraPacket(PacketType.TTS_COMPLETED, messageId = packet.messageId, payload = payloadBytes))
                    scope.launch { transportEngine.send(compPkt) }
                } catch (e: Exception) {
                    android.util.Log.w("TransceiverCoord", "Could not send TTS_COMPLETED: ${e.message}")
                }
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            // Interrupted by preemption
            updateMessage(msg.messageId) { it.copy(state = MessageState.ERROR, text = it.text + " (Interrupted)") }
        } catch (e: Exception) {
            e.printStackTrace()
            updateMessage(msg.messageId) { it.copy(state = MessageState.ERROR) }
            if (secureSessionManager.state.value == SecureSessionState.SECURE_VERIFIED) {
                try {
                    val failPkt = secureSessionManager.encrypt(ItantraPacket(PacketType.TTS_FAILED, messageId = packet.messageId))
                    scope.launch { transportEngine.send(failPkt) }
                } catch (ex: Exception) {
                    android.util.Log.w("TransceiverCoord", "Could not send TTS_FAILED: ${ex.message}")
                }
            }
        } finally {
            currentAudioSink?.release()
            currentAudioSink = null

            // Resume VAD after TTS
            cooldownJob = scope.launch {
                delay(300)
                isTtsPlaying = false
                if (continuousModeJob?.isActive == true) {
                    continuousListenEngine.resetAndResume()
                }
            }
        }
    }

    fun setContinuousMode(enabled: Boolean) {
        if (enabled) {
            if (continuousModeJob != null) return
            com.itantra.core.service.OperationalForegroundService.startContinuous(context)
            continuousListenEngine.start()
            continuousModeJob = scope.launch {
                launch {
                    try {
                        audioSource.stream.collect { samples ->
                            if (!isTtsPlaying) {
                                continuousListenEngine.feedAudio(samples)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
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
            val targetLang = resolveTargetLanguage(sessionManager.activeLanguage.value ?: LanguageCode.HINDI)
            val msg = TransceiverMessage(
                messageId = msgId,
                language = sessionManager.activeLanguage.value ?: LanguageCode.HINDI,
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
        val targetLang = resolveTargetLanguage(sessionManager.activeLanguage.value ?: LanguageCode.HINDI)
        val msg = TransceiverMessage(
            messageId = msgId,
            language = sessionManager.activeLanguage.value ?: LanguageCode.HINDI,
            targetLanguage = targetLang,
            priority = com.itantra.domain.model.MessagePriority.NORMAL,
            text = "Recognizing...",
            source = MessageSource.LOCAL,
            createdAtLocal = System.currentTimeMillis(),
            state = MessageState.STT_PROCESSING
        )
        addMessage(msg)

        scope.launch {
            sttMutex.withLock {
                try {
                    engine.feed(audio)
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val result = engine.finalizeUtterance()
                    val latencyMillis = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
                    val durationMillis = (audio.size.toLong() * 1000) / 16000

                    metricsRecorder.recordSttInferenceTime(latencyMillis)
                    metricsRecorder.recordSttEndpointToFinalText(latencyMillis)
                    metricsRecorder.recordSttAudioDuration(durationMillis)

                    if (result.text.isBlank()) {
                        updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = "Recognition produced no text.") }
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

                    val srcLang = sessionManager.activeLanguage.value ?: LanguageCode.HINDI
                    // Was `currentTargetLanguage.value` (nullable, skips translation entirely
                    // when unset). Now resolves via the peer's advertised language first, so an
                    // unconfigured session still translates into what the peer can actually
                    // read instead of silently sending untranslated text.
                    val targetLang = resolveTargetLanguage(srcLang)
                    var finalTxt = result.text
                    var origTxt: String? = null
                    var translationStatus = com.itantra.domain.model.TranslationStatus.BYPASSED
                    var failureDetail: String? = null

                    // No "Translating..." interim flash here: translation is either genuinely
                    // fast (real engine) or immediate (UnavailableTranslationEngine's instant
                    // no-op) - either way the flash added a moment that looked like the app
                    // was struggling with something, when there's nothing to wait on.
                    if (targetLang != srcLang) {
                        val translationRes = translationRouter.routeAndTranslate(result.text, srcLang, targetLang)
                        if (translationRes.isSuccessful && translationRes.translatedText.isNotBlank()) {
                            finalTxt = translationRes.translatedText
                            origTxt = result.text
                            translationStatus = com.itantra.domain.model.TranslationStatus.SUCCESS
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

                    val payload = finalTxt.toByteArray(Charsets.UTF_8)
                    val rawPcmEq = (durationMillis * 16000 * 2) / 1000

                    updateMessage(msgId) {
                        it.copy(
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

                    sendVoiceMessage(msgId, com.itantra.domain.model.MessagePriority.NORMAL)
                } catch (e: Exception) {
                    e.printStackTrace()
                    updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = "Error: ${e.message}") }
                } finally {
                    if (!isTtsPlaying && continuousModeJob?.isActive == true) {
                        continuousListenEngine.resumeListening()
                    }
                }
            }
        }
    }

    fun startRecording(isCritical: Boolean = false) {
        if (recordingJob != null) return
        val engine = sessionManager.currentSttEngine
        if (engine == null || !engine.isLoaded) {
            val lastMsg = _messages.value.lastOrNull()
            if (lastMsg != null && lastMsg.state == MessageState.ERROR && lastMsg.text == "STT Pack Required") {
                return
            }
            val msgId = nextMessageId()
            addMessage(
                TransceiverMessage(
                    messageId = msgId,
                    language = sessionManager.activeLanguage.value ?: LanguageCode.HINDI,
                    targetLanguage = currentTargetLanguage.value,
                    priority = 0,
                    text = "STT Pack Required",
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

        recordingJob = scope.launch {
            launch {
                delay(60_000L) // 60s max
                stopRecording(msgId)
            }
            try {
                audioSource.stream.collect { samples ->
                    if (samples.isNotEmpty()) {
                        activeRecordingChunks.add(samples.clone())
                        engine.feed(samples)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun processTestAudio(samples: FloatArray) {
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
        recordingJob = scope.launch {
            sttMutex.withLock {
                engine.reset()
                engine.feed(samples)
            }
            stopRecording(msgId)
        }
    }

    fun stopActiveRecording() {
        if (activeRecordingMessageId != 0L) {
            stopRecording(activeRecordingMessageId)
        }
    }

    fun stopRecording(msgId: Long) {
        if (recordingJob == null) return
        recordingJob?.cancel()
        recordingJob = null

        val engine = sessionManager.currentSttEngine ?: return

        val chunksSnapshot = synchronized(activeRecordingChunks) {
            val copy = activeRecordingChunks.toList()
            activeRecordingChunks.clear()
            copy
        }

        val totalSamples = chunksSnapshot.sumOf { it.size }
        val durationMillis = if (totalSamples > 0) {
            (totalSamples * 1000L) / 16000L
        } else {
            SystemClock.elapsedRealtime() - recordingStartTime
        }

        // Save raw audio to debug WAV file for inspection (gated behind BuildConfig.DEBUG)
        if (com.example.itantra.BuildConfig.DEBUG && totalSamples > 0) {
            try {
                val fullPcm = FloatArray(totalSamples)
                var offset = 0
                for (chunk in chunksSnapshot) {
                    System.arraycopy(chunk, 0, fullPcm, offset, chunk.size)
                    offset += chunk.size
                }
                val extWav = java.io.File(context.getExternalFilesDir(null), "debug_ptt.wav")
                val cacheWav = java.io.File(context.cacheDir, "debug_ptt.wav")
                com.itantra.core.audio.WavWriter.writeWavFile(extWav, fullPcm, 16000, 1)
                com.itantra.core.audio.WavWriter.writeWavFile(cacheWav, fullPcm, 16000, 1)
                android.util.Log.i("TransceiverCoordinator", "Saved debug_ptt.wav: $totalSamples samples (${durationMillis}ms) to ${extWav.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.e("TransceiverCoordinator", "Error saving debug_ptt.wav", e)
            }
        }

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
            scope.launch {
                sttMutex.withLock { engine.reset() }
                if (continuousModeJob?.isActive == true && !isTtsPlaying) {
                    continuousListenEngine.resetAndResume()
                }
            }
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

        // Bumped from 200ms to 300ms - accidental taps/brushes on the PTT button were still
        // occasionally clearing this bar and reaching STT with near-empty audio.
        if (durationMillis < 300) {
            scope.launch {
                sttMutex.withLock { engine.reset() }
                if (continuousModeJob?.isActive == true && !isTtsPlaying) {
                    continuousListenEngine.resetAndResume()
                }
            }
            // Cleanly discard micro-tap without leaving persistent error card in UI
            _messages.value = _messages.value.filter { it.messageId != msgId }
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

        updateMessage(msgId) { it.copy(state = MessageState.STT_PROCESSING, text = "Finalizing...") }

        scope.launch {
            sttMutex.withLock {
                try {
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val result = engine.finalizeUtterance()
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

                    if (result.text.isBlank()) {
                        updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = "Speech recognition failed.") }
                        return@withLock
                    }

                    val msg = _messages.value.find { it.messageId == msgId } ?: return@withLock

                    val priorityInt = if (msg.priority == com.itantra.domain.model.MessagePriority.CRITICAL) {
                        com.itantra.domain.model.MessagePriority.CRITICAL
                    } else {
                        com.itantra.domain.model.MessagePriority.NORMAL
                    }

                    val targetLang = msg.targetLanguage ?: resolveTargetLanguage(msg.language ?: LanguageCode.HINDI)
                    val srcLang = msg.language ?: LanguageCode.HINDI
                    var finalTxt = result.text
                    var origTxt: String? = null
                    var translationStatus = com.itantra.domain.model.TranslationStatus.BYPASSED
                    var failureDetail: String? = null

                    var mtLatency = 0L
                    android.util.Log.i("ITANTRA_MT_CALL", "Evaluating MT condition: targetLang=$targetLang, srcLang=$srcLang, msgId=$msgId")
                    if (targetLang != srcLang) {
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

                    val payload = finalTxt.toByteArray(Charsets.UTF_8)
                    val rawPcmEq = (durationMillis * 16000 * 2) / 1000

                    updateMessage(msgId) {
                        it.copy(
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
                    updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = "Error: ${e.message}") }
                } finally {
                    if (continuousModeJob?.isActive == true && !isTtsPlaying) {
                        continuousListenEngine.resetAndResume()
                    }
                }
            }
        }
    }

    fun sendTextMessage(text: String, targetPeerId: String? = null, priority: Int = com.itantra.domain.model.MessagePriority.NORMAL) {
        if (text.isBlank()) return
        scope.launch {
            val msgId = nextMessageId()
            val localLang = sessionManager.activeLanguage.value ?: LanguageCode.ENGLISH
            // FIX 009: resolve target language with the same policy as voice — from user preference,
            // peer capability, or same-language bypass. Previously targetLanguage was always localLang
            // and translationMode=NONE, so typed text was never translated.
            val targetLang = resolveTargetLanguage(localLang)
            val activePeer = targetPeerId ?: _activeConversationPeerId.value ?: _activePeerProfile.value?.deviceId ?: ""
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
                val securePacket = secureSessionManager.encrypt(packet)
                val txMetrics = transportEngine.send(securePacket)
                val rtt = txMetrics.transmissionLatencyMillis.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0L }
                if (rtt > 0) {
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
                updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = "$text (Failed to send)") }
            }
        }
    }


    fun cancelMessage(msgId: Long) {
        updateMessage(msgId) { it.copy(state = MessageState.ERROR, statusDetail = "Cancelled") }
    }

    fun sendVoiceMessage(msgId: Long, priority: Int = com.itantra.domain.model.MessagePriority.NORMAL) {
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
                try {
                    val tCrypto0 = SystemClock.elapsedRealtimeNanos()
                    val securePacket = secureSessionManager.encrypt(packet) // Encrypt inside loop to get a fresh nonce/counter each time
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

                    val txMetrics = transportEngine.send(securePacket)
                    val rtt = txMetrics.transmissionLatencyMillis.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0L }
                    val pBytes = txMetrics.packetBytes.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0 }
                    val semBytes = packet.payload.size
                    val secBytes = securePacket.payload.size
                    val frameBytes = txMetrics.finalFrameBytes.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0 }

                    if (rtt > 0 || sendPriority != com.itantra.domain.model.MessagePriority.CRITICAL) {
                        success = true
                        val estE2e = msg.sttLatencyMillis + msg.mtLatencyMillis + cryptoLatency + (rtt / 2)
                        if (rtt > 0) {
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
                    if (attempt == maxAttempts) {
                        updateMessage(msgId) { it.copy(state = MessageState.ERROR, text = "${msg.text} (Encryption failed)") }
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
                    val encrypted = secureSessionManager.encrypt(heartbeat)
                    transportEngine.send(encrypted)
                } catch (e: Exception) {
                    android.util.Log.w("TransceiverCoordinator", "Encrypted heartbeat failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Confirms SAS match, sends SECURE_VERIFY, and retries up to 3 times (1000ms apart)
     * until the peer responds with its own SECURE_VERIFY and state reaches SECURE_VERIFIED.
     */
    fun confirmPeerVerification() {
        verifyRetryJob?.cancel()
        verifyRetryJob = scope.launch {
            val verifyPacket = try {
                secureSessionManager.confirmSasMatch()
            } catch (e: IllegalStateException) {
                android.util.Log.e("TransceiverCoordinator", "confirmPeerVerification: ${e.message}")
                return@launch
            }
            transportEngine.send(verifyPacket)
            if (secureSessionManager.state.value == SecureSessionState.SECURE_VERIFIED) {
                verifyRetryJob = null
                transportEngine.setAuthenticatedLivenessEnabled(true)
                startEncryptedHeartbeat()
                sendProfileHandshake()
                sendCapabilities()
                retryUnresolvedEmergencies()
                return@launch
            }
            // Retry sending SECURE_VERIFY until peer responds or retries exhausted
            var attempts = 1
            while (attempts < 3 &&
                secureSessionManager.state.value == SecureSessionState.WAITING_USER_VERIFICATION) {
                delay(VERIFY_RETRY_INTERVAL_MS)
                if (secureSessionManager.state.value == SecureSessionState.SECURE_VERIFIED) break
                if (secureSessionManager.state.value == SecureSessionState.WAITING_USER_VERIFICATION) {
                    android.util.Log.w("TransceiverCoordinator", "SECURE_VERIFY retry attempt $attempts")
                    transportEngine.send(verifyPacket)
                    attempts++
                }
            }
            if (secureSessionManager.state.value == SecureSessionState.SECURE_VERIFIED) {
                verifyRetryJob = null
                transportEngine.setAuthenticatedLivenessEnabled(true)
                startEncryptedHeartbeat()
                sendProfileHandshake()
                sendCapabilities()
                retryUnresolvedEmergencies()
            } else {
                android.util.Log.e(
                    "TransceiverCoordinator",
                    "SECURE_VERIFY never confirmed by peer after $attempts attempts"
                )
            }
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
            }
        }
    }

    fun sendEmergencyCode(code: com.itantra.domain.model.EmergencyCode) {
        scope.launch {
            val msgId = nextMessageId()
            val text = com.itantra.domain.model.EmergencyPhraseResolver.resolve(code, sessionManager.activeLanguage.value)
            val msg = TransceiverMessage(
                messageId = msgId,
                language = sessionManager.activeLanguage.value,
                priority = com.itantra.domain.model.MessagePriority.CRITICAL,
                text = text,
                source = MessageSource.LOCAL,
                createdAtLocal = System.currentTimeMillis(),
                state = MessageState.TRANSMITTING
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
                resolvedPhrase = text
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
                    val securePacket = secureSessionManager.encrypt(packet)
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

                    val txMetrics = transportEngine.send(securePacket)
                    val rtt = txMetrics.transmissionLatencyMillis.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0L }
                    val pBytes = txMetrics.packetBytes.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0 }
                    val semBytes = packet.payload.size
                    val secBytes = securePacket.payload.size
                    val frameBytes = txMetrics.finalFrameBytes.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0 }

                    if (rtt > 0) {
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
                val securePacket = secureSessionManager.encrypt(packet)
                transportEngine.send(securePacket)
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
                val securePacket = secureSessionManager.encrypt(packet)
                val txMetrics = transportEngine.send(securePacket)
                val rtt = txMetrics.transmissionLatencyMillis.let { if (it is com.itantra.domain.model.Measurement.Measured) it.value else 0L }
                val ok = rtt > 0
                emergencyStore.recordRetryAttempt(rec.messageId, ok, System.currentTimeMillis())
                if (ok) {
                    emergencyStore.recordTransportAck(rec.messageId)
                }
            } catch (e: Exception) {
                emergencyStore.recordRetryAttempt(rec.messageId, false, System.currentTimeMillis())
            }
        }
    }

    fun shutdown() {
        scope.cancel()
    }
}
