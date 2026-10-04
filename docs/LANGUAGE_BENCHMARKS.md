# Speech model evidence — 2–4 October 2026

## Authorized audit fixes — 4 October 2026

This section supersedes the older Kannada Tiny runtime decision below.
Manual Kannada now uses its SHA-pinned, separately downloadable IndicConformer
CTC INT8 model. Auto-detect still uses Tiny and remains unreliable for Kannada.

| Fresh same-corpus comparison | Whisper Tiny before | Dedicated Kannada CTC after |
|---|---:|---:|
| Clean FLEURS recordings | 30 | 30 |
| WER | 169.80% | 17.23% |
| Native-script outputs | 0/30 | 30/30 |
| Mean decode | 892 ms | 440 ms |
| p95 decode | Not calculated | 1,123 ms |
| CER | Not calculated in this fresh baseline | 6.32% |
| Decode RTF | Not calculated in this fresh baseline | 0.0349 |
| STT files | Shared existing Tiny files | 197,663,333 bytes |
| Peak process working set | Not measured | 554,360,832 bytes (desktop) |

Commands: `python audit/2026-10-04/fixes/kannada_comparison.py` and the same
command with `--ctc`. Raw outputs: `audit/2026-10-04/fixes/kn-tiny-30.json` and
`audit/2026-10-04/fixes/five-language-kn-30.json`. WAV hashes were checked
before decoding. Decode timings exclude model load and are desktop measurements;
the build and other work were running concurrently, so they are not isolated
phone latency estimates. Kannada remains above the 15% accuracy target.

Marathi's existing MMS voice lacks `ॉ` (U+0949). The published upstream
vocabulary was fetched and checked and also lacks it. The synthesis path now
rejects unsupported Marathi letters/marks before native generation, preserves
the original message, and reports a voice error. Its startup phrase is now
Marathi. **This is an explicit failure fix, not a pronunciation/model-quality
fix. A replacement voice and native-speaker ratings remain Not verified.**

Diagnostics self-test now includes ten languages. No language is promoted to
live two-phone validation. Hindi/English model files and decoding were unchanged.
The full audit-fix regression suite passed 444 tests with zero failures.
See [the TXT fix report](AUDIT_FIXES_2026-10-04.txt) for all findings and build evidence.

## Earlier measurements and decisions

The original five-language audit covers Hindi, English, Tamil, Telugu and Odia.
The final search, Bengali/Gujarati extension, Marathi fix and Kannada/Malayalam audit appear below. All numbers are from
commands run on a Windows desktop with clean read speech. **None of these ten audited languages
has passed a live two-phone test.** A generated waveform is not proof that a
speaker sounded intelligible. STT/TTS model downloads require internet during
provisioning; speech inference uses local files after installation.

## Phase A: code-path diagnosis before changes

| Language | Manual mic recognizer and routed code | Output script in prior desktop corpus | Offline TTS voice | APK voice / installation | Exact phone failure |
|---|---|---|---|---|---|
| Hindi | IndicConformer CTC INT8, `hi` selects dedicated model (CTC has no Whisper language token) | Devanagari, 30/30 in current corpus | MMS-VITS `hi` | Downloadable, checksum-checked; not bundled | Not verified: no device trace |
| English | NeMo FastConformer CTC INT8, `en` selects dedicated model | Latin, 30/30 in current corpus | MMS-VITS `en` | Downloadable, checksum-checked; not bundled | Not verified: no device trace |
| Tamil | IndicConformer CTC INT8, `ta` selects dedicated model | Tamil, 30/30 in current corpus | MMS-VITS `ta` | Downloadable, checksum-checked; not bundled | Not verified: no device trace |
| Telugu | IndicConformer CTC INT8, `te` selects dedicated model | Telugu, 30/30 in current corpus | MMS-VITS `te` | Downloadable, checksum-checked; not bundled | Not verified: no device trace |
| Odia | IndicConformer CTC INT8, `or` selects dedicated model | Odia, 30/30 in current corpus | MMS-VITS `or` (`ory` download folder) | Downloadable, checksum-checked; not bundled | Not verified: no device trace |

The microphone records 16-kHz mono PCM and the selected CTC recognizer accepts
16-kHz samples. All five VITS models tested here emit 16-kHz PCM; the app opens
the speaker at the returned sample rate. Each recognizer needs its exact pinned
ONNX model and tokenizer. The TTS loader checks model and token files, with
optional lexicon/data/dict directories when present; those optional files are
not required by these five voices. The `or` wire code maps to the Odia model;
its TTS publisher folder is `ory`. A manually chosen language does not pass a
Whisper language token because it loads a language-specific CTC model. Auto
mode still uses the separate multilingual Whisper path.

