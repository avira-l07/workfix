"""Desktop-only same-audio comparison of Dolphin Small INT8 with selected CTC models.

Usage: python tools/evaluate_dolphin_candidate.py fetch
       python tools/evaluate_dolphin_candidate.py benchmark
The downloaded model is evaluation-only; this does not change the Android pack.
"""

import argparse
import hashlib
import json
import statistics
import tarfile
import time
from pathlib import Path

import validate_stt


ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "tools/stt_models/dolphin-small-int8"
URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    "sherpa-onnx-dolphin-small-ctc-multi-lang-int8-2025-04-02.tar.bz2"
)
ARCHIVE = DEST / "model.tar.bz2"
MANIFESTS = {
    "ta": ROOT / "tools/stt_models/five-language-validation/manifest-ta-30.json",
    "te": ROOT / "tools/stt_models/five-language-validation/manifest-te-30.json",
    "or": ROOT / "tools/stt_models/five-language-validation/manifest-or-30.json",
}


def digest(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def fetch() -> None:
    import requests

    DEST.mkdir(parents=True, exist_ok=True)
    if not (DEST / "model.int8.onnx").is_file():
        partial = DEST / "model.tar.bz2.part"
        offset = partial.stat().st_size if partial.exists() else 0
        headers = {"Range": f"bytes={offset}-"} if offset else {}
        with requests.get(URL, headers=headers, stream=True, timeout=(30, 180)) as response:
            response.raise_for_status()
            append = offset > 0 and response.status_code == 206
            if append and not response.headers.get("Content-Range", "").startswith(f"bytes {offset}-"):
                raise RuntimeError("Invalid resume range")
            with partial.open("ab" if append else "wb") as output:
                for chunk in response.iter_content(1024 * 1024):
                    if chunk:
                        output.write(chunk)
        partial.replace(ARCHIVE)
        with tarfile.open(ARCHIVE, "r:bz2") as bundle:
            for name in ("model.int8.onnx", "tokens.txt"):
                match = next((m for m in bundle if Path(m.name).name == name and m.isfile()), None)
                if match is None:
                    raise RuntimeError(f"Candidate archive lacks {name}")
                with bundle.extractfile(match) as source, (DEST / name).open("wb") as target:
                    while chunk := source.read(1024 * 1024):
                        target.write(chunk)
    print(json.dumps({
        "archive_bytes": ARCHIVE.stat().st_size if ARCHIVE.exists() else None,
        "archive_sha256": digest(ARCHIVE) if ARCHIVE.exists() else None,
        "model_bytes": (DEST / "model.int8.onnx").stat().st_size,
        "model_sha256": digest(DEST / "model.int8.onnx"),
        "tokens_bytes": (DEST / "tokens.txt").stat().st_size,
    }, indent=2), flush=True)


def benchmark() -> None:
    import numpy as np
    import sherpa_onnx
    import soundfile as sf

    model = DEST / "model.int8.onnx"
    tokens = DEST / "tokens.txt"
    if not model.is_file() or not tokens.is_file():
        raise FileNotFoundError("Run fetch first")
    started = time.perf_counter()
    recognizer = sherpa_onnx.OfflineRecognizer.from_dolphin_ctc(
        model=str(model), tokens=str(tokens), num_threads=4,
        decoding_method="greedy_search",
    )
    load_s = time.perf_counter() - started
    report = {
        "candidate": "sherpa-onnx Dolphin Small multilingual CTC INT8",
        "source": URL,
        "model_bytes": model.stat().st_size,
        "model_sha256": digest(model),
        "tokens_bytes": tokens.stat().st_size,
        "tokens_sha256": digest(tokens),
        "sherpa_version": sherpa_onnx.__version__,
        "desktop_peak_working_set_bytes": None,
        "load_seconds": load_s,
        "languages": {},
    }
    for language, manifest_path in MANIFESTS.items():
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
            "rows": rows,
        }
        print(f"{language}: {len(rows)} clips, WER {report['languages'][language]['wer']:.3f}", flush=True)
    report["desktop_peak_working_set_bytes"] = validate_stt.peak_memory_bytes()
    result = ROOT / "tools/stt_results/dolphin-small-int8-five-language.json"
    result.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Saved {result}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("fetch", "benchmark"))
    args = parser.parse_args()
    {"fetch": fetch, "benchmark": benchmark}[args.action]()
