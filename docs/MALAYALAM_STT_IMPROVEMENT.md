# Malayalam focus — 4 October 2026

Manual Malayalam now selects an **experimental, independently downloadable
IndicConformer INT8 CTC pack**, replacing the measured broken Tiny path. It
is the best of the four tested model families, but **under-10% and under-15%
WER are not achieved**. No model was trained. Phone inference, human-rated
voice quality and live two-device delivery remain **Not verified**.

## Paired model comparison

Same first 30 pinned FLEURS Malayalam validation recordings, 16 kHz mono,
Windows desktop, fresh process per family, sherpa-onnx 1.13.8, four CPU threads,
greedy decoding, existing NFC/punctuation WER/CER normalization. No ground-truth
text substitutions or revised primary scoring. Timings exclude capture,
model loading, transport, translation and playback. WER can exceed 100%
through inserted words. All files were checked against pinned hashes.

| Model | WER / CER | Native script | STT mean / p95 | RTF | Model + tokens bytes | Peak desktop process working set bytes |
|---|---:|---:|---:|---:|---:|---:|
| Previous Whisper Tiny INT8 | 193.16% / 102.32% | 0/30 | 1,362 / 3,264 ms | 0.0816 | 103,609,903 | 534,880,256 |
| **Selected IndicConformer Malayalam INT8** | **28.91% / 7.06%** | **30/30** | **664 / 2,194 ms** | **0.0398** | **197,663,160** | **946,454,528** |
| Omnilingual 300M CTC INT8 | 43.16% / 7.69% | 30/30 | 2,005 / 5,870 ms | 0.1201 | 365,438,543 | 1,709,453,312 |
| Dolphin Small CTC INT8 | 102.93% / 105.58% | 0/30 | 885 / 2,824 ms | 0.0530 | 250,163,616 | 1,023,213,568 |
| Whisper Small INT8, cached export | 114.45% / 100.58% | 0/30; 17 empty | 8,026 / 19,354 ms | 0.4808 | 375,485,327 | 1,466,966,016 |

The Whisper runs explicitly used `language="ml", task="transcribe"`. Correct
language selection and installed TTS files alone do not guarantee correct STT.
Whisper Small's failure is an observation for this export/runtime, not a claim
that every implementation of Whisper Small behaves this way. Dolphin returned
other scripts and fails this Malayalam test; the run does not establish advertised
Malayalam support. The dedicated CTC model has no Whisper language/task token:
the Malayalam model file supplies language specificity.

Raw reports: [Tiny](../tools/stt_results/ml-whisper-tiny-30.json),
[selected CTC](../tools/stt_results/five-language-ml-30.json),
[Omnilingual](../tools/stt_results/omnilingual-300m-int8-ml-30.json),
[Dolphin](../tools/stt_results/dolphin-small-int8-ml-30.json),
[Small](../tools/stt_results/ml-whisper-small-30.json).

## Independent 100-recording test

The fixed whole-utterance CTC configuration was then evaluated on 100 distinct
FLEURS **test** sentences, excluding every sentence in the complete validation
split. This set was not used for configuration tuning. Results are distinct
from the paired 30-clip comparison; 22.92% versus 28.91% is **not a measured
model improvement over time**.

| WER / CER | Native script | Mean / p95 STT | RTF | Peak desktop process working set |
|---:|---:|---:|---:|---:|
| **22.92% / 5.27%** | **100/100** | **566 / 1,061 ms** | **0.0368** | **722,341,888 bytes** |

[Raw 100-recording results](../tools/stt_results/ml-indicconformer-whole-100.json).
Public FLEURS revision `168de341b3db6859a9bac1c50a2ef5e3b47647e0`, CC-BY-4.0:

* Validation: 418 rows, `ml_in/validation/0000.parquet`, SHA-256
  `0a118e7e8920fd31f283cfdc0c55fc00537e93c726b3e0727aebc6700b3bacce`.
* Test: 958 rows, `ml_in/test/0000.parquet`, 899,473,320 bytes, SHA-256
  `8b609c11b4ac862f6376a4b4887d59a75791fbc5cffc5bcf3431edb56ced5bf5`.
* Every extracted WAV hash, 16 kHz mono input, row pairing, metric totals,
  script totals and full-validation sentence disjointness were verified.
