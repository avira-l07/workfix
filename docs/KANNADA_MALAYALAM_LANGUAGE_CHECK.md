# Kannada and Malayalam language check — 4 October 2026

**Later Malayalam update:** this document records the initial audit. The subsequent
[Malayalam focus](MALAYALAM_STT_IMPROVEMENT.md) measured three more models and an
independent 100-recording set, then added an experimental Malayalam CTC download
and fixed float32 self-test WAV decoding. Malayalam's current app selection is
now dedicated CTC in manual mode; Kannada's selection remains unchanged. Read
the newer report for the current APK and verification results.

Both current Whisper Tiny paths fail this desktop native-script and accuracy check. Dedicated IndicConformer INT8 candidates improve the same-clip results but remain **candidates only**: they have not replaced the app downloads. Neither achieves the under-10% WER goal. This audit fixed a separate TTS startup-check defect using native phrases; it does not establish phone quality.

## Phase A: current app diagnosis

| Language | Current manual STT / code / sample rate | Raw Tiny script result | TTS model / locale folder | Voice installation | Exact phone failure |
|---|---|---|---|---|---|
| Kannada | Shared Whisper Tiny INT8; `kn`; 16 kHz mono | 0/30 native script; 1 empty output; romanized/English/unrelated text | MMS-VITS `mms-tts-kn`; remote `kan`; 16 kHz output measured | Downloadable, model weights excluded from APK | Not verified; no phone used |
| Malayalam | Shared Whisper Tiny INT8; `ml`; 16 kHz mono | 0/30 native script; 2 empty outputs; romanized/English/unrelated text | MMS-VITS `mms-tts-ml`; remote `mal`; 16 kHz output measured | Downloadable, model weights excluded from APK | Not verified; no phone used |

