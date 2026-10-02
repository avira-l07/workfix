"""Pinned, desktop-only Odia STT candidate check.

Run each step separately so the benchmark's peak memory excludes corpus extraction:
    python tools/benchmark_odia_candidate.py fetch-model
    python tools/benchmark_odia_candidate.py fetch-corpus
    python tools/benchmark_odia_candidate.py benchmark

The sample is the first 20 Google FLEURS or_in validation rows. It measures
read speech on a Windows desktop, not Android or field conversation quality.
"""

import argparse
import hashlib
import io
import json
import statistics
import sys
import time
import unicodedata
from pathlib import Path

import benchmark_conformer_candidates as conformer
import validate_stt


ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "tools/stt_models/indicconformer-candidates/or/model.int8.onnx"
TOKENS = ROOT / "tools/stt_models/indicconformer-candidates/tokens.txt"
CORPUS = ROOT / "tools/stt_models/odia-validation"
PARQUET = CORPUS / "fleurs-or_in-validation.parquet"
MANIFEST = CORPUS / "manifest-or-20.json"
REPORT = ROOT / "tools/stt_results/indicconformer-or-desktop.json"
MODEL_SHA256 = "31730e06bd186bca5c3214003c2a0b5eeb3234d079b5b81aa72547adbe1c9be7"
MODEL_BYTES = 197584928
TOKENS_SHA256 = "ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2"
DATASET_REVISION = "10b20dadef85684efea8561331323382da703432"
PARQUET_SHA256 = "1fb175c987ba8f8cb82b1c978ba183ae772544bee7e79d85cf68aec501cef9c6"
PARQUET_BYTES = 284334110
PARQUET_URL = (
    "https://huggingface.co/datasets/google/fleurs/resolve/"
    f"{DATASET_REVISION}/or_in/validation-00000-of-00001.parquet?download=true"
)
SAMPLE_COUNT = 20


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def verified(path: Path, size: int, digest: str) -> bool:
    return path.is_file() and path.stat().st_size == size and sha256(path) == digest


def download_corpus() -> None:
    import requests

    CORPUS.mkdir(parents=True, exist_ok=True)
    if not verified(PARQUET, PARQUET_BYTES, PARQUET_SHA256):
        partial = PARQUET.with_suffix(".parquet.part")
        offset = partial.stat().st_size if partial.exists() else 0
        headers = {"Range": f"bytes={offset}-"} if offset else {}
        with requests.get(PARQUET_URL, headers=headers, stream=True, timeout=(30, 180)) as response:
            response.raise_for_status()
            resume = offset > 0 and response.status_code == 206
            if resume and not response.headers.get("Content-Range", "").startswith(f"bytes {offset}-"):
                raise RuntimeError("FLEURS resume response has wrong byte range")
            with partial.open("ab" if resume else "wb") as output:
                for chunk in response.iter_content(1024 * 1024):
                    if chunk:
                        output.write(chunk)
        if not verified(partial, PARQUET_BYTES, PARQUET_SHA256):
            raise RuntimeError("FLEURS Parquet size or publisher SHA-256 mismatch")
        partial.replace(PARQUET)
    print(f"Verified FLEURS Parquet: {PARQUET_BYTES} bytes", flush=True)

    # An existing workspace dependency provides pyarrow without a new install.
    sys.path.insert(0, str(ROOT / "tools/stt_models/validation-deps"))
    import pyarrow.parquet as pq
    import soundfile as sf

    columns = ["id", "audio", "transcription", "raw_transcription", "gender"]
    batches = pq.ParquetFile(PARQUET).iter_batches(batch_size=SAMPLE_COUNT, columns=columns)
    rows = next(batches).to_pylist()
    if len(rows) != SAMPLE_COUNT:
        raise RuntimeError(f"Expected {SAMPLE_COUNT} first rows, got {len(rows)}")
    manifest = []
    for index, row in enumerate(rows):
        audio_bytes = row["audio"]["bytes"]
        if not audio_bytes:
            raise RuntimeError(f"FLEURS row {index} has no audio bytes")
        samples, rate = sf.read(io.BytesIO(audio_bytes), dtype="float32")
        if rate != 16000 or samples.ndim != 1:
            raise RuntimeError(f"FLEURS row {index} is not 16 kHz mono audio")
        destination = CORPUS / f"fleurs-or_in-validation-{index}.wav"
        digest = hashlib.sha256(audio_bytes).hexdigest()
        if destination.is_file() and sha256(destination) != digest:
            raise RuntimeError(f"Existing corpus file changed: {destination}")
        if not destination.exists():
            destination.write_bytes(audio_bytes)
        manifest.append({
            "language": "or", "row": index, "id": row["id"],
            "file": str(destination.relative_to(ROOT)), "reference": row["transcription"],
            "raw_reference": row["raw_transcription"], "gender": row["gender"],
            "sha256": digest, "audio_seconds": len(samples) / rate,
            "source": "https://huggingface.co/datasets/google/fleurs",
            "source_revision": DATASET_REVISION, "config": "or_in", "split": "validation",
            "license": "CC-BY-4.0",
        })
    if MANIFEST.exists() and json.loads(MANIFEST.read_text(encoding="utf-8")) != manifest:
        raise RuntimeError("Existing Odia corpus manifest differs; preserving prior evidence")
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Verified and indexed {len(manifest)} fixed Odia recordings", flush=True)


