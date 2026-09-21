# iTantra Offline TTS Implementation Status

## Architecture

TTS runtime:
Sherpa-ONNX (`OfflineTts`, bundled via `app/libs/sherpa-onnx.aar` and native `libsherpa-onnx-c-api.so` for arm64-v8a)

Model format:
VITS / Meta MMS-TTS ONNX (`model.onnx` + `tokens.txt`, 16,000 Hz, single speaker)

Audio output:
`SpeakerAudioSink` wrapping Android `AudioTrack` (`ENCODING_PCM_FLOAT`, `CHANNEL_OUT_MONO`, 16,000 Hz) with audio focus management and drain synchronization

Provisioning mechanism:
Deterministic atomic staging and promotion via `FileLanguagePackStorage.importLanguageTts` / `importFromDirectory` to `context.filesDir/language_packs/<wireCode>/tts/`

## English

Model:
`model.onnx` (114,017,028 bytes) + `tokens.txt` (303 bytes)

SHA-256:
`409bafdb550948dc5c0b216e21b341732ef3cefead8008b512b034f1bdb27132` (Matches `en_dev_manifest.json`)

Installed:
YES (Validated in `models/language_packs/en/tts/`)

Loadable:
YES (Sherpa-ONNX VITS configuration loads cleanly at 16 kHz)

PCM generation:
PASS (52,899 samples, 3.31s at 16 kHz, RMS: 0.1225 from "Hello, this is an iTantra offline voice test.")

Playback pipeline:
PASS (`TransceiverCoordinator` -> `SherpaOnnxSpeechSynthesizer` -> `SpeakerAudioSink` -> `AudioTrack.write`)

Physical speaker:
BLOCKED (Physical device `7229d2bb` disconnected from ADB)

Status:
PASS (Offline / In-App) | BLOCKED (Physical Speaker)

## Hindi

Model:
`model.onnx` (114,043,140 bytes) + `tokens.txt` (472 bytes)

SHA-256:
`c44b4179e7ff0da4d76eac3929fc1dbd67c51f02da8fca083c445e17450c3f75` (Matches `hi_dev_manifest.json`)

Installed:
YES (Validated in `models/language_packs/hi/tts/`)

Loadable:
YES (Sherpa-ONNX VITS configuration loads cleanly at 16 kHz)

PCM generation:
PASS (27,357 samples, 1.71s at 16 kHz, RMS: 0.1601 from "नमस्ते, आप कैसे हैं?")

Playback pipeline:
PASS (`TransceiverCoordinator` -> `SherpaOnnxSpeechSynthesizer` -> `SpeakerAudioSink` -> `AudioTrack.write`)

Physical speaker:
BLOCKED (Physical device `7229d2bb` disconnected from ADB)

Status:
PASS (Offline / In-App) | BLOCKED (Physical Speaker)

## Language Switching

EN -> HI:
PASS (Active English TTS unloaded via native `release()`, Hindi TTS loaded; active language set to `HINDI`; speech requests for English are safely isolated from Hindi engine)

HI -> EN:
PASS (Active Hindi TTS unloaded via native `release()`, English TTS loaded; active language set to `ENGLISH`)

## Missing Model Behavior

STT remains available:
PASS (Verified by unit test: when TTS model is missing or `loadTts=false`, session transitions to `READY`, STT engine is loaded and active, and speech recognition remains 100% operational)

TTS correctly unavailable:
PASS (`currentTtsEngine` is null; non-critical messages are marked with `VOICE_OUTPUT_UNAVAILABLE_NOTE`; fallback diagnostic tones are explicitly labeled `TTS UNAVAILABLE — DIAGNOSTIC TONE`)

## Tests

Unit tests:
PASS (182 executed, 0 failed in `app:testDebugUnitTest`, including 11 dedicated TTS tests in `TtsLifecycleAndStorageTest`)

Build:
PASS (`app:assembleDebug` completed successfully; APK size 121,248,379 bytes)

Integration tests:
PASS (Python Sherpa-ONNX host validation of English and Hindi ONNX models verified waveform acoustic properties and sample rate)

## Physical Device

Status:
BLOCKED — device not connected (`7229d2bb` not detected in `adb devices`)

## Translation

UNAVAILABLE — intentionally untouched (Translation remains cleanly scoped out with `UnavailableTranslationEngine`)

## Remaining Work

Only physical-device AudioTrack/speaker verification remains for TTS once device `7229d2bb` is reconnected.
