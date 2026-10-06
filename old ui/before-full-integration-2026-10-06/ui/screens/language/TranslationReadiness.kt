package com.example.itantra.ui.screens.language

import com.itantra.core.translation.TranslationModelState
import com.itantra.domain.model.LanguageCode

internal fun isOfflineTranslationReady(
    language: LanguageCode,
    states: Map<LanguageCode, TranslationModelState>,
): Boolean = states[language] == TranslationModelState.READY
