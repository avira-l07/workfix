package com.itantra.core.inference

import android.util.Log
import com.itantra.domain.model.LanguageCode

/**
 * Fallback detector that inspects Unicode script blocks in text.
 *
 * Used strictly as a fallback when Whisper's acoustic language detection is absent or ambiguous.
 *
 * Rules:
 * - Unambiguous Indic scripts (Telugu, Tamil, Bengali, Gujarati, Kannada, Malayalam, Odia)
 *   reliably map to their respective LanguageCode.
 * - Devanagari is shared between Hindi and Marathi. It NEVER invents a distinction between them.
 *   If manual context is Hindi or Marathi, that context is used; otherwise it returns null with
 *   a diagnostic log.
 * - Latin script does NOT automatically map to English because Whisper may occasionally romanize
 *   Indic utterances.
 */
object LanguageScriptDetector {

    private const val TAG = "LanguageScriptDetector"

    fun detect(text: String, manualFallback: LanguageCode? = null): LanguageCode? {
        if (text.isBlank()) return null

        var devanagariCount = 0
        var bengaliCount = 0
        var gujaratiCount = 0
        var odiaCount = 0
        var tamilCount = 0
        var teluguCount = 0
        var kannadaCount = 0
        var malayalamCount = 0

        for (ch in text) {
            when (ch.code) {
                in 0x0900..0x097F -> devanagariCount++
                in 0x0980..0x09FF -> bengaliCount++
                in 0x0A80..0x0AFF -> gujaratiCount++
                in 0x0B00..0x0B7F -> odiaCount++
                in 0x0B80..0x0BFF -> tamilCount++
                in 0x0C00..0x0C7F -> teluguCount++
                in 0x0C80..0x0CFF -> kannadaCount++
                in 0x0D00..0x0D7F -> malayalamCount++
            }
        }

        val counts = listOf(
            devanagariCount to null, // Special handling below
            bengaliCount to LanguageCode.BENGALI,
            gujaratiCount to LanguageCode.GUJARATI,
            odiaCount to LanguageCode.ODIA,
            tamilCount to LanguageCode.TAMIL,
            teluguCount to LanguageCode.TELUGU,
            kannadaCount to LanguageCode.KANNADA,
            malayalamCount to LanguageCode.MALAYALAM
        )

        // Find unambiguous script winner
        val nonDevanagari = counts.filter { it.second != null && it.first > 0 }
            .maxByOrNull { it.first }

        if (nonDevanagari != null && nonDevanagari.first >= devanagariCount && nonDevanagari.first >= 2) {
            return nonDevanagari.second
        }

        // Handle Devanagari
        if (devanagariCount >= 2 && devanagariCount > (nonDevanagari?.first ?: 0)) {
            return when (manualFallback) {
                LanguageCode.MARATHI -> LanguageCode.MARATHI
                LanguageCode.HINDI -> LanguageCode.HINDI
                else -> {
                    Log.d(TAG, "LANGUAGE_DETECTION_UNCERTAIN: Devanagari script detected without unambiguous Hindi/Marathi context")
                    null
                }
            }
        }

        // Latin script or unknown: never auto-classify Latin as English here
        return null
    }
}
