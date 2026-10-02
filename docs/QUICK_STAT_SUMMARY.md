# iTantra quick stat summary — 2 October 2026

The five percentages below are the owner's **proposed category weights**, not
achieved scores or the published SIH26173 grading rubric. Diagnostics now shows
these weights alongside live local evidence; it does not calculate an overall
success percentage. The [SIH26173 delivery plan](SIH26173_DELIVERY_PLAN.md)
records the published criteria separately.

| Owner category | Weight | What the app can show now | Acceptance still needed |
|---|---:|---|---|
| Low-Bitrate Voice Compression | 25% | Latest local voice frame bytes, same-message raw 16 kHz PCM reference bytes and reduction | Real low-rate link test; this comparison is not a radio or compressed-codec result |
| Offline Indic STT & TTS Pipeline | 25% | Installed STT count, TTS count and both-ready count across ten catalog languages | Per-language phone WER and human listening ratings; check MT separately |
| P2P Transport & Turnaround Latency | 20% | Current Bluetooth/Wi-Fi Direct connection and last measured packet ACK time | Speech-end to remote audible playback timing on two phones |
| Live Two-Device Offline Demo | 15% | Not verified in software | Complete and record the owner's offline two-phone test |
| Disaster Resilience & Architecture Roadmap | 15% | Direct peer path and SOS code | Field robustness test; multi-hop mesh and satellite/radio gateways are not implemented |

## Language evidence

**Count:** ten selectable languages; four selected STT/TTS pairs with desktop
benchmarks; one additional Odia desktop candidate; zero languages certified by
a live two-device phone test as of this report. The five other languages have
selectable speech packs but no fixed STT WER or human-rated TTS result here.
An installed/ready badge reports files on the device, not that speech is
accurate. Downloads vary by device and are counted live in Diagnostics.

| Language | Current mic path | Desktop STT WER (clips) | STT decode / RTF | Desktop TTS mean synthesis / RTF | STT + TTS file bytes | Phone and listener validation |
|---|---|---:|---:|---:|---:|---|
| Hindi | Dedicated IndicConformer CTC | 9.1% (30) | 789 ms / 0.065 | 969 ms / 0.358 | 311,706,810 | Not measured |
| English | Dedicated NeMo FastConformer CTC | 7.1% (20) | 138 ms / 0.016 | 917 ms / 0.355 | 288,638,821 | Not measured |
| Tamil | Dedicated IndicConformer CTC | 25.5% (10) | 317 ms / 0.030 | 1,548 ms / 0.403 | 311,695,881 | Not measured |
| Telugu | Dedicated IndicConformer CTC | 20.4% (10) | 354 ms / 0.036 | 1,775 ms / 0.426 | 311,701,497 | Not measured |
| Odia | Dedicated IndicConformer CTC candidate | 21.37% (20) | 696 ms / 0.056 | 2,140 ms / 1.02, one thread | 311,699,240 | Not measured |
| Bengali | Shared multilingual Whisper Tiny fallback | Not measured | Not measured | Not measured | Shared STT + own TTS, about 114 MB | Not measured |
| Gujarati | Shared multilingual Whisper Tiny fallback | Not measured | Not measured | Not measured | Shared STT + own TTS, about 114 MB | Not measured |
| Marathi | Shared multilingual Whisper Tiny fallback | Not measured | Not measured | Not measured | Shared STT + own TTS, about 114 MB | Not measured |
| Kannada | Shared multilingual Whisper Tiny fallback | Not measured | Not measured | Not measured | Shared STT + own TTS, about 114 MB | Not measured |
| Malayalam | Shared multilingual Whisper Tiny fallback | Not measured | Not measured | Not measured | Shared STT + own TTS, about 114 MB | Not measured |

These WERs use small clean, read-speech FLEURS corpora on a Windows desktop,
not noisy field speech or Android inference. Lower WER is better. Synthesis
times measure local waveform generation only, not phone-to-phone delay. TTS
generated nonempty audio in the tested five, but native-speaker intelligibility
and naturalness are not rated. The [four-language model report](FOUR_LANGUAGE_MODEL_REPORT.md)
and [Odia model review](ODIA_MODEL_FIT.md) give the methods, model sizes, RTF,
memory caveats and raw-result links.

## Current five-language desktop benchmark

The repeatable 30-utterance FLEURS run on 2 October 2026 gives the following
**desktop-only** measurements. These replace the small-corpus figures above as
the current reference, but they are not phone or field results. No model was
switched between those two measurements; differences do not prove improvement.

| Language | STT WER / CER | Mean / p95 decode | RTF | Mean / p95 TTS, 30 short phrases | Phone two-device status |
|---|---:|---:|---:|---:|---|
| Hindi | 9.1% / 3.9% | 530 / 1,200 ms | 0.044 | 767 / 1,087 ms | Not verified |
| English | 6.9% / 3.8% | 153 / 289 ms | 0.018 | 1,004 / 1,237 ms | Not verified |
| Tamil | 21.8% / 8.7% | 553 / 1,025 ms | 0.054 | 1,317 / 1,976 ms | Not verified |
| Telugu | 22.7% / 7.1% | 337 / 615 ms | 0.035 | 1,312 / 1,703 ms | Not verified |
| Odia | 19.3% / 5.1% | 658 / 1,272 ms | 0.057 | 1,040 / 1,450 ms | Not verified |

Tamil, Telugu and Odia exceed the requested 15% WER target. Tamil and Telugu
also exceed the 1,200-ms mean TTS target on short field phrases. Odia's old
long-sentence TTS number and current short-phrase number use different text and
cannot be treated as a measured speedup. See [the benchmark report](LANGUAGE_BENCHMARKS.md)
for model comparison, disk/RAM impact, reproducible commands, limitations and
the two-phone checklist. The Diagnostics screen shows these desktop references
and offers an on-device, one-recording-per-language smoke test. It never labels
that smoke test or the desktop numbers as a live two-phone validation.

Offline translation is a separate dependency and is not implied by an STT/TTS
pair. In particular, current ML Kit routing does not offer Odia or Malayalam
cross-language translation. The [field test protocol](SIH26173_DEVICE_TEST.md)
defines the remaining phone measurements and what to export from Diagnostics.
