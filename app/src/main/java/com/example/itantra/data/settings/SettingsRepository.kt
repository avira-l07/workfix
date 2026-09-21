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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "itantra_settings")

/**
 * Real persistence for Settings.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val VAD_SENSITIVITY = intPreferencesKey("vad_sensitivity")
        val NOISE_SUPPRESSION_DB = intPreferencesKey("noise_suppression_db")
        val EMERGENCY_OVERRIDE_SILENT = booleanPreferencesKey("emergency_override_silent")
        val EMERGENCY_PLAYBACK_VOLUME = intPreferencesKey("emergency_playback_volume")
        val EMERGENCY_TTS_ANNOUNCE = booleanPreferencesKey("emergency_tts_announce")
        val EMERGENCY_REQUIRE_CONFIRMATION = booleanPreferencesKey("emergency_require_confirmation")
        val OPERATOR_NAME = stringPreferencesKey("operator_name")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            vadSensitivity = prefs[Keys.VAD_SENSITIVITY] ?: 2,
            noiseSuppressionDb = prefs[Keys.NOISE_SUPPRESSION_DB] ?: 18,
            emergencyOverrideSilent = prefs[Keys.EMERGENCY_OVERRIDE_SILENT] ?: true,
            emergencyPlaybackVolume = prefs[Keys.EMERGENCY_PLAYBACK_VOLUME] ?: 100,
            emergencyTtsAnnounce = prefs[Keys.EMERGENCY_TTS_ANNOUNCE] ?: true,
            emergencyRequireConfirmation = prefs[Keys.EMERGENCY_REQUIRE_CONFIRMATION] ?: true,
            operatorName = prefs[Keys.OPERATOR_NAME] ?: "",
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) =
        updateFrom(settings.first(), transform)

    suspend fun updateFrom(current: AppSettings, transform: (AppSettings) -> AppSettings) {
        val next = transform(current)
        dataStore.edit { prefs ->
            prefs[Keys.VAD_SENSITIVITY] = next.vadSensitivity
            prefs[Keys.NOISE_SUPPRESSION_DB] = next.noiseSuppressionDb
            prefs[Keys.EMERGENCY_OVERRIDE_SILENT] = next.emergencyOverrideSilent
            prefs[Keys.EMERGENCY_PLAYBACK_VOLUME] = next.emergencyPlaybackVolume
            prefs[Keys.EMERGENCY_TTS_ANNOUNCE] = next.emergencyTtsAnnounce
            prefs[Keys.EMERGENCY_REQUIRE_CONFIRMATION] = next.emergencyRequireConfirmation
            prefs[Keys.OPERATOR_NAME] = next.operatorName
        }
    }

    suspend fun resetToDefault() = dataStore.edit { it.clear() }
}
