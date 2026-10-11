# iTantra language conversion audit — 11 October 2026

The audit found both **translation errors** and **speech rendering risks**. STT,
translation and TTS are separate stages. Correct transcription does not establish
correct translation; non-silent TTS audio does not establish intelligibility.
The workflow fixes below are implemented. General translation meaning and voice
quality are still open model problems, not certified as solved.

## Scope and measured evidence

- Reviewed production routing for typed messages, push-to-talk, continuous speech,
  received messages, saved-message replay, per-message language conversion, and SOS.
- Inspected all ten selected pack manifests, model frontends, vocabulary files,
  lifecycle and playback language selection. The selected STT weights were retained.
- Verified the actual local TTS weights, tokens and Marathi phoneme data against
  current manifest SHA-256 values. All ten voice packs passed; STT checksums used
  in the roundtrip probes also passed.
- Generated **53 real desktop waveforms**: five native field phrases per language,
  plus numeric probes for Bengali, Kannada and Marathi. All generated finite,
  non-silent PCM. Audio and STT roundtrip transcripts are in `voice-audit.json`
  and `voice-<language>.json`. These are diagnostic probes, not human ratings.
- Inspected **100 language routes**: 10 same-language bypasses, 56 supported ML Kit
  cross-language routes, and 34 unsupported routes involving Malayalam or Odia.
  Their matrix is `translation-routes.csv`. Fresh phone ML Kit inference was
  **not run**: ADB reported no attached devices.
- Ran **72 real Indic-to-Indic candidate routes** on the existing checksum-pinned
  IndicTrans2 320M ONNX INT8 export. All ran without runtime errors, and the test
  phone digit sequence survived all 72. This is **not 100% translation accuracy**.
  The arbitrary name and sentence structure still need review. Outputs are in
  `direct-candidate-audit.json` and `direct-candidate-review.csv`.
- The earlier 6 October candidate experiments remain relevant: the direct
  candidate changed Aviral to a different name and mistranslated the Gujarati
  greeting. Cached English-pivot IndicTrans2 and M2M100 also failed examples.
  No candidate was promoted into the APK based on the new simple probes.

## Findings, fixes and remaining gaps

