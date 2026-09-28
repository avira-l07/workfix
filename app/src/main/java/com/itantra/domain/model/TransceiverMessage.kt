package com.itantra.domain.model

enum class MessageState {
    IDLE,
    RECORDING,
    STT_PROCESSING,
    STT_COMPLETE,
    PACKET_ENCODING,
    TRANSMITTING,
    SENT,
    DELIVERED,
    REMOTE_TTS_READY,
    REMOTE_PLAYING,
    REMOTE_PLAYBACK_CONFIRMED,
    WAITING_ACK,
    ACKNOWLEDGED,
    WAITING_USER_CONFIRMATION,
    ERROR
}

enum class TranslationStatus {
    NONE,
    BYPASSED,
    TRANSLATING,
    SUCCESS,
    FAILED,
    UNSUPPORTED,
    MODEL_MISSING
}

object MessagePriority {
    const val NORMAL = 0
    const val HIGH = 1
    const val CRITICAL = 2
}

enum class MessageSource {
    LOCAL,
    REMOTE
}

const val VOICE_OUTPUT_UNAVAILABLE_NOTE = "Voice output not available — text only"
// SOS without a known recipient belongs to the local broadcast history and is
// visible in every conversation, including when transmission fails. No wire change.
const val BROADCAST_PEER_ID = "BROADCAST"

data class TransceiverMessage(
    val messageId: Long,
    val language: LanguageCode?,
    val targetLanguage: LanguageCode? = null,
    val priority: Int,
    val text: String,
    val originalText: String? = null,
    val translationStatus: TranslationStatus = TranslationStatus.NONE,
    val source: MessageSource,
    val createdAtLocal: Long,
    val state: MessageState,

    // Peer and device identity for conversation isolation
    val peerId: String = "",
    val senderDeviceId: String = "",
    val receiverDeviceId: String = "",
    val isVoiceGenerated: Boolean = false,

    // GPS location sharing (Day 4/5)
    val isLocation: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val locationTimestampMillis: Long? = null,
    val isTimeUnverified: Boolean = false,

    // Metrics per message for E2E traceability
    val sttLatencyMillis: Long = 0,
    val mtLatencyMillis: Long = 0,
    val cryptoLatencyMillis: Long = 0,
    val payloadBytes: Int = 0,
    val semanticBytes: Int = 0,
    val secureBytes: Int = 0,
    val finalFrameBytes: Int = 0,
    val packetBytes: Int = 0,
    val rttMillis: Long = 0,
    val peerTtfaMillis: Long = 0,
    val estimatedE2eMillis: Long = 0,
    val remoteAudioStartConfMillis: Long = 0,
    val rawPcmEquivalentBytes: Int = 0,
    val speechDurationMillis: Long = 0,
    val statusDetail: String? = null
) {
    val isLocationMessage: Boolean
        get() = isLocation || (latitude != null && longitude != null)

    val semanticReductionPercent: Float
        get() {
            if (rawPcmEquivalentBytes == 0) return 0f
            return 100f * (1.0f - (finalFrameBytes.toFloat() / rawPcmEquivalentBytes.toFloat()))
        }

    val semanticBitrateBps: Float
        get() {
            if (speechDurationMillis == 0L) return 0f
            return (finalFrameBytes * 8f) / (speechDurationMillis / 1000f)
        }
}