* Data/cache indexes are ignored, local evaluation files. No training dataset
  or running Tamil/Telugu Kaggle bundle was changed. Existing fetch defaults
  remain Tamil/Telugu/Bengali/Gujarati; Malayalam is explicitly opt-in.

## Memory experiments rejected for accuracy regression

Two bounded-audio experiments used only the same 30 validation clips. Boundaries
were selected from quiet audio, never reference text. Android remains whole-utterance.

| Configuration | WER / CER | Mean / p95 | Peak desktop process working set | Decision |
|---|---:|---:|---:|---|
| Whole utterance | 28.91% / 7.06% | 664 / 2,194 ms | 946,454,528 bytes | Selected experimental baseline |
| <=12 s quiet-boundary, no overlap | 32.81% / 10.84% | 558 / 1,356 ms | 392,658,944 bytes | Reject: accuracy regression |
| <=10 s core + 1 s context each side, timestamp-selected tokens | 36.72% / 12.26% | 603 / 1,563 ms | 408,317,952 bytes | Reject: accuracy regression |

[Quiet-boundary raw results](../tools/stt_results/ml-indicconformer-quiet12-30.json),
[context raw results](../tools/stt_results/ml-indicconformer-context10-30.json).
Sample coverage/bounds were tested. Lower desktop RAM does not establish phone
RAM fit, and latency differences do not establish phone speedups.

A supplementary legacy-chillu text-fold experiment yielded 27.84% WER on the
30 clips, versus unchanged primary 28.91%. This explains only a small portion
of the score; it is **not a recognition improvement**. Neither primary scorer
nor app transcript text was changed. [Separate exploratory result](../tools/stt_results/ml-chillu-orthography-analysis-30.json).

## Android changes and TTS

The existing manual CTC loader/installer is reused. Model path is
`files/language_packs/shared/stt-ml-ctc-v2/`. The manifest and runtime registry
agree on pinned revision `9721eb71eea141fae0982cfcdb9dd2e3d4953c4a`:

* `ml/model.int8.onnx`: 197,595,555 bytes; SHA-256
  `dcbdfa9f773db910508b40b703cb76c5974e8d4c6f123ea81265b40853c3f0c2`.
* Root `tokens.txt`: 67,605 bytes; SHA-256
  `ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2`.

Download promotion checks all hashes; existing shared Tiny files do not mark
Malayalam CTC installed. Missing model fails explicitly with a Language Packs
download instruction, rather than falling back to Tiny or English translation.
Auto-detect intentionally remains the existing shared Whisper path: manually
select Malayalam to use the new pack. No other language model, translation
engine or Bluetooth protocol was changed by this focus.

Existing Malayalam MMS-VITS TTS stays downloadable, 114,052,927 bytes. Its
previous measured 30 trials (five native short phrases repeated six times,
one thread after native warm-up) produced non-silent 16 kHz PCM, no missing
letter/mark in those phrases, **968 ms mean / 1,229 ms p95**, RTF 0.9152,
peak desktop process working set 236,998,656 bytes. This focus did not remeasure
TTS or establish intelligibility. [Raw TTS evidence](../tools/stt_results/five-language-ml-tts-30.json).
STT + TTS files total **311,716,087 bytes**; no ONNX language weights are bundled.

A separate real phone-benchmark bug was found and fixed: bundled FLEURS files
are IEEE float32 WAV, while `WavWriter.readWav()` interpreted every data byte
as PCM16. The reader now parses RIFF chunks, recognizes PCM16/float32 and
rejects unsupported rate/channel/encoding, truncation and non-finite samples.
It honors data length and skips metadata. The new Malayalam sample has exactly
332,160 decoded floats; their byte SHA matches Python soundfile's independent
decode: `b25d087009228ac26511eb67cdee5c60bb92d01085c2382ec7c4541b9131010d`.
This shared fix also corrects the existing diagnostic recordings; it does not
modify live mic PCM capture or speech model choices for other languages.

Diagnostics now exposes the nine-language self-test and truthful Malayalam
desktop references, memory caution, experimental status and unsupported general
translation. STT and TTS success/errors are reported separately. Script success
on one sample is a smoke test, not a phone WER or listening-quality certification.
Current ML Kit routing cannot generally translate Malayalam; original-text
bypass is explicitly labeled by the existing pipeline. Emergency phrasebook
and same-language output do not establish general translation.

## Reproduction and verification

Run CPU benchmarks separately in fresh processes, without a simultaneous build:

