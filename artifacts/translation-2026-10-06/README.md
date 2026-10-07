# Translation routing update — 6 October 2026

The 1.8 APK removes ordinary-message phrasebook substitution and clarifies the
language direction. It does **not** claim to fix Hindi/Gujarati model accuracy.
ML Kit remains the active offline translation engine. No example-specific
translations, name replacements, or greeting tables were added.

## Implemented and checked

- All ordinary speech/text translation calls reach the neural engine for
  supported language routes. Unsupported routes return an explicit error.
- Fixed SOS button codes still resolve their selected emergency message;
  they are separate from free-form translation.
- Saved local translations show the source and output language, such as
  Hindi → Gujarati. Replay continues to use the output language.
- Cancellation is propagated through the shared translation router.
- 471 Android JVM unit tests pass. These tests check code behavior, not the
  accuracy of machine-generated translations.
- The APK's version, CRC, v2 signature and signing-certificate continuity are
  checked by `verify_apk.py`. No phone was connected for installation or review.

## Replacement-model experiments

Actual model outputs are recorded without supplying expected answers to the
inference code. Inputs include the screenshots, different names, directions,
negation, time and numbers. This small diagnostic set is not a held-out accuracy
benchmark; desktop CPU timings are not phone timings.

| Candidate | Result on the reported examples | Decision |
| --- | --- | --- |
| Cached IndicTrans2 rotary 200M pair with English pivot | Preserved Aviral in the name example, but changed the Gujarati greeting to “Why are you having so much fun?” | Not integrated |
| Direct IndicTrans2 Indic-Indic 320M ONNX INT8 | Greeting became “why in enjoyment”; the Hindi name example changed અવિરલ to અવિરત | Not integrated |
| M2M100 418M CTranslate2 INT8 | Gujarati greeting and Hindi→Gujarati name examples failed badly | Not integrated |

See `cached-model-results.json`, `direct-model-results.json` and
`m2m-model-results.json` for the generated text. The public direct and M2M
snapshots were pinned and their file hashes verified. Candidate weights and
Python evaluation dependencies are not packaged into the APK.

Sources and inference references:

- [Google ML Kit translation](https://developers.google.com/ml-kit/language/translation):
  intended for casual/simple use; non-English translation uses English as a pivot.
- [AI4Bharat IndicTrans2](https://github.com/AI4Bharat/IndicTrans2)
- [Direct ONNX export](https://huggingface.co/hari31416/indictrans2-indic-indic-dist-320M-ONNX-int8)
- [M2M100 candidate](https://huggingface.co/jncraton/m2m100_418M-ct2-int8)
- [CTranslate2 M2M100 inference](https://opennmt.net/CTranslate2/guides/transformers.html#m2m-100)

## Work still required for translation quality

Evaluate a stronger conversational model or fine-tune a suitable model on
licensed parallel conversations. Keep development and evaluation sentences
separate, include previously unseen names and ambiguous/incomplete speech,
and compare meanings with fluent speakers. Promote a replacement only after
quality review and phone memory/latency testing. Editing code labels or passing
unit tests cannot establish that the model now preserves meaning.
