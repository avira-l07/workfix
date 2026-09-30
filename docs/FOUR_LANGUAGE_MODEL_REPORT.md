# Four-language speech model evaluation — 30 September 2026

This report measures the four languages selected for the current iTantra
prototype: Hindi, English, Tamil and Telugu. It uses the categories shown by
an [unofficial SIH26173 problem-statement mirror](https://sih2026.vuce.in/ps/SIH26173):
accuracy (40%), efficiency (20%) and latency (20%). Those published weights
sum to 80%; this report does not invent another category or an official pass
threshold. **The prototype has four selectable voice languages, not validated
coverage of all ten languages named in the problem statement.**

## Model choice and provisioning

Each selected microphone language downloads one pinned, SHA-256-checked CTC
model. Each selected receive language downloads one VITS TTS model. The four
CTC models are configured for the existing offline sherpa-onnx Android engine; TTS
uses the existing MMS-VITS path. ML Kit translation models are prepared
separately when a language is selected. No speech weights are bundled in the
default APK. Old Whisper downloads cannot be mistaken for the new CTC packs.

| Language | Selected STT | STT bytes | Selected TTS | TTS bytes | Both speech directions |
|---|---|---:|---|---:|---:|
| Hindi | IndicConformer CTC INT8 | 197,663,198 | MMS-VITS | 114,043,612 | 311,706,810 |
| English | NeMo FastConformer CTC INT8 | 174,621,490 | MMS-VITS | 114,017,331 | 288,638,821 |
| Tamil | IndicConformer CTC INT8 | 197,663,118 | MMS-VITS | 114,032,763 | 311,695,881 |
| Telugu | IndicConformer CTC INT8 | 197,663,298 | MMS-VITS | 114,038,199 | 311,701,497 |

The four STT models total **767,611,104 bytes**; the four TTS models total
**456,131,905 bytes**; both directions total **1,223,743,009 bytes** before
translation models, app data and temporary download storage. The download
choice is per language and per role: Speak installs STT; Receive installs TTS;
selecting both installs both. A language card also allows requesting a full
pack. Each model download is checksum-verified before activation.

The default debug APK is **87,288,428 bytes** (87.3 MB decimal), contains no
language-model ONNX weights, and is Android debug-signed. `testDebugUnitTest`
passed **410 tests with zero failures**; `assembleDebug` passed. APK version is
`1.4-four-ctc` (code 5). Build and signature details are in
`tools/stt_results/apk-verification.json`.

STT source: [IndicConformer sherpa-onnx exports](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx), pinned at revision
`9721eb71eea141fae0982cfcdb9dd2e3d4953c4a`. The model card identifies
the Indic weights as AI4Bharat-derived and the English model as NeMo-derived;
the repository advertises Apache-2.0, while the card lists MIT for Indic
sources and CC-BY-4.0 for the English source. Preserve attribution and review
the upstream terms before distribution. TTS weights come from
[MMS-VITS ONNX exports](https://huggingface.co/willwade/mms-tts-multilingual-models-onnx).

## STT accuracy and desktop inference

WER = (substitutions + deletions + insertions) / reference words; lower is
better. Recordings are fixed human FLEURS audio with exact reference text.
Each before/after pair used **the identical audio files**, verified by ID and
SHA-256. Hindi used 30 test recordings, English 20 validation recordings,
Tamil and Telugu 10 validation recordings each. Tests ran on this Windows
desktop with sherpa-onnx 1.13.8 and 4 CPU threads. These small, clean read
speech sets are candidate comparisons, **not field-accuracy certification**.

| Language | Clips | Previous selected STT WER | New CTC WER | New mean decode | New aggregate RTF |
|---|---:|---:|---:|---:|---:|
| Hindi | 30 | 13.8% | **9.1%** | 789 ms | 0.065 |
| English | 20 | 12.0% | **7.1%** | 138 ms | 0.016 |
| Tamil | 10 | 54.5% | **25.5%** | 317 ms | 0.030 |
| Telugu | 10 | 80.9% | **20.4%** | 354 ms | 0.036 |

The previous models were Hindi fine-tuned Whisper Small, English shared
Whisper Tiny, Tamil fine-tuned Whisper Small and Telugu fine-tuned Whisper
Tiny. The CTC candidates were selected because every language had lower WER
on its fixed corpus and decoding was faster. English and Telugu use more STT
storage than before; Hindi and Tamil use less. RTF is decode time divided by
audio duration; the listed RTF excludes microphone capture, pause detection,
model download/load, transport, translation and audio playback.

Desktop peak process working set during each separate STT benchmark was
**518 MB Hindi, 718 MB English, 400 MB Tamil, and 645 MB Telugu**. This includes
the Python process and sherpa-onnx runtime, so it is not an Android RAM
measurement or a minimum supported device specification. The manifest's
existing 512 MB minimum-RAM field has **not** been validated for these CTC
models; low-memory phone testing remains necessary.

Raw rows, original transcripts, outputs, audio hashes, timings and model
hashes are saved in `tools/stt_results/indicconformer-{hi,en,ta,te}.json`.
The script `tools/benchmark_conformer_candidates.py` reproduces the checks.
Previous results are in `hindi-small-final.json`, `tiny-en-final.json`,
`extra-finetuned-ta.json` and `extra-finetuned-te.json` in the same directory.

## TTS synthesis and quality

The existing four MMS-VITS models generated nonempty PCM for a fixed phrase
in the correct script. Five desktop synthesis runs per language gave:

| Language | Mean synthesis time | Median | Mean synthesis RTF |
|---|---:|---:|---:|
| Hindi | 969 ms | 909 ms | 0.358 |
| English | 917 ms | 912 ms | 0.355 |
| Tamil | 1,548 ms | 1,456 ms | 0.403 |
| Telugu | 1,775 ms | 1,471 ms | 0.426 |

These are *complete synthesis* times, not the Android time from receiving
text to audible playback. The engine skipped punctuation unsupported by its
character tokens; this may affect natural pauses. TTS intelligibility,
pronunciation and flow have **no human rating yet**, so the four TTS models
cannot honestly be called the best or declared to meet the accuracy criterion.
The full trial results and file hashes are in
`tools/stt_results/four-language-tts-desktop.json`, generated by
`tools/benchmark_four_tts.py`.

**Licensing remains a submission issue:** Meta labels the underlying
[MMS-TTS models CC-BY-NC-4.0](https://huggingface.co/facebook/mms-tts),
which is noncommercial and is not an unrestricted open-source model licence.
The SIH statement calls for open-source tools. Do not describe the current
TTS weights as Apache/MIT or assume this restriction is satisfied. An
AI4Bharat Indic-TTS or other permissively licensed, Android-compatible TTS
replacement requires a separate integration and listening benchmark before
it can be selected; changing the model card label is not sufficient.

## What remains unmeasured

This desktop work does **not** measure Android RAM/flash after installation,
idle-listening CPU, microphone endpoint-to-STT delay, text-received-to-audio
start, two-phone sentence-to-voice latency, pause segmentation, human TTS
intelligibility, or translated-output accuracy for all 12 directed language
pairs. The owner will test on a device; no phone was accessed. For a defensible
submission, collect repeated runs on a low- and a mid-range Android phone,
identify device/OS/model versions, and run independent listener tests for
each TTS language. Do not substitute these desktop RTF values for end-to-end
latency or claim that all ten requested languages are validated.
