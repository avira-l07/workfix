package com.itantra.core.translation

import com.itantra.domain.model.LanguageCode
import java.io.File

/**
 * Translation is out of scope for this build — the real MT model (~1.6GB) was never bundled
 * into the APK, and the native JNI bridge was never compiled. Rather than have
 * CTranslate2TranslationEngine attempt (and always fail) to load a model from an empty
 * directory on every app start, this stub makes the "not available" outcome immediate,
 * deliberate, and free of wasted JNI/file-I/O attempts.
 *
 * TranslationRouter and every call site treat this exactly like any other TranslationEngine
 * that failed to load — the failure path already renders honestly (see TransceiverCoordinator's
 * "transcript only" handling and DESIGN_SPEC.md Rule 0) — so no other code needs to change to
 * accommodate this.
 *
 * To bring translation back later: swap AppGraph's `translationEngine` binding back to
 * `CTranslate2TranslationEngine().apply { init(...) }` once a real, appropriately-sized model
 * and the compiled native library are actually bundled. Nothing else in the app needs to change.
 */
class UnavailableTranslationEngine : TranslationEngine {

    override val isLoaded: Boolean = false
    override val supportedSourceLanguages: Set<LanguageCode> = emptySet()
    override val supportedTargetLanguages: Set<LanguageCode> = emptySet()

    override fun init(modelsDir: File) {
        // Intentionally a no-op — no model, no native library, nothing to load.
    }

    override fun release() {
        // Nothing to release.
    }

    override suspend fun translate(
        text: String,
        sourceLang: LanguageCode,
        targetLang: LanguageCode
    ): TranslationResult {
        return TranslationResult(
            originalText = text,
            translatedText = "",
            isSuccessful = false,
            sourceLanguage = sourceLang,
            targetLanguage = targetLang,
            error = "NOT_INCLUDED_IN_BUILD"
        )
    }
}
