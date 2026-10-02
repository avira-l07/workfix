# Two-phone SIH26173 field test

Use the **same debug APK build** on both phones. Record its version and SHA-256, device models, Android versions, free storage, and installed STT/TTS/MT pack versions. Download packs first, then turn off mobile data and internet access. Keep Bluetooth or a local Wi-Fi Direct link enabled; airplane-mode behavior varies by phone. Do not assume a model is ready from its language button alone: check the STT, TTS and MT indicators.

## Core demonstration

1. Verify the peer security code on both phones. Record whether the connection is Bluetooth or Wi-Fi Direct.
2. On phone A, select the intended **mic language** explicitly. On phone B, select the **receiving voice**. For a pure STT/TTS test, keep source and target the same; test cross-language translation separately.
3. Speak a fixed, human-transcribed sentence with PTT. Photograph or video-record the transcript on A, the received text on B, and the start of audible TTS on B. Repeat in the reverse direction.
4. Repeat with the same fixed speech set for Hindi, English, Tamil and Telugu. Test the Odia CTC candidate separately after installation and record whether it runs on the phone. Add the other five languages only when the models are installed and actually work. Record false-script or romanized output as errors, not corrected answers.
5. Test continuous listening separately: a sentence with a pause, two nearby sentences, silence, and background noise. Record premature cuts, merged sentences and false activations.
6. Send one emergency code and separately verify device receipt, audible alert and human acknowledgment. Do not equate transport ACK with a human seeing the alert.
7. Repeat after a disconnect/reconnect, and test failure/retry. A disconnected message must not appear as delivered.

## Measurements

For each language, use a fixed audio set with a reference transcript and at least several speakers. Calculate `WER = (substitutions + deletions + insertions) / reference words` on the raw STT output. Have independent listeners rate TTS intelligibility and natural flow. The existing desktop FLEURS results are candidate evidence, not phone-field WER.

In the APK, open **Diagnostics → Export field test summary** after each session and save the JSON. It includes saved benchmark aggregates and voice-frame byte/latency measurements, without chat text, peer IDs, coordinates, or benchmark sentence text. The built-in **Run comprehensive on-device diagnostics** currently runs only the bundled English recorded-audio corpus; it is not a Hindi/ten-language WER test and does not measure the full two-phone audible delay. Keep the video, human reference transcripts and listener ratings alongside the JSON. An empty metric or absent language means **not measured**.

For end-to-end delay, record **both phones in one video/audio recording** and measure from the end of the spoken sentence to the first audible sound from B. Phone clocks need not be synchronized. Repeat at least ten times per representative language and report median and maximum, including failed trials. Record separately the app's speech decode, packet bytes and transport ACK metrics; those are not identical to audible end-to-end delay.

Measure installed storage, Android memory during idle/STT/TTS, and idle-listening CPU on a low-range and a mid-range phone. Run the same scenario on both. Note ambient noise, distance, link type and battery/thermal state. If a model crashes or runs out of memory, report it.

| Build/device | Language | Link | STT WER | TTS listener result | Frame bytes | Median/max speech→audio | RAM / idle CPU | Result / failure |
|---|---|---|---:|---|---:|---|---|---|
| Fill after real test | Hindi | Bluetooth | Not measured | Not measured | Not measured | Not measured | Not measured | Pending |

This protocol does not test VHF/HF, satellite transmission, multi-hop mesh, or Bhuvan/MOSDAC ingestion. Those require separate hardware/integration acceptance tests.
