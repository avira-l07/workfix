# iTantra quick stat summary — 4 October 2026

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

**Count:** ten selectable languages; ten selected STT/TTS pairs with desktop
benchmarks (Hindi, English, Tamil, Telugu, Odia, Bengali, Gujarati, Marathi, Kannada, Malayalam); zero
languages certified by a recorded live two-device phone test as of this report.
Marathi was switched to its measured dedicated CTC model on 4 October; its
old Tiny path failed. Malayalam now has an experimental dedicated CTC download;
Kannada now has a dedicated CTC download for manual microphone mode, measured
in the authorized audit fixes. Auto-detect still uses Tiny; it is unreliable
for Kannada. Human-rated TTS remains
unverified for every language.
An installed/ready badge reports files on the device, not that speech is
accurate. Downloads vary by device and are counted live in Diagnostics.
The authorized audit-fix regression run passed **444 unit tests with zero failures**.
See [the fix report](AUDIT_FIXES_2026-10-04.txt) for build evidence and remaining gaps.

| Language | Current mic path | Desktop STT WER (clips) | STT decode / RTF | Desktop TTS mean synthesis / RTF | STT + TTS file bytes | Phone and listener validation |
|---|---|---:|---:|---:|---:|---|
| Hindi | Dedicated IndicConformer CTC | 9.1% (30) | 530 ms / 0.044 | 767 ms / 0.817 | 311,706,810 | Not measured |
| English | Dedicated NeMo FastConformer CTC | 6.9% (30) | 153 ms / 0.018 | 1,004 ms / 0.859 | 288,638,821 | Not measured |
| Tamil | Dedicated IndicConformer CTC | 21.8% (30) | 553 ms / 0.054 | 1,317 ms / 0.823 | 311,695,881 | Not measured |
| Telugu | Dedicated IndicConformer CTC | 22.7% (30) | 337 ms / 0.035 | 1,312 ms / 0.918 | 311,701,497 | Not measured |
| Odia | Dedicated IndicConformer CTC candidate | 19.3% (30) | 658 ms / 0.057 | 1,040 ms / 0.938 | 311,699,240 | Not measured |
| Bengali | Dedicated IndicConformer CTC | 14.36% (30) | 729 ms / 0.057 | 1,046 ms / 0.922 | 311,708,339 | Not measured |
| Gujarati | Dedicated IndicConformer CTC | 18.54% (30) | 511 ms / 0.052 | 529 ms / 0.514 | 311,697,392 | Not measured |
| Marathi | Dedicated IndicConformer CTC | 15.70% (30); native script 30/30 | 757 ms / 0.058 | 549 ms / 0.500; unsupported letters now fail explicitly | 311,707,596 | Not measured |
| Kannada | Dedicated IndicConformer CTC; manual mode | 17.23% (30); native script 30/30 | 440 ms / 0.035; p95 1,123 ms | 1,360 ms / 0.863 (earlier desktop measurement) | 311,709,264 | Not measured |
| Malayalam | Dedicated IndicConformer CTC; experimental | 28.91% (30); native script 30/30 | 664 ms / 0.040 | 968 ms / 0.915 | 311,716,087 | Not measured |

These WERs use 30 clean, read-speech FLEURS recordings per language on a Windows desktop,
not noisy field speech or Android inference. Lower WER is better. Synthesis
times measure local waveform generation only, not phone-to-phone delay. TTS
generated nonempty audio in the tested ten, but native-speaker intelligibility
and naturalness are not rated. The [four-language model report](FOUR_LANGUAGE_MODEL_REPORT.md)
and [Odia model review](ODIA_MODEL_FIT.md) give the methods, model sizes, RTF,
memory caveats and raw-result links.

## Current nine-language desktop benchmark

The repeatable 30-utterance FLEURS runs on 2–4 October 2026 give the following
**desktop-only** measurements. These are not phone or field results. The earlier
small-corpus numbers are preserved in [the benchmark report](LANGUAGE_BENCHMARKS.md);
different clips and phrases do not prove a model improvement.

