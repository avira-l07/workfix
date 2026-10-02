package com.itantra.data.benchmark

import com.itantra.data.db.MessageEntity
import com.itantra.domain.model.BenchmarkResult
import com.itantra.domain.model.BenchmarkSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FieldEvidenceExportTest {
    @Test
    fun exportContainsMeasuredSummariesButNoConversationOrReferenceText() {
        val message = MessageEntity(
            messageId = 7,
            languageWireCode = "hi",
            targetLanguageWireCode = "en",
            priority = 0,
            text = "PRIVATE_MESSAGE_TEXT",
            originalText = "PRIVATE_ORIGINAL_TEXT",
            translationStatusName = "SUCCESS",
            sourceName = "LOCAL",
            createdAtLocal = 1234,
            stateName = "DELIVERED",
            peerId = "PRIVATE_PEER_ID",
            isVoiceGenerated = true,
            finalFrameBytes = 80,
            rawPcmEquivalentBytes = 32000,
            speechDurationMillis = 1000,
        )
        val benchmark = BenchmarkSession(
            timestampMs = 1234,
            language = "hi",
            corpusWer = 0.2f,
            results = listOf(BenchmarkResult(
                sentenceId = "1",
                referenceText = "PRIVATE_REFERENCE_TEXT",
                recognizedText = "PRIVATE_RECOGNIZED_TEXT",
                audioDurationMs = 1000,
                processingMs = 100,
                finalizationLatencyMs = 100,
                referenceWordCount = 1,
                wer = 0f,
                substitutions = 0,
                deletions = 0,
                insertions = 0,
            )),
        )
        val export = FieldEvidenceExport.from(
            exportedAtMillis = 2000,
            appVersion = "1.0",
            deviceManufacturer = "test",
            deviceModel = "test",
            androidVersion = "14",
            sttRuns = listOf(benchmark),
            ttsRuns = emptyList(),
            messages = listOf(message),
        )
        val json = Json.encodeToString(export)
        assertEquals(99.75, export.voiceFrames.single().reductionVsRawPcmPercent!!, 0.0001)
        assertEquals(0.2f, export.sttRuns.single().corpusWer)
        assertTrue(json.contains("wireFrameBytes"))
        listOf("PRIVATE_MESSAGE_TEXT", "PRIVATE_ORIGINAL_TEXT", "PRIVATE_PEER_ID", "PRIVATE_REFERENCE_TEXT", "PRIVATE_RECOGNIZED_TEXT")
            .forEach { assertFalse(json.contains(it)) }
    }
}
