package com.example.itantra.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "itantra_settings")

/**
 * Real persistence for Settings.
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val privateContent: com.itantra.core.storage.PrivateContent = com.itantra.core.storage.PrivateContent(),
) {

    private object Keys {
        val VAD_SENSITIVITY = intPreferencesKey("vad_sensitivity")
        val NOISE_SUPPRESSION_DB = intPreferencesKey("noise_suppression_db")
        val EMERGENCY_OVERRIDE_SILENT = booleanPreferencesKey("emergency_override_silent")
        val EMERGENCY_PLAYBACK_VOLUME = intPreferencesKey("emergency_playback_volume")
        val EMERGENCY_TTS_ANNOUNCE = booleanPreferencesKey("emergency_tts_announce")
        val EMERGENCY_REQUIRE_CONFIRMATION = booleanPreferencesKey("emergency_require_confirmation")
        val OPERATOR_NAME = stringPreferencesKey("operator_name")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val COLOR_PALETTE = stringPreferencesKey("color_palette")
    }

    private fun readSettings(prefs: Preferences) = AppSettings(
            vadSensitivity = prefs[Keys.VAD_SENSITIVITY] ?: 2,
            noiseSuppressionDb = prefs[Keys.NOISE_SUPPRESSION_DB] ?: 18,
            emergencyOverrideSilent = prefs[Keys.EMERGENCY_OVERRIDE_SILENT] ?: true,
            emergencyPlaybackVolume = prefs[Keys.EMERGENCY_PLAYBACK_VOLUME] ?: 100,
            emergencyTtsAnnounce = prefs[Keys.EMERGENCY_TTS_ANNOUNCE] ?: true,
            emergencyRequireConfirmation = prefs[Keys.EMERGENCY_REQUIRE_CONFIRMATION] ?: true,
            operatorName = prefs[Keys.OPERATOR_NAME]?.let(privateContent::decode) ?: "",
            themeMode = ThemeMode.entries.firstOrNull { it.name == prefs[Keys.THEME_MODE] } ?: ThemeMode.SYSTEM,
            colorPalette = ColorPalette.entries.firstOrNull { it.name == prefs[Keys.COLOR_PALETTE] } ?: ColorPalette.OCEAN,
        )

    val settings: Flow<AppSettings> = dataStore.data.map(::readSettings)

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = transform(readSettings(prefs))
            prefs[Keys.VAD_SENSITIVITY] = next.vadSensitivity
            prefs[Keys.NOISE_SUPPRESSION_DB] = next.noiseSuppressionDb
            prefs[Keys.EMERGENCY_OVERRIDE_SILENT] = next.emergencyOverrideSilent
            prefs[Keys.EMERGENCY_PLAYBACK_VOLUME] = next.emergencyPlaybackVolume
            prefs[Keys.EMERGENCY_TTS_ANNOUNCE] = next.emergencyTtsAnnounce
            prefs[Keys.EMERGENCY_REQUIRE_CONFIRMATION] = next.emergencyRequireConfirmation
            prefs[Keys.OPERATOR_NAME] = privateContent.encode(next.operatorName)
            prefs[Keys.THEME_MODE] = next.themeMode.name
            prefs[Keys.COLOR_PALETTE] = next.colorPalette.name
        }
    }

    suspend fun resetToDefault() = dataStore.edit { it.clear() }
}