| Language | STT WER / CER | Mean / p95 decode | RTF | Mean / p95 TTS, 30 short phrases | Phone two-device status |
|---|---:|---:|---:|---:|---|
| Hindi | 9.1% / 3.9% | 530 / 1,200 ms | 0.044 | 767 / 1,087 ms | Not verified |
| English | 6.9% / 3.8% | 153 / 289 ms | 0.018 | 1,004 / 1,237 ms | Not verified |
| Tamil | 21.8% / 8.7% | 553 / 1,025 ms | 0.054 | 1,317 / 1,976 ms | Not verified |
| Telugu | 22.7% / 7.1% | 337 / 615 ms | 0.035 | 1,312 / 1,703 ms | Not verified |
| Odia | 19.3% / 5.1% | 658 / 1,272 ms | 0.057 | 1,040 / 1,450 ms | Not verified |
| Bengali | 14.36% / 3.62% | 729 / 1,552 ms | 0.057 | 1,046 / 1,639 ms | Not verified |
| Gujarati | 18.54% / 5.75% | 511 / 786 ms | 0.052 | 529 / 668 ms | Not verified |
| Marathi | 15.70% / 4.64% | 757 / 1,441 ms | 0.058 | 549 / 701 ms; U+0949 skip | Not verified |
| Malayalam | 28.91% / 7.06% | 664 / 2,194 ms | 0.040 | 968 / 1,229 ms | Not verified |

Tamil, Telugu, Odia, Marathi and Malayalam exceed the requested 15% WER target. Tamil and Telugu
also exceed the 1,200-ms mean TTS target on short field phrases. Odia's old
long-sentence TTS number and current short-phrase number use different text and
cannot be treated as a measured speedup. See [the benchmark report](LANGUAGE_BENCHMARKS.md)
for model comparison, disk/RAM impact, reproducible commands, limitations and
the two-phone checklist. The Diagnostics screen shows these desktop references
and offers an on-device, one-recording-per-language smoke test. It never labels
that smoke test or the desktop numbers as a live two-phone validation.

On 3 October, a same-30-recording Tamil/Telugu test of the offline Dolphin
Small INT8 model gave **53.8% / 55.7% WER**, worse than the current dedicated
models' **21.8% / 22.7%**. The requested **under-10% WER is not achieved**;
the app's Tamil/Telugu downloads were deliberately not switched. The exact
comparison and model-size/RAM evidence are in [the benchmark report](LANGUAGE_BENCHMARKS.md).

