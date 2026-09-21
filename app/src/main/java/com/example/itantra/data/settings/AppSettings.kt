package com.example.itantra.data.settings

/**
 * Persisted app configuration. Field ranges match the sliders already designed in
 * 01_settings/code.html — keep this in sync if the UI's slider ranges ever change.
 */
data class AppSettings(
    val vadSensitivity: Int = 2,              // 1 (Low/Noisy) .. 3 (High/Quiet), matches VAD slider
    val noiseSuppressionDb: Int = 18,         // 6 (Gentle) .. 36 (Aggressive), matches suppression slider
    val emergencyOverrideSilent: Boolean = true,
    val emergencyPlaybackVolume: Int = 100,   // 80..100, matches "Emergency Audio Playback Level"
    val emergencyTtsAnnounce: Boolean = true,
    val emergencyRequireConfirmation: Boolean = true,
    val operatorName: String = "",
)
