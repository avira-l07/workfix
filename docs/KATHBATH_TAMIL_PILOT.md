# Tamil Gate B baseline and training pilot

**Same-DEV result — 6 October 2026, ZIP (6):** original source CTC WER is
20.22% on the same sealed 64 DEV clips. The four completed FP32 pilots score
35.27–36.36%, an increase of 15.05–16.14 percentage points. All four worsened
this metric; do not promote them or extend this configuration to a long trial.
Keep the app's existing model packs and preserve current Kaggle outputs.
Audit the training configuration and reproduce source DEV WER before any
further controlled training experiment. The cause is not established. See
[the same-DEV comparison review](TAMIL_SAME_DEV_REVIEW_2026-10-06.md).
The entries below document earlier stages and recovery steps.

**Confirmed completion — 6 October 2026, ZIP (5):** all four FP32 pilots passed,
each with 20 optimizer updates, actual changed weights and matching log/report/
CSV development metrics. Final 64-clip DEV WER ranges from 35.27% to 36.36%.
Every run's update-20 WER is higher than its update-10 WER; no original-model
baseline on this same DEV subset is in the ZIP, so training improvement is not
established. Preserve the full Kaggle outputs, including checkpoints, then use
[the same-DEV source evaluation cell](../tools/kathbath_tamil_same_dev_baseline.txt)
once without retraining. See
[the completed pilot review](TAMIL_PILOT_COMPLETED_REVIEW_2026-10-06.md).

**Continuing after the first FP32 run:** the owner reports that freeze-lower /
seed 17 succeeded. Its final report has not yet been received for review. The
old `run_pilots` cell attempts that run again and is blocked by its existing
report. Replace that cell with all of
[the checked FP32 continuation](../tools/kathbath_tamil_continue_fp32.txt).
It requires successful status, finite loss/dev WER, actual changed weights,
bounded optimizer steps, FP32 precision, matching plan/baseline hashes and an
existing checkpoint before skipping a result. Failed or mismatched reports
stop continuation. It runs only missing mode/seed combinations and generates
the four-run comparison and ZIP. Do not run the old lower cells afterward.
Return the regenerated report ZIP for actual metric review. Local fixture
checks passed for skipping the first run, running exactly the remaining three,
preserving reports, refusing failed reports and safely skipping all four on a
repeat. The fresh-session notebook also checks completed reports before skipping.

**Latest result — 6 October 2026, ZIP (4):** NVIDIA-binding CUDA tests passed,
and training advanced past the native crash. FP16 mixed-precision gradients
were non-finite before optimizer update 1 (global_step=0). Use
[the FP32 recovery cell](../tools/kathbath_tamil_fp32_recovery.txt) once in the
current session, then return its new ZIP. It preserves the failed report and
runner and retries only freeze-lower / seed 17 with the same data/settings and
finite-gradient guard. No reinstall or baseline rerun is needed. See
[the gradient review](TAMIL_FP16_GRADIENT_REVIEW_2026-10-06.md).

**Latest update — 6 October 2026:** the new received ZIP confirms the source
baseline passed and the first training process segfaulted in Numba CUDA driver
context handling during RNNT loss. Use
[the NVIDIA-binding recovery cell](../tools/kathbath_tamil_cuda_binding_recovery.txt)
in the existing session. It checks a tiny RNNT forward/backward pass before
retrying only freeze-lower / seed 17. Do not rerun setup or baseline. See
[the current crash review](TAMIL_CUDA_CRASH_REVIEW_2026-10-06.md) for evidence,
local checks and GPU validation still required. The earlier entries below
describe prior reports and recovery steps.

Prepared 4 October 2026; CPU data-check reports received and reviewed 5 October.
The saved-data check passed: 277 original bundle files verified, 4,428 train and
555 dev clips, 123/14 speakers, and 4,983 rows with zero reported leakage errors.
The report matches the reviewed Gate A SHA-256. Pilot plan: 128 train/64 dev
clips, approximately 0.245/0.123 hours. The ZIP passes its CRC check and embeds
the corrected local helper and pilot runner. Received reports are preserved in
`tools/stt_results/tamil-gate-b-datacheck-2026-10-05/`.
These are user-supplied Kaggle results, not a local rescan of the full corpus.
**Kaggle GPU environment setup,
checkpoint restore, CTC/RNNT baseline and training are Not run here. Phone
accuracy remains Not verified.** The current Tamil pack and its 21.8274% desktop
30-clip WER remain unchanged. There is no new accuracy result.

