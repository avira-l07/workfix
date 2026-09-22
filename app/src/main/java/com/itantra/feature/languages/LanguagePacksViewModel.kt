package com.itantra.feature.languages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.core.inference.ActiveLanguageSessionManager
import com.itantra.domain.model.LanguageCode
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
) : ViewModel() {

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
            repository.setActiveLanguage(code)
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
}
