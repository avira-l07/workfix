"""Evaluate a checksum-pinned sherpa-onnx Omnilingual 300M INT8 candidate.

    python tools/evaluate_omnilingual_candidate.py fetch
    python tools/evaluate_omnilingual_candidate.py benchmark

Evaluation only. No Android pack or model selection changes.
"""

import argparse
import hashlib
import json
import math
import statistics
import time
from pathlib import Path

import validate_stt
from benchmark_five_language_stt import ROOT, DATA, digest, script_ok

REPO = "csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12"
REVISION = "6abf1ece20cd2308bdb7d13cd78ec1c44fa4c094"
MODEL_BYTES = 365352120
MODEL_SHA256 = "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c"
DEST = ROOT / "tools/stt_models/omnilingual-300m-int8"
MODEL = DEST / "model.int8.onnx"
TOKENS = DEST / "tokens.txt"


def fetch():
    import requests

    DEST.mkdir(parents=True, exist_ok=True)
    for name, expected_size, expected_sha in (("model.int8.onnx", MODEL_BYTES, MODEL_SHA256),
                                                ("tokens.txt", None, None)):
        path = DEST / name
        if path.exists() and (expected_sha is None or digest(path) == expected_sha):
            continue
        partial = DEST / (name + ".part")
        offset = partial.stat().st_size if partial.exists() else 0
        url = f"https://huggingface.co/{REPO}/resolve/{REVISION}/{name}?download=true"
        with requests.get(url, headers={"Range": f"bytes={offset}-"} if offset else {},
                          stream=True, timeout=(30, 180)) as response:
            response.raise_for_status()
            append = offset > 0 and response.status_code == 206
            with partial.open("ab" if append else "wb") as output:
                for chunk in response.iter_content(1024 * 1024):
                    output.write(chunk)
        if expected_size is not None and partial.stat().st_size != expected_size:
            raise RuntimeError(f"Incomplete {name}")
        if expected_sha is not None and digest(partial) != expected_sha:
            raise RuntimeError(f"Publisher checksum mismatch: {name}")
        partial.replace(path)
        print(f"Downloaded and verified {name}: {path.stat().st_size} bytes", flush=True)


def benchmark(languages=("ta", "te", "or")):
    import numpy as np
    import sherpa_onnx
    import soundfile as sf

    if not MODEL.exists() or digest(MODEL) != MODEL_SHA256 or not TOKENS.exists():
        raise RuntimeError("Run fetch first; pinned model is missing or corrupt")
    engine = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
        model=str(MODEL), tokens=str(TOKENS), num_threads=4,
        decoding_method="greedy_search")
    results = {}
    for lang in languages:
        rows = json.loads((DATA / f"manifest-{lang}-30.json").read_text(encoding="utf-8"))
        if len(rows) != 30:
            raise RuntimeError(f"Expected 30 ground-truth samples for {lang}")
        measured = []
        for row in rows:
            path = ROOT / row["file"]
            if digest(path) != row["sha256"]:
                raise RuntimeError(f"Corrupt corpus file: {path}")
            audio, rate = sf.read(path, dtype="float32")
            if rate != 16000 or audio.ndim != 1:
                raise RuntimeError(f"Invalid corpus audio: {path}")
            stream = engine.create_stream()
            stream.accept_waveform(rate, np.concatenate([audio, np.zeros(1600, dtype=np.float32)]))
            start = time.perf_counter()
            engine.decode_stream(stream)
            elapsed_ms = (time.perf_counter() - start) * 1000
            text = stream.result.text.strip()
            ref = validate_stt.normalize(row["reference"]).split()
            hyp = validate_stt.normalize(text).split()
            ref_chars, hyp_chars = "".join(ref), "".join(hyp)
            measured.append({"row": row["row"], "output": text, "decode_ms": elapsed_ms,
                             "audio_seconds": len(audio) / rate,
                             "word_errors": validate_stt.distance(ref, hyp), "words": len(ref),
                             "char_errors": validate_stt.distance(ref_chars, hyp_chars),
                             "chars": len(ref_chars), "native_script": script_ok(lang, text)})
            print(f"{lang} {row['row'] + 1}/30", flush=True)
        timings = sorted(r["decode_ms"] for r in measured)
        results[lang] = {"summary": {"count": 30,
            "wer": sum(r["word_errors"] for r in measured) / sum(r["words"] for r in measured),
            "cer": sum(r["char_errors"] for r in measured) / sum(r["chars"] for r in measured),
            "native_script_count": sum(r["native_script"] for r in measured),
            "decode_ms_mean": statistics.mean(timings),
            "decode_ms_p95_nearest_rank": timings[math.ceil(.95 * 30) - 1],
            "rtf": sum(r["decode_ms"] for r in measured) / 1000 /
                   sum(r["audio_seconds"] for r in measured)}, "rows": measured}
    report = {"scope": "Windows desktop, same 30 FLEURS clips as current CTC models",
              "repository": REPO, "revision": REVISION,
              "model_bytes": MODEL_BYTES + TOKENS.stat().st_size,
              "model_sha256": MODEL_SHA256, "tokens_sha256": digest(TOKENS),
              "peak_desktop_working_set_bytes": validate_stt.peak_memory_bytes(),
              "languages": results}
    suffix = "" if tuple(languages) == ("ta", "te", "or") else "-" + "-".join(languages)
    destination = ROOT / f"tools/stt_results/omnilingual-300m-int8{suffix}-30.json"
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Saved {destination}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("fetch", "benchmark"))
    parser.add_argument("--languages", nargs="+", choices=("ta", "te", "or", "bn", "gu", "ml"),
                        default=("ta", "te", "or"))
    args = parser.parse_args()
    if args.action == "fetch":
        fetch()
    else:
        benchmark(args.languages)