def benchmark() -> None:
    import numpy as np
    import sherpa_onnx
    import soundfile as sf

    if not verified(MODEL, MODEL_BYTES, MODEL_SHA256):
        raise RuntimeError("Pinned Odia model missing or SHA-256 mismatch")
    if sha256(TOKENS) != TOKENS_SHA256:
        raise RuntimeError("Pinned shared Indic tokens missing or SHA-256 mismatch")
    if not verified(PARQUET, PARQUET_BYTES, PARQUET_SHA256):
        raise RuntimeError("Pinned FLEURS corpus missing or SHA-256 mismatch")
    rows = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if len(rows) != SAMPLE_COUNT or [row["row"] for row in rows] != list(range(SAMPLE_COUNT)):
        raise RuntimeError("Odia manifest must contain exactly the first 20 validation rows")

    started = time.perf_counter()
    recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=str(MODEL), tokens=str(TOKENS), num_threads=4, decoding_method="greedy_search"
    )
    load_seconds = time.perf_counter() - started
    measured = []
    for item in rows:
        audio_path = ROOT / item["file"]
        if sha256(audio_path) != item["sha256"]:
            raise RuntimeError(f"Corpus audio changed: {audio_path}")
        audio, rate = sf.read(audio_path, dtype="float32")
        if rate != 16000 or audio.ndim != 1:
            raise RuntimeError(f"Invalid benchmark audio: {audio_path}")
        stream = recognizer.create_stream()
        stream.accept_waveform(rate, np.concatenate([audio, np.zeros(1600, dtype=np.float32)]))
        started = time.perf_counter()
        recognizer.decode_stream(stream)
        elapsed = time.perf_counter() - started
        output = stream.result.text.strip()
        reference_words = validate_stt.normalize(item["reference"]).split()
        output_words = validate_stt.normalize(output).split()
        reference_chars = "".join(reference_words)
        output_chars = "".join(output_words)
        letters_marks = [c for c in output if unicodedata.category(c)[0] in "LM"]
        odia_chars = sum("\u0b00" <= c <= "\u0b7f" for c in letters_marks)
        result = {
            **item, "output": output, "seconds": elapsed,
            "word_errors": validate_stt.distance(reference_words, output_words),
            "words": len(reference_words),
            "char_errors": validate_stt.distance(reference_chars, output_chars),
            "chars": len(reference_chars),
            "odia_script_chars": odia_chars, "letter_mark_chars": len(letters_marks),
        }
        measured.append(result)
        print(json.dumps({"row": item["row"], "output": output}, ensure_ascii=False), flush=True)
    total_words = sum(row["words"] for row in measured)
    total_chars = sum(row["chars"] for row in measured)
    total_letters_marks = sum(row["letter_mark_chars"] for row in measured)
    summary = {
        "count": len(measured),
        "wer": sum(row["word_errors"] for row in measured) / total_words,
        "cer": sum(row["char_errors"] for row in measured) / total_chars,
        "odia_script_rate_among_output_letters_marks":
            sum(row["odia_script_chars"] for row in measured) / total_letters_marks if total_letters_marks else None,
        "nonempty_output_count": sum(bool(row["output"]) for row in measured),
        "rtf": sum(row["seconds"] for row in measured) / sum(row["audio_seconds"] for row in measured),
        "decode_ms_mean": statistics.mean(row["seconds"] for row in measured) * 1000,
        "decode_ms_median": statistics.median(row["seconds"] for row in measured) * 1000,
        "decode_ms_max": max(row["seconds"] for row in measured) * 1000,
        "load_seconds": load_seconds,
        "desktop_peak_working_set_bytes": validate_stt.peak_memory_bytes(),
    }
    report = {
        "model": "AI4Bharat IndicConformer CTC INT8", "model_repository": conformer.REPO,
        "model_revision": conformer.REVISION, "language": "or",
        "model_bytes": MODEL_BYTES, "model_sha256": MODEL_SHA256,
        "tokens_bytes": TOKENS.stat().st_size, "tokens_sha256": TOKENS_SHA256,
        "corpus_revision": DATASET_REVISION, "corpus_sha256": PARQUET_SHA256,
        "corpus_selection": "first 20 FLEURS or_in validation rows, original audio",
        "sherpa_version": sherpa_onnx.__version__, "native_module": str(sherpa_onnx.__file__),
        "metric_definition": {
            "wer": "word edit distance / reference words after NFC, lowercase and punctuation removal",
            "cer": "character edit distance / reference characters after the same normalization, excluding spaces",
            "odia_script_rate": "Unicode Odia-block letters and marks / all output letters and marks; empty output excluded from denominator",
            "rtf": "sum of desktop decode seconds / sum of source audio seconds",
            "memory": "peak working set of fresh Windows benchmark process; not Android RAM",
        },
        "summary": summary, "rows": measured,
        "limitations": [
            "Small read-speech sample; not representative field accuracy.",
            "No Android, Bluetooth, translation, or TTS timing included.",
            "Publisher export may have used FLEURS for validation; this is not an unseen holdout claim.",
        ],
    }
    if REPORT.exists():
        raise RuntimeError(f"Report already exists; preserving prior evidence: {REPORT}")
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("fetch-model", "fetch-corpus", "benchmark"))
    args = parser.parse_args()
    if args.action == "fetch-model":
        conformer.fetch(["or"])
    elif args.action == "fetch-corpus":
        download_corpus()
    else:
        benchmark()
