# iTantra Physical Two-Device Receiver TTS Test Checklist

This checklist defines the physical verification procedures required for validating the receiver text-to-speech (TTS) audio output on physical Android devices (API 31+ / Android 12, 14, 15, 16).

---

## Pre-Flight Setup
- **Phone A (Sender)**: Model loaded, microphone permission granted, speaker audio enabled.
- **Phone B (Receiver)**: Model loaded, receiver TTS installed for the test languages, notification and background service active.
- **Pairing**: Bluetooth RFCOMM or Wi-Fi Direct connected with SAS verification completed (`SECURE_VERIFIED`).

---

## Test Cases

### TEST 1: Same-Language Hindi End-to-End Speech & TTS Playback
- **Sender (Phone A)**: Active Language = Hindi (`hi`), Hindi STT model loaded.
- **Receiver (Phone B)**: Active Language = Hindi (`hi`), Hindi TTS model installed (`sherpa-onnx` Hindi VITS).
- **Procedure**:
  1. Press and hold PTT on Phone A.
  2. Speak clearly into Phone A: `"नमस्ते आप कैसे हैं"`
  3. Release PTT.
- **Expected Outcome**:
  - Phone A recognizes speech via Whisper/Sherpa STT: `"नमस्ते आप कैसे हैं"`
  - Phone A transmits encrypted `TEXT` packet (`languageCode = hi`, `targetLanguage = hi`).
  - Phone B receives packet, decodes payload, displays Hindi text in chat thread.
  - Phone B prepares Hindi TTS engine via `sessionManager.ensureCapabilities(HINDI, requireTts = true)`.
  - Phone B synthesizes speech (`FloatArray` PCM generated).
  - Phone B emits `TTS_STARTED` packet back to Phone A.
  - Phone B plays audible Hindi speech output through built-in speaker (`USAGE_MEDIA`).
  - Phone B sends `TTS_COMPLETED` packet upon drain; message reaches `REMOTE_PLAYBACK_CONFIRMED`.

---

### TEST 2: Same-Language English End-to-End Speech & TTS Playback
- **Sender (Phone A)**: Active Language = English (`en`), English STT loaded.
- **Receiver (Phone B)**: Active Language = English (`en`), English TTS installed.
- **Procedure**:
  1. Press and hold PTT on Phone A.
  2. Speak into Phone A: `"Where is the hospital?"`
  3. Release PTT.
- **Expected Outcome**:
  - Phone A transcribes English utterance.
  - Phone B receives English text packet.
  - Phone B synthesizes and audibly plays speech output through device speaker via `USAGE_MEDIA`.
  - Message state transitions: `DELIVERED` -> `REMOTE_PLAYING` -> `REMOTE_PLAYBACK_CONFIRMED`.

---

### TEST 3: Cross-Language Translation to Matching Receiver TTS
- **Sender (Phone A)**: Language = English (`en`).
- **Receiver (Phone B)**: Language = Hindi (`hi`).
- **Procedure**:
  1. Phone A transmits message targeting Hindi or receiver runs offline translation.
  2. Observe language resolution on Phone B.
- **Expected Outcome**:
  - Final translated text is Hindi.
  - Phone B resolves `finalTextLanguage = HINDI`.
  - Phone B synthesizes using Hindi TTS engine (does NOT attempt English TTS for Hindi text).
  - Audible Hindi playback produced on Phone B speaker.
  - If translation fails, English text is displayed as received without switching Phone B's local STT microphone language to English.

---

### TEST 4: Volume Level and Media Audio Route Verification
- **Setup**: Phone B media volume set to minimum (not silent), then adjusted to 70%.
- **Procedure**:
  1. Send incoming message from Phone A.
  2. Adjust volume during and between messages.
- **Expected Outcome**:
  - Normal TTS audio respects standard Android media volume sliders (`STREAM_MUSIC` / `USAGE_MEDIA`).
  - Audio plays through device loudspeaker and is NOT redirected to the earpiece.
  - Critical emergency alerts continue to use `USAGE_ALARM` with override to maximum volume.

---

### TEST 5: Bluetooth Headset Disconnect & Audio Route Restoration
- **Setup**: Pair and connect a Bluetooth headset/earbuds to Phone B.
- **Procedure**:
  1. Receive a message: audio plays through the Bluetooth headset.
  2. Turn off Bluetooth on headset to disconnect it while app remains active.
  3. Send another message from Phone A.
- **Expected Outcome**:
  - `SpeakerAudioSink` detects audio device topology change.
  - Communication device reset (`clearCommunicationDevice`) correctly restores output to `TYPE_BUILTIN_SPEAKER`.
  - Speech is clearly audible from the phone loudspeaker.

---

### TEST 6: Screen Off & Foreground Service Background Audio
- **Setup**: Phone B has the screen locked/turned off with `OperationalForegroundService` active.
- **Procedure**:
  1. Lock screen on Phone B.
  2. Send voice message from Phone A.
- **Expected Outcome**:
  - Phone B receives packet over Bluetooth/Wi-Fi Direct in background.
  - Notification updates with message summary.
  - Synthesizer runs and audio plays audibly through the speaker while screen remains off.
  - Microphone auto-pause suppression prevents feedback loops if continuous listening is enabled.

---

### TEST 7: Rapid Message Queueing (5 Messages)
- **Procedure**:
  1. Send 5 short voice messages from Phone A in rapid succession (~2-3 seconds apart).
- **Expected Outcome**:
  - Receiver queues messages in FIFO order in `messageQueue`.
  - Each message synthesizes and plays sequentially without overlapping audio tracks.
  - No engine recreation races or memory leaks.
  - All 5 messages complete playback and update to `REMOTE_PLAYBACK_CONFIRMED`.

---

### TEST 9: Independent Multilingual Pipeline & Receive Language Separation
- **Procedure**:
  1. **Phone A (Spoken Telugu)**:
     - Phone A has default `SpeechInputMode.AUTO`.
     - Speak into Phone A: `"నాకు నీళ్లు కావాలి"`
     - Verify debug logs: `STT_MODE=AUTO`, `WHISPER_DETECTED=te`, `RESOLVED_SOURCE_LANGUAGE=TELUGU`, `PACKET_SOURCE=TELUGU`.
     - Verify transcript in UI displays native Telugu script.
     - Packet sent with `sourceLanguage = TELUGU`.
  2. **Phone B (Receive Language = Telugu)**:
     - Set Phone B "Receive Messages In" to Telugu (`te`).
     - Phone B receives message: `sourceLanguage = TELUGU`, `desiredReceive = TELUGU`.
     - Text stays in Telugu without English translation.
     - If Telugu TTS installed, speaks in Telugu; if not, displays `TTS_MODEL_NOT_INSTALLED`.
  3. **Phone B (Receive Language = Hindi)**:
     - In `DedicatedChatScreen`, tap Receive chip and pick Hindi (`hi`).
     - Phone A speaks Telugu again.
     - Phone B receives Telugu packet -> translates Telugu to Hindi -> displays Hindi text -> plays Hindi TTS.
  4. **Microphone Independence on Phone B**:
     - Speak Tamil or English into Phone B.
     - Verify Phone B's microphone Whisper STT still runs in AUTO mode and detects the spoken language.
     - Verify Phone B's microphone was NOT changed to Hindi merely because incoming messages or TTS used Hindi.
  5. **Repeat with Other Canonical Languages**:
     - Test Tamil (`ta`), Bengali (`bn`), and English (`en`).