* `AdditionalSttModel` has no Kannada/Malayalam entries; manual recognition loads shared `files/language_packs/shared/stt/tiny-{encoder.int8.onnx,decoder.int8.onnx,tokens.txt}`. The manual language code is the correct `kn` or `ml`, with `task=transcribe` for this comparison. An English target currently uses Whisper native translation; it was **not** benchmarked here.
* Tiny file SHA-256s match both current app manifests. A corrupt local model is not the explanation for these measured failures.
* `LanguageScriptDetector` has Kannada U+0C80–U+0CFF and Malayalam U+0D00–U+0D7F mappings. PTT/continuous processing rejects a returned script diagnostic rather than sending that text as successful native transcription. The desktop benchmark below reports raw engine output, not a full Android UI/transport run.
* TTS uses `<pack>/tts/model.onnx` and `tokens.txt`. All four local voice/token files match the current app manifests. These MMS character voices did not require an additional lexicon or espeak data directory in the actual desktop synthesis tests. Model-loading errors already surface explicit exceptions.
* The active `AppGraph.translationEngine` is ML Kit. Kannada maps to `TranslateLanguage.KANNADA`. Malayalam maps to `null` and is absent from the supported set. The emergency phrasebook and same-language pass-through remain separate; they are not general Malayalam neural translation. The alternate CTranslate2 mapping `mal_Mlym` exists but is not the active engine and was not tested. [Google supported languages](https://developers.google.com/ml-kit/language/translation/translation-language-support) confirms Kannada support and does not list Malayalam.

## Same-recording STT comparison

Four fresh Windows processes: one Tiny and one CTC run per language. The first 30 pinned FLEURS validation utterances per language are identical across candidates. WAV SHA-256, mono channels and 16 kHz sample rate were checked. Four CPU threads, greedy decode, 100 ms trailing silence, no fine-tuning or script-rewriting postprocessing. WER/CER use the existing NFC/punctuation normalization and aggregate edit errors divided by ground-truth totals. WER can exceed 100% through inserted words.

| Language / model | WER / CER | Native script | Mean / p95 STT decode | RTF | Peak desktop process working set | STT file bytes |
|---|---:|---:|---:|---:|---:|---:|
| Kannada — Current Tiny | 169.80% / 106.44% | 0/30 | 1,163 / 2,386 ms | 0.092 | 533,815,296 bytes | 103,609,903 |
| Kannada — IndicConformer candidate | 17.23% / 6.32% | 30/30 | 728 / 1,372 ms | 0.058 | 552,312,832 bytes | 197,663,333 |
| Malayalam — Current Tiny | 193.16% / 102.32% | 0/30 | 1,362 / 3,264 ms | 0.082 | 534,880,256 bytes | 103,609,903 |
| Malayalam — IndicConformer candidate | 28.91% / 7.06% | 30/30 | 664 / 2,194 ms | 0.040 | 946,454,528 bytes | 197,663,160 |

Kannada candidate: native output 30/30 and 17.23% WER; under-15% and under-10% are not achieved. Malayalam candidate: native output 30/30 and 28.91% WER; both accuracy goals are unmet. Native script does not by itself establish correct words or intelligibility.

Malayalam input lengths were 6.54–47.64 seconds, Kannada 4.68–26.34 seconds. The measured Malayalam candidate process working set peaked at 946,454,528 bytes. This is a significant phone-readiness concern, **not an Android RAM measurement**; allocation versus clip duration and actual phone PSS/low-memory behavior still need measurement. Disk size does not establish RAM fit. STT timing excludes capture, translation, link transfer and remote audio playback. Independent 100-clip, noisy and phone tests are Not run.

## TTS results and the startup fix

Five native field phrases per language, six repetitions each after native warm-up, one thread. All 30 trials per language generated non-silent PCM at 16 kHz; none of the five phrases had a missing letter/mark in the token vocabulary. These are 30 synthesis trials, **not 30 distinct texts**. Human intelligibility and naturalness remain Not verified.

| Voice | Mean / p95 synthesis | RTF | Peak desktop process working set | Model + tokens |
|---|---:|---:|---:|---:|
| Kannada MMS-VITS | 1,360 / 1,872 ms | 0.863 | 281,145,344 bytes | 114,045,931 bytes |
| Malayalam MMS-VITS | 968 / 1,229 ms | 0.915 | 236,998,656 bytes | 114,052,927 bytes |

Kannada exceeds the requested 1,200 ms mean TTS target on these short phrases. Malayalam passes that desktop mean target only; phone synthesis and listening quality are Not verified.

Before the fix, `SherpaOnnxSpeechSynthesizer.load()` used default `Hello` for both voices. Direct execution with the actual files confirmed:

* Kannada skips `h`, `e`, `l`, `l`, `o`; the run still returned 4,338 samples, so the old nonempty-PCM check could pass without testing Kannada speech.
* Malayalam skips `h`, `e`, `l`, `l` and retains `o`; it returned 3,840 samples, also not a meaningful Malayalam speech check.
* Native `ಸಹಾಯ ಬೇಕು` and `സഹായം വേണം` produced non-silent waveforms without those unsupported-letter warnings. The load-time smoke text now uses these native phrases. A unit test checks every phrase character against the actual shipped token files and preserves Hindi/English startup text.

No fabricated phoneme IDs, spelling substitutions or new voice weights were introduced. This fix improves what startup checks, not the model's speech accuracy. A ready/downloaded badge still does not certify human intelligibility.

## Candidate files, size and APK impact

The public ONNX export is pinned to `parismitaglobalsolutions/indicconformer-sherpa-onnx`, revision `9721eb71eea141fae0982cfcdb9dd2e3d4953c4a`:

| Candidate file | Bytes | SHA-256 |
|---|---:|---|
| `kn/model.int8.onnx` | 197,595,728 | `b226ce7e4ea35b0dd66991964bd00e011b6b14b0fcdf4f7d1cccd777781c94dc` |
| `ml/model.int8.onnx` | 197,595,555 | `dcbdfa9f773db910508b40b703cb76c5974e8d4c6f123ea81265b40853c3f0c2` |
| Shared `tokens.txt` | 67,605 | `ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2` |

Candidate STT plus current TTS would occupy 311,709,264 bytes for Kannada and 311,716,087 bytes for Malayalam. No candidate weights were added to the APK: they remain ignored benchmark-cache files. A future selective-download integration can reuse the same registry/installer as Marathi, but it has not been shipped by this check. Shared Tiny remains the actual app path.

The [Kannada AI4Bharat source](https://huggingface.co/ai4bharat/indicconformer_stt_kn_hybrid_ctc_rnnt_large) and [Malayalam source](https://huggingface.co/ai4bharat/indicconformer_stt_ml_hybrid_ctc_rnnt_large) declare MIT and their respective languages. Source NeMo checkpoints are gated; this evaluation used the public pinned ONNX export, loaded successfully by sherpa-onnx `from_nemo_ctc`. The current [Kannada MMS voice](https://huggingface.co/facebook/mms-tts-kan) and [Malayalam MMS voice](https://huggingface.co/facebook/mms-tts-mal) declare CC-BY-NC-4.0; commercial redistribution requires appropriate licensing.

## Evaluation provenance and commands

FLEURS revision `168de341b3db6859a9bac1c50a2ef5e3b47647e0`, CC-BY-4.0. Public evaluation audio only; no Kathbath training audio was downloaded and no running Kaggle notebook was modified.

* `kn_in/validation/0000.parquet`: 299,080,789 bytes; SHA-256 `ab2721cf60f02a1f8911baddfa725e1d98af83d67d89a51b9313ae18a0f059ac`.
* `ml_in/validation/0000.parquet`: 386,773,831 bytes; SHA-256 `0a118e7e8920fd31f283cfdc0c55fc00537e93c726b3e0727aebc6700b3bacce`.

```powershell
python tools/benchmark_five_language_stt.py fetch --languages kn ml
python tools/benchmark_five_language_stt.py fetch-model --languages kn ml
python tools/validate_stt.py benchmark --model tiny --label kn-whisper-tiny-30 --manifest tools/stt_models/five-language-validation/manifest-kn-30.json --languages kn
python tools/benchmark_five_language_stt.py benchmark --languages kn
python tools/validate_stt.py benchmark --model tiny --label ml-whisper-tiny-30 --manifest tools/stt_models/five-language-validation/manifest-ml-30.json --languages ml
python tools/benchmark_five_language_stt.py benchmark --languages ml
python tools/benchmark_five_language_tts.py --language kn
python tools/benchmark_five_language_tts.py --language ml
python tools/test_kn_ml_language_audit.py
```

Run each inference benchmark sequentially in a fresh process. Raw reports in `tools/stt_results/`: `kn-whisper-tiny-30.json`, `ml-whisper-tiny-30.json`, `five-language-kn-30.json`, `five-language-ml-30.json`, `five-language-kn-tts-30.json`, `five-language-ml-tts-30.json`. The direct legacy/native warm-up results are `kn-tts-app-smoke.json` and `ml-tts-app-smoke.json`; these capture the pre-fix Hello defect.

## Code and verification

Files touched for this check:

* `tools/benchmark_five_language_stt.py`: pinned corpora/models, native script ranges and CLI choices for Kannada/Malayalam.
* `tools/benchmark_five_language_tts.py`: the two sets of native test phrases.
* `tools/test_kn_ml_language_audit.py`: corpus/model provenance, same-row pairing, recomputed WER/CER/script totals and TTS row verification.
* `app/src/main/java/com/itantra/core/inference/SherpaOnnxSpeechSynthesizer.kt`: native Kannada/Malayalam startup phrases; existing other-language selection preserved.
* `app/src/test/java/com/itantra/core/inference/MultilingualTtsIntegrationTest.kt`: native phrase/token compatibility regression.
* This report, `docs/LANGUAGE_BENCHMARKS.md`, `docs/QUICK_STAT_SUMMARY.md`, and eight raw result JSONs.

`./gradlew.bat testDebugUnitTest assembleDebug --no-daemon`: **BUILD SUCCESSFUL**, **426 tests, 0 failures/errors/skipped**. Both native/wrong-script/empty fixtures passed; corpus hashes/rates and candidate model hashes passed; paired raw metric totals were independently verified. No existing STT download selection changed. Hindi/English accuracy was not re-benchmarked, and no new phone-success claim is made.

Fresh startup-fix APK: `app/build/outputs/apk/debug/app-debug.apk`, **93,693,412 bytes**, SHA-256 `3dbf6230990d87e826768f489c2204ef673e15310f91c078c0605c081e6817aa`. ZIP inspection confirmed no ONNX language weights in `assets/language_packs/`, and both Kannada/Malayalam manifests still select Tiny.

## Still unresolved

* Current app Kannada/Malayalam native STT is not reliable on this benchmark; the tested CTC candidates are not wired into the app yet.
* Kannada candidate accuracy misses both targets and current TTS misses the short-phrase mean latency target.
* Malayalam candidate accuracy is 28.91% WER, has high measured desktop peak RAM, and the active translation engine lacks general Malayalam translation.
* Phone inference/crashes, native-listener quality, noisy speech, 100-utterance independent accuracy, live two-device offline delivery and turnaround latency are **Not verified / Not run** for both.

The next justified step is to test these candidate models on phones and a larger independent corpus before calling them production-ready. A candidate download integration can be made separately with explicit unverified status; native output alone is not enough to mark all ten languages working.
