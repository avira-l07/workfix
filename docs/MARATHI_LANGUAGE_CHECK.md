# Marathi language check — 4 October 2026

Result: the old Marathi Whisper Tiny path failed the desktop native-script
and accuracy check. Manual Marathi now uses the measured IndicConformer INT8
model through an on-demand download. Its desktop WER is 15.70% with 30/30
native-script transcripts; the under-10% goal is not met. Phone and listener
validation are still Not verified.

## Diagnosis before the fix

* `LanguageCode.MARATHI` has wire code `mr`.
* Before this fix, `AdditionalSttModel` had no Marathi entry. Manual Marathi used
  the shared Tiny encoder/decoder/tokens from `ModelFileSpecs.getSttSpec`.
* `SherpaOnnxSpeechRecognizer` passes `mr` in manual mode. For a Marathi
  target the task is `transcribe`; an English target uses Whisper's `translate`
  task by design. This benchmark isolates Marathi transcription.
* Script resolution preserves Marathi when manual context is Marathi;
  Devanagari alone cannot distinguish Marathi from Hindi.
* The prior `mr_dev_manifest.json` specified downloadable Whisper Tiny STT and MMS-VITS
  TTS files. All benchmarked Tiny files match its hashes, ruling out a corrupt
  local model as the explanation for this run.
* Offline translation maps Marathi to ML Kit `TranslateLanguage.MARATHI`
  and the alternative Indic route to `mar_Deva`. These mappings are code
  evidence; phone translation/model readiness was not tested.

## Measured speech recognition

Thirty ground-truth Marathi FLEURS validation recordings were checked for
16-kHz mono audio and SHA-256 integrity. Same recordings and normalization
(`validate_stt.normalize`) for both recognizers; fresh process per model,
four CPU threads, greedy decode and 100-ms appended silence. No fine-tuning
or test-driven decoding changes were made.

| Metric | Previous Whisper Tiny INT8 | Current dedicated IndicConformer INT8 |
|---|---:|---:|
| WER | 133.94% | 15.70% |
| CER | 103.70% | 4.64% |
| Native-script transcripts | 0/30 | 30/30 |
| Mean / p95 decode | 1,022 / 2,898 ms | 757 / 1,441 ms |
| RTF | 0.079 | 0.058 |
| Peak desktop process working set | 527,376,384 bytes | 537,788,416 bytes |
| STT model + tokens | 103,609,903 bytes (manifest) | 197,663,198 bytes |

WER may exceed 100% when insertions outnumber the reference words. Tiny produced
romanized, English and unrelated-script text in this test. Native script is a
script check, not proof of correct Marathi language or intelligibility.
Timing covers decode, not capture, translation, Bluetooth or remote playback.
Desktop working set is not Android RAM. The 30 clean read-speech clips do not
establish field/noisy accuracy; a separate 100-clip and noisy test is Not run.

