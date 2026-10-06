# Marathi offline TTS repair — 4 October 2026

Marathi now uses a separately downloadable **Piper mr_IN-google-medium**
voice with offline eSpeak phonemes, speaker 0. Desktop checks passed for the
previously unsupported doctor vowel. **Android download/playback, phone RAM,
native-listener intelligibility and two-phone operation are Not verified.**
This is a technically tested replacement, not a claim of perfect Marathi.
Marathi STT remains the existing IndicConformer model (15.70% desktop WER).

## Root cause and replacement

The old MMS-VITS token vocabulary lacks `ॉ` (U+0949). In raw engine inference,
`डॉक्टरांना बोलवा` therefore loses that vowel; the previously added app guard
rejects it explicitly instead of playing misleading speech. Adding a character
to a token file would not teach its missing embedding or pronunciation.

Piper's trained tokens represent phonemes. Its offline eSpeak frontend maps
Marathi text to those phonemes; the doctor phrase produces `ɔ` and differs from
`डक्टरांना बोलवा` and `डाक्टरांना बोलवा`. `कॉफी` differs from `कफी` as well.
All generated phoneme characters in 17 tested texts have trained token IDs.
These checks establish frontend coverage for these examples; they do not rate
whether a Marathi listener finds the speech accurate, natural or intelligible.

