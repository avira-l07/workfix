# Tamil/Telugu Kathbath Gate A review

Reviewed 4 October 2026. **Gate A passes the supplied-report review for preparing
the Tamil 10-hour training trial. Training, export and phone validation have not
run.** This review does not certify an accuracy improvement or make the existing
Gate B template ready to execute without its runtime checks.

Evidence: the user supplied `itantra_gate_a_report.json` and
`KATHBATH_DATA_AUDIT.md` from the completed Kaggle run. The measured audit is
preserved in [KATHBATH_DATA_AUDIT.md](KATHBATH_DATA_AUDIT.md); the JSON is preserved
locally under ignored `tools/stt_training/gate_a_review_2026-10-04/`.

| Reported Kaggle measurement | Tamil | Telugu |
|---|---:|---:|
| Total prepared hours, including dev | 10.000152 | 10.000146 |
| Train / dev hours | 8.882 / 1.118 | 8.888 / 1.112 |
| Train / dev clips | 4,428 / 555 | 4,355 / 609 |
| Train / dev speaker groups | 123 / 14 | 75 / 8 |
| Total speaker groups | 137 | 83 |
| Train/dev speaker overlap | 0 reported | 0 reported |
| Held-out matches / errors | 0 reported | 0 reported |
| Noisy test-only clips | 1,377 | 953 |
| Male / female clips | 4,187 / 796 | 2,626 / 2,338 |
| Female clip share | 15.97% | 47.10% |
| Duration bins: 1-5 / 5-10 / 10-20 seconds | 681 / 3,766 / 536 | 620 / 3,821 / 523 |
| Training vocabulary size | 22,831 | 20,356 |
| Dev unique-word coverage by training vocabulary | 51.08% | 55.15% |
| Source shards | 23, 28, 33 | 14, 21, 23, 27 |
| All train shards inspected | 40 | 33 |
| Noisy training/dev fraction | 0% | 0% |
| Prepared reference transcripts | Native-script filter passed | Native-script filter passed |
| Post-training WER / CER | Not run | Not run |

The 10-hour target is **train plus dev**, not 10 hours of training plus an
additional dev set. Dev uses 10.22% / 9.64% of speaker groups, respectively.

## Checks performed locally on the supplied reports

An assertion-based Python review passed these checks:

- Both complete JSON blocks in the Markdown exactly equal their counterparts
  in the JSON report; the Markdown contains actual newlines.
- The provenance object exactly matches the original uploaded ZIP. All 277
  working-file hashes were recomputed from that ZIP and matched.
- All expected shard indices are present. Selected metadata hours and distinct
  speakers were independently recomputed from the reported shard metadata.
- Clip totals equal input rows, gender counts and duration-bin counts; speaker
  split counts equal the reported total. Both prepared totals meet 10 hours.
- Source-shard lists, language codes, source revisions and license records agree.
- Leakage output says `ta: checked 4983 rows; overlaps/errors=0` and
  `te: checked 4964 rows; overlaps/errors=0`. The reported noisy-test counts are
  1,377 and 953. Native reference-text filtering and the 20-minute speaker-cap
  setting are recorded.

Source revision: `5b9e92849222026d9141acba4e8434fe816396bf`.
Kathbath attribution is AI4Bharat; CC-BY-4.0 is retained, with the report's
CC0-packaging / IndicCorp-text provenance discrepancy recorded.

Received-file SHA-256:

- JSON: `73ea703471c99427ce0fbe9757c9854a2607bfe9bb35d6eeda4772a252384451`
- Markdown: `f8499eb9aad71c5c6dd8b6a44c6280cc55e9d18cbfae9bda68055b094c29cc4d`

## Limits and the next gate

The raw WAVs, manifests and combined protection indexes were not supplied to
this Windows host. Consequently, local review establishes report consistency
and bundle provenance, not an independent rescan of every audio file or
reconstruction of speaker groups. Recheck the actual files in the GPU runtime
before training, including audio-path rebasing when saved outputs are mounted
under `/kaggle/input` instead of their old `/kaggle/working` paths.

Tamil's clip gender distribution is about 84% male. Both trials contain clean
training/dev audio only. These are limitations for a pilot, not reasons to
discard the prepared data. Preserve the noisy test set exclusively for testing;
any training noise augmentation must come from a separately licensed source.
Dev vocabulary coverage measures distinct reference words, not STT accuracy.
Native-script reference filtering is not a measured recognizer output rate.

Next: prepare Tamil Gate B using the preserved private Kaggle outputs. Validate
the actual NeMo runtime/config, GPU memory, CTC/RNNT baseline, seed wiring,
precision, augmentation, dev-only early stopping and checkpoint preservation
before starting a trial. The existing notebook is still a guarded template.
Telugu follows after the Tamil trial is reviewed. No larger run or app-pack
promotion is justified by this data-preparation report.

All post-training accuracy, synthesis behavior, model speed/RAM, phone accuracy
and live two-device results remain **Not run / Not verified**, as appropriate.
