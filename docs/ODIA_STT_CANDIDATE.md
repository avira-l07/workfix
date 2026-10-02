# Odia STT candidate — desktop measurement, 1 October 2026

The [IndicConformer ONNX exporter](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx) now includes an Odia CTC model. Its card says the original source-tokenizer problem was repaired during export. iTantra downloads this model on demand for manually selected Odia microphone input. The shared Whisper Tiny model still does not accept an Odia language token.

The model file at pinned revision `9721eb71eea141fae0982cfcdb9dd2e3d4953c4a` is 197,584,928 bytes with SHA-256 `31730e06bd186bca5c3214003c2a0b5eeb3234d079b5b81aa72547adbe1c9be7`. The matching shared tokens are 67,605 bytes with SHA-256 `ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2`. The app verifies both before activation.

Using the first 20 original recordings from the [Google FLEURS `or_in` validation split](https://huggingface.co/datasets/google/fleurs), desktop sherpa-onnx 1.13.8 with four threads produced:

| Measure | Result |
|---|---:|
| Corpus WER | 21.37% (81 errors / 379 reference words) |
| Corpus CER | 5.93% |
| Output script | 100% of output letters/marks in Odia Unicode block; 20/20 nonempty |
| Aggregate decode RTF | 0.056 |
| Mean / maximum decode | 696 ms / 1,685 ms |
| Peak Windows process working set | 529,027,072 bytes (~505 MiB) |

The [benchmark script](../tools/benchmark_odia_candidate.py) pins the model and corpus revisions and verifies their hashes. [Raw results](../tools/stt_results/indicconformer-or-desktop.json) include transcripts, errors and per-clip timings. The audio/model cache under `tools/stt_models/` is intentionally ignored by Git.

This is a small, clean read-speech sample. It does not measure Android memory, microphone accuracy, noisy field speech, translation, TTS quality, Bluetooth/Wi-Fi transfer or speech-to-audible delay. The exporter may have used FLEURS in its own validation; treat this as a candidate check, not an unseen holdout or a field pass. Odia cross-language translation remains unavailable in the current ML Kit pipeline.