The [pinned source voice card](https://huggingface.co/rhasspy/piper-voices/blob/c10ece1aade47bb51c153c893d14e5bf8e5b7117/mr/mr_IN/google/medium/MODEL_CARD)
describes a nine-speaker Marathi model with 22,050-Hz output. Preparation follows
the [Sherpa Piper conversion](https://k2-fsa.github.io/sherpa/onnx/tts/piper.html):
append metadata identifying VITS/Piper, Marathi, eSpeak, nine speakers and the
output rate. The source has no such metadata. Exact byte comparison confirmed
that the converted ONNX is the original graph/weights plus a 128-byte suffix.

The initial conversion also copied five multi-codepoint phoneme keys. Sherpa's
native loader terminated on the first such key (`aɪ`). The final map retains
only supported single-codepoint keys with their original trained IDs; all
17 phoneme checks and the audio trials passed without unknown-phoneme warnings.
This compatibility conversion is not an assertion that every Piper frontend
uses the same encoding for every language or voice.

## Fresh before/after measurements

Windows desktop, sherpa-onnx 1.13.8, CPU provider, one inference thread, fresh
process per voice, speaker 0, speed 1. One warm-up phrase is excluded. Five fixed
short phrases are repeated six times (30 trials, **not 30 unique sentences**):
`मदत हवी आहे`, `पाणी पाठवा`, `रस्ता बंद आहे`, `आम्ही सुरक्षित आहोत`,
`डॉक्टरांना बोलवा`. p95 uses the nearest rank. RTF is total synthesis time
divided by total generated audio duration. This is a small desktop workload,
not an isolated Android latency or speech-end-to-remote-playback measurement.

| Measured property | Legacy MMS | Piper replacement |
|---|---:|---:|
| Mean synthesis | 808.36 ms | 181.14 ms |
| p95 synthesis | 1,046.67 ms | 252.30 ms |
| Synthesis RTF | 0.7221 | 0.1620 |
| Initial engine load, before warm-up | 1,214.41 ms | 1,426.62 ms |
| Peak desktop process working set | 259,977,216 bytes | 226,062,336 bytes |
| Model + tokens | 114,044,398 bytes | 76,769,436 bytes |
| Full offline frontend | Not applicable | 17,991,651 bytes |
| Installed TTS files | 114,044,398 bytes | 94,761,087 bytes |
| Output sample rate | 16,000 Hz | 22,050 Hz |
| Nonempty, finite, non-silent waveform trials | 30/30; doctor vowel skipped | 30/30 |
| Intelligibility / MOS / TTS accuracy score | Not measured | Not measured |
| Android playback / resource measurements | Not verified | Not verified |

The legacy row deliberately calls raw MMS inference, bypassing the old app
coverage guard. Six doctor trials emitted missing-U+0949 warnings. Non-silent
legacy audio is therefore not a pronunciation pass. Earlier 549-ms MMS numbers
remain historical results; the comparison above uses a fresh paired workload.
Process peak RAM includes the Python runtime/native libraries, not model-only
RAM or Android PSS. Cold loading did **not** improve with this candidate.

Additional signal probes: `डॉक्टरांना`, `डक्टरांना`, `डाक्टरांना`, `ऑक्सिजन`,
both `अॅम्ब्युलन्स` / `ॲम्ब्युलन्स`, `कॅम्पमध्ये`, `कॉफी`, `कफी`, `ळ`, `ऱ`,
and `१२३`, using the full strings in the script. All 12 produced finite,
non-silent PCM at 22,050 Hz. A fresh process using only the six frontend files
required by offline import also produced valid PCM for all 17 texts.
This does not certify every Devanagari character, number or domain phrase.

## Candidates considered

| Candidate | Published artifact size | Work completed | RAM / latency / APK impact |
|---|---:|---|---|
| Piper mr_IN-google-medium | 76,768,179-byte source ONNX; 94.8-MB installed TTS including full frontend | Downloaded, converted, benchmarked and integrated on demand | 226.1-MB peak desktop process; 181-ms mean; no bundled ONNX weights |
| shreyask/bol-tts-marathi-onnx | Q8 283,189,621 bytes; FP32 325,500,108 bytes, before voices/G2P assets | Inspected model card/config/artifact metadata; not downloaded for inference | Not measured; requires Kokoro-style inputs, voice styles and Marathi G2P; much larger storage download |
| ai4bharat/vits_rasa_13 | 160,708,568-byte source safetensors | Inspected metadata; gated model/config access returned HTTP 401 | Not measured; no tested Sherpa ONNX deployment path |
| naklitechie/mms-tts-mr-ONNX | 114,313,491-byte ONNX | Inspected metadata; another MMS export without proof of repaired vowel coverage | Not measured; no demonstrated advantage over existing MMS |

Artifact evidence is saved with the raw results. Primary candidate pages:
[Bol](https://huggingface.co/shreyask/bol-tts-marathi-onnx),
[Rasa](https://huggingface.co/ai4bharat/vits_rasa_13),
[MMS re-export](https://huggingface.co/naklitechie/mms-tts-mr-ONNX).
Unbenchmarked candidates have no claimed quality, memory or latency scores.

## Android integration and safety

* Manifest `mr_dev_manifest.json` is version **3.0.0**. Its STT configuration
  is unchanged. Old two-file MMS TTS installs no longer satisfy readiness.
* The existing component download flow fetches the pinned 76.8-MB raw model
  and 9.0-MB eSpeak ZIP, verifies both hashes, appends the tested metadata,
  copies 1,129 bytes of bundled phoneme tokens, then checks prepared files.
  ZIP extraction rejects traversal, duplicates, unrelated paths and excess size.
  Promotion uses the existing staged install/rollback path.
* TTS download is **85,805,199 bytes**; full installed TTS is **94,761,087 bytes**,
  excluding tiny verification markers. Marathi STT + TTS is **292,424,285 bytes**.
  Only tiny tokens/source notices were added to APK assets, not model weights
  or a new native dependency. Installed-size UI counts the full frontend.
* Offline directory import creates nested frontend paths and preserves an
  installed pack when the replacement is incomplete. Minimal six-file imports
  can occupy less disk than the full standard download; broader text coverage
  for that reduced import remains untested beyond the 17 probes.
* Marathi text uses eSpeak rather than a raw-letter-versus-phoneme-token check.
  Mixed foreign alphabets fail explicitly with the original text preserved.
  Missing frontend files give a visible re-download error instead of a fallback.
  Existing non-Marathi voice layouts and behavior remain unchanged.
* Inference reads local model/frontend files. Network access is used only for
  model provisioning; no TTS network API was introduced. Existing playback uses
  the returned sample rate, including this voice's 22,050 Hz.
* Diagnostics displays desktop references and retains **Not verified** phone
  status. TTS evaluation exports identify Piper/speaker 0; Settings links the
  correct voice source. The native Android AAR contains version `1.13.8` and
  Piper/eSpeak support strings; this is binary inspection, not Android execution.

### Pinned hashes

| File | SHA-256 |
|---|---|
| Source ONNX | `e1200d474a74ebd6d1737be2c7affe56f1f9efc18915d4595d7f5c2b15cf06f4` |
| Prepared ONNX | `5b180a9fed4abf9877c2fe6be91128a14dca4fbb1f2d93a8144948e5af3caf8e` |
| Phoneme tokens | `30615b46df803181140b628ca264f33860e90a0834977936274da840dd36c905` |
| Frontend ZIP | `bc4525eafe31b4e3f5e43aea495f3169e97dd2544f1bbfe95514ce8a61baee39` |
| Voice config, LF-normalized text | `11055302ee1e3c9902e5e96c03cbfc0a9eec29b1b06b91ef52a3c66e9873edbb` |
| Android metadata suffix (128 bytes) | `73f79db63b661f00559a6b1868b119be10359f6ef0882ca929ae6df82abf1cd6` |

Source revision: `c10ece1aade47bb51c153c893d14e5bf8e5b7117`.
The full six-file frontend checksum map is in the manifest. The ZIP's 355
regular files match the originally benchmarked tar.bz2 frontend byte-for-byte.

## Reproduce and inspect

Pinned raw model/config are under
`tools/stt_models/marathi-piper-candidate`. For a fresh checkout, fetch
`mr_IN-google-medium.onnx` and `.onnx.json` from the pinned voice directory,
and extract the checksum-matched Sherpa `espeak-ng-data.zip` into that same
directory. The legacy MMS model remains in `models/bundled/mr/tts/model.onnx`;
legacy tokens remain in `app/src/main/assets/language_packs/mr/tts/tokens.txt`.
Python dependencies used: sherpa-onnx, onnx, numpy and soundfile; all inference
commands below operate on installed local files.

```text
python tools/evaluate_marathi_piper.py prepare
python tools/evaluate_marathi_piper.py benchmark --voice mms
python tools/evaluate_marathi_piper.py benchmark --voice piper --debug
python tools/evaluate_marathi_piper.py phonemes
```

The existing `python tools/benchmark_five_language_tts.py --language mr`
command now delegates to this Piper benchmark. Other languages retain their
existing benchmark paths. The legacy baseline has its own pinned old hashes,
so switching the app manifest does not invalidate the comparison command.

Raw evidence: `tools/stt_results/marathi-tts-repair-2026-10-04/`:
`mms-sid0-1threads.json`, `piper-sid0-1threads.json`, `phoneme-probes.json`,
`android-pack-shape-check.json`, benchmark logs, candidate metadata and WAVs.
`piper-sid0-probe-0.wav` is the doctor phrase for a future native listener.

Regression verification: **454 unit tests; 0 failures, 0 errors, 0 skipped**,
including ten new Marathi tests and the updated existing pack-layout test.
Production debug lint passed with **0 errors, 68 warnings and 1 hint**.
Unit-test-source and Android-test-source lint analysis were excluded, matching
the existing audit build; this is not a full test-source lint pass.
Debug APK assembly passed. The exact commands, final APK hash and archive checks
are recorded in `android-verification.log`, `final-packaging.log` and
`apk-verification.json` in the evidence directory.

Fresh debug APK: `artifacts/marathi-tts-repair-2026-10-04/app-debug.apk`,
**94,600,345 bytes** (94.6 MB decimal), SHA-256
`41fe70a8bb9e401aa7ba472729a4a4a283d1dc1a85d1f2a202fa0935cbadce67`.
ZIP CRC inspection passed; version 3.0.0 manifest, phoneme tokens, source notice
and native error messages are present. No language-pack ONNX weights are
bundled. All nine other language manifests and Marathi's STT manifest section
match the original Git HEAD contents. This is a debug build, not a signed
submission release or an Android execution test.

## Phone/listener checklist, when devices are available

Install the new debug APK, open Language Packs and download **Marathi TTS**.
The old MMS install is intentionally shown as incomplete until replaced.
Keep the dedicated Marathi STT pack; choose Marathi mic explicitly.
Disconnect internet after provisioning. Run the existing Diagnostics self-test.
Then speak/play the doctor, oxygen, ambulance, coffee and numeral probes and
have a Marathi listener record correctness instead of treating PCM as a rating.

| Pending check | Result to record |
|---|---|
| Android download, upgrade and cold model load | Not verified; device/Android version, failure/log, load ms |
| Phone synthesis and playback | Not verified; mean/p95 synthesis, output rate, audible playback |
| Native listener: doctor vowel and broader pronunciation | Not verified; exact failures, intelligibility %, ratings |
| Low/mid-range phone resource fit | Not verified; peak PSS/RSS, low-memory behavior, sustained load |
| Offline phone A → B Marathi voice/text | Not verified; transcript, receiver audio, end-to-end delay/failures |

## Distribution review

The voice card assigns **CC BY-SA 4.0 to its OpenSLR 64 dataset** and describes
LibriTTS-R base training. It does not separately state a model-weight licence;
weight/base-model distribution rights and attribution need review before a
public release. eSpeak NG source declares GPL version 3 or later; applicable
notices/source obligations for the existing AAR/frontend and downloaded data
also need review. Source credits are bundled in
`language_packs/mr/PIPER_SOURCE_NOTICE.txt` and linked in Settings. This report
does not grant commercial redistribution clearance.

## Files changed for this repair

* `app/src/main/assets/language_packs/mr_dev_manifest.json`
* `app/src/main/assets/language_packs/mr/piper-tokens.txt`
* `app/src/main/assets/language_packs/mr/PIPER_SOURCE_NOTICE.txt`
* `app/src/main/java/com/itantra/core/inference/MarathiPiperVoice.kt`
* `app/src/main/java/com/itantra/core/inference/ModelFileSpec.kt`
* `app/src/main/java/com/itantra/core/inference/TtsTextCoverage.kt`
* `app/src/main/java/com/itantra/core/inference/SherpaOnnxSpeechSynthesizer.kt`
* `app/src/main/java/com/itantra/data/languagepack/FileLanguagePackStorage.kt`
* `app/src/main/java/com/itantra/data/languagepack/RealLanguagePackRepository.kt`
* `app/src/main/java/com/itantra/feature/benchmark/TtsEvaluationViewModel.kt`
* `app/src/main/java/com/example/itantra/ui/screens/diagnostics/DiagnosticsScreen.kt`
* `app/src/main/java/com/example/itantra/ui/screens/settings/SettingsScreen.kt`
* `app/src/test/java/com/itantra/core/inference/MarathiPiperVoiceTest.kt`
* `app/src/test/java/com/itantra/data/languagepack/LanguagePackIntegrityTest.kt`
* `tools/evaluate_marathi_piper.py`, `tools/benchmark_five_language_tts.py`
* `docs/QUICK_STAT_SUMMARY.md`, `docs/LANGUAGE_BENCHMARKS.md`,
  `docs/MARATHI_LANGUAGE_CHECK.md`, `docs/MODEL_PROVENANCE_AND_DISTRIBUTION.md`
* This report, `MARATHI_TTS_REPAIR_SUMMARY.txt`, and saved evidence/debug APK.

Pre-existing Kaggle preflight/runbook changes are separate work and were preserved.
