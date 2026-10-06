package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode

data class ModelFileSpec(
    val type: EngineType,
    val languageCode: LanguageCode,
    val requiredFiles: List<String>,
    val mainModelFile: String,
    val tokensFile: String,
    val auxFile: String? = null,
    val isShared: Boolean = false,
    val sharedPath: String? = null
) {
    enum class EngineType { STT, TTS }
}

object ModelFileSpecs {
    // Language codes present in the published Whisper encoder metadata.
    val supportedWhisperLanguages: Set<LanguageCode> = LanguageCode.entries.toSet() - LanguageCode.ODIA
    // Odia uses a dedicated CTC model; the shared Whisper export has no Odia token.
    val supportedSttLanguages: Set<LanguageCode> = LanguageCode.entries.toSet()

    /** Shared Whisper file layout. Prefer AdditionalSttModel for a manually selected CTC language. */
    fun getSttSpec(lang: LanguageCode): ModelFileSpec {
        return ModelFileSpec(
            type = ModelFileSpec.EngineType.STT,
            languageCode = lang,
            requiredFiles = listOf(
                "tiny-encoder.int8.onnx",
                "tiny-decoder.int8.onnx",
                "tiny-tokens.txt"
            ),
            mainModelFile = "tiny-encoder.int8.onnx",
            auxFile = "tiny-decoder.int8.onnx",
            tokensFile = "tiny-tokens.txt",
            isShared = true,
            sharedPath = "shared/stt"
        )
    }

    fun getTtsSpec(lang: LanguageCode): ModelFileSpec? {
        when (lang) {
            LanguageCode.HINDI,
            LanguageCode.ENGLISH,
            LanguageCode.BENGALI,
            LanguageCode.GUJARATI,
            LanguageCode.MARATHI,
            LanguageCode.KANNADA,
            LanguageCode.MALAYALAM,
            LanguageCode.TAMIL,
            LanguageCode.TELUGU,
            LanguageCode.ODIA -> {}
            else -> return null
        }
        val modelFile = "model.onnx"

        return ModelFileSpec(
            type = ModelFileSpec.EngineType.TTS,
            languageCode = lang,
            requiredFiles = listOf(
                modelFile,
                "tokens.txt"
            ) + if (lang == LanguageCode.MARATHI) MarathiPiperVoice.requiredDataFiles else emptyList(),
            mainModelFile = modelFile,
            tokensFile = "tokens.txt",
            auxFile = ""
        )
    }
}
