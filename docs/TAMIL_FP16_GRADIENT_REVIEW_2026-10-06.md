# Tamil pilot gradient review — 6 October 2026

Received `itantra_tamil_pilot_reports (4).zip`; ZIP CRC passed. Preserved at
`tools/stt_results/tamil-gate-b-fp16-gradient-review-2026-10-06/received-reports.zip`.
SHA-256: `b718f858e53d84179b8a68a8d0a22b143be2a3627e35b29941baa8569a4e0cb3`.

## Confirmed progress

NVIDIA bindings were enabled with Numba 0.59.1 and cuda-python 12.4.0. The tiny
RNNT CUDA test passed Torch-to-Numba interoperability and forward/backward in
both FP32 and FP16, with finite, nonzero gradients. FP32 loss was
6.301324367523193, gradient norm 1.5216000080108643; FP16 loss was
6.301080703735352, gradient norm 1.521588683128357.

The full first pilot advanced through several batches without the prior
segmentation fault. It then failed at the finite-gradient guard before the
first optimizer update. Reported global_step is 0; the three recorded losses
are finite (13.6620, 2.5333, 3.3966). The trace is in the Lightning AMP optimizer
hook and reports `Non-finite gradients; review precision/runtime`.

Peak GPU allocated/reserved bytes were 1,408,221,696 / 1,472,200,704. The archive
has no checkpoint, measured development WER or completed weight-update result.
The original baseline remains passed; no training accuracy gain is established.
The subsequent original training-cell rerun was blocked by the existing report,
and overwrote the original training log, but the distinct NVIDIA-binding log
and pilot report retain the actual new failure.

## Next recovery

The evidence identifies non-finite gradients under FP16 mixed precision. It
does not distinguish loss-scaler warmup overflow from another numerical issue.
[PyTorch's AMP documentation](https://docs.pytorch.org/docs/stable/notes/amp_examples.html)
describes skipping an update when gradients are non-finite. This experiment's
guard stops immediately instead of allowing such updates. Retain that guard
and test full FP32 training, which removes autocast and gradient scaling.
[Lightning supports `32-true`](https://lightning.ai/docs/pytorch/2.3.2/reference/common/precision_basic).

Paste all of `tools/kathbath_tamil_fp32_recovery.txt` into one new Code cell in
the current Kaggle session. Run only that cell once and return its new ZIP.
No setup, package installation, source baseline or Gate A rerun is needed.

The cell requires the exact reviewed zero-update error, mode, seed, passing
baseline and NVIDIA-binding preflight. It refuses successful/different results
or any checkpoint. Before retrying, it preserves the old runner, failed report
and effective config. Failed-report backup is at the working-folder root so the
existing report packer includes it. Existing logs and metric directories remain.
The retry reloads the original source checkpoint; it is not a resumed partial
training run. Batch size, accumulation, learning rate, both RNNT/CTC losses,
data seals, mode, seed and 20-update bound are unchanged. The runner records
`training_precision=32-true`; the runtime's old precision field describes its
mixed-precision capability, not the selected FP32 trainer setting.

Fresh-session notebooks also use the FP32 pilot policy, so all subsequent
mode/seed comparisons use consistent training precision. The recovery retries
only the first mode/seed; remaining runs need the first result reviewed.

## Local validation

CPU regression checks passed for preserving a successful report, backing up the
reviewed failed report, source patch parity, baseline preservation, one retry,
duplicate-attempt refusal, notebook embedded-source parity, finite-gradient
guard retention, data seals and failure ZIP redaction. One initial test run was
interrupted by Windows temporary-directory cleanup; the full rerun passed.
Actual FP32 training, finite updates, development WER and checkpoint creation
remain unverified until Kaggle executes the retry. No app model or APK changed.
