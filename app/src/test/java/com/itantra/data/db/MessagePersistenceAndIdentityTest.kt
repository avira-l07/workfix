package com.itantra.data.db

import com.itantra.core.metrics.WerResult
import com.itantra.core.metrics.WordErrorRateCalculator
import com.example.itantra.data.settings.AppSettings
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.MessagePriority
import com.itantra.domain.model.MessageSource
import com.itantra.domain.model.MessageState
import com.itantra.domain.model.TransceiverMessage
import com.itantra.domain.model.TranslationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessagePersistenceAndIdentityTest {

    // 1. Verify MessageEntity to/from TransceiverMessage lossless mapping
    @Test
    fun testMessageEntityLosslessMapping() {
        val original = TransceiverMessage(
            messageId = 1726801234567L,
            language = LanguageCode.HINDI,
            targetLanguage = LanguageCode.ENGLISH,
            priority = MessagePriority.NORMAL,
            text = "नमस्ते दुनिया",
            originalText = "Hello world",
            translationStatus = TranslationStatus.SUCCESS,
            source = MessageSource.LOCAL,
            createdAtLocal = 1726801234567L,
            state = MessageState.DELIVERED,
            sttLatencyMillis = 180L,
            mtLatencyMillis = 45L,
            cryptoLatencyMillis = 3L,
            payloadBytes = 32,
            semanticBytes = 24,
            secureBytes = 40,
            finalFrameBytes = 48,
            packetBytes = 56,
            rttMillis = 90L,
            peerTtfaMillis = 210L,
            estimatedE2eMillis = 315L,
            remoteAudioStartConfMillis = 1726801234900L,
            rawPcmEquivalentBytes = 16000 * 2 * 4,
            speechDurationMillis = 4000L,
            statusDetail = "Translation not available in this build — transcript only"
        )

        val entity = original.toEntity()
        assertEquals(original.messageId, entity.messageId)
        assertEquals("hi", entity.languageWireCode)
        assertEquals("en", entity.targetLanguageWireCode)
        assertEquals("SUCCESS", entity.translationStatusName)
        assertEquals("LOCAL", entity.sourceName)
        assertEquals(4000L, entity.speechDurationMillis)
        assertEquals("Translation not available in this build — transcript only", entity.statusDetail)

        val restored = entity.toDomain()
        assertEquals(original.messageId, restored.messageId)
        assertEquals(original.language, restored.language)
        assertEquals(original.targetLanguage, restored.targetLanguage)
        assertEquals(original.priority, restored.priority)
        assertEquals(original.text, restored.text)
        assertEquals(original.originalText, restored.originalText)
        assertEquals(original.translationStatus, restored.translationStatus)
        assertEquals(original.source, restored.source)
        assertEquals(original.createdAtLocal, restored.createdAtLocal)
        assertEquals(original.state, restored.state)
        assertEquals(original.speechDurationMillis, restored.speechDurationMillis)
        assertEquals(original.statusDetail, restored.statusDetail)
    }

    // 2. Verify AppSettings operatorName field and default
    @Test
    fun testAppSettingsOperatorNameDefaultAndCopy() {
        val defaultSettings = AppSettings()
        assertEquals("", defaultSettings.operatorName)

        val customized = defaultSettings.copy(operatorName = "ALPHA-LEADER")
        assertEquals("ALPHA-LEADER", customized.operatorName)
    }

    // 3. Verify operatorName resolution logic for message cards
    @Test
    fun testOperatorNameResolutionForMessageCard() {
        fun resolveSender(source: MessageSource, operatorName: String): String {
            return if (source == MessageSource.LOCAL) {
                if (operatorName.isNotBlank()) operatorName else "OPERATOR (Local)"
            } else "REMOTE PEER"
        }

        // Blank name falls back to OPERATOR (Local)
        assertEquals("OPERATOR (Local)", resolveSender(MessageSource.LOCAL, ""))
        assertEquals("OPERATOR (Local)", resolveSender(MessageSource.LOCAL, "   "))

        // Configured callsign replaces the hardcoded label
        assertEquals("ALPHA-LEADER", resolveSender(MessageSource.LOCAL, "ALPHA-LEADER"))
        assertEquals("Capt. R. Sharma", resolveSender(MessageSource.LOCAL, "Capt. R. Sharma"))

        // Remote peer always identifies as REMOTE PEER
        assertEquals("REMOTE PEER", resolveSender(MessageSource.REMOTE, "ALPHA-LEADER"))
    }

    // 4. Verify Timestamp formatting: HH:mm IST is strictly single-line
    @Test
    fun testTimestampFormattingIsSingleLine() {
        val timeFormat = SimpleDateFormat("HH:mm 'IST'", Locale.US)
        val formatted = timeFormat.format(Date(1726801234567L))

        assertNotNull(formatted)
        assertTrue("Formatted time must end with IST", formatted.endsWith(" IST"))
        assertFalse("Formatted time must never contain newline characters", formatted.contains("\n"))
        assertFalse("Formatted time must never contain carriage return characters", formatted.contains("\r"))
        assertEquals(9, formatted.length) // e.g. "02:28 IST" or "14:28 IST" (5 chars + 1 space + 3 chars)
    }

    // 5. Verify WordErrorRateCalculator accurately computes WER on benchmark pairs
    @Test
    fun testWordErrorRateCalculatorAccuracy() {
        // Perfect match
        val res1 = WordErrorRateCalculator.calculate("Hello how are you today", "Hello how are you today")
        assertEquals(0.0f, res1.wer, 0.001f)
        assertEquals(0, res1.substitutions)
        assertEquals(0, res1.deletions)
        assertEquals(0, res1.insertions)

        // Single substitution: "Please" vs "Pleased"
        val res2 = WordErrorRateCalculator.calculate("Pleased to meet you", "Please to meet you")
        assertEquals(0.25f, res2.wer, 0.001f)
        assertEquals(1, res2.substitutions)

        // Number substitution: "eight nine two" vs "892"
        val res3 = WordErrorRateCalculator.calculate("My contact number is eight nine two", "My contact number is 892")
        assertEquals(3, res3.deletions + res3.substitutions + res3.insertions)

        // Corpus-level aggregation
        val corpus = WordErrorRateCalculator.calculateCorpusWer(listOf(res1, res2))
        assertEquals(9, corpus.totalReferenceWords)
        assertEquals(1, corpus.totalSubstitutions)
        assertEquals(1.0f / 9.0f, corpus.corpusWer, 0.001f)
    }
}
