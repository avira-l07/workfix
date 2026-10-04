# Kathbath Gate A: manual Kaggle handoff

Prepared 2026-10-03; supplied Kaggle reports reviewed 2026-10-04. Scope: Tamil
and Telugu data preparation only. Gate A passes the supplied-report review;
see [the review](KATHBATH_GATE_A_REVIEW.md) and
[the measured audit](KATHBATH_DATA_AUDIT.md). Training and new accuracy results
are **Not run**; phone validation is **Not verified**. Existing app models
are unchanged. Raw Kaggle audio/manifests were not rescanned on this host.

## Run

1. Import `tools/kathbath_gate_a.ipynb` into a new private Kaggle notebook.
2. Upload `tools/stt_training/kaggle_gate_a_bundle.zip` as a private Kaggle
   Dataset, and attach that dataset to the notebook using Add Input. Kaggle may
   automatically extract the ZIP; the loader accepts either the extracted
   `bundle-provenance.json` with its files or the original ZIP.
3. Enable Internet. Add and enable a Kaggle secret named `HF_TOKEN`, using the
   account that has accepted access to AI4Bharat/Kathbath. Never paste the token
   into a code cell or share it in logs. Gate A does not require GPU training.
4. Run all cells. Use a fresh session/output directory for a complete rerun;
   the exporter refuses to overwrite a partial or previous trial.
5. Download `/kaggle/working/itantra_gate_a_report.json` and
   `/kaggle/working/itantra/docs/KATHBATH_DATA_AUDIT.md`, and share them with the
   notebook's output logs. If a cell fails, share its error instead; do not
   bypass the assertion or start training.

If an older notebook reports `Attach exactly one private Kaggle Dataset
containing kaggle_gate_a_bundle.zip`, replace its first code cell with
`tools/stt_training/kaggle_setup_cell.txt`, then rerun it. The initial loader
incorrectly required the ZIP to survive Kaggle's automatic extraction.
No bundle re-upload is required if the extracted dataset is already attached.

The notebook contains no training cell. Gate B requires review of the actual
Gate A report. Its separate notebook remains a guarded, unexecuted template;
seed wiring, precision, augmentation and early stopping require runtime checks
before it is used. Tamil training must precede Telugu. No larger run is approved.

## What Gate A measures

Metadata is inspected across all expected 40 Tamil / 33 Telugu train shards.
Spread-shard samples are 0/13/26/39 and 0/11/22/32 respectively. Shard selection
uses actual speaker overlap and caps each speaker at 20 minutes, aiming for
10 hours per language. Duration and native-script filtering may require more
data; insufficient usable hours fail the gate rather than fabricate coverage.

WAVs are 16-kHz mono PCM16. Normalization uses `validate_stt.normalize`.
Approximately 10% of speaker groups form dev; no speaker occurs in both splits.
Full FLEURS validation/test protection is merged with all available Kathbath
non-train Parquet rows and Kathbath noisy test rows. Normalized text, source ID
and WAV hashes are checked before and after preparation. The report includes
hours, clips, speakers, gender counts, duration histogram, vocabulary coverage,
source shards, licenses and leakage-check output.

| Reported Kaggle measurement | Tamil | Telugu |
|---|---|---|
| Selected shards | 23, 28, 33 | 14, 21, 23, 27 |
| Sampled cross-shard speaker overlap | 0:13 has 24; other sampled pairs 0 | 0:11 has 31; other sampled pairs 0 |
| Final train/dev hours | 8.882 / 1.118 | 8.888 / 1.112 |
| Final train/dev clips | 4,428 / 555 | 4,355 / 609 |
| Final train/dev speakers | 123 / 14; overlap 0 | 75 / 8; overlap 0 |
| Real-data leakage output | 4,983 rows; overlaps/errors=0 | 4,964 rows; overlaps/errors=0 |
| Training / post-training WER | Not run | Not run |

Local synthetic tests exercise speaker separation, speaker caps, transcript
parsing and all three leakage checks. They do not certify real data or accuracy.
The bundle records SHA-256 hashes of its working files and verifies them in
Kaggle. It includes public FLEURS evaluation WAVs, not Kathbath training audio.
Do not upload generated Kathbath WAVs/checkpoints back into the source repo.

## Source and licensing evidence

[AI4Bharat Kathbath](https://huggingface.co/datasets/ai4bharat/Kathbath) declares
CC-BY-4.0 in Hub metadata. Its README also describes CC0 packaging and IndicCorp
web-text provenance. Retain AI4Bharat attribution and the discrepancy in the
audit; do not silently relabel the whole corpus CC0.

The official [indicSUPERB repository](https://github.com/AI4Bharat/indicSUPERB)
provides noisy audio/transcript archives. This session checked HTTP headers:
audio archive 1,418,004,480 bytes, transcript archive 15,646,720 bytes. The small
transcript archive was inspected in memory: Tamil/Telugu unknown-test transcripts
use `kb_data_noisy_m4a/<language>/test/transcription_n2w.txt`, with filename plus
transcript lines. The noisy audio archive was **not downloaded locally** and its
internal layout remains unverified. Kaggle checks exact transcript/audio matches
and fails on a mismatch. Computed download hashes are recorded for provenance;
they are not publisher-supplied integrity signatures.

## Local handoff verification

The ZIP is 163,354,469 bytes (about 156 MiB), with SHA-256
`7f234a2fb424dc5278ea1875bc873f41f1401aa6922efe16e25874da84b7de50`.
All 277 bundled working files match their recorded hashes; ZIP integrity passes.
It contains 260 public FLEURS WAVs (30 validation + 100 test per language).
The notebook requires Python 3.11+.

`python tools/test_kathbath_trial.py` and `python tools/test_speech_leakage.py`
passed. The first includes actual preparation of synthetic WAVs, preservation
of speaker/shard ID zero, and 18/2 disjoint speaker groups. These synthetic
results are not corpus measurements. Nine affected Python scripts and both
notebook schemas/code cells passed syntax validation.
`./gradlew.bat testDebugUnitTest --no-daemon` succeeded; the Android task was
up to date because no Android source changed in this handoff. Its existing
report has 423 tests, zero failures/errors and zero skipped tests.
`python tools/check_speech_leakage.py workspace-check` reported no local
training manifests, so there is no real local training corpus leakage result.

Files for this handoff: `kathbath_trial.py`, `kathbath_noisy_test.py`,
`test_kathbath_trial.py`, `package_kathbath_gate_a.py`, `kathbath_gate_a.ipynb`,
and updates to `prepare_speech_finetune.py`, `check_speech_leakage.py`,
`finetune_indicconformer.ipynb`, `evaluate_stt_preprocessing.py`,
`export_finetuned_indic_ctc.py`, `validate_stt.py`, this document,
`STT_FINETUNING_RUNBOOK.md` and `LANGUAGE_BENCHMARKS.md`.

The supplied successful Kaggle reports establish that data preparation ran;
elapsed time and peak disk requirements were not captured in those reports.
Training-runtime compatibility and independent rechecking of the saved raw
files remain **Not verified**. The reviewed outputs must be preserved privately
before the training runtime is prepared.