The candidate's language model is from the pinned public ONNX export
`parismitaglobalsolutions/indicconformer-sherpa-onnx`, revision
`9721eb71eea141fae0982cfcdb9dd2e3d4953c4a`, file `mr/model.int8.onnx`.
Model bytes: 197,595,593; SHA-256:
`1ea81e55c4b9b12624c9d02a5b9c1b6f7c871c78a55ff52d333f81cb5136eaf2`.
Shared Indic tokens add 67,605 bytes. The
[AI4Bharat source](https://huggingface.co/ai4bharat/indicconformer_stt_mr_hybrid_ctc_rnnt_large)
declares MIT and Marathi CTC/RNNT support. The candidate is loaded through
sherpa-onnx `from_nemo_ctc`; it is now a downloadable manual Marathi pack.
The pack adds about 94 MB of STT files versus Tiny for a standalone
Marathi install. Shared Tiny may remain needed by other selected languages.
No speech model weights are added to the base APK; only a small sample WAV is added for Diagnostics.

## Measured TTS and a vocabulary defect

The current local Marathi MMS-VITS model and tokens match the app manifest's
SHA-256 hashes. Five short field phrases, six repetitions each after warm-up,
one thread: **30 non-silent outputs at 16 kHz**, mean synthesis **548.7 ms**,
p95 **701.4 ms**, RTF **0.500**, peak desktop process working set
**240,410,624 bytes**. Model plus tokens: **114,044,398 bytes**.

The phrase `डॉक्टरांना बोलवा` triggered sherpa's `Skip unknown character`
warning for **U+0949**, the vowel sign `ॉ`, in all six repetitions. The sign is
absent from the current token file. Nonempty audio therefore does not establish
complete pronunciation: the frontend skips a speech-relevant character.
The report now records letters/marks absent from the token file per phrase.
Human intelligibility and naturalness are **Not verified**.

Do not append an invented token ID: a new ID needs a corresponding trained
model embedding. Check a supported Marathi voice/tokenizer or a documented
normalization method, then run a native-speaker listening test. No pronunciation
substitution or app TTS change was made in this audit. The
[MMS Marathi source voice](https://huggingface.co/facebook/mms-tts-mar)
is CC-BY-NC-4.0, as are the other current MMS voices; it needs appropriate
licensing before commercial distribution.

## Reproduce and evidence

FLEURS dataset revision: `168de341b3db6859a9bac1c50a2ef5e3b47647e0`, file
`mr_in/validation/0000.parquet`, 359,315,258 bytes, SHA-256
`b94d93579851303530cb0ae0bf66b78f60e4e2e9bc767f62c223681520f5ff0d`.
[Google FLEURS](https://huggingface.co/datasets/google/fleurs) is CC-BY-4.0.
Downloaded evaluation files and candidate weights stay in ignored local caches.

```powershell
python tools/benchmark_five_language_stt.py fetch --languages mr
python tools/benchmark_five_language_stt.py fetch-model --languages mr
python tools/validate_stt.py benchmark --model tiny --label mr-whisper-tiny-30 --manifest tools/stt_models/five-language-validation/manifest-mr-30.json --languages mr
python tools/benchmark_five_language_stt.py benchmark --languages mr
python tools/benchmark_five_language_tts.py --language mr
```

Raw outputs: `tools/stt_results/mr-whisper-tiny-30.json`,
`five-language-mr-30.json`, `five-language-mr-tts-30.json`. The initial TTS run's
token-coverage fields were added afterward using the same token file and its
recorded phrases; latency measurements were preserved. Future runs record
token coverage directly. Marathi script fixtures, corpus hashes, all 30 TTS
rows and six unsupported-character phrase repetitions were checked locally;
affected Python scripts passed syntax validation. The Android integration is described below.

Phone STT/TTS, translation quality, listener ratings, 100/noisy STT tests and
live two-device delay remain **Not verified / Not run**. Marathi is not counted
as phone-validated. Native-script STT success is desktop evidence only.


## App fix — 4 October 2026

* Added Marathi to `AdditionalSttModel`, selecting the same CTC model and token
  hashes measured above. Files go to `shared/stt-mr-ctc-v2`; the pinned download
  verifies SHA-256 and file length before atomic installation.
* Updated `mr_dev_manifest.json` to version 2.0.0 and 197,663,198 STT bytes;
  the existing 114,044,398-byte TTS files and hashes are unchanged. Together
  they occupy 311,707,596 bytes (about 312 MB) after a fresh install.
* The existing registry-based repository and recognizer paths handle readiness,
  download and inference. An old shared Tiny install cannot count as Marathi
  ready; missing dedicated files produce an explicit download-required error.
* A manual Marathi mic uses source-language CTC transcription even when the
  target is English; offline text translation remains in the translation router.
  Auto-detect remains shared Whisper and does not use this dedicated model.
* Diagnostics now shows Marathi desktop WER/CER and mean STT/TTS timings, the
  TTS vocabulary warning, and an eight-language self-test. Its new Marathi
  recording is a checksum-verified FLEURS evaluation sample at 16 kHz.
* Regression checks cover manifest/runtime consistency, Marathi script detection,
  sample provenance, old-install readiness, missing-pack errors and Marathi-to-
  English routing. No model training, TTS tokenizer replacement or pronunciation
  substitution is claimed.

### Install and check

Install the fresh APK over the existing app. Open Language Packs, download the
Marathi STT pack (about 198 MB; the old Tiny files are not sufficient), retain
or download Marathi TTS, and select **Marathi** as the mic language. With peers
disconnected and continuous listening stopped, run the Diagnostics self-test
and listen to its Marathi phrase. Then record the two-phone test separately.
Existing Tiny files are retained for Auto-detect and other fallback languages.

### Files touched for the app integration

* `app/src/main/java/com/itantra/core/inference/AdditionalSttModel.kt`
* `app/src/main/assets/language_packs/mr_dev_manifest.json`
* `app/src/main/java/com/itantra/core/inference/FiveLanguageSelfTest.kt`
* `app/src/main/java/com/example/itantra/ui/screens/diagnostics/DiagnosticsScreen.kt`
* `app/src/main/assets/benchmark/five_self_test/mr.wav` and `manifest.json`
* `app/src/test/java/com/itantra/core/inference/AdditionalSttModelTest.kt`
* `app/src/test/java/com/itantra/core/inference/FiveLanguageSelfTestTest.kt`
* `app/src/test/java/com/itantra/core/transceiver/PipelineGeneralizationTest.kt`
* `tools/prepare_five_self_test_audio.py`
* `docs/MARATHI_LANGUAGE_CHECK.md`, `docs/LANGUAGE_BENCHMARKS.md`,
  `docs/QUICK_STAT_SUMMARY.md`


### Build verification

Executed `./gradlew.bat testDebugUnitTest assembleDebug --no-daemon` with the
existing Gradle cache: **BUILD SUCCESSFUL**, **425 tests**, **0 failures**,
**0 errors**, **0 skipped**. The fresh APK is
`app/build/outputs/apk/debug/app-debug.apk`, **93,503,298 bytes** (about 93.5 MB),
SHA-256 `ccd9730a2bc83945a85ff390b9f078bad49068147c331c4942ec49d02789ab42`.
ZIP inspection verified the updated Marathi manifest, all eight sample hashes
and zero ONNX language-model weights under `assets/language_packs/`.
The extra Marathi sample is test audio, not a model weight. The pinned local
model and token hashes match the download manifest. WER/CER and native-script
counts were independently recomputed from the raw old/new reports, with the
same 30 source row indices. `git diff --check` passed.

This does not establish Android model load time, phone decode performance,
audibility, pronunciation quality, live delivery or field accuracy. Those
remain Not verified; no phone was used in this integration.
