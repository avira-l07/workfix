package com.itantra.core.translation

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.itantra.core.inference.LanguageScriptDetector
import com.itantra.domain.model.LanguageCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 5/6/7/8/10: Google ML Kit on-device translation engine for Hindi↔English.
 *
 * Supports exactly two translation routes:
 *   - Hindi → English
 *   - English → Hindi
 *
 * Models are downloaded once via [prepareOfflineModels] (requires Internet + Wi-Fi).
 * After provisioning, [translate] runs fully offline — no cloud calls.
 *
 * Two [Translator] clients are cached and reused across calls — never recreated per sentence.
 *
 * IMPORTANT: ML Kit requires Google Play Services on the device. Devices without
 * Play Services (AOSP/custom ROMs) will fail at model download and translation.
 */
class MlKitOfflineTranslationEngine(
    private val context: Context
) : TranslationEngine {

    companion object {
        private const val TAG = "MlKitMT"
    }

    override val supportedSourceLanguages: Set<LanguageCode> =
        setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)

    override val supportedTargetLanguages: Set<LanguageCode> =
        setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)

    // Cached translators — created once, reused across all calls
    private var hiToEnTranslator: Translator? = null
    private var enToHiTranslator: Translator? = null

    private val _modelState = MutableStateFlow(TranslationModelState.NOT_INSTALLED)
    val modelState: StateFlow<TranslationModelState> = _modelState

    private var _isLoaded = false

    override val isLoaded: Boolean
        get() = _isLoaded

    override fun init(modelsDir: File) {
        // ML Kit manages its own model storage internally.
        // We just create the translator instances here.
        createTranslators()
        // Check if models are already downloaded
        checkModelReadiness()
    }

    /**
     * Convenience init for when no modelsDir is needed (ML Kit manages its own storage).
     */
    fun init() {
        createTranslators()
        checkModelReadiness()
    }

    private fun createTranslators() {
        try {
            val hiToEnOptions = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.HINDI)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            hiToEnTranslator = Translation.getClient(hiToEnOptions)

            val enToHiOptions = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.HINDI)
                .build()
            enToHiTranslator = Translation.getClient(enToHiOptions)

            Log.i(TAG, "Translator clients created (HI→EN, EN→HI)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create translator clients", e)
        }
    }

    private fun checkModelReadiness() {
        val modelManager = RemoteModelManager.getInstance()
        val hiModel = TranslateRemoteModel.Builder(TranslateLanguage.HINDI).build()
        val enModel = TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build()

        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                val hasHi = models.any { it.language == TranslateLanguage.HINDI }
                val hasEn = models.any { it.language == TranslateLanguage.ENGLISH }
                if (hasHi && hasEn) {
                    _modelState.value = TranslationModelState.READY
                    _isLoaded = true
                    Log.i(TAG, "Both HI and EN models already downloaded — READY")
                } else {
                    _modelState.value = TranslationModelState.NOT_INSTALLED
                    _isLoaded = false
                    Log.i(TAG, "Models not yet downloaded: HI=$hasHi, EN=$hasEn")
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Could not check model readiness: ${e.message}")
                _modelState.value = TranslationModelState.NOT_INSTALLED
                _isLoaded = false
            }
    }

    /**
     * Phase 7: Downloads both Hindi and English translation models for offline use.
     *
     * Requires Internet (Wi-Fi preferred). Call this during setup/provisioning phase.
     * After this completes with [TranslationModelState.READY], all subsequent
     * [translate] calls work fully offline.
     */
    suspend fun prepareOfflineModels(): TranslationModelState = withContext(Dispatchers.IO) {
        _modelState.value = TranslationModelState.DOWNLOADING

        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        try {
            // Download both translators' models
            hiToEnTranslator?.downloadModelIfNeeded(conditions)?.await()
            Log.i(TAG, "HI→EN model download complete")

            enToHiTranslator?.downloadModelIfNeeded(conditions)?.await()
            Log.i(TAG, "EN→HI model download complete")

            _modelState.value = TranslationModelState.READY
            _isLoaded = true
            Log.i(TAG, "All translation models provisioned — READY OFFLINE")
            TranslationModelState.READY
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            _modelState.value = TranslationModelState.FAILED
            _isLoaded = false
            TranslationModelState.FAILED
        }
    }

    /**
     * Phase 8: translate() uses only local ML Kit Translator — never calls REST/cloud.
     * Returns MODEL_NOT_PROVISIONED if model is not downloaded.
     * Never silently sends source-language text as translated output.
     *
     * Phase 10: Translation output validation.
     */
    override suspend fun translate(
        text: String,
        sourceLang: LanguageCode,
        targetLang: LanguageCode
    ): TranslationResult {
        // Same-language bypass
        if (sourceLang == targetLang) {
            return TranslationResult(
                originalText = text,
                translatedText = text,
                isSuccessful = true,
                sourceLanguage = sourceLang,
                targetLanguage = targetLang
            )
        }

        // Validate supported routes
        if (sourceLang !in supportedSourceLanguages || targetLang !in supportedTargetLanguages) {
            return failure(text, sourceLang, targetLang, "UNSUPPORTED_ROUTE")
        }

        // Check model provisioning
        if (!_isLoaded) {
            // Re-check in case models were downloaded externally
            checkModelReadiness()
            if (!_isLoaded) {
                return failure(text, sourceLang, targetLang, "MODEL_NOT_PROVISIONED")
            }
        }

        val translator = when {
            sourceLang == LanguageCode.HINDI && targetLang == LanguageCode.ENGLISH -> hiToEnTranslator
            sourceLang == LanguageCode.ENGLISH && targetLang == LanguageCode.HINDI -> enToHiTranslator
            else -> return failure(text, sourceLang, targetLang, "UNSUPPORTED_ROUTE")
        }

        if (translator == null) {
            return failure(text, sourceLang, targetLang, "ENGINE_NOT_INITIALIZED")
        }

        return try {
            val translated = withContext(Dispatchers.IO) {
                translator.translate(text).await()
            }

            // Phase 10: Translation output validation
            if (translated.isBlank()) {
                return failure(text, sourceLang, targetLang, "TRANSLATION_EMPTY")
            }

            // Phase 10: HI→EN validation — should not simply echo Hindi source
            if (sourceLang == LanguageCode.HINDI && targetLang == LanguageCode.ENGLISH) {
                if (translated.trim() == text.trim()) {
                    Log.w(TAG, "HI→EN produced identical output — possible echo")
                }
            }

            // Phase 10: EN→HI validation — result must contain Devanagari
            if (sourceLang == LanguageCode.ENGLISH && targetLang == LanguageCode.HINDI) {
                if (!LanguageScriptDetector.containsDevanagari(translated)) {
                    Log.w(TAG, "EN→HI produced non-Devanagari output: ${translated.take(50)}")
                    return TranslationResult(
                        originalText = text,
                        translatedText = translated,
                        isSuccessful = false,
                        sourceLanguage = sourceLang,
                        targetLanguage = targetLang,
                        error = "OUTPUT_SCRIPT_MISMATCH"
                    )
                }
            }

            if (com.example.itantra.BuildConfig.DEBUG) {
                // Log only metadata, never plaintext (Phase 24)
                Log.d(TAG, "translate: ${sourceLang.wireCode}→${targetLang.wireCode} " +
                    "inLen=${text.length} outLen=${translated.length}")
            }

            TranslationResult(
                originalText = text,
                translatedText = translated,
                isSuccessful = true,
                sourceLanguage = sourceLang,
                targetLanguage = targetLang
            )
        } catch (e: Exception) {
            Log.e(TAG, "Translation inference failed: ${e.message}")
            failure(text, sourceLang, targetLang, "INFERENCE_FAILED")
        }
    }

    override fun release() {
        try {
            hiToEnTranslator?.close()
            enToHiTranslator?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing translators: ${e.message}")
        }
        hiToEnTranslator = null
        enToHiTranslator = null
        _isLoaded = false
        _modelState.value = TranslationModelState.NOT_INSTALLED
        Log.i(TAG, "Translators released")
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
