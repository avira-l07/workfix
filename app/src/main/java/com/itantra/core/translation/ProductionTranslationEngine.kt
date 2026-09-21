package com.itantra.core.translation

import com.itantra.domain.model.LanguageCode
import java.io.File

/**
 * Production-facing Hindi<->English translation engine.
 *
 * It deliberately reuses the existing CTranslate2TranslationEngine rather than
 * duplicating translation logic. Model integrity is checked first.
 *
 * IMPORTANT:
 * Real inference requires the native library expected by
 * CTranslate2TranslationEngine (libitantra_mt_jni.so) plus its actual runtime
 * dependencies. If the native runtime is absent, this class fails safely with
 * MODEL_RUNTIME_OR_LOAD_FAILED instead of pretending that translation succeeded.
 */
class ProductionTranslationEngine : TranslationEngine {

    override val supportedSourceLanguages: Set<LanguageCode> =
        setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)

    override val supportedTargetLanguages: Set<LanguageCode> =
        setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)

    private var manager: OfflineTranslationModelManager? = null
    private var delegate: CTranslate2TranslationEngine? = null

    override val isLoaded: Boolean
        get() {
            val engine = delegate ?: return false
            return engine.indicEnReady && engine.enIndicReady
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
