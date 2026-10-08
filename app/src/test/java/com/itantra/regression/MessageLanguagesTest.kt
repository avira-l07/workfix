package com.itantra.regression

import com.itantra.core.translation.TranslationResult
import com.itantra.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class MessageLanguagesTest {
    private fun translatedMessage(id: Long = 1) = sampleMessage(id, MessageState.DELIVERED).copy(
        language = LanguageCode.HINDI, targetLanguage = LanguageCode.GUJARATI,
        text = "shown Gujarati", originalText = "original Hindi", translationStatus = TranslationStatus.SUCCESS)

    @Test fun `multiple choices translate independently from original without sending or changing preferences`() = runBlocking {
        BugfixFixture().use { f ->
            val message = translatedMessage()
            f.add(message)
            val receiveLanguage = f.repository.observeReceiveLanguage().first()
            val stt = f.session.activeSttLanguage.value
            f.translationAction = { text, source, target -> TranslationResult(text, "${target.wireCode}: $text", true, source, target) }
            val english = f.coordinator.translateMessage(1, LanguageCode.ENGLISH)
            val tamil = f.coordinator.translateMessage(1, LanguageCode.TAMIL)
            assertEquals("en: original Hindi", english.translatedText)
            assertEquals("ta: original Hindi", tamil.translatedText)
            assertEquals(listOf(LanguageCode.HINDI to LanguageCode.ENGLISH, LanguageCode.HINDI to LanguageCode.TAMIL), f.routes.toList())
            assertEquals(listOf(message), f.coordinator.messages.value)
            assertEquals(receiveLanguage, f.repository.observeReceiveLanguage().first())
            assertEquals(stt, f.session.activeSttLanguage.value)
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test fun `received text uses its known displayed language rather than guessing old original metadata`() = runBlocking {
        BugfixFixture().use { f ->
            f.add(translatedMessage().copy(source = MessageSource.REMOTE, language = LanguageCode.GUJARATI))
            val result = f.coordinator.translateMessage(1, LanguageCode.ENGLISH)
            assertEquals("shown Gujarati", result.originalText)
            assertEquals(LanguageCode.GUJARATI, result.sourceLanguage)
            assertEquals(listOf(LanguageCode.GUJARATI to LanguageCode.ENGLISH), f.routes.toList())
        }
    }

    @Test fun `original language choice bypasses the translation model`() = runBlocking {
        BugfixFixture().use { f ->
            f.add(translatedMessage())
            val result = f.coordinator.translateMessage(1, LanguageCode.HINDI)
            assertTrue(result.isSuccessful)
            assertEquals("original Hindi", result.translatedText)
            assertTrue(f.routes.isEmpty())
        }
    }

    @Test fun `failed translations cannot be spoken as successful conversions`() = runBlocking {
        BugfixFixture().use { f ->
            val message = translatedMessage()
            f.add(message)
            f.translationAction = { text, source, target -> TranslationResult(text, "unusable", false, source, target, "MODEL_MISSING") }
            val result = f.coordinator.translateMessage(1, LanguageCode.TAMIL)
            assertFalse(result.isSuccessful)
            f.coordinator.replayMessageTranslation(1, result)
            assertEquals(0, f.synthCalls.get())
            assertTrue(f.coordinator.savedSpeechPlayback.value.error!!.contains("Translate"))
            assertEquals(listOf(message), f.coordinator.messages.value)
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test fun `selected conversion reaches the matching voice and can be played repeatedly`() = runBlocking {
        BugfixFixture().use { f ->
            f.add(translatedMessage())
            f.translationAction = { text, source, target -> TranslationResult(text, "${target.wireCode} output", true, source, target) }
            val english = f.coordinator.translateMessage(1, LanguageCode.ENGLISH)
            val tamil = f.coordinator.translateMessage(1, LanguageCode.TAMIL)
            listOf(english, tamil, tamil).forEachIndexed { index, result ->
                f.coordinator.replayMessageTranslation(1, result)
                f.awaitCondition { f.speechRequests.size == index + 1 && !f.coordinator.savedSpeechPlayback.value.busy }
                assertEquals(result.targetLanguage, f.speechRequests.last().languageCode)
                assertEquals(result.translatedText, f.speechRequests.last().text)
            }
            assertEquals(listOf(LanguageCode.ENGLISH, LanguageCode.TAMIL, LanguageCode.TAMIL), f.spoken.toList())
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test fun `missing voice does not prevent translated text or synthesize another language`() = runBlocking {
        BugfixFixture().use { f ->
            f.add(translatedMessage())
            f.ttsInstalled = { it != LanguageCode.TAMIL }
            val result = f.coordinator.translateMessage(1, LanguageCode.TAMIL)
            assertTrue(result.isSuccessful)
            assertFalse(f.coordinator.isMessageVoiceInstalled(LanguageCode.TAMIL))
            f.coordinator.replayMessageTranslation(1, result)
            assertTrue(f.coordinator.savedSpeechPlayback.value.error!!.contains("Receive (TTS)"))
            assertEquals(0, f.synthCalls.get())
        }
    }

    @Test fun `trash during translation invalidates the result and prevents later replay`() = runBlocking {
        BugfixFixture().use { f ->
            f.add(translatedMessage())
            val began = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
            f.translationAction = { text, source, target -> began.complete(Unit); resume.await(); TranslationResult(text, "output", true, source, target) }
            val translation = async { runCatching { f.coordinator.translateMessage(1, LanguageCode.TAMIL) } }
            withTimeout(2000) { began.await() }
            assertTrue(f.coordinator.moveMessageToTrash(1))
            resume.complete(Unit)
            assertTrue(translation.await().exceptionOrNull() is IllegalStateException)
            f.coordinator.replayMessageTranslation(1, TranslationResult("original Hindi", "output", true, LanguageCode.HINDI, LanguageCode.TAMIL))
            assertEquals(0, f.synthCalls.get())
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test fun `location unknown language and unfinished recording are not translation inputs`() = runBlocking {
        BugfixFixture().use { f ->
            listOf(sampleMessage(1).copy(isLocation = true), sampleMessage(2).copy(language = null),
                sampleMessage(3, MessageState.RECORDING)).forEach { message ->
                f.add(message)
                assertNull(message.translationInput)
                assertTrue(runCatching { f.coordinator.translateMessage(message.messageId, LanguageCode.HINDI) }.isFailure)
            }
            assertTrue(f.routes.isEmpty())
        }
    }
}
