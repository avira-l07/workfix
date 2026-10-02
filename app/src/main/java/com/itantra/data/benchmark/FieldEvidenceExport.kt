package com.itantra.data.benchmark

import com.itantra.data.db.MessageEntity
import com.itantra.domain.model.BenchmarkSession
import com.itantra.domain.model.TtsEvaluationSession
import kotlinx.serialization.Serializable

/** Shareable measurement summary. Conversation text, peer IDs and locations stay private. */
@Serializable
data class FieldEvidenceExport(
    val exportedAtMillis: Long,
    val appVersion: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidVersion: String,
    val sttRuns: List<SttRunSummary>,
    val ttsRuns: List<TtsRunSummary>,
    val voiceFrames: List<VoiceFrameSummary>,
) {
    companion object {
        fun from(
            exportedAtMillis: Long,
            appVersion: String,
            deviceManufacturer: String,
            deviceModel: String,
            androidVersion: String,
            sttRuns: List<BenchmarkSession>,
            ttsRuns: List<TtsEvaluationSession>,
            messages: List<MessageEntity>,
        ) = FieldEvidenceExport(
            exportedAtMillis = exportedAtMillis,
            appVersion = appVersion,
            deviceManufacturer = deviceManufacturer,
            deviceModel = deviceModel,
            androidVersion = androidVersion,
            sttRuns = sttRuns.map {
                SttRunSummary(
                    timestampMs = it.timestampMs,
                    language = it.language,
                    modelVersion = it.modelVersion,
                    evidenceLevel = it.evidenceLevel.name,
                    utterances = it.totalUtterances,
                    referenceWords = it.totalReferenceWords,
                    substitutions = it.substitutions,
                    deletions = it.deletions,
                    insertions = it.insertions,
                    corpusWer = it.corpusWer,
                    corpusCer = it.corpusCer,
                    meanRtf = it.meanRtf,
                    medianFinalizationLatencyMs = it.medianFinalizationLatencyMs,
                )
            },
            ttsRuns = ttsRuns.map {
                TtsRunSummary(
                    timestampMs = it.timestampMs,
                    language = it.language,
                    modelIdentity = it.modelIdentity,
                    evidenceLevel = it.evidenceLevel.name,
                    sentencesTested = it.sentencesTested,
                    intelligibleCount = it.intelligibleCount,
                    meanNaturalness = it.meanNaturalness,
                    meanPronunciation = it.meanPronunciation,
                    meanRtf = it.meanRtf,
                )
            },
            voiceFrames = messages.filter { it.isVoiceGenerated }.map {
                VoiceFrameSummary(
                    recordedAtMillis = it.createdAtLocal,
                    source = it.sourceName,
                    state = it.stateName,
                    micLanguage = it.languageWireCode,
                    targetLanguage = it.targetLanguageWireCode,
                    speechDurationMillis = it.speechDurationMillis,
                    sttLatencyMillis = it.sttLatencyMillis,
                    mtLatencyMillis = it.mtLatencyMillis,
                    wireFrameBytes = it.finalFrameBytes.takeIf { bytes -> bytes > 0 }
                        ?: it.packetBytes.takeIf { bytes -> bytes > 0 },
                    rawPcmEquivalentBytes = it.rawPcmEquivalentBytes.takeIf { bytes -> bytes > 0 },
                    reductionVsRawPcmPercent = it.toDomain().measuredWireReductionVsPcmPercent,
                    transportRttMillis = it.rttMillis.takeIf { millis -> millis > 0 },
                    remoteAudioStartConfirmationMillis = it.remoteAudioStartConfMillis.takeIf { millis -> millis > 0 },
                )
            },
        )
    }
}

@Serializable
data class SttRunSummary(
    val timestampMs: Long,
    val language: String,
    val modelVersion: String,
    val evidenceLevel: String,
    val utterances: Int,
    val referenceWords: Int,
    val substitutions: Int,
    val deletions: Int,
    val insertions: Int,
    val corpusWer: Float,
    val corpusCer: Float,
    val meanRtf: Float,
    val medianFinalizationLatencyMs: Long,
)

@Serializable
data class TtsRunSummary(
    val timestampMs: Long,
    val language: String,
    val modelIdentity: String,
    val evidenceLevel: String,
    val sentencesTested: Int,
    val intelligibleCount: Int,
    val meanNaturalness: Float,
    val meanPronunciation: Float,
    val meanRtf: Float,
)

@Serializable
data class VoiceFrameSummary(
    val recordedAtMillis: Long,
    val source: String,
    val state: String,
    val micLanguage: String?,
    val targetLanguage: String?,
    val speechDurationMillis: Long,
    val sttLatencyMillis: Long,
    val mtLatencyMillis: Long,
    val wireFrameBytes: Int?,
    val rawPcmEquivalentBytes: Int?,
    val reductionVsRawPcmPercent: Double?,
    val transportRttMillis: Long?,
    val remoteAudioStartConfirmationMillis: Long?,
)
