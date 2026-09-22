package com.itantra.core.translation

import com.itantra.domain.model.LanguageCode
import java.io.File

/**
 * Production-facing Hindi<->English translation engine.
 *
 * It deliberately reuses the existing CTranslate2TranslationEngine rather than
 * duplicating translation logic. Model integrity is checked first.
 *
 * FIX 007 — EXTERNAL BLOCKER:
 * Real inference requires the native JNI library (libitantra_mt_jni.so) to be
 * compiled and packaged under app/src/main/jniLibs/arm64-v8a/. Without it the
 * native System.loadLibrary() call silently fails and nativeCreateEngine() is
 * never linked, making all translation attempts return MODEL_RUNTIME_OR_LOAD_FAILED.
 * This is reported as an EXTERNAL BLOCKER: the runtime and converted CTranslate2
 * model artifacts (indic-en/, en-indic/) are not yet included in the repository.
 * When they are available, init() will succeed and directional readiness flags
 * (indicEnReady, enIndicReady) on CTranslate2TranslationEngine will be set.
 *
 * Self-check error taxonomy (returned in TranslationResult.error):
 *   MODEL_NOT_INSTALLED  — model directory absent from storage
 *   MODEL_INVALID        — model directory present but files missing/corrupt
 *   MODEL_RUNTIME_OR_LOAD_FAILED — native library loaded but engine init failed
 *   INFERENCE_FAILED     — engine loaded, native call threw an exception
 *   ENGINE_NOT_INITIALIZED — init() not called before translate()
 *   UNSUPPORTED_ROUTE    — language pair outside this engine's scope
 *
 * FIX 070:
 * isLoaded is true when AT LEAST ONE translation direction is ready.
 * Use isDirectionLoaded(src, tgt) to check a specific route before routing.
 */
class ProductionTranslationEngine : TranslationEngine {

    override val supportedSourceLanguages: Set<LanguageCode> =
        setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)

    override val supportedTargetLanguages: Set<LanguageCode> =
        setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)

    private var manager: OfflineTranslationModelManager? = null
    private var delegate: CTranslate2TranslationEngine? = null

    /**
     * FIX 070: isLoaded is true when at least ONE translation direction is resident.
     * This allows the engine to be used for a valid route even when the opposite
     * direction model has not been installed yet.
     */
    override val isLoaded: Boolean
        get() {
            val engine = delegate ?: return false
            return engine.indicEnReady || engine.enIndicReady
        }

    /**
     * Returns true only if the specific [sourceLang]->[targetLang] direction is
     * ready for inference. Callers (TransceiverCoordinator, sendCapabilities) should
     * prefer this over isLoaded for per-route decisions.
     */
    fun isDirectionLoaded(sourceLang: LanguageCode, targetLang: LanguageCode): Boolean {
        if (sourceLang == targetLang) return true  // bypass: no MT needed
        val engine = delegate ?: return false
        return when {
            sourceLang == LanguageCode.HINDI && targetLang == LanguageCode.ENGLISH -> engine.indicEnReady
            sourceLang == LanguageCode.ENGLISH && targetLang == LanguageCode.HINDI -> engine.enIndicReady
            // For other Indic pairs pivot translation requires both directions
            targetLang == LanguageCode.ENGLISH -> engine.indicEnReady
            sourceLang == LanguageCode.ENGLISH -> engine.enIndicReady
            else -> engine.indicEnReady && engine.enIndicReady  // Indic->Indic pivot
        }
    }

    override fun init(modelsDir: File) {
        release()

        val localManager = OfflineTranslationModelManager(modelsDir)
        manager = localManager

        // CTranslate2TranslationEngine already supports partial directional loading,
        // so initialize it when at least one verified direction exists.
        val hiEnReady = localManager.isReady(
            OfflineTranslationModelManager.Direction.HINDI_TO_ENGLISH
        )
        val enHiReady = localManager.isReady(
            OfflineTranslationModelManager.Direction.ENGLISH_TO_HINDI
        )

        if (!hiEnReady && !enHiReady) {
            return
        }

        val engine = CTranslate2TranslationEngine()
        engine.init(modelsDir)
        delegate = engine
    }

    override suspend fun translate(
        text: String,
        sourceLang: LanguageCode,
        targetLang: LanguageCode
    ): TranslationResult {
        if (sourceLang == targetLang) {
            return TranslationResult(
                originalText = text,
                translatedText = text,
                isSuccessful = true,
                sourceLanguage = sourceLang,
                targetLanguage = targetLang
            )
        }

        if (sourceLang !in supportedSourceLanguages || targetLang !in supportedTargetLanguages) {
            return failure(text, sourceLang, targetLang, "UNSUPPORTED_ROUTE")
        }

        val localManager = manager
            ?: return failure(text, sourceLang, targetLang, "ENGINE_NOT_INITIALIZED")

        val direction = when {
            sourceLang == LanguageCode.HINDI && targetLang == LanguageCode.ENGLISH ->
                OfflineTranslationModelManager.Direction.HINDI_TO_ENGLISH

            sourceLang == LanguageCode.ENGLISH && targetLang == LanguageCode.HINDI ->
                OfflineTranslationModelManager.Direction.ENGLISH_TO_HINDI

            else ->
                return failure(text, sourceLang, targetLang, "UNSUPPORTED_ROUTE")
        }

        val inspection = localManager.inspect(direction)
        when (inspection.status) {
            OfflineTranslationModelManager.Status.MISSING ->
                return failure(text, sourceLang, targetLang, "MODEL_NOT_INSTALLED")

            OfflineTranslationModelManager.Status.INVALID ->
                return failure(
                    text,
                    sourceLang,
                    targetLang,
                    "MODEL_INVALID:${inspection.reason ?: "UNKNOWN"}"
                )

            OfflineTranslationModelManager.Status.READY -> Unit
        }

        val engine = delegate
            ?: return failure(text, sourceLang, targetLang, "MODEL_RUNTIME_OR_LOAD_FAILED")

        val directionLoaded = when (direction) {
            OfflineTranslationModelManager.Direction.HINDI_TO_ENGLISH ->
                engine.indicEnReady

            OfflineTranslationModelManager.Direction.ENGLISH_TO_HINDI ->
                engine.enIndicReady
        }

        if (!directionLoaded) {
            return failure(text, sourceLang, targetLang, "MODEL_RUNTIME_OR_LOAD_FAILED")
        }

        return try {
            engine.translate(text, sourceLang, targetLang)
        } catch (_: Throwable) {
            failure(text, sourceLang, targetLang, "INFERENCE_FAILED")
        }
    }

    override fun release() {
        try {
            delegate?.release()
        } catch (_: Throwable) {
            // Release must never crash app shutdown/reconfiguration.
        }
        delegate = null
        manager = null
    }

    private fun failure(
        text: String,
        source: LanguageCode,
        target: LanguageCode,
        error: String
    ) = TranslationResult(
        originalText = text,
        translatedText = "",
        isSuccessful = false,
        sourceLanguage = source,
        targetLanguage = target,
        error = error
    )
}