```powershell
python tools/evaluate_omnilingual_candidate.py benchmark --languages ml
python tools/evaluate_dolphin_candidate.py benchmark --languages ml
python tools/validate_stt.py benchmark --model small --label ml-whisper-small-30 --model-dir app/build/whisper-small --prefix small- --manifest tools/stt_models/five-language-validation/manifest-ml-30.json --languages ml
python tools/evaluate_malayalam_chunking.py --self-test
python tools/evaluate_malayalam_chunking.py --mode quiet12 --set 30
python tools/evaluate_malayalam_chunking.py --mode context10 --set 30
python tools/fetch_speech_heldout.py --languages ml
python tools/evaluate_malayalam_chunking.py --mode whole --set 100
python tools/test_malayalam_focus.py
python tools/prepare_five_self_test_audio.py
$env:GRADLE_USER_HOME='C:\Users\avira\.gradle'
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
```

**BUILD SUCCESSFUL: 432 tests, zero failures/errors/skipped.** New regressions
cover missing Malayalam pack/no Tiny fallback, old-file readiness, native/wrong/
empty script, pinned manifest identity, PCM16 round-trip, float32 metadata/data
length handling, malformed audio and the actual Malayalam sample. Python raw
metric/provenance checks and chunk-boundary tests passed. No phones used.

APK: `app/build/outputs/apk/debug/app-debug.apk`, **95,065,050 bytes**, SHA-256
`d6a0c0f52a2034336637a5dad9223ebef9ee7f1a2634b4050abed5426c0b1d40`.
APK ZIP inspection confirms the new Malayalam manifest/sample and no language
ONNX weights. APK growth versus the previous audit's 93,693,412 bytes is
1,371,638 bytes, chiefly the bundled speech sample; model download is separate.

Files touched for this focus:

* `app/src/main/java/com/itantra/core/inference/AdditionalSttModel.kt`
* `app/src/main/assets/language_packs/ml_dev_manifest.json`
* `app/src/main/java/com/itantra/core/inference/FiveLanguageSelfTest.kt`
* `app/src/main/java/com/itantra/core/audio/WavWriter.kt`
* `app/src/main/java/com/example/itantra/ui/screens/diagnostics/DiagnosticsScreen.kt`
* `app/src/main/java/com/example/itantra/ui/screens/language/LanguagePacksScreen.kt` (Malayalam model label and experimental disclosure only)
* `app/src/main/assets/benchmark/five_self_test/ml.wav`, `manifest.json`
* `AdditionalSttModelTest.kt`, `FiveLanguageSelfTestTest.kt`, `PipelineGeneralizationTest.kt`, `WavWriterTest.kt`
* `tools/prepare_five_self_test_audio.py`, `fetch_speech_heldout.py`, `evaluate_malayalam_chunking.py`, `test_malayalam_focus.py`
* `tools/evaluate_omnilingual_candidate.py`, `evaluate_dolphin_candidate.py` (Malayalam CLI choice only)
* This report, `QUICK_STAT_SUMMARY.md`, `LANGUAGE_BENCHMARKS.md`, dated update in `KANNADA_MALAYALAM_LANGUAGE_CHECK.md`, and seven focused raw report JSONs.

## Phone checklist — not completed

Install the new APK over the existing app; download Malayalam **STT** (~197.7 MB)
in Language Packs. Keep/download Malayalam **TTS** (~114.1 MB). Select Malayalam
as the mic language, not Auto. For the first speech check choose Malayalam as
the target as well, avoiding an unsupported translation route. Run Diagnostics
self-test while disconnected and with continuous listening stopped; listen to
`സഹായം വേണം`. Then test normal spoken phrases and the offline two-phone route.

| Measurement | Phone A / B or listener result |
|---|---|
| Phone model / Android / available RAM | Not verified |
| Malayalam self-test text, script, STT ms, TTS ms | Not verified |
| Audible intelligible Malayalam, listener score | Not verified |
| 30+ spoken utterances with ground truth, WER/CER | Not verified |
| Peak Android PSS, long utterance/OOM check | Not verified |
| Offline A speech -> B native text and audible voice | Not verified |
| Speech-end -> B playback delay / delivery / retry | Not verified |

The remaining accuracy work needs representative Malayalam error analysis and
model/data improvement, followed by an untouched held-out test. None of the
tested larger models or bounded-inference variants justifies an under-10% claim.
