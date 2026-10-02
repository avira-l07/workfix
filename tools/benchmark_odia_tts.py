"""Measure the app's Odia MMS-VITS TTS path and save audio for human review.

This measures desktop synthesis, not speech intelligibility or Android latency.
Run from the repository root after provisioning the cached Odia TTS model.
"""

import argparse
import hashlib
import json
import statistics
import time
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

import validate_stt


ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "models/bundled/or/tts/model.onnx"
TOKENS = ROOT / "app/src/main/assets/language_packs/or/tts/tokens.txt"
MANIFEST = ROOT / "app/src/main/assets/language_packs/or_dev_manifest.json"
RESULT = ROOT / "tools/stt_results/odia-tts-desktop.json"
SAMPLES = ROOT / "tools/stt_results/odia-tts-samples"
PHRASES = {
    "help": "ସାହାଯ୍ୟ ଆବଶ୍ୟକ।",
    "medical": "ତୁରନ୍ତ ଡାକ୍ତରୀ ସହାୟତା ଆବଶ୍ୟକ।",
    "rescue": "ଦୟାକରି ତୁରନ୍ତ ଏକ ଉଦ୍ଧାରକାରୀ ଦଳ ପଠାନ୍ତୁ।",
}


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def write_wav(path: Path, samples: np.ndarray, sample_rate: int) -> None:
    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(sample_rate)
        output.writeframes(pcm.tobytes())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--threads", type=int, choices=(1, 2), default=1)
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    expected = manifest["ttsModel"]["checksumsSha256"]
    for path in (MODEL, TOKENS):
        if not path.is_file() or sha256(path) != expected[path.name]:
            raise RuntimeError(f"Missing or unexpected Odia TTS file: {path}")

    started = time.perf_counter()
    tts = sherpa_onnx.OfflineTts(
        sherpa_onnx.OfflineTtsConfig(
            model=sherpa_onnx.OfflineTtsModelConfig(
                vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                    model=str(MODEL), tokens=str(TOKENS)
                ),
                num_threads=args.threads,
            )
        )
    )
    load_seconds = time.perf_counter() - started
    tts.generate(PHRASES["help"])  # warm native runtime before timing

    SAMPLES.mkdir(parents=True, exist_ok=True)
    results = {}
    for name, phrase in PHRASES.items():
        trials = []
        for index in range(5):
            started = time.perf_counter()
            audio = tts.generate(phrase)
            seconds = time.perf_counter() - started
            samples = np.asarray(audio.samples)
            if samples.size == 0 or audio.sample_rate <= 0:
                raise RuntimeError(f"Empty Odia TTS result for {name}")
            duration = samples.size / audio.sample_rate
            trials.append({
                "synthesis_seconds": seconds,
                "audio_seconds": duration,
                "rtf": seconds / duration,
                "pcm_samples": int(samples.size),
                "sample_rate_hz": audio.sample_rate,
            })
            if index == 0 and args.threads == 1:
                write_wav(SAMPLES / f"{name}.wav", samples, audio.sample_rate)
        results[name] = {
            "text": phrase,
            "mean_synthesis_seconds": statistics.mean(t["synthesis_seconds"] for t in trials),
            "median_synthesis_seconds": statistics.median(t["synthesis_seconds"] for t in trials),
            "mean_rtf": statistics.mean(t["rtf"] for t in trials),
            "trials": trials,
        }
        print(f"{name}: {results[name]['mean_synthesis_seconds']:.3f}s, "
              f"RTF {results[name]['mean_rtf']:.3f}", flush=True)

    report = {
        "scope": "Desktop generation only; no human rating or phone latency.",
        "threads": args.threads,
        "model_bytes": MODEL.stat().st_size,
        "model_sha256": sha256(MODEL),
        "tokens_bytes": TOKENS.stat().st_size,
        "tokens_sha256": sha256(TOKENS),
        "load_seconds": load_seconds,
        "peak_desktop_working_set_bytes": validate_stt.peak_memory_bytes(),
        "phrases": results,
    }
    destination = RESULT if args.threads == 1 else RESULT.with_name("odia-tts-desktop-2threads.json")
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
