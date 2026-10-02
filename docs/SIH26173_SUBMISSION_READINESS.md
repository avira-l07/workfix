# SIH26173 desktop readiness — 2 October 2026

The current build is ready for the owner's two-phone field test, but it is not yet a validated final submission.

## Built and checked

| Artifact/check | Result |
|---|---|
| Debug APK for phone tests | `app/build/outputs/apk/debug/app-debug.apk` — 87,692,318 bytes — SHA-256 `60A6D0715E4DBBAE04CDE96C99D2FD8DFC354AD97F0274DE7E1BED6B3C75107D` |
| Release build | `app/build/outputs/apk/release/app-release-unsigned.apk` — 74,161,759 bytes — SHA-256 `3D0BB83F7E41F4F38672784AD4F038559C4D1E12B95FA9DE3FCC298C4738A324` |
| Unit tests | `testDebugUnitTest`: 420 passed, zero failures; `assembleDebug` passed after the Quick Stat Summary wording update |
| Release lint/build | `assembleRelease`: passed |
| Download packaging | Both APKs carry ten language-pack manifests and only `silero_vad.onnx` among ONNX assets; STT/TTS weights are downloaded separately. ZIP CRC checks passed. The five CTC manifest paths match the pinned runtime download URLs. |

The release APK is **unsigned**, so use the debug APK for tonight's phone test. A release signing key and a signed-build check are still needed for distribution. The APK supports `arm64-v8a`; use compatible phones.

The debug APK above includes the [Quick Stat Summary](QUICK_STAT_SUMMARY.md) on the native Diagnostics screen. The release artifact in the table predates that UI addition and has not been rebuilt for this report.

## Claims for the submission

- The implemented architecture is on-device STT, compact encrypted text over a direct Bluetooth/Wi-Fi Direct link, and local TTS at the receiving phone. State that two-phone performance is **pending field measurement** until the test passes.
- Four established selected STT models (Hindi, English, Tamil, Telugu) have fixed desktop WER samples in [the model report](FOUR_LANGUAGE_MODEL_REPORT.md). A fifth, [Odia candidate](ODIA_STT_CANDIDATE.md), has a separate small desktop test. Do not present these as field WER or ten-language validation.
- The [Odia fit review](ODIA_MODEL_FIT.md) includes three local TTS samples and desktop synthesis timings. TTS intelligibility, Odia phone performance and two-phone delay are still unmeasured. The Odia TTS load check now uses an Odia phrase.
- Bengali, Gujarati, Marathi, Kannada and Malayalam use a multilingual fallback whose accuracy is not yet measured here. Odia mic recognition is available as an on-demand CTC candidate; Android performance remains unverified. Odia text/TTS remain available.
- Frame-byte savings in the app compare an actual message frame to uncompressed 16 kHz PCM. They are not a radio throughput or compressed voice codec result. End-to-end audible latency remains unmeasured.
- The [official problem statement](https://sih.gov.in/sih2026PS) permits a two-phone demonstration. Satellite gateways, multi-hop mesh and map ingestion are outside the required demonstration and are not implemented end to end.

## Decisions before public distribution

Review [model sources and distribution terms](MODEL_PROVENANCE_AND_DISTRIBUTION.md), especially Meta MMS TTS's CC BY-NC 4.0 restriction and Google ML Kit translation attribution. Sign the release build with an owner-controlled key. Publish language, device and link measurements only after completing the [field test protocol](SIH26173_DEVICE_TEST.md).