The existing STT entry point tells the user when a selected STT pack is absent.
The receive path previously marked a message delivered when one of these five
TTS voices was missing; it now shows a specific missing-voice/load error and
reports TTS failure to the peer when a secure session exists. The new
Diagnostics self-test displays a separate STT/TTS error per language. Other
languages' fallback behavior was not changed.

## Model decision

| Candidate | Evidence on target languages | Model/storage impact | Desktop RAM / APK impact | Decision |
|---|---|---|---|---|
| Current dedicated IndicConformer/English FastConformer INT8 | Prior 10–30-clip WER and new 30-clip measurements below | STT 174.6 MB English or ~197.7 MB each Indic; per-language downloaded | Current fresh-process peak in table below; no model weights in base APK | Retain pending a measured better model |
| Whisper Tiny INT8 multilingual | Prior same 10-clip Tamil 109.0% WER, Telugu 136.2% WER | ~99 MB shared | Prior desktop peak ~426 MB; zero base-APK model impact if on demand | Reject for Tamil/Telugu accuracy |
| Whisper Small INT8 multilingual | Prior same 10-clip Tamil 84.1% WER, Telugu 100.0% WER | ~375.5 MB shared encoder, decoder and tokens | Prior desktop peak ~1.21 GB; ~+178 MB installed vs a dedicated Indic model, zero base-APK impact if on demand | Reject for Tamil/Telugu accuracy and memory |
| Tamil fine-tuned Whisper Small / Telugu fine-tuned Whisper Tiny | Prior same 10-clip Tamil 54.5% WER / Telugu 80.9% WER | Tamil roughly Small, Telugu roughly Tiny; exact total distribution size not verified | Prior desktop peak ~1.01 GB; no base-APK impact if on demand | Reject: worse WER |
| sherpa-onnx Omnilingual 300M CTC INT8 | **Same 30 clips:** Tamil 53.8%, Telugu 43.9%, Odia 53.7% WER; native script on 29/30, 30/30, 29/30 | 365,438,543 bytes model + tokens, shared; +167.8 MB if replacing one Indic STT, −227.5 MB if replacing all three | Measured peak Windows working set 1,136 MB; zero base-APK impact only if on-demand | Reject: accuracy and latency worse than all three current models |
| sherpa-onnx Dolphin Small CTC multilingual INT8 | **Same 30 clips:** Tamil 53.8% WER / 19.8% CER, Telugu 55.7% WER / 18.1% CER; native script 30/30 for both | 250,163,616 bytes model + tokens, shared; +52.5 MB if replacing one dedicated Indic STT | Measured peak Windows working set 627 MB; zero base-APK impact only if implemented as download | Reject: WER is more than twice the current models' |

The existing Odia IndicConformer candidate is acceptable as an **opt-in
device-unverified candidate**, not a production-certified voice pipeline:
19.3% WER on 30 clean clips misses a high-accuracy bar, and field/noisy speech
is unmeasured. Tamil and Telugu also miss the requested under-15% WER target.
No model switch is justified by the measured alternatives. The code leaves the
selective download system intact.

For VITS, `python tools/benchmark_target_tts.py --threads 1` and `--threads 2`
compared one warmed long field sentence per language over five desktop trials.
Mean synthesis milliseconds were Tamil **1,956 vs 2,214**, Telugu **2,175 vs
2,447**, and Odia **1,613 vs 1,689** (one thread vs two). More threads were
slower in this check, so the app's one-thread setting remains. The result files
are `tools/stt_results/target-tts-{1,2}threads.json`. This does not settle
Android threading or the long-sentence latency target.

