# Tamil Gate B: reuse saved data, then check the GPU runtime

Prepared 4 October 2026. The received Gate A report passed review; the saved
Kaggle Output page lists all three required folders. **Training is not run.**
This handoff is a data/GPU preflight, not a complete training notebook.

## Received CPU result: 5 October 2026

The new JSON and report ZIP now record `saved_data_verified=true`, the exact
reviewed Gate A report SHA-256, 277 verified bundle files, 4,428 train/555 dev
clips and 123/14 speakers. The leakage guard reports 4,983 rows with zero
overlaps/errors, and 1,377 noisy held-out clips are accounted for. The fixed
128/64 pilot subset was prepared. The ZIP CRC check passed; its embedded
helper and runner match the corrected local sources. The received reports
are preserved under `tools/stt_results/tamil-gate-b-datacheck-2026-10-05/`.

This supersedes the earlier failed report. Actual CUDA/runtime compatibility,
source baseline, training and improved WER remain Not checked / Not run.
Proceed to `KATHBATH_TAMIL_PILOT.md`; no separate CPU preflight or Gate A rerun
is needed. The GPU pilot rechecks its own input before loading models.

## Path repair on 5 October 2026

Follow-up repair: the updated Kaggle screenshot reaches FLEURS WAV loading but
raises `FileNotFoundError`. The previous resolver validated the normalized
`part` but joined the original `relative` string. Windows accepted the original
backslashes, hiding the Linux bug in the local fixture. The resolver now opens
`(root / part).resolve(strict=True)`. A regression check intercepts the path join
and requires the normalized POSIX path, while preserving real file containment
and checksum checks. It reproduced the old bug locally before the fix.

In an already-running Kaggle session, edit that one line in the helper cell,
rerun the helper cell, then the saved-data check and remaining cells. A new
session or Gate A rerun is unnecessary. Actual complete Kaggle verification is
still pending; no training or accuracy improvement is claimed by this repair.

The user's screenshot records an `Unsafe saved path` error for a relative
Windows-style benchmark path. The older Kaggle runtime cell subsequently
overwrote the failure status with `Saved data passed`; that report is **not a
successful data preflight**. Its displayed runtime is CPU-only with no CUDA GPU.
The enabled HF secret does not establish checkpoint access or model compatibility.

The updated helper normalizes relative Windows/POSIX separators in memory, also
when classifying source scripts. Original report, manifest and WAV checksums
remain enforced; absolute paths, drive paths, UNC paths, traversal and escapes
are rejected. Successful checks set `saved_data_verified=true`. The updated
runtime cell requires that flag and refuses an existing error before changing
status. Both the preflight and pilot notebook embed the corrected helper.

Import the corrected preflight into Kaggle, attach the same complete Gate A
Version #1 and run all cells from the top. CPU is adequate for this data check;
the training pilot requires a CUDA GPU. Repeating Gate A or downloading its
corpus again is not required to apply this fix. Actual Kaggle verification still
has to finish successfully before training.

`python tools/test_kathbath_gate_b_data.py` and
`python tools/test_kathbath_tamil_pilot.py` passed after the repair. Checks cover
Windows paths in both provenance keys and evaluation manifests, unchanged source
report hashes, unsafe-path rejection and the screenshot's false-pass scenario.
These are synthetic local regression checks, not speech accuracy measurements.

The [next Tamil baseline/pilot notebook](KATHBATH_TAMIL_PILOT.md) is now prepared.
Finish this preflight and return its JSON; the next notebook also includes the
saved-data checks and does not require repeating Gate A when saved audio is complete.

## Exact next steps in Kaggle

1. Create a new private notebook and import
   `tools/kathbath_gate_b_preflight.ipynb`.
2. Add Input > Notebook > Your Work: attach the completed output of
   `aviraltindori/notebook2efe523570`, Version #1. This must supply `itantra`,
   `itantra-kathbath`, `itantra-noisy`, and `itantra_gate_a_report.json`.
   The original `language 2` bundle alone does not contain prepared training data.
3. Select an available CUDA GPU in the notebook settings. Enable the existing
   `HF_TOKEN` secret for this new notebook. Do not put the token in a cell.
4. Run all cells in this new preflight notebook. It reads saved audio without
   downloading Kathbath, rewriting input files, or copying training WAVs.
   Run from the top after a kernel restart. The first code cell defines the
   helper functions; the next cell uses them. The updated notebook explicitly
   imports `Path`/JSON in that check cell and saves a clear failure report if
   helpers were skipped. The runtime cell refuses to mark failed data as passed.
5. Download `itantra_gate_b_preflight.json` from Output and return it. A
   one-report ZIP is also generated. Preserve the new output version using
   Quick Save with output files included; do not rerun Gate A.

## What this actually checks

- SHA-256 of the exact reviewed Gate A report and all original bundled files.
- Rebasing old `/kaggle/working` audio paths into the preserved read-only input.
- Actual 16-kHz mono PCM16 WAV headers, durations, speaker counts and the
  20-minute speaker cap; native reference transcripts and source provenance.
- The existing leakage guard for train/dev against combined FLEURS/Kathbath
  protection, plus train/dev speaker, source-ID and audio separation.
- Noisy test-only rows, actual WAV checksums and inclusion in the protected index.
- Rebased small evaluation manifests; all evaluation audio stays test-only.
- Actual Python/PyTorch versions, CUDA availability, per-device GPU memory,
  NVIDIA driver inventory, installed package versions and secret availability.

The preflight retains the existing normalization and guard scripts from the
checksum-verified Gate A bundle. It writes only scripts, small manifests and
reports into `/kaggle/working/itantra-gate-b`.

## What remains before training

AI4Bharat's [Tamil model card](https://huggingface.co/ai4bharat/indicconformer_stt_ta_hybrid_ctc_rnnt_large)
requires its NeMo `nemo-v2` fork. The fork's current
[setup](https://github.com/AI4Bharat/NeMo/blob/nemo-v2/setup.py) and
[requirements](https://github.com/AI4Bharat/NeMo/blob/nemo-v2/requirements/requirements.txt)
were inspected. Its legacy dependencies need an isolated, compatible training
environment; an existing Kaggle Python 3.13 session is not evidence of compatibility.
No compatibility failure or successful install is claimed before a runtime check.

After receiving the actual preflight report: prepare/check that environment,
restore the pinned Tamil checkpoint, reproduce CTC and RNNT source baselines,
verify optimizer/seed/precision/augmentation/early-stopping configuration and
measure training memory with a small pilot. Then run the bounded Tamil trial.
Telugu follows review of Tamil; no full-dataset run or app promotion occurs here.

## Local validation

`python tools/test_kathbath_gate_b_data.py` passed using small synthetic WAVs.
Checks include successful path rebasing and original leakage execution; no WAV
copies; rejection of traversal, the wrong reviewed report, an unreviewed Telugu
switch, tampered source code, tampered noisy-test audio, cross-split duplicate
audio/speakers and truncated WAV payloads. These tests are not recognition-accuracy
measurements.

Startup regression checks also passed: executing the saved-data cell in a fresh
namespace produces a saved, actionable helper-cell error rather than `NameError`;
the GPU cell preserves that failed report; the ZIP cell works without earlier
globals; executing the helper then the check passes on the synthetic fixture.

All preflight notebook code cells passed syntax checks; the embedded helper
matches the tested `kathbath_gate_b_data.py`. Real saved Kaggle WAV verification,
GPU/runtime checks, baselines, training, export and phone results are **Not run /
Not verified** until the user runs the applicable stage. Android app code is
unchanged by this handoff.
