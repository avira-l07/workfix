"""Reproducible 30-recording desktop STT benchmark for the five target languages.

    python tools/benchmark_five_language_stt.py fetch --languages en ta te or
    python tools/benchmark_five_language_stt.py benchmark --languages hi en ta te or

The fetch command downloads pinned FLEURS validation Parquet and extracts only
its first 30 WAV rows. Downloads are benchmark inputs, never app runtime assets.
"""

import argparse
import hashlib
import io
import json
import math
import statistics
import sys
import time
import unicodedata
from pathlib import Path

import validate_stt

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "tools/stt_models/five-language-validation"
MODELS = ROOT / "tools/stt_models/indicconformer-candidates"
REPORTS = ROOT / "tools/stt_results"
SOURCES = {
    "en": ("en_us", "7e091085abba9be9d2772cee2aa59b9bb4140112", 236549523,
           "7c3eeb11a9597bd52cdc1b0d637e85389fe094cfd8763913e7bf4fdf7a853959"),
    "ta": ("ta_in", "3c66608478c631530e8191bd124449803b75a772", 288019911,
           "9dffc417039ecdef0b080fd3a1386a71a233532f9438fe8901e382ce6a656908"),
    "te": ("te_in", "72123ea69f98657407511489578634bc9dbb57ea", 204572374,
           "8b60e97e369c4d905dc945801c896f1ce2a325f717326ec37bec335e4c44b637"),
    "or": ("or_in", "10b20dadef85684efea8561331323382da703432", 284334110,
           "1fb175c987ba8f8cb82b1c978ba183ae772544bee7e79d85cf68aec501cef9c6"),
}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def source_file(lang):
    return (ROOT / "tools/stt_models/odia-validation/fleurs-or_in-validation.parquet"
            if lang == "or" else DATA / f"fleurs-{lang}-validation.parquet")


def fetch(lang):
    import requests
    import soundfile as sf

    config, revision, expected_bytes, expected_sha = SOURCES[lang]
    parquet = source_file(lang)
    parquet.parent.mkdir(parents=True, exist_ok=True)
    if not parquet.exists() or digest(parquet) != expected_sha:
        partial = parquet.with_suffix(".parquet.part")
        offset = partial.stat().st_size if partial.exists() else 0
        url = (f"https://huggingface.co/datasets/google/fleurs/resolve/{revision}/"
               f"{config}/validation-00000-of-00001.parquet?download=true")
        with requests.get(url, headers={"Range": f"bytes={offset}-"} if offset else {},
                          stream=True, timeout=(30, 180)) as response:
            response.raise_for_status()
            append = offset > 0 and response.status_code == 206
            with partial.open("ab" if append else "wb") as output:
                for chunk in response.iter_content(1024 * 1024):
                    output.write(chunk)
        if digest(partial) != expected_sha:
            raise RuntimeError(f"Publisher SHA-256 mismatch or incomplete download: {lang}")
        partial.replace(parquet)
    if parquet.stat().st_size != expected_bytes:
        raise RuntimeError(f"Publisher size mismatch: {lang}")
    sys.path.insert(0, str(ROOT / "tools/stt_models/validation-deps"))
    import pyarrow.parquet as pq

    rows = next(pq.ParquetFile(parquet).iter_batches(
        batch_size=30, columns=["id", "audio", "transcription"])).to_pylist()
    if len(rows) != 30:
        raise RuntimeError(f"Expected 30 {lang} recordings")
    DATA.mkdir(parents=True, exist_ok=True)
    manifest = []
    for i, row in enumerate(rows):
        audio_bytes = row["audio"]["bytes"]
        audio, rate = sf.read(io.BytesIO(audio_bytes), dtype="float32")
        if rate != 16000 or audio.ndim != 1:
            raise RuntimeError(f"Invalid 16-kHz mono WAV: {lang}/{i}")
        destination = DATA / f"{lang}-{i:02d}.wav"
        if destination.exists() and digest(destination) != hashlib.sha256(audio_bytes).hexdigest():
            raise RuntimeError(f"Existing audio changed: {destination}")
        destination.write_bytes(audio_bytes)
        manifest.append({"language": lang, "row": i, "id": row["id"],
                         "file": str(destination.relative_to(ROOT)),
                         "reference": row["transcription"],
                         "sha256": digest(destination), "audio_seconds": len(audio) / rate,
                         "source_revision": revision, "parquet_sha256": expected_sha,
                         "license": "CC-BY-4.0"})
    (DATA / f"manifest-{lang}-30.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{lang}: verified publisher Parquet, extracted 30 recordings", flush=True)


