package com.example.itantra.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.itantra.data.settings.AppSettings
import com.example.itantra.data.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    fun setVadSensitivity(value: Int) = update { it.copy(vadSensitivity = value.coerceIn(1, 3)) }
    fun setNoiseSuppressionDb(value: Int) = update { it.copy(noiseSuppressionDb = value.coerceIn(6, 36)) }
    fun setEmergencyOverrideSilent(enabled: Boolean) = update { it.copy(emergencyOverrideSilent = enabled) }
    fun setEmergencyPlaybackVolume(value: Int) = update { it.copy(emergencyPlaybackVolume = value.coerceIn(80, 100)) }
    fun setEmergencyTtsAnnounce(enabled: Boolean) = update { it.copy(emergencyTtsAnnounce = enabled) }
    fun setEmergencyRequireConfirmation(enabled: Boolean) = update { it.copy(emergencyRequireConfirmation = enabled) }
    fun setOperatorName(name: String) = update { it.copy(operatorName = name) }

    fun resetToDefault() {
        viewModelScope.launch { repository.resetToDefault() }
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.updateFrom(settings.value, transform) }
    }
}

class SettingsViewModelFactory(private val repository: SettingsRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            return SettingsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
