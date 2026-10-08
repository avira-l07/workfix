package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.SpeechSynthesisResult

internal fun requireSpeechAudio(audio: SpeechSynthesisResult) {
    check(audio.sampleRateHz > 0 && audio.pcmAudio.isNotEmpty()) { "TTS_EMPTY_PCM" }
    check(audio.channelCount == 1 && audio.pcmAudio.all { it.isFinite() } &&
        audio.pcmAudio.any { kotlin.math.abs(it) > 0.001f }) { "TTS produced silent or invalid audio" }
}

/**
 * Returns a diagnostic string if this result should be blocked, or null if it is valid to transmit.
 *
 * Rules:
 * - AUTO mode only: reject if Whisper detected a language we do not support at all.
 * - Both modes: reject a script mismatch; selecting Hindi does not make English text Hindi.
 * - Whisper native translate-to-English: never check script (output is expected to be Latin).
 */
internal fun speechOutputDiagnostic(
    text: String,
    detectedCode: String,
    resolvedLanguage: LanguageCode,
    autoDetect: Boolean,
    translatesToEnglish: Boolean
): String? {
    // Only reject in AUTO mode when Whisper detected a language we have no codec for.
    if (autoDetect && detectedCode.isNotBlank() && languageCodeFromWhisper(detectedCode) == null) {
        return "UNSUPPORTED_SPEECH_LANGUAGE"
    }
    // Native Whisper translation always produces English text — script check would be wrong.
    if (translatesToEnglish) return null
    // Validate the output rather than relabelling it based on the user's selection.
    return LanguageScriptDetector.detectScriptMismatch(text, resolvedLanguage)?.diagnostic
}
