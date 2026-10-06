# Tamil bounded pilot completed — 6 October 2026

**Subsequent result:** ZIP (6) supplies the missing original-model baseline on
the same DEV subset: 20.22% WER versus 35.27–36.36% for the trained pilots.
All four regressed. See [the same-DEV review](TAMIL_SAME_DEV_REVIEW_2026-10-06.md)
for the current decision. The following records the earlier ZIP (5) review.

Received `itantra_tamil_pilot_reports (5).zip`; ZIP CRC passed. Preserved at
`tools/stt_results/tamil-gate-b-completed-pilot-2026-10-06/received-reports.zip`.
SHA-256: `60aa7cd8764b304b91f7e850d8ebac4a90c57260aa74d2d633cb2e0daed8bd87`.
Machine-readable review: `review-summary.json` in that same folder.

All four reports, process exit markers and CSV development metrics agree.
Each run used FP32, completed 20 optimizer updates over 80 training batches,
recorded changed weights and finite loss/development WER, and exited with code 0.
Both RNNT and CTC objectives remained active (CTC weight 0.3). All reports match
the exact same plan and source baseline hashes. The received runner's training,
scoring and data-verification functions match the reviewed local runner.

Pilot dataset: 128 TRAIN / 64 DEV clips, 123 / 14 speakers, approximately
0.245 / 0.123 hours. Each run restores the original source checkpoint separately.

| Mode | Seed | DEV WER at update 10 | Final DEV WER at update 20 | Training/validation seconds | Peak GPU allocated GiB |
|---|---:|---:|---:|---:|---:|
| Freeze lower | 17 | 32.76% | 35.27% | 138.86 | 2.05 |
| Freeze lower | 29 | 32.29% | 36.21% | 136.64 | 2.07 |
| Full encoder | 17 | 34.01% | 36.36% | 140.04 | 2.67 |
| Full encoder | 29 | 33.23% | 35.74% | 139.59 | 2.70 |

Final two-seed mean DEV WER: freeze-lower 35.74%, full encoder 36.05%.
The difference is only 0.31 percentage points on a small subset; do not select
a production winner from it. Every run's update-20 DEV WER exceeds update-10
DEV WER. That is observed deterioration within this short experiment, not
proof of a particular cause or regression against the original source model.

The original baseline's CTC 20.05% / RNNT 18.53% were measured on 30 protected
FLEURS clips, not this 64-clip Kathbath DEV subset. Comparing those numbers to
the pilot DEV WER would not establish a training gain or regression.
The report ZIP contains no original-model WER on this same DEV subset.

## Preserve and evaluate next

Keep the current Kaggle session available until outputs are preserved. Save a
notebook version including its current working outputs. The compact report ZIP
deliberately excludes `last-pilot.ckpt` files; preserve those in the full private
Kaggle output before ending the session. Their bytes have not been independently
verified from this report-only archive.

Use `tools/kathbath_tamil_same_dev_baseline.txt` once in a new Code cell. It
loads only the original source `.nemo`, verifies its checksum and sealed data,
and validates on the identical 64 DEV clips using FP32 and the same model's
CTC validation metrics. It never fits a model, backpropagates, or changes pilot
reports/checkpoints. It creates `same-dev-baseline-report.json`, compares all
four pilot results to that source DEV WER and repacks the report ZIP.

Local mocked-runtime checks passed for using the original checkpoint, identical
DEV manifest and validation configuration, preserving reports, calculating
percentage-point deltas and refusing to overwrite an existing result. These
are software checks, not another GPU result. Return the next ZIP for actual
same-data metric review before scheduling longer training or selecting settings.

The bounded readiness experiment is complete. Full-data training, a locked
post-training held-out comparison, export, Android accuracy and model promotion
remain separate work. The app currently retains its existing model packs.
