"""Desktop-only same-audio Indic comparison of Dolphin Small INT8.

Usage: python tools/evaluate_dolphin_candidate.py fetch
       python tools/evaluate_dolphin_candidate.py benchmark
The downloaded model is evaluation-only; this does not change the Android pack.
"""

import argparse
import hashlib
import json
import statistics
import time
from pathlib import Path

import validate_stt
from benchmark_five_language_stt import script_ok


ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "tools/stt_models/dolphin-small-int8"
REPO = "csukuangfj/sherpa-onnx-dolphin-small-ctc-multi-lang-int8-2025-04-02"
REVISION = "c8b6689509acfcd744c04e5e169164f9ac4cae32"
MODEL_BYTES = 249658954
MODEL_SHA256 = "c1afcb9265de0ebd853eb8f570b371f399a6f9b2b9af9a3cb17c2e509171e697"
TOKENS_SHA256 = "c3788261a51df1899ea4b210b552cd42139204de72c0ad60f6cebb199078872e"
def digest(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def fetch() -> None:
    import requests

    DEST.mkdir(parents=True, exist_ok=True)
    for name, expected_bytes, expected_sha in (("model.int8.onnx", MODEL_BYTES, MODEL_SHA256),
                                                ("tokens.txt", 504662, TOKENS_SHA256)):
        path = DEST / name
        if path.is_file() and path.stat().st_size == expected_bytes and (expected_sha is None or digest(path) == expected_sha):
            continue
        partial = DEST / (name + ".part")
        offset = partial.stat().st_size if partial.exists() else 0
        headers = {"Range": f"bytes={offset}-"} if offset else {}
        url = f"https://huggingface.co/{REPO}/resolve/{REVISION}/{name}?download=true"
        with requests.get(url, headers=headers, stream=True, timeout=(30, 180)) as response:
            response.raise_for_status()
            append = offset > 0 and response.status_code == 206
            if append and not response.headers.get("Content-Range", "").startswith(f"bytes {offset}-"):
                raise RuntimeError("Invalid resume range")
            with partial.open("ab" if append else "wb") as output:
                for chunk in response.iter_content(1024 * 1024):
                    if chunk:
                        output.write(chunk)
        if partial.stat().st_size != expected_bytes or (expected_sha and digest(partial) != expected_sha):
            raise RuntimeError(f"Incomplete or corrupt {name}")
        partial.replace(path)
        print(f"Verified {name}: {expected_bytes} bytes", flush=True)
    print(json.dumps({
        "repository": REPO,
        "revision": REVISION,
        "model_bytes": (DEST / "model.int8.onnx").stat().st_size,
        "model_sha256": digest(DEST / "model.int8.onnx"),
        "tokens_bytes": (DEST / "tokens.txt").stat().st_size,
    }, indent=2), flush=True)


def benchmark(languages=("ta", "te")) -> None:
    import numpy as np
    import sherpa_onnx
    import soundfile as sf

    model = DEST / "model.int8.onnx"
    tokens = DEST / "tokens.txt"
    if not model.is_file() or model.stat().st_size != MODEL_BYTES or digest(model) != MODEL_SHA256 or not tokens.is_file() or digest(tokens) != TOKENS_SHA256:
        raise FileNotFoundError("Run fetch first")
    started = time.perf_counter()
    recognizer = sherpa_onnx.OfflineRecognizer.from_dolphin_ctc(
        model=str(model), tokens=str(tokens), num_threads=4,
        decoding_method="greedy_search",
    )
    load_s = time.perf_counter() - started
    report = {
        "candidate": "sherpa-onnx Dolphin Small multilingual CTC INT8",
        "source": f"https://huggingface.co/{REPO}/tree/{REVISION}",
        "model_bytes": model.stat().st_size,
        "model_sha256": digest(model),
        "tokens_bytes": tokens.stat().st_size,
        "tokens_sha256": digest(tokens),
        "sherpa_version": sherpa_onnx.__version__,
        "desktop_peak_working_set_bytes": None,
        "load_seconds": load_s,
        "languages": {},
    }
    for language in languages:
        manifest_path = ROOT / f"tools/stt_models/five-language-validation/manifest-{language}-30.json"
        items = [item for item in json.loads(manifest_path.read_text(encoding="utf-8"))
                 if item["language"] == language]
        if len(items) != 30:
            raise RuntimeError(f"Expected 30 pinned recordings for {language}")
        rows = []
        for item in items:
            path = ROOT / item["file"]
            if digest(path) != item["sha256"]:
                raise RuntimeError(f"Corpus hash mismatch: {path}")
            audio, rate = sf.read(path, dtype="float32")
            if rate != 16000 or audio.ndim != 1:
                raise RuntimeError(f"Expected mono 16 kHz: {path}")
            stream = recognizer.create_stream()
            stream.accept_waveform(rate, np.concatenate([audio, np.zeros(1600, dtype=np.float32)]))
            started = time.perf_counter()
            recognizer.decode_stream(stream)
            elapsed = time.perf_counter() - started
            hypothesis = stream.result.text.strip()
            reference_words = validate_stt.normalize(item["reference"]).split()
            hypothesis_words = validate_stt.normalize(hypothesis).split()
            ref_chars = list("".join(reference_words))
            hyp_chars = list("".join(hypothesis_words))
            rows.append({"id": item["id"], "reference": item["reference"],
                         "output": hypothesis, "decode_ms": elapsed * 1000,
                         "native_script": script_ok(language, hypothesis),
                         "audio_seconds": len(audio) / rate,
                         "word_errors": validate_stt.distance(reference_words, hypothesis_words),
                         "words": len(reference_words),
                         "char_errors": validate_stt.distance(ref_chars, hyp_chars),
                         "chars": len(ref_chars)})
        decode = sorted(row["decode_ms"] for row in rows)
        report["languages"][language] = {
            "count": len(rows),
            "wer": sum(row["word_errors"] for row in rows) / sum(row["words"] for row in rows),
            "cer": sum(row["char_errors"] for row in rows) / sum(row["chars"] for row in rows),
            "decode_ms_mean": statistics.mean(decode),
            "decode_ms_p95_nearest_rank": decode[(95 * len(decode) + 99) // 100 - 1],
            "rtf": sum(row["decode_ms"] for row in rows) / 1000 /
                   sum(row["audio_seconds"] for row in rows),
            "native_script_count": sum(row["native_script"] for row in rows),
            "rows": rows,
        }
        print(f"{language}: {len(rows)} clips, WER {report['languages'][language]['wer']:.3f}", flush=True)
    report["desktop_peak_working_set_bytes"] = validate_stt.peak_memory_bytes()
    result = ROOT / f"tools/stt_results/dolphin-small-int8-{'-'.join(languages)}-30.json"
    result.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Saved {result}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("fetch", "benchmark"))
    parser.add_argument("--languages", nargs="+", choices=("ta", "te", "bn", "gu", "ml"),
                        default=("ta", "te"))
    args = parser.parse_args()
    if args.action == "fetch":
        fetch()
    else:
        benchmark(args.languages)
