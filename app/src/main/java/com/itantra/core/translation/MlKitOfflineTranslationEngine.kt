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
import kotlinx.coroutines.flow.update
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

        val ML_KIT_SUPPORTED_LANGUAGES: Set<LanguageCode> = setOf(
            LanguageCode.HINDI,
            LanguageCode.ENGLISH,
            LanguageCode.BENGALI,
            LanguageCode.GUJARATI,
            LanguageCode.MARATHI,
            LanguageCode.KANNADA,
            LanguageCode.TAMIL,
            LanguageCode.TELUGU
        )

        fun toMlKitLanguageTag(lang: LanguageCode): String? = when (lang) {
            LanguageCode.HINDI -> TranslateLanguage.HINDI
            LanguageCode.ENGLISH -> TranslateLanguage.ENGLISH
            LanguageCode.BENGALI -> TranslateLanguage.BENGALI
            LanguageCode.GUJARATI -> TranslateLanguage.GUJARATI
            LanguageCode.MARATHI -> TranslateLanguage.MARATHI
            LanguageCode.KANNADA -> TranslateLanguage.KANNADA
            LanguageCode.TAMIL -> TranslateLanguage.TAMIL
            LanguageCode.TELUGU -> TranslateLanguage.TELUGU
            LanguageCode.MALAYALAM -> null
            LanguageCode.ODIA -> null
        }
    }

    override val supportedSourceLanguages: Set<LanguageCode> = ML_KIT_SUPPORTED_LANGUAGES
    override val supportedTargetLanguages: Set<LanguageCode> = ML_KIT_SUPPORTED_LANGUAGES

    // Cached translators — created on-demand per language pair, reused across calls
    private val translators = java.util.concurrent.ConcurrentHashMap<Pair<LanguageCode, LanguageCode>, Translator>()

    private val _modelState = MutableStateFlow(TranslationModelState.NOT_INSTALLED)
    val modelState: StateFlow<TranslationModelState> = _modelState

    private val _pairModelStates = MutableStateFlow<Map<LanguageCode, TranslationModelState>>(
        ML_KIT_SUPPORTED_LANGUAGES.associateWith { TranslationModelState.NOT_INSTALLED }
    )
    val pairModelStates: StateFlow<Map<LanguageCode, TranslationModelState>> = _pairModelStates

    private var _isLoaded = false

    override val isLoaded: Boolean
        get() = _isLoaded

    override fun init(modelsDir: File) {
        init()
    }

    /**
     * Convenience init for when no modelsDir is needed (ML Kit manages its own storage).
     */
    fun init() {
        createDefaultTranslators()
        checkModelReadiness()
    }

    private fun createDefaultTranslators() {
        try {
            getOrCreateTranslator(LanguageCode.HINDI, LanguageCode.ENGLISH)
            getOrCreateTranslator(LanguageCode.ENGLISH, LanguageCode.HINDI)
            Log.i(TAG, "Default translator clients created (HI<->EN)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create default translator clients", e)
        }
    }

    fun getOrCreateTranslator(source: LanguageCode, target: LanguageCode): Translator? {
        val srcTag = toMlKitLanguageTag(source) ?: return null
        val tgtTag = toMlKitLanguageTag(target) ?: return null
        return translators.computeIfAbsent(source to target) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(srcTag)
                .setTargetLanguage(tgtTag)
                .build()
            Translation.getClient(options)
        }
    }

    fun checkModelReadiness() {
        val modelManager = RemoteModelManager.getInstance()
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                val downloadedTags = models.map { it.language }.toSet()
                val hasEn = downloadedTags.contains(TranslateLanguage.ENGLISH)
                val newMap = mutableMapOf<LanguageCode, TranslationModelState>()

                for (lang in ML_KIT_SUPPORTED_LANGUAGES) {
                    val tag = toMlKitLanguageTag(lang)
                    val isReady = if (lang == LanguageCode.ENGLISH) {
                        hasEn
                    } else {
                        hasEn && tag != null && downloadedTags.contains(tag)
                    }
                    newMap[lang] = if (isReady) TranslationModelState.READY else TranslationModelState.NOT_INSTALLED
                }
                _pairModelStates.value = newMap
                val hasHi = newMap[LanguageCode.HINDI] == TranslationModelState.READY
                _modelState.value = if (hasHi) TranslationModelState.READY else TranslationModelState.NOT_INSTALLED
                _isLoaded = hasHi || newMap.values.any { it == TranslationModelState.READY }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Could not check model readiness: ${e.message}")
            }
    }

    /**
     * Downloads translation models for a specific language pair with English.
     */
    suspend fun prepareOfflineModelForLanguage(lang: LanguageCode): TranslationModelState = withContext(Dispatchers.IO) {
        if (!ML_KIT_SUPPORTED_LANGUAGES.contains(lang) || lang == LanguageCode.ENGLISH) {
            return@withContext TranslationModelState.READY
        }
        val langTag = toMlKitLanguageTag(lang) ?: return@withContext TranslationModelState.NOT_INSTALLED
        _pairModelStates.update { it + (lang to TranslationModelState.DOWNLOADING) }

        val conditions = DownloadConditions.Builder().build()
        try {
            val toEn = getOrCreateTranslator(lang, LanguageCode.ENGLISH)
            val fromEn = getOrCreateTranslator(LanguageCode.ENGLISH, lang)

            toEn?.downloadModelIfNeeded(conditions)?.await()
            fromEn?.downloadModelIfNeeded(conditions)?.await()

            _pairModelStates.update { it + (lang to TranslationModelState.READY) }
            checkModelReadiness()
            TranslationModelState.READY
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed for $lang", e)
            _pairModelStates.update { it + (lang to TranslationModelState.FAILED) }
            TranslationModelState.FAILED
        }
    }

    /**
     * Selectively prepares offline translation models only for the specified languages.
     * Skips Malayalam and Odia (which have no ML Kit on-device model).
     */
    suspend fun prepareOfflineModels(languages: Set<LanguageCode>): Map<LanguageCode, TranslationModelState> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<LanguageCode, TranslationModelState>()
        for (lang in languages) {
            if (lang == LanguageCode.MALAYALAM || lang == LanguageCode.ODIA) {
                results[lang] = TranslationModelState.NOT_INSTALLED
                continue
            }
            if (lang == LanguageCode.ENGLISH) {
                results[lang] = TranslationModelState.READY
                continue
            }
            results[lang] = prepareOfflineModelForLanguage(lang)
        }
        results
    }

    /**
     * Backward-compatible prepareOfflineModels for Hindi<->English.
     */
    suspend fun prepareOfflineModels(): TranslationModelState = withContext(Dispatchers.IO) {
        prepareOfflineModelForLanguage(LanguageCode.HINDI)
    }

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

        val translator = getOrCreateTranslator(sourceLang, targetLang)
            ?: return failure(text, sourceLang, targetLang, "ENGINE_NOT_INITIALIZED")

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
            translators.values.forEach { it.close() }
            translators.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing translators: ${e.message}")
        }
        _isLoaded = false
        _modelState.value = TranslationModelState.NOT_INSTALLED
        _pairModelStates.value = ML_KIT_SUPPORTED_LANGUAGES.associateWith { TranslationModelState.NOT_INSTALLED }
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
