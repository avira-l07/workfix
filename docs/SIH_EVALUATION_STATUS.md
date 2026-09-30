# Essential evaluation status — 29 September 2026

## Implemented repair

Build **1.2-on-demand** (version code 3) uses a Hindi-specific Whisper Small
INT8 model when the user explicitly selects Hindi. English, other supported
languages and auto-detection keep the shared Tiny model. The Hindi recognizer
transcribes; the existing text-translation stage handles different target languages.
Only one STT engine is loaded at a time.

Native repairs join byte-BPE tokens before Unicode cleanup and give Hindi Small
an adequate, bounded token allowance. Tiny retains its original decoding bound.
The app's old native-library folder was overriding the repaired AAR; packaging
now uses the AAR libraries, and the APK's native build ID and model checksums
have been verified against the final build.

Hindi model download verifies all hashes in a staging directory before
activation, preserves the previous model on failure, and uses a separate versioned
folder. Language models are downloaded from the Language Packs screen on demand;
the installer contains only code, the VAD model and native speech libraries. Old Tiny files do not qualify as Hindi readiness. No chat, emergency,
transport, database or encryption redesign was introduced.

## Measured evidence

A fixed first 30 Hindi recordings from **FLEURS test** were used. This split matters:
the Hindi fine-tune used FLEURS train/dev during training. The English regression
set contains 20 fixed FLEURS validation recordings. These are real human recordings. WER is computed on raw engine output before app cleanup.

| Desktop configuration | Hindi WER | English WER |
|---|---:|---:|
| Original Tiny, original native engine | 127.66% | 12.04% |
| Hindi-specific Small, repaired native engine | 13.77% | English remains Tiny |

WER may exceed 100% because inserted words count. This is a small read-speech
sample, not a claim of 86.23% accuracy for every speaker or environment. The model
passed our internal Hindi WER gate of 35%; that is not an official SIH threshold.
Final repeat-run timing and English results are in `tools/stt_results/evaluation-summary.json`.

The initial candidate run measured Hindi desktop RTF 1.20 and peak process memory
1,183,809,536 bytes (~1.10 GiB). RTF above one is slower than the audio duration.
Phone latency, memory and low/mid-range responsiveness remain unmeasured.

A separate synthetic short-phrase test produced two exact transcripts out of
three; “मैं कौन हूँ” was still imperfect. This is a smoke test, not human-speech
accuracy evidence. It establishes neither perfect short-command recognition nor
TTS human intelligibility.

- **407 unit tests passed**, zero failures/errors in the final full run.
- `testDebugUnitTest assembleDebug` succeeded.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, about 87.3 MB (exact final size and hash in the verification JSON).
- Hindi STT: 375,430,905 bytes (~358.0 MiB); Hindi TTS: 114,043,612 bytes. These are first-use downloads, not installer bytes.
- English shared Tiny STT: 103,609,903 bytes; English TTS: 114,017,331 bytes. Other languages reuse shared Tiny after it is installed.
- APK hash and verified model/native entries: `tools/stt_results/apk-verification.json`.
- Owner performs all phone testing; no phone was accessed or modified.

## Owner's first test

Install the new APK as an update and check version **1.2-on-demand**. Connect to
the internet, open **Language Packs**, and prepare Hindi's missing models (about
489 MB total download). Wait for **STT READY** and **TTS READY**, then test offline.
Allow additional internal storage for the models and installation overhead.
Select **Hindi under Mic language** (explicitly, not Auto); select Hindi as the target for a pure
transcription test. Speak a few complete natural sentences. Check English by
explicitly selecting English next after downloading English's STT/TTS pack (about
218 MB). A disconnected peer prevents sending but
should not prevent local recognition.

Then pair two phones to test text transmission and receiver TTS. Use a single
video/audio recording showing both phones to measure speech-end to remote-audio
start without subtracting unsynchronized clocks. A fluent listener should judge
TTS clarity and flow. Record device model, RAM, Android version and thermal state.

## Remaining evaluation gaps

- Odia STT is unsupported by this model family; the app rejects it before native
  initialization and discloses the limitation. This does not meet a ten-language
  completeness claim. Seven additional languages remain accuracy-unbenchmarked.
- Auto-detect retains Tiny; explicitly selected Hindi is required for the new model.
- Current `peerTtfaMillis` measures synthesis before audio-sink initialization,
  not receive-to-audible latency. `TTS_STARTED` precedes playback. Do not present
  these as acoustic end-to-end measurements. Continuous VAD also lacks PTT's
  pure-inference RTF updates. Avoid expanding features; improve these measurements
  after the owner confirms the core speech loop.
- Idle-listening CPU, Android RAM, installed storage, low/mid-range phone behavior,
  human TTS intelligibility and two-phone latency remain unmeasured.

The supplied criteria link, https://sih2026.vuce.in/ps/SIH26173, identifies itself
as an unofficial community archive. It reproduces accuracy 40%, efficiency 20%
and latency 20%, with no numerical pass thresholds and no specified remaining
20%. Do not turn internal targets into official requirements.

Rebuild/model provenance: `tools/STT_REPAIR.md`. On-demand setup:
`docs/ON_DEMAND_PACKS.md`. Benchmark downloads and ONNX weights are Git-ignored;
the minimal APK builds from a fresh checkout and downloads selected model files at runtime.
