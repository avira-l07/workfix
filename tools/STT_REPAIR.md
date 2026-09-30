# Speech-engine repair and validation

No phone access is required for these desktop checks. Android runtime performance
and microphone capture still require the owner's separate device test.

## Native provenance

- Base: k2-fsa/sherpa-onnx v1.13.8, commit
  `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`.
- Unicode repair: upstream commit
  <https://github.com/k2-fsa/sherpa-onnx/commit/2edc882fe0ba>, Whisper portion.
  Byte-BPE token fragments must be joined before UTF-8 cleanup; normalizing each
  fragment independently deletes bytes belonging to Indic characters.
- Local decoding repair for Hindi transcription with Small (12 decoder layers): replace the six-token-per-second / half-context ceiling
  with a 20-token-per-second allowance, a 64-token minimum, and the remaining
  model context as the hard limit. End-of-transcript still stops decoding.
  This avoids prematurely cutting off token-heavy scripts; it does not guarantee
  accurate recognition and can increase worst-case latency. Tiny and other
  language requests retain the original bound; a blanket increase made Tiny's
  repetition loops longer in the held-out test, so it was restricted.
- Build repair: quote the JNI linker version-script path for Windows directories
  containing spaces.
- Combined reproducible diff: `sherpa-whisper-repair.patch`.
- Official base AAR SHA-256:
  `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`.
  Packaging preserves its Java classes and replaces the arm64 JNI and ONNX
  runtime libraries together. The app currently packages arm64 only.

## Reproduce

Run from the repository root (C++ Build Tools, Android SDK/NDK and Python needed):

```powershell
./tools/build_stt_native.ps1 -Target Windows
python tools/package_stt_native.py desktop
$env:PYTHONPATH = "$PWD/tools/stt_models/patched-python"
python tools/validate_stt.py benchmark --model small --label small-repaired
./tools/build_stt_native.ps1 -Target Android
```

`package_stt_native.py android` explicitly updates the app's AAR; do that only
after reviewing validation. It never installs an APK or modifies a global Python
installation.

The app disables the legacy `src/main/jniLibs` source directory, which otherwise
overrides the AAR's repaired runtime. APK verification must compare the packaged
arm64 libraries with the final stripped build output, not merely check the AAR.

## Adopted Hindi model

Hindi in MANUAL mode uses `vasista22/whisper-hindi-small`, exported by
`ippocode/indic-asr-onnx`, pinned revision
`1f6243ec6dffc4de2db84fe1446576d1ff52c252`. Other languages and AUTO retain Tiny.
The Hindi model always transcribes; existing text translation handles a different
target language. A checksum-verified, versioned `shared/stt-hi-v1` directory keeps
the Hindi model separate from shared Tiny and TTS. Only one STT is loaded at once.

Run `fetch_hindi_candidate.py`, install `pyarrow` under
`tools/stt_models/validation-deps`, then `extract_hindi_test.py` to reproduce the
held-out corpus. `stage_hindi_model.py` checks the benchmark gate and model hashes
before staging files for an optional bundled build. ONNX weights remain Git-ignored.
The default minimal APK builds from a fresh checkout and downloads Hindi's pinned,
checksum-verified model files when the user prepares that pack. Use
`tools/configure_model_bundle.py` only to prepare optional bundled demo builds.

The model was trained on FLEURS train/dev, so its comparison uses the first 30
Hindi **test** rows, not the previous validation rows. On that sample it measured
13.77% WER versus original Tiny's 127.66%. Larger-than-100% WER includes insertions.
Desktop RTF was 1.20 and peak process working set about 1.10 GiB. No Android speed
or memory guarantee follows from these numbers.

## Benchmark design

`validate_stt.py fetch` retrieves the first 20 available FLEURS validation
recordings for Hindi and English, in dataset order. Manifest entries contain the
reference, source, split, license and audio SHA-256. Source:
<https://huggingface.co/datasets/google/fleurs>, CC-BY-4.0.

The reports include every hypothesis, aggregate word/character error rate,
load time, recognition time divided by audio duration (RTF), native-module path
and, for newer runs, process peak memory. Text normalization is NFC, lowercase,
letters/marks/numbers and collapsed whitespace. Baseline reports are preserved.

Acceptance criteria established before the repaired run: Hindi WER at most 35%
and at least 25% relative improvement over Tiny; English WER at most 20% and no
more than three percentage points above Tiny; desktop aggregate RTF at most 3.
These are a small regression screen, not population accuracy or phone-speed
claims. Devanagari percentage alone is not an accuracy criterion.

`check_hindi_speech.py` is a supplementary synthetic short-phrase smoke test,
not a substitute for human recordings. Never describe synthetic results as
recorded-user speech.
