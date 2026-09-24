package com.itantra.core.inference

import com.itantra.core.storage.LanguagePackStorage
import com.itantra.core.translation.MlKitOfflineTranslationEngine
import com.itantra.core.translation.TranslationModelState
import com.itantra.domain.model.LanguageCode

/**
 * Phase 17: Offline Pre-Flight Readiness Checker.
 *
 * Reports the readiness status of all six speech feature components:
 *   1. Hindi STT
 *   2. English STT
 *   3. Hindi TTS
 *   4. English TTS
 *   5. Hindi→English MT
 *   6. English→Hindi MT
 *
 * Only reports ALL_SPEECH_FEATURES_READY_OFFLINE if all six are ready.
 */
class OfflineReadinessChecker(
    private val storage: LanguagePackStorage,
    private val sessionManager: ActiveLanguageSessionManager,
    private val translationEngine: com.itantra.core.translation.TranslationEngine
) {

    enum class ComponentStatus {
        READY_OFFLINE,
        NOT_INSTALLED,
        DOWNLOADING,
        FAILED
    }

    data class ComponentReadiness(
        val component: String,
        val status: ComponentStatus
    )

    data class OfflineReadinessReport(
        val hindiStt: ComponentReadiness,
        val englishStt: ComponentReadiness,
        val hindiTts: ComponentReadiness,
        val englishTts: ComponentReadiness,
        val hiToEnMt: ComponentReadiness,
        val enToHiMt: ComponentReadiness
    ) {
        val allReady: Boolean
            get() = listOf(hindiStt, englishStt, hindiTts, englishTts, hiToEnMt, enToHiMt)
                .all { it.status == ComponentStatus.READY_OFFLINE }
    }

    fun checkReadiness(): OfflineReadinessReport {
        return OfflineReadinessReport(
            hindiStt = checkStt(LanguageCode.HINDI),
            englishStt = checkStt(LanguageCode.ENGLISH),
            hindiTts = checkTts(LanguageCode.HINDI),
            englishTts = checkTts(LanguageCode.ENGLISH),
            hiToEnMt = checkMt("Hindi→English"),
            enToHiMt = checkMt("English→Hindi")
        )
    }

    private fun checkStt(lang: LanguageCode): ComponentReadiness {
        val spec = ModelFileSpecs.getSttSpec(lang)
        val packDir = storage.packDirectory(lang)
        val packsDir = packDir.parentFile

        val sttDir = if (spec.isShared && spec.sharedPath != null) {
            java.io.File(packsDir, spec.sharedPath)
        } else {
            java.io.File(packDir, "stt")
        }

        val allFilesPresent = spec.requiredFiles.all { fileName ->
            val f = java.io.File(sttDir, fileName)
            f.exists() && f.length() > 0L
        }

        val status = if (allFilesPresent) ComponentStatus.READY_OFFLINE else ComponentStatus.NOT_INSTALLED
        return ComponentReadiness("${lang.name} STT", status)
    }

    private fun checkTts(lang: LanguageCode): ComponentReadiness {
        val installed = storage.isTtsInstalled(lang)
        val status = if (installed) ComponentStatus.READY_OFFLINE else ComponentStatus.NOT_INSTALLED
        return ComponentReadiness("${lang.name} TTS", status)
    }

    private fun checkMt(direction: String): ComponentReadiness {
        val mlKitEngine = translationEngine as? MlKitOfflineTranslationEngine
        val status = if (mlKitEngine != null) {
            when (mlKitEngine.modelState.value) {
                TranslationModelState.READY -> ComponentStatus.READY_OFFLINE
                TranslationModelState.DOWNLOADING -> ComponentStatus.DOWNLOADING
                TranslationModelState.FAILED -> ComponentStatus.FAILED
                TranslationModelState.NOT_INSTALLED -> ComponentStatus.NOT_INSTALLED
            }
        } else {
            // Non-ML Kit engine — check if loaded
            if (translationEngine.isLoaded) ComponentStatus.READY_OFFLINE else ComponentStatus.NOT_INSTALLED
        }
        return ComponentReadiness("$direction MT", status)
    }
}
