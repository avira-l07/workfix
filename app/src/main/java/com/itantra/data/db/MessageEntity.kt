package com.itantra.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.MessageSource
import com.itantra.domain.model.MessageState
import com.itantra.domain.model.TransceiverMessage
import com.itantra.domain.model.TranslationStatus

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    val messageId: Long,
    val languageWireCode: String?,
    val targetLanguageWireCode: String?,
    val priority: Int,
    val text: String,
    val originalText: String?,
    val translationStatusName: String,
    val sourceName: String,
    val createdAtLocal: Long,
    val stateName: String,
    val peerId: String = "",
    val senderDeviceId: String = "",
    val receiverDeviceId: String = "",
    val isVoiceGenerated: Boolean = false,
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
    fun toDomain(): TransceiverMessage {
        val lang = languageWireCode?.let { LanguageCode.fromWireCode(it) }
        val targetLang = targetLanguageWireCode?.let { LanguageCode.fromWireCode(it) }
        val transStatus = runCatching { TranslationStatus.valueOf(translationStatusName) }.getOrDefault(TranslationStatus.NONE)
        val src = runCatching { MessageSource.valueOf(sourceName) }.getOrDefault(MessageSource.LOCAL)
        val st = runCatching { MessageState.valueOf(stateName) }.getOrDefault(MessageState.DELIVERED)

        return TransceiverMessage(
            messageId = messageId,
            language = lang,
            targetLanguage = targetLang,
            priority = priority,
            text = text,
            originalText = originalText,
            translationStatus = transStatus,
            source = src,
            createdAtLocal = createdAtLocal,
            state = st,
            peerId = peerId,
            senderDeviceId = senderDeviceId,
            receiverDeviceId = receiverDeviceId,
            isVoiceGenerated = isVoiceGenerated,
            sttLatencyMillis = sttLatencyMillis,
            mtLatencyMillis = mtLatencyMillis,
            cryptoLatencyMillis = cryptoLatencyMillis,
            payloadBytes = payloadBytes,
            semanticBytes = semanticBytes,
            secureBytes = secureBytes,
            finalFrameBytes = finalFrameBytes,
            packetBytes = packetBytes,
            rttMillis = rttMillis,
            peerTtfaMillis = peerTtfaMillis,
            estimatedE2eMillis = estimatedE2eMillis,
            remoteAudioStartConfMillis = remoteAudioStartConfMillis,
            rawPcmEquivalentBytes = rawPcmEquivalentBytes,
            speechDurationMillis = speechDurationMillis,
            statusDetail = statusDetail
        )
    }

    companion object {
        fun fromDomain(msg: TransceiverMessage): MessageEntity {
            return MessageEntity(
                messageId = msg.messageId,
                languageWireCode = msg.language?.wireCode,
                targetLanguageWireCode = msg.targetLanguage?.wireCode,
                priority = msg.priority,
                text = msg.text,
                originalText = msg.originalText,
                translationStatusName = msg.translationStatus.name,
                sourceName = msg.source.name,
                createdAtLocal = msg.createdAtLocal,
                stateName = msg.state.name,
                peerId = msg.peerId,
                senderDeviceId = msg.senderDeviceId,
                receiverDeviceId = msg.receiverDeviceId,
                isVoiceGenerated = msg.isVoiceGenerated,
                sttLatencyMillis = msg.sttLatencyMillis,
                mtLatencyMillis = msg.mtLatencyMillis,
                cryptoLatencyMillis = msg.cryptoLatencyMillis,
                payloadBytes = msg.payloadBytes,
                semanticBytes = msg.semanticBytes,
                secureBytes = msg.secureBytes,
                finalFrameBytes = msg.finalFrameBytes,
                packetBytes = msg.packetBytes,
                rttMillis = msg.rttMillis,
                peerTtfaMillis = msg.peerTtfaMillis,
                estimatedE2eMillis = msg.estimatedE2eMillis,
                remoteAudioStartConfMillis = msg.remoteAudioStartConfMillis,
                rawPcmEquivalentBytes = msg.rawPcmEquivalentBytes,
                speechDurationMillis = msg.speechDurationMillis,
                statusDetail = msg.statusDetail
            )
        }
    }
}

fun TransceiverMessage.toEntity(): MessageEntity = MessageEntity.fromDomain(this)
