# Five-language speech evidence — 2–3 October 2026

Scope: Hindi, English, Tamil, Telugu and Odia only. All numbers below are from
commands run on a Windows desktop with clean read speech. **None of these five
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
| sherpa-onnx Dolphin Small CTC multilingual INT8 | Same-corpus WER and Odia/Tamil/Telugu script rate **not verified**; publisher archive download did not complete in this environment | Publisher ONNX ~239 MiB; archive reported 191,534,345 bytes | Peak RAM not verified; base-APK impact zero only if implemented as download | Evaluation-only script; do not ship yet |

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

On a phone, install the five desired STT and TTS packs, disconnect from a peer,
stop continuous listening, open Diagnostics and tap **Run five-language
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

No language is "validated" until its two-phone record is filled and passes.

## Build verification

`./gradlew.bat testDebugUnitTest assembleDebug --no-daemon` passed on this
checkout: 422 tests, 0 failures (the prior 420 remain green, with two new
five-language self-test checks). Fresh debug APK:
`app/build/outputs/apk/debug/app-debug.apk`, 91,285,316 bytes, SHA-256
`6E1B9E029128CCC0C7024CE7E6108633811ECD06D15ED9032B55AA3D352F1AAF`.

Files changed for this task: `FiveLanguageSelfTest.kt`, `MainActivity.kt`,
`DiagnosticsScreen.kt`, `TransceiverCoordinator.kt`,
`FiveLanguageSelfTestTest.kt`,
`app/src/main/assets/benchmark/five_self_test/{manifest.json,hi.wav,en.wav,ta.wav,te.wav,or.wav}`,
`tools/prepare_five_self_test_audio.py`,
`tools/benchmark_five_language_stt.py`,
`tools/benchmark_five_language_tts.py`,
`tools/benchmark_target_tts.py`,
`tools/evaluate_dolphin_candidate.py`,
`tools/evaluate_omnilingual_candidate.py`, eleven benchmark result JSON files
plus the two thread-comparison JSON files under `tools/stt_results/`, this
report and `docs/QUICK_STAT_SUMMARY.md`.
