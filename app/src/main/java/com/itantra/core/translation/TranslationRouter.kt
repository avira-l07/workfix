package com.itantra.core.translation

import com.itantra.domain.model.LanguageCode

/**
 * Phase 24: Updated from the stale "translation not included in this build" to reflect
 * the current state where ML Kit offline translation is wired in.
 * This message is shown when translation models are not yet provisioned.
 */
const val TRANSLATION_SCOPE_NOTE = "Offline translation model not installed \u2014 provision via Diagnostics"

/**
 * Shown when translation models ARE provisioned and ready.
 */
const val TRANSLATION_READY_NOTE = "Offline Hindi\u2194English translation ready"

class TranslationRouter(
    private val engine: TranslationEngine
) {
    val invocationCount = java.util.concurrent.atomic.AtomicInteger(0)

    suspend fun routeAndTranslate(
        text: String,
        source: LanguageCode,
        target: LanguageCode
    ): TranslationResult {
        invocationCount.incrementAndGet()
        // Was unconditional Log.i with the full message text - fine for a hackathon build,
        // but every translated message ends up in plaintext in Logcat. Gated behind
        // BuildConfig.DEBUG so it's still there while developing but stripped from release.
        if (com.example.itantra.BuildConfig.DEBUG) {
            android.util.Log.i("ITANTRA_MT_CALL", "routeAndTranslate: source=$source, target=$target, len=${text.length}")
        }

        if (source == target) {
            return TranslationResult(text, text, true, source, target)
        }

        // Section 22: Deterministic emergency phrasebook fallback — works 100% offline without neural MT
        val emergencyPhrase = com.itantra.domain.model.EmergencyPhraseResolver.translateEmergencyPhrase(text, source, target)
        if (emergencyPhrase != null) {
            if (com.example.itantra.BuildConfig.DEBUG) {
                android.util.Log.i("ITANTRA_MT_CALL", "TranslationRouter: Resolved via deterministic emergency phrasebook -> '$emergencyPhrase'")
            }
            return TranslationResult(
                originalText = text,
                translatedText = emergencyPhrase,
                isSuccessful = true,
                sourceLanguage = source,
                targetLanguage = target
            )
        }

        if (!engine.supportedSourceLanguages.contains(source) || !engine.supportedTargetLanguages.contains(target)) {
            android.util.Log.w("ITANTRA_MT_CALL", "TranslationRouter: UNSUPPORTED_ROUTE between $source and $target")
            return TranslationResult(text, "", false, source, target, error = "UNSUPPORTED_ROUTE")
        }

        return try {
            val res = engine.translate(text, source, target)
            if (com.example.itantra.BuildConfig.DEBUG) {
                android.util.Log.i("ITANTRA_MT_CALL", "TranslationRouter: success=${res.isSuccessful}, error=${res.error}")
            }
            res
        } catch (e: Exception) {
            android.util.Log.e("ITANTRA_MT_CALL", "TranslationRouter: Exception in engine.translate", e)
            TranslationResult(text, "", false, source, target, error = "INFERENCE_FAILED")
        }
    }
}
