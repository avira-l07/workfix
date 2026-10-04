# Tamil, Telugu, Bengali and Gujarati STT fine-tuning

Current scope (4 October 2026): **Tamil/Telugu Kathbath 10-hour trial**.
The user completed Gate A in Kaggle and supplied its reports. The
[Gate A review](KATHBATH_GATE_A_REVIEW.md) passes report consistency and
provenance checks; the [measured audit](KATHBATH_DATA_AUDIT.md) records about
8.88 training hours plus 1.12 dev hours per language and zero reported overlaps.
Raw audio/manifests were not independently rescanned on this host. Follow
[the Gate A handoff](KATHBATH_GATE_A.md) for files and preservation instructions.
Training is **Not run**; Gate B remains a guarded template requiring runtime
checks. Bengali/Gujarati work below is historical and outside this trial.

Status on 3 October 2026: **training and export not run**. The existing
checksum-verified on-demand packs remain active. A desktop 30-clip score is
not evidence of phone accuracy. Under 10% WER is a research target, not a
promised result.

## Feasibility and sources

The available Windows host has an NVIDIA RTX 2050 with 4,096 MiB VRAM,
Python 3.14.6, CPU-only PyTorch, no NeMo installation, and roughly 8.3 GB
free on the workspace drive at preflight. The four source `.nemo` checkpoints
are gated and require the owner's accepted Hugging Face access. No checkpoint
was restored here; therefore PyTorch CTC/RNNT baselines, model training,
FP32/INT8 comparisons and improved packs are **Not run**. Plan for a Linux
CUDA runtime with at least 16 GB GPU memory and enough disk for datasets,
checkpoints and exports; check the source model's actual memory use before
committing to that size. A planning allowance is 4–12 GPU-hours per run,
subject to measured data size and runtime. Two seeds per Tamil/Telugu language
would therefore need several runs, not one short session.

