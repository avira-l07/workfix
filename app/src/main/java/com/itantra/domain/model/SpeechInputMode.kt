package com.itantra.domain.model

/**
 * Operating mode for on-device speech-to-text input.
 *
 * [AUTO]: Whisper automatically identifies the spoken language from audio and transcribes
 * in that native language script without forcing a fixed language code.
 * [MANUAL]: Whisper is explicitly guided with a fixed language code hint.
 */
enum class SpeechInputMode {
    AUTO,
    MANUAL;

    companion object {
        fun fromSavedValue(value: String?): SpeechInputMode =
            entries.firstOrNull { it.name == value } ?: MANUAL
    }
}
