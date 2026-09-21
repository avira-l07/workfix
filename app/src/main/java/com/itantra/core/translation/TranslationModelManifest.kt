package com.itantra.core.translation

import kotlinx.serialization.Serializable

/**
 * Manifest for one offline machine-translation direction.
 *
 * Put manifest.json inside:
 *   filesDir/translation_models/indic-en/
 *   filesDir/translation_models/en-indic/
 *
 * Every file named in [requiredFiles] must have a SHA-256 entry.
 */
@Serializable
data class TranslationModelManifest(
    val modelId: String,
    val version: String,
    val direction: String,
    val runtime: String = "ctranslate2",
    val requiredFiles: List<String>,
    val sha256: Map<String, String>,
    val minimumAppVersion: Int = 1
)
