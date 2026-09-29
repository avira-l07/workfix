package com.example.itantra.ui.screens.language

import com.itantra.core.translation.TranslationModelState
import com.itantra.domain.model.LanguageCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationReadinessTest {
    @Test fun hindiAndEnglishAreNotReadyUntilProvisioningConfirmsIt() {
        for (language in listOf(LanguageCode.HINDI, LanguageCode.ENGLISH)) {
            assertFalse(isOfflineTranslationReady(language, emptyMap()))
            for (state in TranslationModelState.entries) {
                if (state != TranslationModelState.READY) {
                    assertFalse(isOfflineTranslationReady(language, mapOf(language to state)))
                }
            }
            assertTrue(isOfflineTranslationReady(language, mapOf(language to TranslationModelState.READY)))
        }
    }
}
