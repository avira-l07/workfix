"""Recompute Malayalam candidate evidence and check independent test provenance.

Requires the ignored benchmark corpus cache. Does not infer phone validation.
"""

import json
import math
import statistics
import sys

from benchmark_five_language_stt import ROOT, SOURCES, digest, script_ok, source_file
from check_speech_leakage import text_hash
from validate_stt import distance, normalize


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


def check(rows, summary, corpus):
    assert len(rows) == len(corpus) == summary["count"]
    for row, source in zip(rows, corpus):
        if "row" in row:
            assert row["row"] == source["row"]
        else:
            assert row["id"] == source["id"]
        ref, hyp = normalize(source["reference"]), normalize(row["output"])
        assert row["word_errors"] == distance(ref.split(), hyp.split())
        assert row["words"] == len(ref.split())
        assert row["char_errors"] == distance(ref.replace(" ", ""), hyp.replace(" ", ""))
        assert row["chars"] == len(ref.replace(" ", ""))
        assert bool(row["native_script"]) == script_ok("ml", row["output"])
        assert abs(row["audio_seconds"] - source["audio_seconds"]) < 1e-6
    for metric, errors, total in (("wer", "word_errors", "words"), ("cer", "char_errors", "chars")):
        assert abs(summary[metric] - sum(r[errors] for r in rows) / sum(r[total] for r in rows)) < 1e-12
    assert summary["native_script_count"] == sum(r["native_script"] for r in rows)
    times = sorted(r.get("decode_ms", r.get("seconds", 0) * 1000) for r in rows)
    assert abs(summary["decode_ms_mean"] - statistics.mean(times)) < 1e-6
    assert abs(summary["decode_ms_p95_nearest_rank"] - times[math.ceil(.95*len(times))-1]) < 1e-6


def main():
    validation = read("tools/stt_models/five-language-validation/manifest-ml-30.json")
    baseline = read("tools/stt_results/five-language-ml-30.json")
    check(baseline["rows"], baseline["summary"], validation)
    for mode in ("quiet12", "context10"):
        experiment = read(f"tools/stt_results/ml-indicconformer-{mode}-30.json")
        check(experiment["rows"], experiment["summary"], validation)
        assert experiment["summary"]["wer"] > baseline["summary"]["wer"], "Rejected accuracy regression"
        for row in experiment["rows"]:
            ranges = row["chunk_ranges_samples"]
            assert ranges[0][0] == 0
            assert ranges[-1][1] == round(row["audio_seconds"] * 16000)
            assert all(b-a <= (10 if mode == "context10" else 12)*16000 for a,b in ranges)
            assert all(ranges[i][1] == ranges[i+1][0] for i in range(len(ranges)-1))
    for name in ("ml-whisper-tiny-30", "ml-whisper-small-30"):
        report = read(f"tools/stt_results/{name}.json")
        check(report["rows"], report["summaries"]["ml"], validation)
    omni = read("tools/stt_results/omnilingual-300m-int8-ml-30.json")["languages"]["ml"]
    check(omni["rows"], omni["summary"], validation)
    dolphin = read("tools/stt_results/dolphin-small-int8-ml-30.json")["languages"]["ml"]
    check(dolphin["rows"], dolphin, validation)

    heldout = read("tools/stt_models/five-language-test/manifest-ml-100.json")
    measured = read("tools/stt_results/ml-indicconformer-whole-100.json")
    check(measured["rows"], measured["summary"], heldout)
    sys.path.insert(0, str(ROOT / "tools/stt_models/validation-deps"))
    import pyarrow.parquet as pq
    full_validation = set()
    assert digest(source_file("ml")) == SOURCES["ml"][3]
    for batch in pq.ParquetFile(source_file("ml")).iter_batches(
            batch_size=64, columns=["transcription"]):
        full_validation.update(text_hash(row["transcription"]) for row in batch.to_pylist())
    assert len({text_hash(r["reference"]) for r in heldout}) == 100
    assert not full_validation.intersection(text_hash(r["reference"]) for r in heldout)
    for row in heldout:
        assert row["split"] == "test" and row["source_revision"] == SOURCES["ml"][1]
        assert row["parquet_sha256"] == "8b609c11b4ac862f6376a4b4887d59a75791fbc5cffc5bcf3431edb56ced5bf5"
        assert digest(ROOT / row["file"]) == row["sha256"]
    manifest = read("app/src/main/assets/language_packs/ml_dev_manifest.json")
    assert manifest["sttModel"]["sizeBytes"] == measured["model_bytes"]
    assert manifest["sttModel"]["checksumsSha256"]["ml/model.int8.onnx"] == measured["model_sha256"]
    assert manifest["downloadSizeBytes"] == manifest["sttModel"]["sizeBytes"] + manifest["ttsModel"]["sizeBytes"]
    print("Malayalam: raw WER/CER/script/timing totals, rejected chunking, 100 test references disjoint from full validation, WAV hashes and app model identity verified")


if __name__ == "__main__":
    main()
