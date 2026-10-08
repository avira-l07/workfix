package com.example.itantra.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.itantra.data.settings.AppSettings
import com.example.itantra.data.settings.SettingsRepository
import com.example.itantra.data.settings.ThemeMode
import com.example.itantra.data.settings.ColorPalette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    private val _operatorNameInput = MutableStateFlow<TextFieldValue?>(null)
    val operatorNameInput: StateFlow<TextFieldValue?> = _operatorNameInput.asStateFlow()

    init {
        viewModelScope.launch {
            val name = repository.settings.first().operatorName
            // Load once: asynchronous disk echoes must not replace an active edit.
            if (_operatorNameInput.value == null) {
                _operatorNameInput.value = TextFieldValue(name, selection = TextRange(name.length))
            }
        }
    }

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
    fun setOperatorName(value: TextFieldValue) {
        val previousText = _operatorNameInput.value?.text
        _operatorNameInput.value = value
        if (value.text != previousText) update { it.copy(operatorName = value.text) }
    }
    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }
    fun setColorPalette(palette: ColorPalette) = update { it.copy(colorPalette = palette) }

    fun resetToDefault() {
        _operatorNameInput.value = TextFieldValue()
        viewModelScope.launch { repository.resetToDefault() }
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.update(transform) }
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
