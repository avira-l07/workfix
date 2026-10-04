# Bengali and Gujarati candidate comparison — 4 October 2026

Work proceeded on Bengali/Gujarati as the stated assumption after asking which two languages. All new runs are Windows desktop experiments on the same checksum-verified 30 FLEURS validation clips per language. Each candidate/language ran in a separate process; no training data was downloaded. Existing IndicConformer scores are earlier same-corpus baselines, not fresh latency measurements.

| Language / model | WER / CER | Native script | Mean / p95 decode (ms) | RTF | Peak process RAM (bytes) | Model + tokens (bytes) | Decision |
|---|---|---|---|---|---|---|---|
| Bengali / current IndicConformer | 14.36% / 3.62% | 30/30 | 729 / 1552 | 0.057 | 543,227,904 | 197,663,183 | Keep |
| Bengali / omnilingual-300m-int8 | 35.82% / 8.37% | 30/30 | 1463 / 2992 | 0.115 | 1,115,537,408 | 365,438,543 | Reject: higher WER |
| Bengali / dolphin-small-int8 | 32.00% / 6.90% | 30/30 | 609 / 1428 | 0.048 | 779,001,856 | 250,163,616 | Reject: higher WER |
| Gujarati / current IndicConformer | 18.54% / 5.75% | 30/30 | 511 / 786 | 0.052 | 476,336,128 | 197,663,066 | Keep |
| Gujarati / omnilingual-300m-int8 | 32.06% / 8.55% | 30/30 | 1033 / 1574 | 0.105 | 895,737,856 | 365,438,543 | Reject: higher WER |
| Gujarati / dolphin-small-int8 | 50.78% / 27.81% | 28/30 | 410 / 648 | 0.042 | 613,330,944 | 250,163,616 | Reject: higher WER |

## Result and next work

Neither replacement improves accuracy. Gujarati Dolphin also produces two transcripts outside the expected native-script check. Both candidates failed the 30-clip comparison, so further 100/noisy tests and integration are not justified for these models. No Android model route, pack version, speech fallback or APK was changed. Under-10% WER remains unachieved.

Prior independent 100-clip baselines: Bengali 14.31% WER / 4.12% CER, Gujarati 17.13% / 4.47%; both native script on 100/100. Prior desktop TTS runs generated nonempty 16-kHz PCM: Bengali mean/p95 1,046/1,639 ms, Gujarati 529/668 ms. These synthesis results are not human intelligibility ratings and have not been rerun in this comparison.

The next accuracy experiment is a speaker-disjoint fine-tuning trial of the existing dedicated models, using the prepared full FLEURS protection indexes. Actual Bengali/Gujarati training corpus preparation, Kathbath/noisy protection, CTC/RNNT PyTorch baselines and training are Not run. A future trial needs its own Gate A audit; the running Tamil/Telugu notebook and dataset bundle are unchanged.

A smaller Bengali streaming candidate is published in [sherpa documentation](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html). Its [Alpha Cephei source card](https://huggingface.co/alphacep/vosk-model-small-streaming-bn) declares Apache-2.0 and publisher-reported FLEURS WER 20.6%. This is a different test setup, not our result or proof of improvement. Local accuracy, RAM, Android fit and transport integration for that candidate are Not run. It uses a streaming transducer API, unlike the current offline CTC path.

## Reproduce

```powershell
python tools/evaluate_omnilingual_candidate.py benchmark --languages bn
python tools/evaluate_omnilingual_candidate.py benchmark --languages gu
python tools/evaluate_dolphin_candidate.py benchmark --languages bn
python tools/evaluate_dolphin_candidate.py benchmark --languages gu
```

Each output file is `tools/stt_results/<candidate>-<language>-30.json`; recorded model revisions/hashes and per-clip outputs are in those files. Both model families were already present locally. The evaluator changes add language selection and separate report names, preserving the original Tamil/Telugu reports. Python syntax and CLI checks passed; no Android source changed in this comparison.

Phone WER, human listening, noisy speech and live two-device latency remain **Not verified**.