def benchmark(lang):
    import numpy as np
    import sherpa_onnx
    import soundfile as sf

    manifest_path = (ROOT / "tools/stt_models/fleurs-hi-test-manifest.json"
                     if lang == "hi" else DATA / f"manifest-{lang}-30.json")
    if not manifest_path.exists():
        raise RuntimeError(f"Missing 30-recording corpus for {lang}; run fetch")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    rows = [row for row in manifest if row["language"] == lang][:30]
    if len(rows) != 30:
        raise RuntimeError(f"At least 30 ground-truth utterances required: {lang}")
    model = MODELS / lang / "model.int8.onnx"
    tokens = MODELS / ("en/tokens.txt" if lang == "en" else "tokens.txt")
    if not model.is_file() or not tokens.is_file():
        raise RuntimeError(f"Pinned model files missing for {lang}")
    recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=str(model), tokens=str(tokens), num_threads=4, decoding_method="greedy_search")
    measured = []
    for item in rows:
        audio_file = ROOT / item["file"]
        if digest(audio_file) != item["sha256"]:
            raise RuntimeError(f"Audio checksum mismatch: {audio_file}")
        samples, rate = sf.read(audio_file, dtype="float32")
        if rate != 16000 or samples.ndim != 1:
            raise RuntimeError(f"Invalid audio: {audio_file}")
        stream = recognizer.create_stream()
        stream.accept_waveform(rate, np.concatenate([samples, np.zeros(1600, dtype=np.float32)]))
        start = time.perf_counter()
        recognizer.decode_stream(stream)
        seconds = time.perf_counter() - start
        reference = validate_stt.normalize(item["reference"]).split()
        output = stream.result.text.strip()
        hypothesis = validate_stt.normalize(output).split()
        chars_ref, chars_hyp = "".join(reference), "".join(hypothesis)
        measured.append({"row": item["row"], "output": output, "decode_ms": seconds * 1000,
                         "audio_seconds": len(samples) / rate,
                         "word_errors": validate_stt.distance(reference, hypothesis),
                         "words": len(reference),
                         "char_errors": validate_stt.distance(chars_ref, chars_hyp),
                         "chars": len(chars_ref),
                         "native_script": script_ok(lang, output)})
        print(f"{lang} {item['row'] + 1}/30", flush=True)
    timings = sorted(row["decode_ms"] for row in measured)
    summary = {"count": len(measured),
               "wer": sum(r["word_errors"] for r in measured) / sum(r["words"] for r in measured),
               "cer": sum(r["char_errors"] for r in measured) / sum(r["chars"] for r in measured),
               "decode_ms_mean": statistics.mean(timings),
               "decode_ms_p95_nearest_rank": timings[math.ceil(.95 * len(timings)) - 1],
               "rtf": sum(r["decode_ms"] for r in measured) / 1000 /
                      sum(r["audio_seconds"] for r in measured),
               "native_script_count": sum(r["native_script"] for r in measured),
               "peak_desktop_working_set_bytes": validate_stt.peak_memory_bytes()}
    REPORTS.mkdir(parents=True, exist_ok=True)
    destination = REPORTS / f"five-language-{lang}-30.json"
    destination.write_text(json.dumps({"scope": "Windows desktop, clean FLEURS speech",
                                       "model_bytes": model.stat().st_size + tokens.stat().st_size,
                                       "model_sha256": digest(model),
                                       "summary": summary, "rows": measured},
                                      ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{lang}: WER={summary['wer']:.1%}, CER={summary['cer']:.1%}", flush=True)


def script_ok(lang, text):
    ranges = {"hi": (0x0900, 0x097F), "ta": (0x0B80, 0x0BFF),
              "te": (0x0C00, 0x0C7F), "or": (0x0B00, 0x0B7F)}
    letters = [c for c in text if unicodedata.category(c)[0] in "LM"]
    if not letters:
        return False
    if lang == "en":
        return sum(c.isascii() and c.isalpha() for c in letters) > len(letters) / 2
    lo, hi = ranges[lang]
    return sum(lo <= ord(c) <= hi for c in letters) > len(letters) / 2


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("fetch", "benchmark"))
    parser.add_argument("--languages", nargs="+", choices=("hi", "en", "ta", "te", "or"),
                        default=["hi", "en", "ta", "te", "or"])
    arguments = parser.parse_args()
    for language in arguments.languages:
        if arguments.action == "fetch":
            if language != "hi":
                fetch(language)
        else:
            benchmark(language)