The final Tamil/Telugu preflight rejected three more families before benchmarking:
one 600M Indic encoder alone exceeded the ~500 MB phone budget, MMS-1B-all has
a noncommercial license and unverified phone fit, and Parakeet v3 exceeds 2.5 GB
while omitting Tamil/Telugu. **Gate: FAIL; no new model was shipped for these two.**
Fine-tuning is also **Not run**: this host lacks a usable CUDA/NeMo training
runtime and the gated source checkpoints and training corpus have not been
obtained. A separate 100-sentence FLEURS **test** set measured the existing
Tamil pack at **31.60% WER / 16.36% CER**, Telugu at **21.69% / 6.71%**,
and Bengali/Gujarati at
**14.31% / 4.12%** and **17.13% / 4.47%** respectively; these are desktop-only
and are not directly comparable to the 30-clip numbers as a change over time.
Full validation/test overlap indexes are prepared for all four languages,
but no training manifests exist
yet. See [fine-tuning evidence](LANGUAGE_BENCHMARKS.md#fine-tuning-feasibility-and-held-out-preparation-3-october-2026)
and [runbook](STT_FINETUNING_RUNBOOK.md).
On identical Bengali/Gujarati clips, the old Whisper fallback scored 132.36% /
118.37% WER with 0/30 native-script transcripts each. Dedicated IndicConformer
models scored 14.36% / 18.54% and 30/30 native script each, so these two now
have checksum-verified on-demand STT downloads. Both MMS-VITS voices generated
16-kHz audio on desktop; human intelligibility and phone behavior are unverified.
The voices' CC-BY-NC-4.0 license needs review before commercial distribution.

On 4 October, extra Bengali/Gujarati model comparisons found no improvement:
Omnilingual 300M INT8 scored 35.82% / 32.06% WER and Dolphin Small INT8
32.00% / 50.78%, on the same 30 recordings as the current models' 14.36% /
18.54%. Dedicated packs remain the best of these tested candidates. See the
[measured review](BENGALI_GUJARATI_CANDIDATE_REVIEW.md). No new validation
or success score is claimed.

The [Marathi audit and fix](MARATHI_LANGUAGE_CHECK.md) measured dedicated
IndicConformer at 15.70% WER and 30/30 native-script output, against the old
Tiny path's 133.94% and 0/30 on the same recordings. Manual Marathi now uses
the dedicated, checksum-verified on-demand pack. Install the new APK, download
Marathi STT in Language Packs and explicitly select Marathi as the mic
language; Auto-detect still uses shared Whisper. The old Tiny install does
not satisfy Marathi readiness. Current Marathi TTS produces audio but skips
U+0949 in one tested field phrase; it is not pronunciation-certified. The
under-10% WER goal, phone inference, noisy speech and translation quality
remain unverified or unmet.

## Kannada and Malayalam audit — old fallback versus dedicated models

| Language | Tiny WER / native script | Dedicated model WER / CER | Dedicated STT mean / p95 | Current TTS mean / p95 | Phone status |
|---|---:|---:|---:|---:|---|
| Kannada | 169.80% / 0 of 30 | 17.23% / 6.32%; native 30/30 | 728 / 1,372 ms | 1,360 / 1,872 ms | Not verified |
| Malayalam | 193.16% / 0 of 30 | 28.91% / 7.06%; native 30/30 | 664 / 2,194 ms | 968 / 1,229 ms | Not verified |

These are paired 30-clip desktop runs on 4 October. Kannada remains a candidate;
Malayalam is now an experimental manual-mic download. Both dedicated models miss the under-10% goal. Malayalam's model
peak desktop working set was 946,454,528 bytes; Android RAM fit is not established.
Current Kannada TTS misses the 1,200 ms short-phrase mean target. Both voices
produced non-silent 16-kHz waveforms in all 30 synthesis trials and no missing
letters/marks in the five tested native phrases, but listener quality is unverified.
Their misleading English `Hello` startup checks were fixed to use native phrases.
The Malayalam focus additionally fixed the Android WAV reader: float32 self-test
recordings were incorrectly decoded as PCM16. The Diagnostics self-test now includes
nine languages. See the
[complete Kannada/Malayalam check](KANNADA_MALAYALAM_LANGUAGE_CHECK.md) for evidence,
RAM/disk sizes, reproducible commands and remaining gaps; its original selection
description is superseded for Malayalam by the [focused report](MALAYALAM_STT_IMPROVEMENT.md).

The independent **100-recording Malayalam test** measured **22.92% WER / 5.27% CER**,
native script **100/100**, mean/p95 decode **566 / 1,061 ms**, RTF **0.0368** and
peak desktop working set **722,341,888 bytes**. It excludes all full-validation
sentences and was not used for tuning. It is a different set from the paired 30;
the lower number is not a measured improvement over 28.91%.
Omnilingual, Dolphin and Whisper Small did not beat dedicated Malayalam CTC.
Chunking lowered measured RAM but worsened WER, so it was rejected. Download the
new Malayalam STT pack and explicitly select Malayalam mic; Auto-detect remains
shared Whisper. General Malayalam translation and phone/listener validation remain
unavailable or Not verified. Accuracy is still above both requested targets.

Offline translation is a separate dependency and is not implied by an STT/TTS
pair. In particular, current ML Kit routing does not offer Odia or Malayalam
cross-language translation. The [field test protocol](SIH26173_DEVICE_TEST.md)
defines the remaining phone measurements and what to export from Diagnostics.
