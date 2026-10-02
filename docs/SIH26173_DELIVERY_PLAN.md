# SIH26173 delivery plan and claim boundary

Checked against the [SIH 2026 problem statement](https://sih.gov.in/sih2026PS) on 1 October 2026. The published task is a local Android speech-to-text → compact text over Wi-Fi/Bluetooth → text-to-speech loop, with a two-phone demonstration. Its stated evaluation categories are accuracy (40%), efficiency (20%), and latency (20%); no threshold or extra 20% category is specified. A satellite uplink, NavIC messaging gateway, multi-hop mesh, and Bhuvan/MOSDAC ingestion are **not** requirements of this problem statement.

## Current baseline

| Requirement or claim | Current evidence | Remaining acceptance gate |
|---|---|---|
| Direct two-phone STT → text → TTS | Native Android pipeline, Bluetooth RFCOMM / Wi-Fi Direct, offline model provisioning, unit tests | Real two-phone offline demonstration with received speech and timestamps |
| Ten-language STT and TTS | Five pinned CTC STT models have small desktop WER samples, including an Odia candidate; multilingual Tiny is a fallback for the other five languages. All ten TTS files are selectable, but human quality is not rated. | Each of ten languages needs a working device path, fixed human audio WER set, and listener-rated TTS. Do not count a language picker as validation. |
| Low-bitrate efficiency | Actual framed packet bytes are recorded per sent message. Current debug APK is about 87.8 MB; speech files are downloaded separately. | Measure frame bytes and savings against an explicitly named baseline, installed storage, Android RAM, idle CPU, and link behavior. |
| Low latency | Desktop decode/synthesis times are in `FOUR_LANGUAGE_MODEL_REPORT.md`; phone-to-phone audible latency is not measured. | Capture speech-end to remote-audio-start on two phones with one external clock; include pause detection, translation, transport and playback. |
| Offline operation | STT/TTS/MT run on device after their required models are downloaded. | Test in airplane mode with the actual installed packs and a direct local link. |

## Execution order

1. **Make every claim measurable.** Show savings only from a voice message whose own wire-frame and raw-PCM reference bytes were recorded. Show the actual active Bluetooth/Wi-Fi Direct transport. Keep packet size, speech latency, and delivery status distinct. This prevents a UI number from being mistaken for an HF/satellite throughput measurement.
2. **Prove the core loop on two phones.** Use the [device test protocol](SIH26173_DEVICE_TEST.md) for Hindi, English, Tamil, Telugu and the Odia candidate; record failures as well as successes. Validate PTT and hands-free mode, offline model readiness, alert delivery, and human acknowledgment separately. A successful unit test or desktop model benchmark does not close this gate.
3. **Expand and validate language coverage.** Retain the five selected CTC models while collecting fixed, real test speech for Bengali, Gujarati, Marathi, Kannada and Malayalam. Select a pinned model only after WER, script correctness, Android memory/latency and checksum tests. The [Odia candidate](ODIA_STT_CANDIDATE.md) passed a small desktop read-speech check and is now selectable on demand; Android memory, latency and field speech remain unverified. TTS needs listener evaluation for all ten languages and a distribution licence review. Translation between languages is a useful product extension, not a substitute for STT/TTS in each required language.
4. **Measure low-rate behavior honestly.** The current packet framing costs 40 bytes plus the encrypted payload's 16-byte authentication tag, before the UTF-8 text. At a hypothetical 100 bit/s, a 100-byte frame alone needs at least 8 seconds to serialize, before retransmissions, handshake, STT or TTS. Test the real byte counts and channel conditions; do not promise instant voice over an unspecified 100 bit/s radio.
5. **Only if a specific embedded link is available, add its adapter.** The statement allows an embedded device **or** another phone. A real radio/satellite adapter requires the device protocol, frame size, duplex rules, error handling, and access for testing. The two-phone path satisfies the stated demonstration choice without inventing a gateway.

## Extensions outside SIH26173's required scope

- Opt-in GPS location sharing already exists as a separate packet. Automatically attaching precise location to every speech or SOS packet would add bytes and disclose location; require a field need, explicit user control, and a fresh-fix policy first.
- An offline incident export could use GeoJSON, but direct Bhuvan/MOSDAC ingestion needs an actual receiving system and agreed schema/API. Do not claim that integration before testing it.
- Multi-hop relay needs multiple simultaneous peers, end-to-end encryption across relays, bounded duplication/expiry, and a three-device test. The current one-peer session cannot be called mesh.
- NavIC positioning on a compatible phone is not evidence of a NavIC messaging uplink. Do not claim satellite transmission without suitable hardware and a measured end-to-end demonstration.

## Release gate

Publish a result table with build hash, device models, model hashes, language-wise WER, listener TTS ratings, packet bytes, offline readiness, RAM, installed size, idle CPU, and speech-end to audible remote playback. Label any missing result **not measured**. Use a release-signed build and verify model licences before distributing beyond development.
