# iTantra evaluation snapshot and phone test kit

Snapshot: 6 October 2026. The chart uses saved clean, read-speech desktop
benchmarks of the packs currently selected by the app. Their model SHA-256
values match the current pack manifests. It is not a new phone or field run.

The chart's WER is word edit errors divided by reference words. CER uses
character edit errors divided by reference characters, excluding spaces.
Lower is better. Text uses NFC, lowercase and punctuation-stripped
normalization. WER can exceed 100% when a recognizer inserts many extra words;
it is not an overall app success percentage.

## Current selected packs: 30 clips per language

| Language | WER | CER | STT mean / p95 ms | TTS mean / p95 ms |
|---|---:|---:|---:|---:|
| Hindi | 9.14% | 3.85% | 530 / 1,200 | 767 / 1,087 |
| English | 6.88% | 3.78% | 153 / 289 | 1,004 / 1,237 |
| Bengali | 14.36% | 3.62% | 729 / 1,552 | 1,046 / 1,639 |
| Gujarati | 18.54% | 5.75% | 511 / 786 | 529 / 668 |
| Marathi | 15.70% | 4.64% | 757 / 1,441 | 181 / 252 |
| Kannada | 17.23% | 6.32% | 440 / 1,123 | 1,360 / 1,872 |
| Malayalam | 28.91% | 7.06% | 664 / 2,194 | 968 / 1,229 |
| Tamil | 21.83% | 8.69% | 553 / 1,025 | 1,317 / 1,976 |
| Telugu | 22.70% | 7.14% | 337 / 615 | 1,312 / 1,703 |
| Odia | 19.28% | 5.07% | 658 / 1,272 | 1,040 / 1,450 |

Hindi uses 30 FLEURS test recordings; the other nine use 30 validation recordings
per language. Each language has different speech samples.

STT timings measure decoding only. TTS timings measure waveform generation on
five short field phrases repeated six times per language. Neither measures
speech-end to remote speaker latency. Marathi uses its selected Piper voice;
the other nine use the selected MMS voices. Kannada timings use the later
authorized audit run on the same clips. Desktop RTF, sampled peak process RAM,
checksums and raw report paths are in `evaluation-stats.csv` and `.json`.

Total: **300 STT clips** and **300 TTS synthesis trials** in those saved
benchmarks. The fresh 6 October replay check additionally generated valid,
non-silent audio twice in every language: **20/20 synthesis trials passed**.
The latest Android build has **464 passing host-side tests**, zero failures,
errors or skips. Automated tests and generated audio do not rate pronunciation
or demonstrate phone accuracy. Phone WER, human TTS ratings and end-to-end
two-phone speech latency have no completed measurements in this snapshot.

## Additional evaluation sets

These separate 100-clip FLEURS test sets must stay separate from the 30-clip chart.
They use different recordings, so a numerical difference is not evidence that
the model changed or improved.

| Language | Clips | WER | CER |
|---|---:|---:|---:|
| Bengali | 100 | 14.31% | 4.12% |
| Gujarati | 100 | 17.13% | 4.47% |
| Malayalam | 100 | 22.92% | 5.27% |
| Tamil | 100 | 31.60% | 16.36% |
| Telugu | 100 | 21.69% | 6.71% |

The Kaggle Tamil experiment used another sealed **64-clip Kathbath DEV subset**.
Original source CTC WER: **20.22%**. The four trained pilots measured **35.27%,
36.21%, 36.36% and 35.74%** after 20 optimizer steps. All regressed against the
same-set original. They were not promoted into the app. These DEV results do
not replace the current app benchmark or a held-out test.

## Quick phone check

1. Install `D:/new itantra/artifacts/current-ui-2026-10-06/iTantra-1.7-connected-ui.apk`.
   In **Settings → Language Packs**, install each desired language's Send/STT
   and Receive/TTS packs while internet is available.
