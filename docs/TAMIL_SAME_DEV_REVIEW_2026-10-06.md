# Tamil source versus trained pilots — 6 October 2026

Received `itantra_tamil_pilot_reports (6).zip`. ZIP CRC passed. Preserved at
`tools/stt_results/tamil-gate-b-same-dev-review-2026-10-06/received-reports.zip`.
SHA-256: `17ee4dc4deb688df489868c1ff9780f4212e6336d88dc2e6402ecbdff1f28792`.
Machine-readable review: `review-summary.json` in the same directory.

The original source checkpoint was evaluated on the same sealed 64-clip
Kathbath DEV subset used by all four pilots. The received evaluation script
restores the original checkpoint, uses the same CTC/Tamil validation dataset
configuration, and calls validation only. Evaluation and pilot training use
FP32 (`32-true`). The runtime's `precision: 16-mixed` field records a capability
preference, not the actual precision chosen for these runs.

| Model | Seed | Same-DEV CTC WER | Increase over original |
|---|---:|---:|---:|
| Original source | — | 20.22% | — |
| Freeze lower | 17 | 35.27% | 15.05 percentage points |
| Freeze lower | 29 | 36.21% | 15.99 percentage points |
| Full encoder | 17 | 36.36% | 16.14 percentage points |
| Full encoder | 29 | 35.74% | 15.52 percentage points |

Lower WER is better. All four pilots worsened this development metric.
Successful execution establishes that the runtime can train, but does not
establish an accuracy improvement. None qualifies for app promotion.

The new evaluation completed 64/64 clips with process exit code 0. Plan and
source-checkpoint checksum metadata match the reviewed experiment. Each pilot
completed 20 updates with changed weights; its final metric matches its CSV,
comparison report and successful process log. The received training function
matches the reviewed local implementation. Historical failed attempts remain
in the archive and do not invalidate the completed FP32 results.

Source checkpoint SHA-256:
`40a6fce3374ceb67daa2a071e92f0948c51c6a95b734d4881a3bcfe0b1bed32a`.
Pilot plan SHA-256:
`0013520354a4a37bace2e9eeca77ad7286e0852d3bf5af87f1a33b3f32662b55`.
The original `.nemo`, audio and private trained checkpoints are not included
in the compact ZIP; their bytes cannot be independently rechecked here.

## Decision and next work

Keep the app's existing model packs. Preserve the current Kaggle output,
including private checkpoints, before ending the session. Do not rerun Gate A
or extend this training configuration to a long trial on the strength of these
results. The source model's performance remains the reference for this subset.

The cause of degradation is not established. Before another training trial,
audit training versus evaluation preprocessing, tokenizer/language routing,
normalization state and optimizer settings. A useful next diagnostic is a
validation measurement before the first update using the training setup; it
should reproduce the original 20.22% on this sealed DEV subset. If it does,
compare a few controlled updates while monitoring DEV WER, changing one
training setting at a time. Do not infer a particular cause from this ZIP or
promise that more steps or a lower learning rate will fix it.

This is a small development comparison, not a final held-out or Android test.
The existing 30-clip FLEURS source baseline and desktop app benchmark measure
different evaluation sets. No application model, export, APK or submission
presentation was changed during this review.
