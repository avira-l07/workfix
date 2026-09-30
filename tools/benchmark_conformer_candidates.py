"""Reproducible desktop comparison for four pinned IndicConformer ONNX packs.

Download: python tools/benchmark_conformer_candidates.py fetch --languages ta te
Measure:  python tools/benchmark_conformer_candidates.py benchmark --languages ta te
Uses the existing fixed FLEURS manifests and reports desktop inference only.
"""

import argparse
import hashlib
import json
import statistics
import sys
import time
from pathlib import Path

import validate_stt

sys.stdout.reconfigure(encoding="utf-8")


ROOT = Path(__file__).resolve().parents[1]
MODEL_ROOT = ROOT / "tools/stt_models/indicconformer-candidates"
REPORT_ROOT = ROOT / "tools/stt_results"
REPO = "parismitaglobalsolutions/indicconformer-sherpa-onnx"
REVISION = "9721eb71eea141fae0982cfcdb9dd2e3d4953c4a"
LANGUAGES = ("hi", "en", "ta", "te")
MANIFESTS = {
    "hi": ROOT / "tools/stt_models/fleurs-hi-test-manifest.json",
    "en": ROOT / "tools/stt_models/fleurs-manifest.json",
    "ta": ROOT / "tools/stt_models/extra-language-test/manifest-ta-te.json",
    "te": ROOT / "tools/stt_models/extra-language-test/manifest-ta-te.json",
}


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def fetch(languages: list[str]) -> None:
    import requests

    selected = sorted(set(languages))
    paths = ["tokens.txt"] + [f"{lang}/model.int8.onnx" for lang in selected]
    if "en" in selected:
        paths.append("en/tokens.txt")
    for remote in paths:
        directory = remote.rsplit("/", 1)[0] if "/" in remote else ""
        inventory = requests.get(
            f"https://huggingface.co/api/models/{REPO}/tree/{REVISION}/{directory}", timeout=60
        )
        inventory.raise_for_status()
        expected = next(item for item in inventory.json() if item["path"] == remote)
        destination = MODEL_ROOT / remote
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.is_file() and destination.stat().st_size == expected["size"]:
            if "lfs" not in expected or sha256(destination) == expected["lfs"]["oid"]:
                print(f"Cached {remote}", flush=True)
                continue
        partial = destination.with_name(destination.name + ".part")
        url = f"https://huggingface.co/{REPO}/resolve/{REVISION}/{remote}?download=true"
        for attempt in range(8):
            offset = partial.stat().st_size if partial.exists() else 0
            headers = {"Range": f"bytes={offset}-"} if offset else {}
            try:
                with requests.get(url, headers=headers, stream=True, timeout=(30, 180)) as response:
                    response.raise_for_status()
                    append = offset > 0 and response.status_code == 206
                    if append and not response.headers.get("Content-Range", "").startswith(f"bytes {offset}-"):
                        raise RuntimeError(f"Bad resume range for {remote}")
                    with partial.open("ab" if append else "wb") as output:
                        for chunk in response.iter_content(1024 * 1024):
                            if chunk:
                                output.write(chunk)
                break
            except requests.RequestException as error:
                if attempt == 7:
                    raise
                print(f"Retrying {remote} from {partial.stat().st_size if partial.exists() else 0}: {error}", flush=True)
        if partial.stat().st_size != expected["size"]:
            raise RuntimeError(f"Incomplete {remote}")
        if "lfs" in expected and sha256(partial) != expected["lfs"]["oid"]:
            raise RuntimeError(f"Checksum mismatch for {remote}")
        partial.replace(destination)
        print(f"Fetched {remote}: {destination.stat().st_size} bytes", flush=True)


def benchmark(languages: list[str]) -> None:
    import numpy as np
    import sherpa_onnx
    import soundfile as sf

    for lang in languages:
        model = MODEL_ROOT / lang / "model.int8.onnx"
        tokens = MODEL_ROOT / ("en/tokens.txt" if lang == "en" else "tokens.txt")
        if not model.is_file() or not tokens.is_file():
            raise FileNotFoundError(f"Run fetch for {lang} first")
        manifest = json.loads(MANIFESTS[lang].read_text(encoding="utf-8"))
        recordings = [item for item in manifest if item["language"] == lang]
        start = time.perf_counter()
        recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
            model=str(model), tokens=str(tokens), num_threads=4, decoding_method="greedy_search"
        )
        load_seconds = time.perf_counter() - start
        rows = []
        for item in recordings:
            audio_path = ROOT / item["file"]
            if sha256(audio_path) != item["sha256"]:
                raise RuntimeError(f"Test recording changed: {audio_path}")
            audio, rate = sf.read(audio_path, dtype="float32")
            if rate != 16000 or audio.ndim != 1:
                raise RuntimeError(f"Expected 16 kHz mono: {audio_path}")
            stream = recognizer.create_stream()
            stream.accept_waveform(rate, np.concatenate([audio, np.zeros(1600, dtype=np.float32)]))
            start = time.perf_counter()
            recognizer.decode_stream(stream)
            seconds = time.perf_counter() - start
            output = stream.result.text.strip()
            reference_words = validate_stt.normalize(item["reference"]).split()
            hypothesis_words = validate_stt.normalize(output).split()
            row = {
                **item,
                "output": output,
                "seconds": seconds,
                "audio_seconds": len(audio) / rate,
                "word_errors": validate_stt.distance(reference_words, hypothesis_words),
                "words": len(reference_words),
            }
            rows.append(row)
            print(json.dumps({"language": lang, "row": item["row"], "output": output}, ensure_ascii=False), flush=True)
        summary = {
            "count": len(rows),
            "wer": sum(row["word_errors"] for row in rows) / sum(row["words"] for row in rows),
            "rtf": sum(row["seconds"] for row in rows) / sum(row["audio_seconds"] for row in rows),
            "decode_ms_mean": statistics.mean(row["seconds"] for row in rows) * 1000,
            "decode_ms_median": statistics.median(row["seconds"] for row in rows) * 1000,
            "decode_ms_max": max(row["seconds"] for row in rows) * 1000,
            "load_seconds": load_seconds,
        }
        REPORT_ROOT.mkdir(parents=True, exist_ok=True)
        path = REPORT_ROOT / f"indicconformer-{lang}.json"
        path.write_text(json.dumps({
            "model": "AI4Bharat IndicConformer CTC INT8",
            "revision": REVISION,
            "language": lang,
            "model_bytes": model.stat().st_size,
            "model_sha256": sha256(model),
            "tokens_bytes": tokens.stat().st_size,
            "tokens_sha256": sha256(tokens),
            "sherpa_version": sherpa_onnx.__version__,
            "native_module": str(sherpa_onnx.__file__),
            "desktop_peak_working_set_bytes": validate_stt.peak_memory_bytes(),
            "summary": summary,
            "rows": rows,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"{lang}: {summary}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("fetch", "benchmark"))
    parser.add_argument("--languages", nargs="+", choices=LANGUAGES, default=list(LANGUAGES))
    args = parser.parse_args()
    if args.action == "fetch":
        fetch(args.languages)
    else:
        benchmark(args.languages)