2. Disconnect from a peer and stop hands-free listening. Open
   **Settings → Diagnostics → RUN TEN-LANGUAGE SELF-TEST**. Listen to each
   voice and inspect the transcript, script, decoding time, generation time and
   errors. Missing packs must be installed before their language can pass.
   This uses one recording per language and does not estimate WER.
3. In Talk, select a language explicitly rather than Auto-detect. Record a
   short native-language phrase, finish it and open Notes. Tap **Play speech**,
   listen to the whole phrase, then play it again. Check Stop during preparation
   and speech. For Marathi, use Devanagari text. Have a speaker of the language
   judge whether the words are understandable and pronounced correctly.
4. From Diagnostics, choose **EXPORT FIELD TEST SUMMARY** and save the JSON.
   Keep the self-test screen results as well; the exported field summary is not
   a corpus WER report.

## Measure phone accuracy

`phone-test-template.csv` contains 30 reference sentences for each of the ten
languages, taken from the existing benchmark manifests. The reference corpus
is Google FLEURS, CC BY 4.0. Its source IDs and splits are preserved in separate
columns; sample IDs match the benchmark language/row indexes.

1. Make a working copy of that CSV. Record phone model, APK version, actual STT
   pack hash/version, mic mode, speaker ID and condition. The prefilled model
   hash refers to the current selected pack; change it if testing another pack.
2. In Talk, select the language manually and set the target to the **same
   language**. Read the reference exactly, finish recording, then use the
   saved Talk message's **Copy transcript** icon to fill `hypothesis`.
   This avoids translation. The latest Notes also retain the original spoken
   transcript; a translated chat message is not the correct input for an STT test.
3. Mark the row `tested=yes`. If recognition produced no text, leave
   `hypothesis` blank and still mark `tested=yes`; it counts as an error.
   Leave untested rows unmarked. Obtain enough readings from people who speak
   the language; use at least all 30 per language for a useful initial sample.
4. Keep quiet and noisy readings as separate `condition` groups. New phone
   readings use different audio from the desktop benchmark, so they measure
   the phone recording pipeline and that new speech sample. A controlled model
   comparison needs identical recordings and unchanged normalization.
5. Save a CSV with UTF-8 encoding. From PowerShell in `D:/new itantra`, run:

```powershell
C:/Python314/python.exe tools/score_phone_stt.py artifacts/evaluation-2026-10-06/phone-test-completed.csv
```

The scorer creates `phone-test-completed.scores.json` with corpus WER/CER for
each language/condition/device/APK/model/mic-mode group. It excludes untested
rows, counts failed blank transcripts and rejects duplicate sample IDs within
a group. It does not create a TTS quality score from waveform generation.
Send the completed CSV, score JSON and field summary for review.

If using the extracted test ZIP away from the project, keep `score_phone_stt.py`
and `validate_stt.py` together and run `python score_phone_stt.py
phone-test-completed.csv` in that folder. The scorer uses only Python's standard
library. `tts-samples/` contains one generated WAV per language for a first
listening check; a short sample is not a general pronunciation assessment.

For a two-phone test, connect and verify peers, remove internet access while
keeping the selected direct transport available, and record speech-end to
remote audible playback separately. TTS replay of a saved local item does not
send a new message or change its delivery acknowledgments.

## Reproduce or check this kit

The chart generator rechecks all 300 saved reference/hypothesis pairs and their
recorded word/character errors, and verifies model hashes against app manifests.
It uses the configured bundled Python runtime with ReportLab and PDFium.

```powershell
& 'C:/Users/avira/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' tools/create_current_evaluation_report.py
C:/Python314/python.exe tools/score_phone_stt.py --self-check
C:/Python314/python.exe tools/check_saved_tts.py
```

The last command runs fresh local TTS inference; the chart generator only
summarizes existing measurements. Raw desktop STT can be remeasured with
`tools/benchmark_five_language_stt.py benchmark --languages hi en bn gu mr kn ml ta te or`
using the existing checksum-pinned model and audio files. Keep the current
reports before a rerun. Corpus tests do not require another Kaggle training run.