## Exact next steps

### Latest received ZIP: baseline passed, first training process stopped

The third received ZIP on 5 October confirms source CTC WER **20.05%** and RNNT
WER **18.53%**, both 30/30 native-script outputs on the protected 30 clips.
The CTC compatibility gate passed. These are untrained source GPU baselines;
they are not fine-tuning gains or Android measurements.

The first freeze-lower / seed 17 log ends at training start with an FP32 RNNT
fallback warning. It contains no fatal traceback or pilot report, and the old
wrapper discarded the exit code. No memory-exhaustion diagnosis is confirmed.
The notebook now records process exit codes and enables Python fault handling.
Use [the one-run diagnostic cell](../tools/kathbath_tamil_training_diagnostic.txt)
in the current session, before the failed training cell. Run only that cell and
return its new ZIP; do not rerun setup or baseline. See
[the report review](TAMIL_PILOT_REPORT_REVIEW_2026-10-05.txt) for evidence and limits.

### Current session: validation-config repair (5 October 2026)

The new baseline screenshot reports `'NoneType' object has no attribute 'get'`.
The restore helper cleared `validation_ds`, but the pinned NeMo BPE inference
loader reads `self.cfg.validation_ds.get('use_start_end_token', False)`.
The helper now retains that tokenizer option in a dictionary, with
`manifest_filepath=None` and deferred dataset setup. Stale source train/test
paths stay disabled. Failure reports also retain a credential-redacted traceback.

Keep the current GPU session open. Paste the complete contents of
[the validation-config recovery cell](../tools/kathbath_tamil_validation_config_recovery.txt)
into one new cell immediately before the failed baseline. Run the recovery
cell once, then rerun **only the source-baseline cell**. It backs up the runner,
failed report and log; it refuses to replace a successful or different report.
Do not rerun setup or Run All. The full pilot notebook is updated for future
sessions. Kaggle retry, CTC/RNNT WER and training remain unverified until reports
are returned.

1. The CPU saved-data check has passed; preserve its output. Proceed to the
   GPU pilot below. No separate preflight rerun or Gate A rerun is required.
2. Create a **new private Kaggle notebook**. Import
   [kathbath_tamil_pilot.ipynb](../tools/kathbath_tamil_pilot.ipynb) with File >
   Import Notebook. It includes its own preflight; the separate JSON helps
   troubleshooting but is not a required notebook input.
3. Add Input > Notebook > Your Work: attach successful **Version #1** of
   `aviraltindori/notebook2efe523570`. It must contain the report and complete
   `itantra`, `itantra-kathbath` and `itantra-noisy` folders. Attach exactly one
   complete output. The `language 2` bundle alone is insufficient.