| Priority | Finding | Action / current limit |
| --- | --- | --- |
| High | The current ML Kit engine changes some names, greetings and conversational meaning. Non-English routes use an English intermediary. | Structural checks added; model meaning itself remains unresolved. Requires reviewed parallel references and model comparison. |
| High | TTS character coverage was checked only for Marathi. MMS can silently skip unknown letters/marks, foreign names and numeric content. | Extended coverage checks to all character voices; original text is retained and incomplete speech is stopped. No ordinary phrase or name whitelist added. |
| High | Digit tokens are missing in seven MMS voices. Bengali and Kannada have all ASCII digits, but their numeric roundtrip probes lose the number and their waveforms barely grow. | All nine MMS voices now require digits to be written in words before speech. A tested, locale-aware number normalizer is still needed. Token presence alone is insufficient. |
| High | A source-only received packet could be labelled with the receiver's preferred language without translating. | Uses the payload/source language; regression test runs through the real encrypted receive coordinator. |
| High | A received packet with no language metadata could default to Hindi and select a guessed voice. | Preserves text with unknown language and does not translate or play guessed speech. |
| High | Changed numbers, signs, separators or omitted identifiers could be accepted as successful MT. | Shared router conservatively requires review when numeric runs differ. Indic digit forms normalize to equivalent values while preserving leading zeros. Written-out or reformatted numbers can trigger review even when legitimate. No guessed numeric repairs. |
| Medium | Source script could clearly conflict with selected language; wrong-script echoes could become a successful translation. | Shared checks reject clear Indic-script conflicts and obvious wrong-script output. Shared Hindi/Marathi script and romanized or mixed input remain ambiguous. Script checks cannot verify meaning. |
| Medium | An engine could return blank successful output or the wrong source/target metadata. | Router rejects these before a successful message conversion can be used. |
| Medium | ML Kit download/inference cancellation was caught as an ordinary failure. | Cancellation is now propagated; unsupported Malayalam/Odia provisioning no longer returns READY. |
| Medium | Whisper native English translation retained the acoustic source language in its result. | Result metadata now describes the actual English output, avoiding a wrong note/replay language or a second translation from a false source. CTC recognition and weights are unchanged. |
| Medium | Canonical Unicode was checked, but native synthesis received the unnormalized original. | Synthesis now uses the same NFC, ASCII case and whitespace normalization as coverage checks. This does not translate or remove unsupported letters. |
| Medium | Per-message conversion errors suggested missing models even when numeric/script validation failed. | New validation errors explain the actual problem and preserve the original message. |
| Open | Receiver-side or later conversion may translate text already translated by the sender. Older remote history does not retain a dependable original-language field. | Protocol/history limitation: true original-to-every-target conversion needs original text plus its language to travel and be stored together. No speculative protocol/database migration in this patch. |
| Open | Very short, incomplete, code-switched or romanized utterances lack context. Unknown names may be translated as common words. | A general entity-aware model/normalizer must handle unseen names and mixed scripts. No example-specific substitutions or forced grammar corrections. |
| Open | Question intonation, borrowed words and pronunciation can be poor despite full character coverage. | Native listener review of the supplied audio, followed by comparative voice evaluation, is needed. The waveform and roundtrip checks do not certify this. |
| Open | Locale-specific number reading differs by context: phone number, quantity, time, decimal, currency, coordinate. Marathi eSpeak reads the phone probe as a large cardinal number. | Preserve numeric text. Number normalization must distinguish contexts and test leading zeros, signs, decimal separators and units. Marathi numeric speech is not certified as a phone-number reader. |
| Open | Model smoke tests and installed badges show runtime readiness, not linguistic accuracy. | Do not interpret those badges, host tests or the probes as a quality percentage. |

## Current voice inventory

All nine MMS voices use a character frontend without configured number-expansion
rules. Marathi uses Piper with eSpeak phonemes. The missing-digit column describes
the actual pinned vocabulary, not an intelligibility score.

| Language | Voice | ASCII digits absent from vocabulary | Current numeric speech |
| --- | --- | --- | --- |
| Hindi | MMS | 5, 6, 7, 9 | Stop; require written-out numbers |
| English | MMS | 7, 8, 9 | Stop; require written-out numbers |
| Bengali | MMS | None | Stop; real numeric probe loses number |
| Gujarati | MMS | All ten | Stop; require written-out numbers |
| Marathi | Piper / eSpeak | Phonemes, not character digits | Can generate numeric speech; context/pronunciation needs review |
| Kannada | MMS | None | Stop; real numeric probe loses number |
| Malayalam | MMS | 7, 8, 9 | Stop; require written-out numbers |
| Tamil | MMS | 8 | Stop; require written-out numbers |
| Telugu | MMS | 0, 1, 2, 3, 4, 5, 7, 8, 9 | Stop; require written-out numbers |
| Odia | MMS | 8 | Stop; require written-out numbers |

The local MMS tokens accept native scripts but generally cannot speak Latin names
embedded in Indic text. The app now refuses such unsupported speech rather than
deleting part of the message. General transliteration or a better multilingual
voice remains necessary; a whitelist of names would not solve this.
ML Kit's native-script output safeguard also applies to every Indic target.
A valid Latin-only name can therefore require review instead of being labelled
as native Indic output. This conservative check does not identify people or prove
that a transliterated name is correct.