| Source | Intended use | Version / access | License and status |
|---|---|---|---|
| [AI4Bharat IndicConformer Tamil](https://huggingface.co/ai4bharat/indicconformer_stt_ta_hybrid_ctc_rnnt_large) | Trainable initialization | `8c31aa8d04964b8fc87e4eaaee7916f7d2c024da`, gated; exact file `indicconformer_stt_ta_hybrid_rnnt_large.nemo` | MIT; not downloaded |
| [AI4Bharat IndicConformer Telugu](https://huggingface.co/ai4bharat/indicconformer_stt_te_hybrid_ctc_rnnt_large) | Trainable initialization | `4133836fef3b0687fdffaa32512f3496657c0b48`, gated | MIT; not downloaded |
| [AI4Bharat IndicConformer Bengali](https://huggingface.co/ai4bharat/indicconformer_stt_bn_hybrid_ctc_rnnt_large) | Trainable initialization if cheap gate fails | `15a1cd06245262b914f27ed445a604b7b278d187`, gated | MIT; not downloaded |
| [AI4Bharat IndicConformer Gujarati](https://huggingface.co/ai4bharat/indicconformer_stt_gu_hybrid_ctc_rnnt_large) | Trainable initialization if cheap gate fails | `85fd7b5800dbeb55daa535c9d5c9bd38874859c7`, gated | MIT; not downloaded |
| [Google FLEURS](https://huggingface.co/datasets/google/fleurs) | Full validation/test protection; 30/100 held-out evaluation. Train split is only a future data candidate | Per-language Parquet revision and SHA pinned in `tools/benchmark_five_language_stt.py` and `tools/fetch_speech_heldout.py` | CC-BY-4.0; validation/test downloaded; **zero** train rows used |
| [AI4Bharat IndicVoices](https://huggingface.co/datasets/ai4bharat/IndicVoices) | Future multi-speaker training candidate | Gated; inspect language/split and consent documentation after access | CC-BY-4.0 dataset card; not downloaded |
| [Mozilla Common Voice](https://commonvoice.mozilla.org/dav/terms) | Future Tamil/Telugu candidate | Confirm available release, language, split and transcript coverage before use | CC0; not downloaded |

Kathbath is now the Kaggle Gate A candidate described above; no Kathbath audio
has been downloaded on this laptop. Shrutilipi, OpenSLR, MUCS and IISc Vaani are **not used**; their
specific releases, redistribution terms and speaker/split provenance still
need checking. No private or user audio was scraped or committed. Actual
prepared hours, speakers and noisy-test counts are now reported in the supplied
Gate A outputs; their audio remains in private Kaggle storage, not on this host.

## Exact local preparation commands

All raw downloaded audio, protection indexes and prepared training data stay
in Git-ignored `tools/stt_models/` or `tools/stt_training/`.

```powershell
python tools/benchmark_five_language_stt.py fetch --languages ta te bn gu
python tools/fetch_speech_heldout.py --languages ta te bn gu
python tools/test_speech_leakage.py
python tools/check_speech_leakage.py workspace-check
python tools/evaluate_stt_preprocessing.py --language ta --method baseline --set 100
```

For any later **licensed and speaker-consented** source, create a private JSONL
with `audio_filepath`, `text`, `source`, `source_version`, `license`, `source_id`,
`speaker_id` (or `source_recording_id`), `noisy` and `consent` for user-collected
recordings. Use source IDs stable across split copies. Then, per language:

```powershell
python tools/prepare_speech_finetune.py --language ta --input D:\private\ta-speech.jsonl --protected tools/stt_training/ta-protected.json
python tools/check_speech_leakage.py check --language ta --protected tools/stt_training/ta-protected.json --manifest tools/stt_training/manifests/ta-train.jsonl tools/stt_training/manifests/ta-dev.jsonl
```

Repeat with `te`, `bn`, `gu`. The preparation script resamples to 16-kHz mono,
keeps 1–20-second native-script utterances, and splits by speaker/source
recording, never by clip. It writes an audit JSON for hours, groups, sources,
noisy fraction, duration bins and dev-vocabulary coverage. Its license
allowlist is deliberately narrow; verify each exact dataset release and add
its license only after review. FLEURS validation/test audio and any matching
normalized text/source ID are rejected. The guard also checks original audio
bytes; a deliberately re-encoded duplicate with different text and source ID
may evade a byte hash, so source provenance must be reviewed as well.

## Cloud training and export

Open `tools/finetune_indicconformer.ipynb` on a CUDA Linux runtime. Set
`ITANTRA_REPO`, `ITANTRA_TRAIN_OUT` (outside Git), and a secret `HF_TOKEN` after
accepting all needed checkpoint gates. Transfer the **private** prepared
manifests/audio and protected indexes to that runtime. The notebook restores
the pinned source checkpoint and requires CTC and RNNT 30-clip baselines to be
recorded before training. Its training commands are templates and must be
checked against the installed AI4Bharat NeMo fork's actual Hydra config. Run
both seeds, a freeze-vs-full pilot, augmentation from licensed material,
dev-only early stopping, and preserve config/log/checkpoint paths outside Git.
Do not select hyperparameters using either held-out test set.

After a best checkpoint exists, the export command is:

```bash
python tools/export_finetuned_indic_ctc.py --language ta --checkpoint /private/best.nemo --trace-wav /private/train-sample.wav --output-dir /private/ta-candidate
python tools/evaluate_stt_preprocessing.py --language ta --method baseline --set 30 --model-dir /private/ta-candidate --label seed17
python tools/evaluate_stt_preprocessing.py --language ta --method baseline --set 100 --model-dir /private/ta-candidate --label seed17
```

The exporter attempts FP32 and MatMul-only dynamic INT8 NeMo-CTC ONNX plus a
sherpa load/decode smoke test. It has **not** been run on a fine-tuned model.
Measure FP32 and INT8 separately in fresh processes, with two seeds and the
same clean/noisy held-out manifests. If INT8 loses more than one absolute WER
point, test alternative quantization. Gate promotion on lower WER across all
three sets, native script, speed and RAM within about 1.3× of baseline, no
leakage, verified package checksum, and phone testing. Only then update the
per-language manifest/version; retain the old pack as a fallback. No new pack
is present now, so the app has no fine-tuned option to select.

## Consented noisy phone set

For each language, ask speakers for explicit recording consent and store it
privately. Record at least a small fixed set of exact field phrases on two
phones, with quiet-room, fan and outdoor/traffic conditions. Keep speakers and
phrases out of training/dev. Save 16-kHz mono WAV and a private manifest with
the same `file`, `sha256`, `reference`, `row` fields as the 100-clip manifest.
Use `--manifest /private/noisy-ta.json --label noisy` with
`evaluate_stt_preprocessing.py` for both baseline and candidate; mark phone
recognition and two-device performance **Not verified** until that test runs.