4. Enable Internet, an available CUDA GPU and the existing `HF_TOKEN` secret
   for this new notebook. Accept access on the [Tamil checkpoint page](https://huggingface.co/ai4bharat/indicconformer_stt_ta_hybrid_ctc_rnnt_large)
   using the account owning that token. Never paste its value into code.
   The runner requires at least **14 GiB reported VRAM** and uses one GPU.
   Runtime setup checks at least 15 GiB filesystem free space. Kaggle's output
   quota is separate: the notebook stops before another run if its own working
   folder exceeds 16 GiB. Actual model memory compatibility remains unverified.
5. Run All once. On success **or failure**, download
   **`itantra_tamil_pilot_reports.zip`** from Output. Preserve current outputs
   with a save option that includes them; avoid a full rerun merely to obtain
   reports. Share the ZIP for review.

## What executes

### Float32 baseline repair: 5 October 2026

The received GPU ZIP confirms the pinned Python 3.11.13 / Torch 2.2.2+cu121 /
NeMo runtime passed and the 523,192,320-byte Tamil checkpoint restored. The
baseline failed before scoring with `unknown format: 3`: the pilot used the
training PCM16 duration reader on IEEE float32 FLEURS evaluation audio.

The corrected scorer uses the existing SoundFile dependency for protected
PCM16/float32 evaluation WAVs, requiring 16-kHz mono, nonempty finite samples.
Original checksums remain enforced; no benchmark audio is converted or changed.
Training/noisy PCM16 checks remain unchanged. All 30 original local Tamil
benchmark WAVs now pass checksum and duration reading. These are audio-format
checks, not a new STT benchmark or a passing baseline gate.

To recover the running Kaggle session, copy the complete contents of
`tools/kathbath_tamil_float_wav_recovery.txt` into a new code cell immediately
before the failed baseline cell. Run the recovery cell once, then rerun only
the original source-baseline cell. It checks all 30 WAVs with the installed
environment, preserves the old failed report/log and refuses to overwrite a
successful baseline. It reuses the installed runtime and checkpoint cache.
Do not rerun the bootstrap or setup cells, which would overwrite/reinstall
the running setup. Continue to the bounded pilots only if the baseline passes.
The corrected full pilot notebook is available for future fresh sessions.

Local regression checks passed for float32/PCM16, invalid rate/channel/empty/
non-finite input, source parity, failure preservation and repeatable recovery.
Received reports and local repair evidence are recorded under
`tools/stt_results/tamil-gate-b-float32-repair-2026-10-05/`. Kaggle baseline
retry, training and improved WER remain **Not run / Not measured**.

- The existing saved-data guard verifies original bundle hashes, actual WAVs,
  train/dev speaker separation, contribution caps and protected FLEURS/Kathbath
  indexes. Audio stays in read-only input; no WAVs are copied or redownloaded.
- An isolated Python 3.11 environment leaves the notebook kernel unchanged.
  The [AI4Bharat NeMo fork](https://github.com/AI4Bharat/NeMo/tree/8dce88cf8e94963e2033c3137f7b9993b51db88a)
  is pinned to `8dce88cf8e94963e2033c3137f7b9993b51db88a`.
  The [official PyTorch 2.2.2 CUDA 12.1 wheels](https://pytorch.org/get-started/previous-versions/)
  and constrained ASR dependencies are candidate settings until pip checks,
  imports, restore and training pass. Python is installed using
  [uv's managed-Python support](https://docs.astral.sh/uv/guides/install-python/).
- Tamil source checkpoint revision is pinned to
  `8c31aa8d04964b8fc87e4eaaee7916f7d2c024da`. The downloaded SHA-256, size and
  actual class are recorded. Stale source training paths are cleared on restore;
  the tokenizer, vocabulary and both source losses are retained.
- Both CTC and RNNT are measured on the protected 30 clips. The CTC gate requires
  30 native-script outputs and a WER within 3 percentage points of the original
  desktop baseline. This is a compatibility tolerance, not a training success
  criterion. CER, GPU transcription wall time including loading, RTF and peak
  memory are recorded. These GPU timings are not Android decode latency.
- **Four bounded pilots:** freeze lower half of encoder vs full encoder, seeds
  17 and 29, **20 optimizer updates per run**, batch 1/accumulation 4.
  A fixed speaker-diverse subset has 128 TRAIN and 64 DEV clips, 1–10 seconds.
  Actual hours/speakers are reported; no test score selects clips. Tamil `lang`
  fields and real six-field multilingual batches are checked. SpecAugment and
  speed perturbation apply only to training. Finite loss/gradients, actual weight
  changes, dev CTC WER, memory, elapsed time and effective config are recorded.
  Early stopping monitors dev CTC WER.

The ZIP contains reports, small configs and logs. It excludes audio, virtualenv,
source weights and private last-pilot checkpoints. Those snapshots stay in
private notebook output and are **last pilot states, not best/production models**.
Failed stages still pack reports; secret values are redacted from errors/logs.

## Remaining evidence

Twenty steps establish only that training produces measurable updates. Improved
general WER and the under-10% goal remain unmeasured. The full 10-hour trial,
best-dev checkpoint selection, locked 30/100/full-noisy evaluation, two-seed
generalization, FP32/INT8 export comparisons, size/speed/RAM acceptance and
recorded phone/listener tests follow pilot review. Telugu follows the reviewed
Tamil trial. No other language or app model changes in this task.

## Local validation

Executed on the CPU-only Windows host; all passed:

```powershell
python tools/test_kathbath_tamil_pilot.py
python tools/test_kathbath_gate_b_data.py
python tools/test_speech_leakage.py
python tools/test_kathbath_trial.py
```

The new checks execute the actual notebook bootstrap in temporary storage,
verify embedded source parity and compilable cells, exercise baseline gates,
speaker selection, freeze/full behavior, the real leakage guard, changed-file
rejection and failed-stage ZIP/secret redaction. These are synthetic safety
checks, not NeMo compatibility or speech accuracy evidence. Public source
inspection receipts are in
`tools/stt_results/tamil-gate-b-preparation-2026-10-04/nemo-source-provenance.json`.
