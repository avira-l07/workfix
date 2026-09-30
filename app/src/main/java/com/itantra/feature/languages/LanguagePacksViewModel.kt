package com.itantra.feature.languages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.core.inference.ActiveLanguageSessionManager
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.SpeechInputMode
import com.itantra.domain.model.LanguagePackSummary
import com.itantra.domain.repository.LanguagePackRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LanguagePacksUiState(
    val packs: List<LanguagePackSummary> = emptyList(),
)

class LanguagePacksViewModel(
    private val repository: LanguagePackRepository,
    private val sessionManager: ActiveLanguageSessionManager? = null,
    private val storage: com.itantra.core.storage.LanguagePackStorage? = null,
    private val translationEngine: com.itantra.core.translation.TranslationEngine? = null,
    private val provisioner: ((Set<LanguageCode>) -> Unit)? = null,
) : ViewModel() {

    val translationStates: StateFlow<Map<LanguageCode, com.itantra.core.translation.TranslationModelState>> =
        (translationEngine as? com.itantra.core.translation.MlKitOfflineTranslationEngine)?.pairModelStates
            ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap())

    val uiState: StateFlow<LanguagePacksUiState> = repository.observePackSummaries()
        .map { packs -> LanguagePacksUiState(packs = packs) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LanguagePacksUiState(),
        )

    /**
     * Starts downloading the language pack for [code] directly without switching active language (FIX 006).
     */
    fun downloadPack(code: LanguageCode) {
        viewModelScope.launch {
            repository.startDownload(code)
        }
    }

    /**
     * Cancels an in-progress download for [code].
     */
    fun cancelDownload(code: LanguageCode) {
        viewModelScope.launch {
            repository.cancelDownload(code)
        }
    }

    /**
     * Requests activation of the selected language pack in repository.
     * Model lifecycle is strictly managed by AppGraph's centralized observer.
     */
    fun activateLanguage(code: LanguageCode) {
        viewModelScope.launch {
            if (repository.setActiveLanguage(code)) {
                repository.setManualSttLanguage(code)
                repository.setSpeechInputMode(SpeechInputMode.MANUAL)
            }
        }
    }

    val targetLanguage: StateFlow<LanguageCode?> = repository.observeTargetLanguage()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    fun setTargetLanguage(code: LanguageCode?) {
        viewModelScope.launch {
            repository.setTargetLanguage(code)
        }
    }

    val enabledMicLanguages: StateFlow<Set<LanguageCode>> = repository.observeEnabledMicLanguages()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)
        )

    val enabledListenLanguages: StateFlow<Set<LanguageCode>> = repository.observeEnabledListenLanguages()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = setOf(LanguageCode.HINDI, LanguageCode.ENGLISH)
        )

    private val _stagedMicLanguages = kotlinx.coroutines.flow.MutableStateFlow<Set<LanguageCode>?>(null)
    val stagedMicLanguages: StateFlow<Set<LanguageCode>?> = _stagedMicLanguages

    private val _stagedListenLanguages = kotlinx.coroutines.flow.MutableStateFlow<Set<LanguageCode>?>(null)
    val stagedListenLanguages: StateFlow<Set<LanguageCode>?> = _stagedListenLanguages

    fun toggleStagedMicLanguage(code: LanguageCode) {
        val current = _stagedMicLanguages.value ?: enabledMicLanguages.value
        _stagedMicLanguages.value = if (current.contains(code)) {
            if (current.size > 1) current - code else current // keep at least 1
        } else {
            current + code
        }
    }

    fun toggleStagedListenLanguage(code: LanguageCode) {
        val current = _stagedListenLanguages.value ?: enabledListenLanguages.value
        _stagedListenLanguages.value = if (current.contains(code)) {
            if (current.size > 1) current - code else current // keep at least 1
        } else {
            current + code
        }
    }

    fun resetStagedLanguages() {
        _stagedMicLanguages.value = null
        _stagedListenLanguages.value = null
    }

    fun retryProvision(code: LanguageCode) {
        viewModelScope.launch {
            repository.startDownload(code)
        }
    }

    /**
     * Day 2 (TASK 2, 3, 4): Confirms staged selections, persists them, and triggers selective
     * TTS download + ML Kit provisioning only for the selected languages.
     */
    fun applyAndProvisionSelected() {
        val finalMic = _stagedMicLanguages.value ?: enabledMicLanguages.value
        val finalListen = _stagedListenLanguages.value ?: enabledListenLanguages.value

        viewModelScope.launch {
            repository.setEnabledMicLanguages(finalMic)
            repository.setEnabledListenLanguages(finalListen)
            _stagedMicLanguages.value = null
            _stagedListenLanguages.value = null

            val allSelected = finalMic + finalListen
            val summaries = repository.observePackSummaries().firstOrNull() ?: emptyList()

            // 1. Trigger selective TTS download only for selected languages not already downloaded
            for (code in allSelected) {
                val summary = summaries.find { it.language.code == code }
                val isDownloaded = summary?.isTtsDownloaded == true
                if (!isDownloaded) {
                    repository.startDownload(code)
                }
            }

            // 2. Trigger selective ML Kit provisioning (skip ML and OR)
            provisioner?.invoke(allSelected) ?: com.itantra.app.AppGraph.provisionSelectedLanguages(allSelected)
        }
    }
}