The fresh diagnostic roundtrip includes Hindi “डॉक्टर को बुलाइए” coming back as
“तब तेरे को बुलाइए”, and Gujarati “રસ્તો બંધ છે” as “તેસ્ તો બંધ છે”. Either TTS
or the recognizing STT model can contribute to those errors. Listen to
[the Hindi sample](<D:/new itantra/artifacts/model-conversion-audit-2026-10-11/hi-5.wav>)
and [the Gujarati sample](<D:/new itantra/artifacts/model-conversion-audit-2026-10-11/gu-3.wav>)
before attributing them exclusively to the voice model. `tts-listening-review.csv`
contains all 53 audio inputs and recognition probes for listener review.

## What to test on the phone

1. Install the new test APK as an update; keep existing models and messages.
2. First type native text to isolate MT from recognition. Compare the original
   meaning, translated text and played voice separately. Test both Hindi→Gujarati
   and Gujarati→Hindi using complete sentences, several previously unseen names,
   directions, negation and questions.
3. Enter a phone number with a leading zero and a signed decimal. Changed numeric
   MT should preserve the original and show a review message. MMS speech should
   explicitly stop, not read a shortened number. Written-out numeric text may
   play when every character is supported; listener correctness still needs review.
4. Speak the same text to compare STT against the typed test. A transcription
   error requires a separate recognition investigation.
5. Repeat on both phones. Confirm received text retains its real language,
   chooses the matching installed voice and can be replayed without changing STT.

For the next quality decision, review the existing Hindi/Gujarati reference
workbook: 20 development and 100 reserved evaluation cases per direction. Its
saved status still has **zero human-approved cases**. Use corrected references
to compute chrF, separately check entities/numbers/negation/directions, and compare
ML Kit with candidates on the same reviewed sentences. Keep held-out cases out of
phrase rules, training and tuning. Additional languages need fluent reviewers.
Promote a model only after quality, licence, phone RAM, cold-load latency and
offline runtime compatibility checks. Desktop CPU timings are not phone timings.
For a quick diagnostic review, `hindi-gujarati-review.csv` also combines the
earlier reported examples with the fresh Hindi/Gujarati candidate probes.
Those diagnostic cases must not be used to claim held-out accuracy.

## Verified delivery

- Test APK: `iTantra-1.16-model-audit-test.apk`, package `com.example.itantra`,
  version code 17. Release assembly and Android lintVital passed.
- **589 host tests passed**, with zero failures, errors or skips. This includes
  routing, numeric preservation, voice coverage and encrypted receive regressions.
- APK signature verification passed with the existing development certificate;
  this is a phone-test build. Signed content matches the assembled release.
- The APK is not debuggable, disallows backup, retains the previous native speech
  libraries, contains the new validation safeguards and has no Android logging
  method references in release DEX.
- SHA-256: `98970ca61e20f93fbf40fe3f867d42792751f9e9673f7248349dfdcf8405e18c`.
  Full checks are saved in `verification.json` and reproduced by
  `verify_delivery.py`. Phone validation and human listening review remain open.

## Source references

- [ML Kit translation overview](https://developers.google.com/ml-kit/language/translation):
  English intermediary and intended casual/simple use.
- [ML Kit Android model management](https://developers.google.com/ml-kit/language/translation/android).
- [Sherpa character frontend](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/offline-tts-character-frontend.cc):
  unknown characters are skipped. The same frontend is present in the local source.
- [IndicTrans2 official project](https://github.com/AI4Bharat/IndicTrans2):
  the Indic-centric distilled model has 320M parameters; that is not its byte size.
- [Pinned candidate export](https://huggingface.co/hari31416/indictrans2-indic-indic-dist-320M-ONNX-int8):
  export snapshot and file hashes are included in the new candidate report.
- [Original Gujarati MMS model card](https://huggingface.co/facebook/mms-tts-guj):
  MMS weights use CC-BY-NC-4.0; a commercial release needs an appropriate voice licence.
  Model/runtime licences must be checked separately before any replacement.

No model weights were changed, no ordinary translations were hardcoded, and
candidate inference remains a desktop audit only. Build/test/signature evidence
is recorded in `verification.json` after the final build.
