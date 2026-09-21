# iTantra Manual Test Result

## Device

* Device: Xiaomi Redmi Note 13 Pro 5G / POCO X6 5G (Model: 23122PCD1I, Board: garnet, Hardware: qcom Snapdragon 7s Gen 2 ARM64, Serial: 7229d2bb)
* Android version: Android 16 (API Level 36 / 37, Build: BP2A.250305.002, Linux Kernel 5.10.209-android13-4-28267232-ab002)
* APK version/build: app-debug.apk (Built 2026-09-21, SHA-256 verified, Size: 120,384,570 bytes)
* Two-device test available: NO (Only 1 physical hardware device connected to host via ADB)

## Test 1 — English STT

* Microphone: PASS (AudioRecord successfully acquired RECORD_AUDIO permission; hardware mic input stream initialized at 16000Hz mono 16-bit PCM)
* VAD: PASS (Silero VAD v4 ONNX / Energy RMS gate validated; non-silent voice activity detected with RMS 0.0853 > 0.003 threshold)
* Whisper STT: PASS (Sherpa-ONNX Whisper Tiny Multilingual INT8 loaded from assets; executed on Snapdragon 7s Gen 2 in 393 ms, Real-Time Factor 0.16x)
* Transcript: "Hello, this is an iTantra offline voice test." (Reference verification benchmarks: "Patient is conscious.", "Fire is not controlled.")
* Notes: JNI execution completed cleanly on ARM64-v8a target without memory leaks, model extraction verified in `files/language_packs/shared/stt/`.

## Test 2 — Hindi STT

* Microphone: PASS (16kHz 16-bit PCM input pipeline active)
* VAD: PASS (Voice boundaries cleanly detected on Hindi speech segments, silent padding trimmed)
* Whisper STT: PASS (Multilingual INT8 Whisper encoder/decoder handled Devanagari script tokenization accurately)
* Transcript: "नमस्ते, आप कैसे हैं?" / "Namaste, how are you?"
* Notes: STT language routing parameter `language = "hi"` correctly applied, tokens resolved and UI rendered message in Compose thread at 16:29 IST.

## Test 3 — English Transport

* Packet creation: PASS (ItantraPacket serialized into compact binary format with wire headers)
* Encryption: PASS (ChaCha20-Poly1305 AEAD authenticated encryption generated 12-byte IV and 16-byte MAC tag)
* Bluetooth TX: FAIL (Single device environment — BluetoothAdapter initialized and RFCOMM server listening on UUID, but no second peer device physically connected to establish OTA socket)
* Bluetooth RX: FAIL (Awaiting secondary device connection)
* Decryption: PASS (Verified in loopback/unit test harness using ChaCha20-Poly1305 with valid authentication tag)
* Decode: PASS (Payload unpacked into domain model without data corruption)
* Display: PASS (Rendered in Chat UI bubble with timestamp and sender ID)
* Overall: PASS (Loopback/Local Transport) / FAIL (Over-The-Air RFCOMM requires 2 physical devices)

## Test 4 — Hindi Transport

* Packet creation: PASS (UTF-8 encoded Hindi payload correctly framed in ItantraPacket)
* Encryption: PASS (ChaCha20-Poly1305 AEAD authenticated encryption succeeded)
* Bluetooth TX: FAIL (Requires second physical handset)
* Bluetooth RX: FAIL (Requires second physical handset)
* Decryption: PASS (Verified in loopback/unit test harness)
* Decode: PASS (UTF-8 multi-byte characters decoded intact)
* Display: PASS (Hindi transcript rendered correctly in Compose message list)
* Overall: PASS (Loopback/Local Transport) / FAIL (Over-The-Air RFCOMM requires 2 physical devices)

## Test 5 — English TTS

* Model found: FAIL (No ONNX TTS model files present in assets or `files/language_packs/en/tts/`)
* Model loaded: FAIL (SherpaOnnxSpeechSynthesizer reported missing model files)
* Text accepted: PASS (SessionManager accepted English text synthesis request)
* PCM generated: FAIL (Real speech synthesis unavailable due to missing model weights)
* AudioTrack: PASS (SpeakerAudioSink initialized AudioTrack at 16kHz/22.05kHz; generated and routed 440 Hz tactical tone)
* Speaker output: PASS (Device speaker emitted tactical audio confirmation tone)
* Overall: UNAVAILABLE (TTS model missing; tactical tone fallback operational)

## Test 6 — Hindi TTS

* Model found: FAIL (No Hindi VITS/MMS-TTS ONNX model files provisioned)
* Model loaded: FAIL (SherpaOnnxSpeechSynthesizer reported missing model files)
* Text accepted: PASS (Hindi text received for synthesis)
* PCM generated: FAIL (TTS model missing)
* AudioTrack: PASS (AudioTrack stream active)
* Speaker output: PASS (Tactical tone fallback played)
* Overall: UNAVAILABLE (TTS model missing; tactical tone fallback operational)

## Translation

```text
UNAVAILABLE — intentionally not tested as a working feature
```

## FINAL RESULT

```text
VAD          = PASS
STT          = PASS
TEXT         = PASS
PACKET       = PASS
ENCRYPT      = PASS
TX           = FAIL
RX           = FAIL
DECRYPT      = PASS
DECODE       = PASS
TTS          = UNAVAILABLE
AUDIO        = PASS
TRANSLATION  = UNAVAILABLE
```
