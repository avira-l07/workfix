"""Verify Kannada/Malayalam audit provenance, scripts and raw metric totals.

Run after fetching and benchmarking both languages. No phone results are inferred.
"""

import hashlib
import json
from pathlib import Path

from benchmark_five_language_stt import ROOT, SOURCES, NEW_MODELS, script_ok


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    for code, text in (("kn", "ಸಹಾಯ ಬೇಕು"), ("ml", "സഹായം വേണം")):
        assert script_ok(code, text)
        for wrong in ("", "We need help", "मदद चाहिए"):
            assert not script_ok(code, wrong)
        corpus = read(f"tools/stt_models/five-language-validation/manifest-{code}-30.json")
        assert len(corpus) == 30
        for row in corpus:
            assert row["source_revision"] == SOURCES[code][1]
            assert row["parquet_sha256"] == SOURCES[code][3]
            assert sha(ROOT / row["file"]) == row["sha256"]
        model = ROOT / f"tools/stt_models/indicconformer-candidates/{code}/model.int8.onnx"
        assert model.stat().st_size == NEW_MODELS[code][0]
        assert sha(model) == NEW_MODELS[code][1]
        tiny = read(f"tools/stt_results/{code}-whisper-tiny-30.json")
        candidate = read(f"tools/stt_results/five-language-{code}-30.json")
        for report, summary in ((tiny, tiny["summaries"][code]), (candidate, candidate["summary"])):
            rows = report["rows"]
            assert [row["row"] for row in rows] == [row["row"] for row in corpus]
            for metric, errors, total in (("wer", "word_errors", "words"), ("cer", "char_errors", "chars")):
                assert abs(summary[metric] - sum(r[errors] for r in rows) / sum(r[total] for r in rows)) < 1e-12
            assert sum(bool(r["native_script"]) for r in rows) == summary["native_script_count"]
        tts = read(f"tools/stt_results/five-language-{code}-tts-30.json")
        assert len(tts["rows"]) == 30
        assert len({row["phrase"] for row in tts["rows"]}) == 5
        assert all(row["sample_rate_hz"] == 16000 and row["audio_ms"] > 0 for row in tts["rows"])
        print(f"{code}: provenance, scripts, paired STT metrics and TTS rows verified")


if __name__ == "__main__":
    main()
