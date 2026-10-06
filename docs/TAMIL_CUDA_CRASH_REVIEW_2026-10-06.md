# Tamil pilot CUDA crash review — 6 October 2026

Received `itantra_tamil_pilot_reports (3).zip`; ZIP CRC passed. Preserved at
`tools/stt_results/tamil-gate-b-native-crash-review-2026-10-06/received-reports.zip`.
SHA-256: `953f03f38653de7e667ce3eb57eae5181614182d8354f695ccad7f9b4c1dd395`.

Saved-data checks, runtime setup, source model restore and source baseline completed.
The first freeze-lower / seed 17 process exited with `-11`, before any recorded
optimizer update. There is no pilot report or checkpoint in the archive.
No accuracy improvement has been established.

The fatal trace identifies Numba CUDA driver context handling:
`safe_cuda_api_call → ensure_context → as_cuda_array → gpu_rnnt → RNNTLoss`.
The current thread was inside hybrid RNNT/CTC training, not audio reading or
model download. This localizes the failure; it does not prove the specific
underlying driver defect or memory exhaustion.

Installed Numba is 0.59.1, llvmlite 0.42.0 and Torch 2.2.2+cu121. The installed
package list has no `cuda-python`. The environment did not enable NVIDIA
bindings, so the default ctypes CUDA driver path was active. Numba's
[version-matched documentation](https://numba.readthedocs.io/en/0.59.1/cuda/bindings.html)
supports selecting NVIDIA bindings with `NUMBA_CUDA_USE_NVIDIA_BINDING=1` before
Numba import. [CUDA Python 12.4.0](https://pypi.org/project/cuda-python/12.4.0/)
provides a CPython 3.11 Linux wheel. This is a targeted alternative driver path,
not a confirmed GPU fix until it executes successfully.

## Current-session recovery

Paste the whole `tools/kathbath_tamil_cuda_binding_recovery.txt` into one new
Kaggle Code cell, then run only that cell. Keep the session open.

It verifies the reviewed crash and passing baseline, refuses to replace any
existing training report/checkpoint/metrics, preserves the old log and config,
installs pinned `cuda-python==12.4.0` using existing package constraints, and
enables NVIDIA bindings for newly launched subprocesses.

The small probe first checks Torch-to-Numba CUDA array interoperability, then
executes the pinned RNNT loss forward/backward in FP32 and FP16. It requires
finite loss, finite nonzero gradients, the pinned runtime and unchanged sealed
data. A failed probe prevents training. Its report records binding selection,
versions and losses. Only after success does it retry freeze-lower / seed 17.
The first retry is deliberately separate from the remaining modes/seeds.

Download the regenerated `itantra_tamil_pilot_reports.zip` on success or failure.
Do not rerun setup, baseline or Gate A. The fresh-session notebook also includes
the pinned binding and CUDA loss preflight, but importing it into an existing
session would replace embedded helper files; use the recovery cell instead.

## Validation and limits

Local CPU regression checks passed: recovery stops before training on probe
failure, preserves baseline/crash evidence, dispatches exactly one bounded
pilot after a simulated probe pass, retains notebook/source parity, and
preserves leakage, baseline, scope and report-redaction guards. Runner training,
scoring and data-verification functions match the received runner unchanged.
Actual RNNT CUDA preflight, retry, updated weights and development WER must be
confirmed by the next Kaggle report. No app model or APK was changed.