For the Omnilingual 300M comparison, the same pinned 30-clip corpus yielded
mean STT decode times of 1,209 ms Tamil, 1,127 ms Telugu and 2,106 ms Odia,
versus 553, 337 and 658 ms for the current dedicated models. Its detailed
WER/CER, p95, RTF and per-clip output are in
`tools/stt_results/omnilingual-300m-int8-30.json`, produced by
`python tools/evaluate_omnilingual_candidate.py fetch` and `benchmark`.
The publisher documents the [sherpa-onnx Omnilingual ONNX package](https://k2-fsa.github.io/sherpa/onnx/omnilingual-asr/models.html).

For the Dolphin Small comparison, the same pinned 30-clip corpus yielded mean
STT decode times of 439 ms Tamil and 429 ms Telugu, against 553 and 337 ms for
the current models. The exact publisher revision and SHA-256-checked model,
per-clip outputs, p95 and RAM are recorded in
`tools/stt_results/dolphin-small-int8-ta-te-30.json`. Reproduce with
`python tools/evaluate_dolphin_candidate.py fetch` and `benchmark`.
The model remains evaluation-only: it worsened WER and would add download and
memory cost. The [sherpa-onnx Dolphin documentation](https://k2-fsa.github.io/sherpa/onnx/Dolphin/pretrained.html)
describes its offline-compatible format. This comparison did not change any
Android language pack or Hindi/English model.

The under-10% WER request for Tamil and Telugu is **not met** on this corpus.
The current 30 clean recordings have 86 word errors in 394 Tamil reference
words and 106 in 467 Telugu reference words. A 10% target requires at most 39
and 46 errors respectively, so small decoding-flag changes are not an evidence-
based route to the target. The [IISc SraVaani publisher](https://vaani.iisc.ac.in/models/sravaani)
reports 28.1% FLEURS WER for Tamil and 23.1% for Telugu; the
[Lemura Tamil model publisher](https://huggingface.co/lemuralabs/tamil-asr-qwen3)
reports 25.27% for Tamil. Those results use different clips and normalization,
so they are screening evidence rather than a direct app comparison. Keep the
native-script dedicated models while pursuing a genuinely
better phone-compatible model or language-specific training on a separate
held-out corpus. No Tamil/Telugu phone result is claimed here.

## Before/after evidence

"Before" uses the small corpora previously recorded in
`docs/QUICK_STAT_SUMMARY.md`. "Current" uses the scripts below. Corpus length,
test split/phrase choice and background load differ; changes in numbers are
**not model improvements** because the model was not replaced.

| Language | Before STT WER (clips), decode mean | Current WER / CER (30), mean / p95 decode, RTF | Current STT peak desktop working set | Before TTS mean | Current TTS mean / p95 (30 short-phrase syntheses) | TTS peak desktop working set |
|---|---|---|---|---|---|---|
| Hindi | 9.1% (30), 789 ms | 9.1% / 3.9%, 530 / 1,200 ms, 0.044 | 516 MB | 969 ms | 767 / 1,087 ms | 236 MB |
| English | 7.1% (20), 138 ms | 6.9% / 3.8%, 153 / 289 ms, 0.018 | 514 MB | 917 ms | 1,004 / 1,237 ms | 224 MB |
| Tamil | 25.5% (10), 317 ms | 21.8% / 8.7%, 553 / 1,025 ms, 0.054 | 472 MB | 1,548 ms | 1,317 / 1,976 ms | 272 MB |
| Telugu | 20.4% (10), 354 ms | 22.7% / 7.1%, 337 / 615 ms, 0.035 | 475 MB | 1,775 ms | 1,312 / 1,703 ms | 252 MB |
| Odia | 21.37% (20), 696 ms | 19.3% / 5.1%, 658 / 1,272 ms, 0.057 | 529 MB | 2,140 ms | 1,040 / 1,450 ms | 248 MB |

The downloaded model and tokenizer sizes (bytes) are:

| Language | STT files | TTS files | Combined installed speech files | Base APK model weight |
|---|---:|---:|---:|---:|
| Hindi | 197,663,198 | 114,043,612 | 311,706,810 | 0 |
| English | 174,621,490 | 114,017,331 | 288,638,821 | 0 |
| Tamil | 197,663,118 | 114,032,763 | 311,695,881 | 0 |
| Telugu | 197,663,298 | 114,038,199 | 311,701,497 | 0 |
| Odia | 197,652,533 | 114,046,707 | 311,699,240 | 0 |

The five short TTS phrases are a different workload from the old long-sentence
numbers. Odia's 1,040-ms mean **does not prove a 1,100-ms speedup**. Tamil and
Telugu still exceed the 1,200-ms mean target on the short-phrase workload; Odia
exceeds it at p95. Android speed and listener quality are Not verified.

Raw results are `tools/stt_results/five-language-*-30.json` and
`tools/stt_results/five-language-*-tts-30.json`. Run
`python tools/benchmark_five_language_stt.py fetch --languages en ta te or`
to download checksum-pinned [Google FLEURS](https://huggingface.co/datasets/google/fleurs)
validation audio under its CC BY 4.0 license. Hindi uses the existing pinned
30-recording FLEURS test manifest. Run
`python tools/benchmark_five_language_stt.py benchmark --languages hi en ta te or`
and run `python tools/benchmark_five_language_tts.py --language CODE` in a
fresh process for each `CODE` (`hi`, `en`, `ta`, `te`, `or`). WER/CER use NFC,
lowercase and punctuation-stripped text, edit distance divided by reference
word/character count; p95 is nearest-rank. RTF is synthesis/decode duration
divided by output/input audio duration. Peak RAM is the fresh Windows process
working set, **not Android peak RAM**. No TTS human quality score is claimed.

## Device self-test and two-phone record

On a phone, install the desired STT and TTS packs among the eight self-test languages, disconnect from a peer,
stop continuous listening, open Diagnostics and tap **Run eight-language
self-test**. It runs one bundled 16-kHz FLEURS sample per language, reports
recognized text and Unicode script, synthesizes a short phrase, attempts local
speaker playback, and reports elapsed decode/synthesis time, output sample rate,
sampled process PSS and explicit errors. This smoke test cannot estimate WER,
voice intelligibility, sustained peak RAM or peer transport success.

For the live offline test, turn off mobile data and Wi-Fi internet on both
phones, while leaving the intended direct Bluetooth/Wi-Fi Direct transport
available. Record phone models, Android versions, installed pack versions and
ambient noise. Connect phone A to phone B, speak an unscripted phrase in each
language on A, inspect the native-script text on both, listen on B, repeat in
the reverse direction, and capture speech-end-to-remote-audible latency. Mark
each result below only after actually performing it.

| Language | A → B native script / intelligible voice / no crash / latency | B → A same checks | Tested devices, date, notes | Status |
|---|---|---|---|---|
| Hindi | ____ | ____ | ____ | Not verified |
| English | ____ | ____ | ____ | Not verified |
| Tamil | ____ | ____ | ____ | Not verified |
| Telugu | ____ | ____ | ____ | Not verified |
| Odia | ____ | ____ | ____ | Not verified |
| Bengali | ____ | ____ | ____ | Not verified |
| Gujarati | ____ | ____ | ____ | Not verified |
| Marathi | ____ | ____ | ____ | Not verified |
| Kannada | ____ | ____ | ____ | Not verified |
| Malayalam | ____ | ____ | ____ | Not verified |

No language is "validated" until its two-phone record is filled and passes.

## Final Tamil/Telugu search — gate: FAIL

This was a bounded preflight of three new model families after the already
measured Whisper, fine-tuned Whisper, Omnilingual and Dolphin comparisons above.
No new candidate passed the phone-size/language/license preflight, so no
additional WER run or Android model switch was justified. “Not verified” means
we did not load or benchmark that candidate; it is not a measured failure.
The exact 30-clip baseline remains 21.8% Tamil and 22.7% Telugu WER.

| New candidate / source | License | Measured file size / phone budget | Runtime, loaded, per-language download | WER, CER, mean/p95, RTF, desktop peak RAM, native script | Verdict |
|---|---|---|---|---|---|
| [AI4Bharat 600M multilingual INT8 ONNX export](https://huggingface.co/yashwork-byte/indic-conformer-600m-int8-onnx) | MIT (export metadata) | `assets/encoder.onnx` alone is **652,623,111 bytes**; exceeds the ~500 MB model budget before other files. Android RAM: Not verified. | ONNX export; sherpa compatibility and load: Not verified. Shared download possible in principle, but too large. | All metrics: Not verified. | Reject at size preflight. |
| [Meta MMS-1B-all](https://huggingface.co/facebook/mms-1b-all) | CC-BY-NC-4.0 | 1B parameters; exact downloadable bytes and Android RAM: Not verified. | Transformer/ONNX export would be required; no sherpa load verified. Per-language adapter download alone does not remove the base-model cost. | All metrics: Not verified. | Reject commercial-use restriction and phone-budget uncertainty. |
| [NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) | CC-BY-4.0 | `model.safetensors` **2,508,311,120 bytes**; exceeds budget. Android RAM: Not verified. | NeMo model; no sherpa load verified. Publisher language list excludes Tamil/Telugu. | All metrics: Not verified. | Reject size and language coverage. |

The `ippocode/indic-asr-onnx` Tamil/Telugu entries are the fine-tunes already
tested above, so repeating them would not be a new candidate. A compatible,
public, phone-fit RNNT export was not identified. **Tamil/Telugu: ceiling reached
among the models evaluated here.** This is a limit of this search and corpus,
not proof that under-10% WER is impossible. The requested under-10% target and
the alternate under-15% plus faster gate both fail. Both current on-demand
IndicConformer packs remain unchanged.

To repeat the model-file size preflight with network access, query the publishers'
file metadata (no model download required):

```powershell
python -c "import json,urllib.request; q=[('yashwork-byte/indic-conformer-600m-int8-onnx','assets/encoder.onnx'),('nvidia/parakeet-tdt-0.6b-v3','model.safetensors')]; [(lambda a: print(repo,[(x['path'],x['size']) for x in a if x.get('path')==want]))(json.load(urllib.request.urlopen('https://huggingface.co/api/models/'+repo+'/tree/main?recursive=true'))) for repo,want in q]"
```

## Bengali and Gujarati after the FAIL gate

The same first 30 Google FLEURS validation recordings per language were pinned
as converted Parquet: `bn_in/validation/0000.parquet`, SHA-256
`f45b98e38812554174a7a13b7f66a778f1f63c48d9723fc37619f3f24132f340`;
`gu_in/validation/0000.parquet`, SHA-256
`a740a6a5b2853e46604fdc0a503e918e051474861cb8b2044214b419f861e296`.
The source dataset is [Google FLEURS](https://huggingface.co/datasets/google/fleurs)
(CC BY 4.0). Both recognizers were benchmarked in fresh processes on identical
audio and normalization. WER above 100% is possible when substitutions and
insertions outnumber reference words.

| Language and STT | WER / CER (30) | Native script | Mean / p95 decode | RTF | Peak desktop working set | STT files |
|---|---:|---:|---:|---:|---:|---:|
| Bengali, existing shared Whisper Tiny | 132.36% / 110.24% | 0/30 | 1,282 / 3,079 ms | 0.101 | 517,083,136 bytes | Shared model; separate download size not applicable |
| Bengali, dedicated IndicConformer INT8 | **14.36% / 3.62%** | **30/30** | **729 / 1,552 ms** | 0.057 | 543,227,904 bytes | 197,663,183 bytes |
| Gujarati, existing shared Whisper Tiny | 118.37% / 104.58% | 0/30 | 800 / 1,414 ms | 0.081 | 484,212,736 bytes | Shared model; separate download size not applicable |
| Gujarati, dedicated IndicConformer INT8 | **18.54% / 5.75%** | **30/30** | **511 / 786 ms** | 0.052 | 476,336,128 bytes | 197,663,066 bytes |

The dedicated models are from the pinned [sherpa ONNX IndicConformer export](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx)
(Apache-2.0 export; AI4Bharat source MIT). Each language is a separate,
SHA-256-checked download, never a bundled model. The Bengali model hash is
`e9120a534f69df065314be468bf15579f1b92a4cd8c07ad119b80b69244718a8`;
Gujarati is `822ed7f0b809bbd479275bf91c913d05564b88c0d082bbcba2f37999b88cb598`.
Their common tokenizer hash is
`ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2`.
The app now routes manual `bn`/`gu` input to these CTC models, with an explicit
missing-pack message. Auto-detect retains its separate multilingual path.

| TTS voice | 30 short phrases: mean / p95 synthesis | RTF | Output | Peak desktop working set | TTS files | Human intelligibility |
|---|---:|---:|---|---:|---:|---|
| Bengali MMS-VITS | 1,046 / 1,639 ms | 0.922 | Nonempty PCM, 16 kHz | 243,507,200 bytes | 114,045,156 bytes | Not verified |
| Gujarati MMS-VITS | 529 / 668 ms | 0.514 | Nonempty PCM, 16 kHz | 224,362,496 bytes | 114,034,326 bytes | Not verified |

The [Bengali](https://huggingface.co/facebook/mms-tts-ben) and
[Gujarati](https://huggingface.co/facebook/mms-tts-guj) MMS-VITS voices are
**CC-BY-NC-4.0**. Commercial distribution would require an appropriate license
or different voices. File load and audio generation passed on desktop; listening
quality, phone RAM, phone speed, noisy speech and two-device audible turnaround
are **Not verified**. ML Kit's offline translation router has `bn` and `gu`
source/target mappings, but installed MT packs and translation quality were not
tested here. STT/TTS readiness does not prove translation.

Reproduce in fresh processes with:

```powershell
python tools/benchmark_five_language_stt.py fetch --languages bn gu
python tools/benchmark_five_language_stt.py fetch-model --languages bn gu
python tools/validate_stt.py benchmark --model tiny --label bn-whisper-tiny-30 --manifest tools/stt_models/five-language-validation/manifest-bn-30.json --languages bn
python tools/validate_stt.py benchmark --model tiny --label gu-whisper-tiny-30 --manifest tools/stt_models/five-language-validation/manifest-gu-30.json --languages gu
python tools/benchmark_five_language_stt.py benchmark --languages bn
python tools/benchmark_five_language_stt.py benchmark --languages gu
python tools/benchmark_five_language_tts.py --language bn
python tools/benchmark_five_language_tts.py --language gu
```

Raw per-clip results are `tools/stt_results/{bn,gu}-whisper-tiny-30.json`,
`five-language-{bn,gu}-30.json`, and `five-language-{bn,gu}-tts-30.json`.
The single-sample Diagnostics self-test now includes Bengali and Gujarati;
it is a local smoke test, **not** a WER or two-device validation.

## Build verification

`./gradlew.bat testDebugUnitTest assembleDebug --no-daemon` passed on this
checkout: **423 tests, 0 failures, 0 skipped**. The fresh debug APK is
`app/build/outputs/apk/debug/app-debug.apk`, **92,523,679 bytes**, SHA-256
`9d55b4f5aa8445e7820c6e9ce23c7909ecce2743f0c58b6d0b27fc36b8f1c4e7`.
APK inspection found ten language manifests and **zero** on-demand model ONNX
files. It does include the app's separate **643,854-byte Silero VAD ONNX**;
"no model weights" would therefore be inaccurate. A local-assets incremental build initially included model weights and
produced an 891-MB APK; the build now excludes model weights from debug and
release, and the APK was regenerated without the stale incremental ZIP payload.

The current change touches the Bengali/Gujarati pack manifests, model routing,
the seven-language Diagnostics self-test and samples, language-pack build
packaging, the pipeline/model/self-test unit assertions, the benchmark scripts,
raw JSON results in `tools/stt_results/`, and this report plus
`docs/QUICK_STAT_SUMMARY.md`. A real-network redirect unit test was replaced
with a loopback redirect fixture so it also passes offline. The Tamil/Telugu recognition and Hindi/English/Odia
model selections were not changed. Phone accuracy, listener intelligibility,
noisy-speech performance and two-device latency remain **Not verified**.

## Fine-tuning feasibility and held-out preparation (3 October 2026)

The source AI4Bharat hybrid CTC/RNNT checkpoints are MIT-licensed but gated;
none was restored on this host. Its 4-GB RTX 2050 has CPU-only PyTorch and no
NeMo installation. **No fine-tuning, FP32 export or new INT8 pack has run.**
The current on-demand packs remain active. The exact model revisions, data
license review, consent rules, cloud notebook and commands are in
[the fine-tuning runbook](STT_FINETUNING_RUNBOOK.md).

Complete FLEURS validation **and** test Parquet splits were indexed before
preparing any training data. The ignored local protection files cover normalized
text, original audio SHA-256 and source IDs. No train/dev rows have been
collected, so an actual corpus overlap result cannot yet be claimed. A
synthetic probe using a protected test clip was rejected for all three overlap
types; `workspace-check` reported `No local training manifests; leakage check
has no training rows`. The build invokes this check when manifests exist.
With a temporary Tamil test clip added as a training row,
`./gradlew.bat verifySpeechDataLeakage --no-daemon` exited 1 and reported one
row with three overlaps (transcript, source ID, audio). The probe was removed;
the subsequent `workspace-check` again reported no local training manifests.

| Language | Protected validation / test rows | Distinct 100-clip test | Training hours / speakers / noisy test |
|---|---:|---:|---|
| Tamil | 377 / 591 | 100, no normalized-text overlap with validation | No training data / Not measured / Not recorded |
| Telugu | 311 / 472 | 100, no normalized-text overlap with validation | No training data / Not measured / Not recorded |
| Bengali | 402 / 920 | 100, no normalized-text overlap with validation | No training data / Not measured / Not recorded |
| Gujarati | 432 / 1,000 | 100, no normalized-text overlap with validation | No training data / Not measured / Not recorded |

The 100-clip sets are FLEURS **test**, distinct from the 30 FLEURS **validation**
clips, and use the same normalization in `tools/validate_stt.py`. Both are
held-out; neither was used to train. Pinned download revisions and SHA-256 are
in `tools/fetch_speech_heldout.py`; raw Parquet/WAV and private indexes are
ignored by Git. The noisy/phone set is **Not recorded** pending speaker consent.

| Current INT8 CTC pack | Validation WER / CER (30) | Separate test WER / CER (100) | Test native-script output | Test mean / p95 decode | Test RTF | Test peak desktop working set |
|---|---:|---:|---:|---:|---:|---:|
| Tamil | 21.8% / 8.7% | 31.60% / 16.36% | 100/100 | 545 / 1,144 ms | 0.040 | 1,301,086,208 bytes |
| Telugu | 22.7% / 7.1% | 21.69% / 6.71% | 100/100 | 378 / 630 ms | 0.034 | 596,193,280 bytes |
| Bengali | 14.36% / 3.62% | 14.31% / 4.12% | 100/100 | 474 / 887 ms | 0.035 | 548,446,208 bytes |
| Gujarati | 18.54% / 5.75% | 17.13% / 4.47% | 100/100 | 496 / 950 ms | 0.048 | 530,997,248 bytes |

Raw 100-clip results: `tools/stt_results/cheap-{ta,te,bn,gu}-baseline-100.json`.
These are Windows desktop results. Different 30- and 100-clip
scores are **not** a before/after model improvement.

The cheap-fix gate for Bengali and Gujarati was tested on the *same* 30 clips,
in a fresh process per condition. Neither language reached the required 12%
WER, so training remains the next candidate step once suitable data/compute
exist. No preprocessing change was shipped.

| Method | Bengali WER / CER (30) | Gujarati WER / CER (30) | Decision |
|---|---:|---:|---|
| Existing CTC baseline | 14.36% / 3.62% | 18.54% / 5.75% | Reference |
| RMS normalization | 15.09% / 3.55% | 18.37% / 5.44% | Fails 12% gate |
| Energy-only VAD trimming | 15.09% / 3.65% | 19.93% / 5.51% | Fails 12% gate |
| RNNT vs CTC | Not run | Not run | Gated trainable checkpoint unavailable |
| Beam vs greedy | Unsupported by tested sherpa NeMo-CTC loader | Unsupported | Loader rejected `modified_beam_search`: only `greedy_search` supported |
| Offline n-gram LM rescoring | Not verified | Not verified | No supported path confirmed for this loader |

Fine-tuned 30/100/noisy WER, two-seed variance, PyTorch CTC/RNNT baseline,
FP32-vs-INT8 quantization loss, export size/speed/RAM and phone/two-device
accuracy are all **Not run or Not verified**. No pack promotion is justified.

## Kathbath trial handoff (4 October 2026)

Tamil/Telugu Gate A scripts and a Kaggle notebook are prepared; the user will
execute the notebook. See [run instructions and audit status](KATHBATH_GATE_A.md).
The trial caps speaker contributions, creates disjoint train/dev groups and
protects full FLEURS plus Kathbath non-train/noisy rows. Real-data preparation,
leakage output and all training measurements remain **Not run**. No new WER
or model improvement is claimed. The existing baseline tables remain applicable.

## Bengali/Gujarati additional candidates (4 October 2026)

Four new fresh-process desktop runs compared existing local Omnilingual 300M
INT8 and Dolphin Small INT8 models on the same pinned 30 clips. Bengali WER
was **35.82% / 32.00%**, versus current IndicConformer **14.36%**. Gujarati
was **32.06% / 50.78%**, versus **18.54%**. Both replacements fail the accuracy
comparison. No pack switch or new APK is justified. The
[candidate review](BENGALI_GUJARATI_CANDIDATE_REVIEW.md) records CER, native
script, mean/p95 decode, RTF, peak RAM, size, exact commands and remaining work.
Phone and listener validation remain **Not verified**.

## Marathi audit before integration (4 October 2026)

The previous shared Tiny recognizer scored **133.94% WER / 103.70% CER** with
**0/30 native-script outputs** on pinned Marathi FLEURS clips. The dedicated
IndicConformer INT8 candidate scored **15.70% / 4.64%**, native **30/30**, on
the same clips. It was initially audited as a candidate before app integration.
Current Marathi MMS-VITS produced non-silent 16-kHz audio over 30 short-phrase
trials, mean/p95 **549/701 ms**, but skipped **U+0949** in `डॉक्टरांना बोलवा`.
The [complete audit](MARATHI_LANGUAGE_CHECK.md) includes routing evidence,
hashes, disk/RAM, commands, raw outputs and limitations. Phone/listener and
independent noisy tests remain unverified.


## Marathi manual model integration (4 October 2026)

Marathi now uses the above dedicated IndicConformer INT8 model in manual mic
mode. This changes the model selection, not the measured desktop results:
**133.94% → 15.70% WER**, **0/30 → 30/30 native-script transcripts**, and
**1,022 → 757 ms mean decode** on the same 30 FLEURS validation recordings.
Under-10% WER is still not achieved. The app download manifest and runtime
registry pin the benchmarked model SHA-256 and token SHA-256. Marathi STT
requires a new on-demand download; an old Tiny install cannot satisfy its
readiness check. Model weights remain excluded from the APK. Auto-detect is
unchanged and still uses shared Whisper.

Diagnostics includes Marathi in the eight-language self-test and displays the
measured desktop references with phone status Not verified. Current TTS mean
549 ms is waveform synthesis only; the known U+0949 token omission remains
reported, with no invented replacement token or human intelligibility claim.
See [the fix report](MARATHI_LANGUAGE_CHECK.md) for installation steps, exact
file sizes and the list of touched files.


## Kannada/Malayalam audit and TTS startup fix (4 October 2026)

On identical 30-clip FLEURS validation recordings per language, current manual
Whisper Tiny scored **169.80% Kannada WER / 106.44% CER** and **193.16%
Malayalam WER / 102.32% CER**, both **0/30 native script**. Tiny produced one
empty Kannada and two empty Malayalam transcripts. The pinned dedicated
IndicConformer INT8 candidates scored **17.23% / 6.32%** and **28.91% / 7.06%**,
both **30/30 native script**, mean decode **728 / 664 ms**. They remain
benchmark candidates; neither is wired into the app. Neither meets under-10% WER.

Current MMS-VITS voices generated non-silent 16-kHz PCM over 30 trials each
(five short native phrases repeated six times), mean/p95 **1,360/1,872 ms**
for Kannada and **968/1,229 ms** for Malayalam. No missing letter/mark was found
in those five phrases. Human intelligibility and phone behavior are unverified.
Kannada exceeds the requested short-phrase mean TTS latency target. Malayalam's
CTC process peaked at **946,454,528 desktop working-set bytes**; Android RAM
fit remains unverified. The active ML Kit route supports Kannada, not general
Malayalam translation; same-language and emergency phrasebook behavior are separate.

A direct model run confirmed that the old default `Hello` startup phrase skips
all its letters in Kannada and most in Malayalam while still returning PCM.
`SherpaOnnxSpeechSynthesizer` now uses native smoke phrases for these two; the
new unit regression checks every phrase character against the real token files.
**426 tests passed, 0 failures/errors/skipped**, and the startup-fix APK built.
No STT model selection changed and no new model weights were bundled. The
Diagnostics self-test still covers eight installed dedicated-model languages;
it has not been expanded to pretend these two current Tiny paths work.

The [complete audit](KANNADA_MALAYALAM_LANGUAGE_CHECK.md) contains per-language
routing, paired before/candidate tables, hashes, sizes/RAM, raw report paths,
commands and APK verification. Phone/noisy/listener/two-device validation is
**Not verified / Not run** for both languages.

## Malayalam focus and experimental integration — 4 October 2026

This supersedes the initial audit's Malayalam selection; Kannada is unchanged.
Manual Malayalam now downloads its dedicated IndicConformer INT8 CTC pack,
with checksum verification and explicit missing-pack errors. Auto-detect remains
shared Whisper. The under-10% WER goal remains **unmet**.

On the same 30 Malayalam validation recordings, dedicated CTC measured **28.91%
WER**, compared with previous Tiny **193.16%**, Omnilingual **43.16%**, Dolphin
**102.93%** and Whisper Small **114.45%**. Neither larger candidate improved the
selected model. Quiet-boundary/context chunking reduced desktop working set
but worsened WER to **32.81% / 36.72%**, so Android chunking was not shipped.

An independent, validation-disjoint 100-recording FLEURS **test** measured
**22.92% WER / 5.27% CER**, native script **100/100**, mean/p95 **566 / 1,061 ms**,
RTF **0.0368**, peak desktop process working set **722,341,888 bytes**. This is
a separate dataset, not a measured reduction from the paired 30-clip score.
Peak desktop RAM on the 30-clip model run was **946,454,528 bytes**; Android
PSS and low-memory behavior remain **Not verified**. TTS uses the existing
MMS voice: earlier mean/p95 **968 / 1,229 ms**, listener quality unverified.

The nine-language Diagnostics self-test now includes Malayalam. Its WAV reader
had a real bug: float32 recordings were interpreted as PCM16. RIFF-aware
PCM16/float32 decoding now matches an independent soundfile decode of the
Malayalam recording exactly. **432 unit tests passed with zero failures**,
and the debug APK built without bundled ONNX language weights.

See [the focused report](MALAYALAM_STT_IMPROVEMENT.md) for per-candidate disk/RAM,
raw reports, reproducible commands, touched files, APK hash and phone checklist.
General Malayalam ML Kit translation remains unavailable, and phone/noisy/
human-listener/two-device results remain **Not verified**.
