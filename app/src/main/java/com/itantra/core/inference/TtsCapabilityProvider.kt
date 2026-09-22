package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode

/**
 * Provides inquiry into whether TTS assets are physically installed on disk for [language].
 * Used by TransceiverCoordinator to verify receiver voice playback feasibility before synthesis.
 */
fun interface TtsCapabilityProvider {
    fun isTtsInstalled(language: LanguageCode): Boolean
}
