package com.itantra.core.inference

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import com.itantra.core.audio.SpeakerAudioSink
import com.itantra.core.audio.WavWriter
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.SpeechSynthesisRequest
import kotlin.math.abs

/** One bundled utterance per language: a phone smoke test, not a WER or listening-quality benchmark. */
object FiveLanguageSelfTest {
    val languages = listOf(
        LanguageCode.HINDI, LanguageCode.ENGLISH, LanguageCode.TAMIL,
        LanguageCode.TELUGU, LanguageCode.ODIA, LanguageCode.BENGALI,
        LanguageCode.GUJARATI, LanguageCode.MARATHI, LanguageCode.MALAYALAM,
        LanguageCode.KANNADA,
    )

    private val phrases = mapOf(
        LanguageCode.HINDI to "मदद चाहिए",
        LanguageCode.ENGLISH to "We need help",
        LanguageCode.TAMIL to "உதவி தேவை",
        LanguageCode.TELUGU to "సహాయం కావాలి",
        LanguageCode.ODIA to "ସାହାଯ୍ୟ ଆବଶ୍ୟକ",
        LanguageCode.BENGALI to "সাহায্য দরকার",
        LanguageCode.GUJARATI to "મદદ જોઈએ છે",
        LanguageCode.MARATHI to "मदत हवी आहे",
        LanguageCode.MALAYALAM to "സഹായം വേണം",
        LanguageCode.KANNADA to "ಸಹಾಯ ಬೇಕು",
    )

    data class Result(
        val language: LanguageCode,
        val sttText: String = "",
        val script: String = "Not verified",
        val sttDecodeMs: Long? = null,
        val ttsSynthesisMs: Long? = null,
        val ttsSampleRateHz: Int? = null,
        val audioPlaybackAttempted: Boolean = false,
        val sampledProcessPssMb: Int? = null,
        val sttError: String? = null,
        val ttsError: String? = null,
    ) {
        val sttPassed: Boolean get() = sttError == null && script == "Native script"
        val ttsGenerated: Boolean get() = ttsError == null && ttsSynthesisMs != null
    }

    fun scriptStatus(language: LanguageCode, text: String): String {
        val range = when (language) {
            LanguageCode.HINDI, LanguageCode.MARATHI -> 0x0900..0x097F
            LanguageCode.TAMIL -> 0x0B80..0x0BFF
            LanguageCode.TELUGU -> 0x0C00..0x0C7F
            LanguageCode.ODIA -> 0x0B00..0x0B7F
            LanguageCode.BENGALI -> 0x0980..0x09FF
            LanguageCode.GUJARATI -> 0x0A80..0x0AFF
            LanguageCode.MALAYALAM -> 0x0D00..0x0D7F
            LanguageCode.KANNADA -> 0x0C80..0x0CFF
            LanguageCode.ENGLISH -> 0x0041..0x007A
            else -> return "Not verified"
        }
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return "Empty"
        val native = letters.count { it.code in range }
        return if (native * 2 > letters.length) "Native script" else "Wrong or romanized script"
    }

    suspend fun run(
        context: Context,
        session: ActiveLanguageSessionManager,
        onResult: suspend (Result) -> Unit,
    ) {
        val previousStt = session.activeSttLanguage.value
        val previousAuto = session.isSttAutoDetect.value
        val previousTarget = session.activeTargetLanguage.value
        val previousTts = session.activeTtsLanguage.value
        try {
            for (language in languages) {
                session.releaseAll()
                var result = Result(language)
                try {
                    session.ensureStt(language, autoDetect = false, targetLanguage = null)
                    val engine = session.currentSttEngine as? SherpaOnnxSpeechRecognizer
                        ?: error("Selected STT engine is unavailable")
                    val audio = context.assets.open("benchmark/five_self_test/${language.wireCode}.wav")
                        .use { WavWriter.readWav(it) }
                    check(audio.isNotEmpty()) { "Bundled STT sample is empty" }
                    val start = SystemClock.elapsedRealtimeNanos()
                    val transcript = engine.decodeDirect(audio, silencePadding = 1600)
                    result = result.copy(
                        sttText = transcript.text,
                        script = scriptStatus(language, transcript.text),
                        sttDecodeMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000,
                    )
                    if (result.script != "Native script") {
                        result = result.copy(sttError = "No native-script transcript")
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    result = result.copy(sttError = error.message ?: "STT failed")
                }
                try {
                    session.ensureTts(language)
                    val engine = session.currentTtsEngine ?: error("Selected TTS engine is unavailable")
                    val start = SystemClock.elapsedRealtimeNanos()
                    val synthesized = engine.synthesize(SpeechSynthesisRequest(
                        languageCode = language,
                        text = phrases.getValue(language),
                        correlationId = "five-language-self-test",
                    ))
                    val synthesisMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                    check(synthesized.sampleRateHz > 0 && synthesized.pcmAudio.any { abs(it) > 0.001f }) {
                        "TTS generated silent or invalid audio"
                    }
                    val speaker = SpeakerAudioSink(context)
                    try {
                        speaker.init(synthesized.sampleRateHz)
                        speaker.play(synthesized.pcmAudio)
                        speaker.flushAndStop()
                    } finally {
                        speaker.release()
                    }
                    result = result.copy(
                        ttsSynthesisMs = synthesisMs,
                        ttsSampleRateHz = synthesized.sampleRateHz,
                        audioPlaybackAttempted = true,
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    result = result.copy(ttsError = error.message ?: "TTS or playback failed")
                }
                result = result.copy(sampledProcessPssMb = (Debug.getPss() / 1024).toInt())
                onResult(result)
            }
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                session.releaseAll()
                runCatching { if (previousStt != null) session.ensureStt(previousStt, previousAuto, previousTarget) }
                runCatching { if (previousTts != null) session.ensureTts(previousTts) }
            }
        }
    }
}
